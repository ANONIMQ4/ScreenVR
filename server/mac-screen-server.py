#!/usr/bin/env python3
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
import shutil
import socket
import signal
import subprocess
import threading

active_lock = threading.Lock()
active_conn = None
active_proc = None


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8094)
    parser.add_argument("--control-port", type=int, default=8095)
    parser.add_argument("--fps", type=int, default=30)
    parser.add_argument("--size", default="960x540")
    parser.add_argument("--bitrate", default="2500k")
    parser.add_argument("--fit", choices=("contain", "cover"), default="contain")
    return parser.parse_args()


class StreamConfig:
    def __init__(self, args):
        width, height = args.size.split("x", 1)
        self.lock = threading.Lock()
        self.width = int(width)
        self.height = int(height)
        self.fps = int(args.fps)
        self.bitrate = str(args.bitrate)
        self.fit = str(args.fit)

    def snapshot(self):
        with self.lock:
            return {
                "width": self.width,
                "height": self.height,
                "fps": self.fps,
                "bitrate": self.bitrate,
                "fit": self.fit,
            }

    def update(self, payload):
        with self.lock:
            self.width = clamp_int(payload.get("width", self.width), 320, 2340)
            self.height = clamp_int(payload.get("height", self.height), 180, 2160)
            self.fps = clamp_int(payload.get("fps", self.fps), 10, 60)
            bitrate = clamp_int(payload.get("bitrate", int(str(self.bitrate).rstrip("k"))), 300, 25000)
            self.bitrate = f"{bitrate}k"
            fit = str(payload.get("fit", self.fit)).lower()
            self.fit = fit if fit in ("contain", "cover") else "contain"
            return {
                "width": self.width,
                "height": self.height,
                "fps": self.fps,
                "bitrate": self.bitrate,
                "fit": self.fit,
            }


def clamp_int(value, minimum, maximum):
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        parsed = minimum
    if parsed % 2 == 1:
        parsed += 1
    return max(minimum, min(maximum, parsed))


def ffmpeg_cmd(config):
    width = str(config["width"])
    height = str(config["height"])
    fps = int(config["fps"])
    bitrate = str(config["bitrate"])
    if config["fit"] == "cover":
        video_filter = (
            f"fps={fps},scale={width}:{height}:force_original_aspect_ratio=increase,"
            f"crop={width}:{height},setpts=N/({fps}*TB)"
        )
    else:
        video_filter = (
            f"fps={fps},scale={width}:{height}:force_original_aspect_ratio=decrease,"
            f"pad={width}:{height}:(ow-iw)/2:(oh-ih)/2,setpts=N/({fps}*TB)"
        )
    return [
        ffmpeg_path(),
        "-hide_banner",
        "-loglevel",
        "warning",
        "-f",
        "avfoundation",
        "-capture_cursor",
        "1",
        "-i",
        "1:none",
        "-vf",
        video_filter,
        "-an",
        "-c:v",
        "libx264",
        "-preset",
        "ultrafast",
        "-tune",
        "zerolatency",
        "-pix_fmt",
        "yuv420p",
        "-b:v",
        bitrate,
        "-maxrate",
        bitrate,
        "-bufsize",
        bitrate,
        "-g",
        str(fps),
        "-bf",
        "0",
        "-x264-params",
        f"keyint={fps}:min-keyint={fps}:scenecut=0:repeat-headers=1",
        "-bsf:v",
        "h264_metadata=aud=insert",
        "-f",
        "h264",
        "pipe:1",
    ]


def ffmpeg_path():
    extra_paths = [
        "/opt/homebrew/bin",
        "/usr/local/bin",
        "/usr/bin",
        "/bin",
    ]
    path = os.environ.get("PATH", "")
    os.environ["PATH"] = ":".join(extra_paths + ([path] if path else []))
    found = shutil.which("ffmpeg")
    if found:
        return found
    for candidate in ("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg"):
        if os.path.exists(candidate):
            return candidate
    return "ffmpeg"


def close_active():
    global active_conn, active_proc
    with active_lock:
        if active_conn is not None:
            try:
                active_conn.close()
            except OSError:
                pass
            active_conn = None
        if active_proc is not None:
            try:
                active_proc.terminate()
                active_proc.wait(timeout=1)
            except OSError:
                pass
            except subprocess.TimeoutExpired:
                active_proc.kill()
                try:
                    active_proc.wait(timeout=1)
                except (OSError, subprocess.TimeoutExpired):
                    pass
            active_proc = None


def kill_stale_ffmpeg():
    try:
        output = subprocess.check_output(["/bin/ps", "-axo", "pid=,command="], text=True)
    except (OSError, subprocess.SubprocessError):
        return

    for line in output.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        pid_text, _, command = stripped.partition(" ")
        if "ffmpeg" not in command or "pipe:1" not in command or "-f h264" not in command:
            continue
        try:
            os.kill(int(pid_text), signal.SIGKILL)
        except (OSError, ValueError):
            pass


def pipe_to_client(conn, config_store):
    global active_conn, active_proc
    config = config_store.snapshot()
    print(f"client connected: {config}", flush=True)
    close_active()
    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    proc = subprocess.Popen(ffmpeg_cmd(config), stdout=subprocess.PIPE, bufsize=0)
    with active_lock:
        active_conn = conn
        active_proc = proc
    try:
        while True:
            chunk = proc.stdout.read(32768)
            if not chunk:
                break
            conn.sendall(chunk)
    except OSError:
        pass
    finally:
        stop_process(proc)
        conn.close()
        with active_lock:
            if active_conn is conn:
                active_conn = None
            if active_proc is proc:
                active_proc = None
        print("client disconnected", flush=True)


def stop_process(proc):
    try:
        proc.terminate()
        proc.wait(timeout=1)
    except OSError:
        pass
    except subprocess.TimeoutExpired:
        proc.kill()
        try:
            proc.wait(timeout=1)
        except (OSError, subprocess.TimeoutExpired):
            pass


def start_control_server(host, port, config_store):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path != "/config":
                self.send_error(404)
                return
            self.send_json(config_store.snapshot())

        def do_POST(self):
            if self.path != "/config":
                self.send_error(404)
                return
            length = int(self.headers.get("Content-Length", "0"))
            try:
                payload = json.loads(self.rfile.read(length).decode("utf-8"))
                updated = config_store.update(payload)
                print(f"config updated: {updated}", flush=True)
                self.send_json(updated)
            except Exception as exc:
                self.send_error(400, str(exc))

        def log_message(self, fmt, *args):
            return

        def send_json(self, payload):
            data = json.dumps(payload).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    server = ThreadingHTTPServer((host, port), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()


def main():
    args = parse_args()
    kill_stale_ffmpeg()
    config_store = StreamConfig(args)
    start_control_server(args.host, args.control_port, config_store)
    initial = config_store.snapshot()
    print(f"Serving raw H.264 on rawh264://{args.host}:{args.port}?w={initial['width']}&h={initial['height']}", flush=True)
    print(f"Control endpoint on http://{args.host}:{args.control_port}/config", flush=True)
    print(f"For USB mode run: adb reverse tcp:{args.port} tcp:{args.port}", flush=True)
    print(f"For controls run: adb reverse tcp:{args.control_port} tcp:{args.control_port}", flush=True)
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((args.host, args.port))
        server.listen(1)
        while True:
            conn, _ = server.accept()
            threading.Thread(target=pipe_to_client, args=(conn, config_store), daemon=True).start()


if __name__ == "__main__":
    main()
