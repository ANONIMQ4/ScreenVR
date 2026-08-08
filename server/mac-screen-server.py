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
import time

active_lock = threading.Lock()
active_conn = None
active_procs = []


class H264StreamStats:
    def __init__(self):
        self.tail = b""
        self.aud_count = 0
        self.slice_count = 0
        self.byte_count = 0
        self.last_report = time.monotonic()

    def add(self, chunk):
        self.byte_count += len(chunk)
        data = self.tail + chunk
        tail_len = len(self.tail)
        index = 0
        while True:
            start = data.find(b"\x00\x00\x01", index)
            if start < 0:
                break
            header_index = start + 3
            if header_index >= tail_len and header_index < len(data):
                nal_type = data[header_index] & 0x1F
                if nal_type == 9:
                    self.aud_count += 1
                elif nal_type in (1, 5):
                    self.slice_count += 1
            index = header_index + 1
        self.tail = data[-5:]
        self.report_if_due()

    def report_if_due(self):
        now = time.monotonic()
        elapsed = now - self.last_report
        if elapsed < 1:
            return
        aud_fps = self.aud_count / elapsed
        slice_fps = self.slice_count / elapsed
        mbps = (self.byte_count * 8 / 1_000_000) / elapsed
        print(f"h264 aud {aud_fps:.1f} slice {slice_fps:.1f} send {mbps:.2f} Mbit/s", flush=True)
        self.aud_count = 0
        self.slice_count = 0
        self.byte_count = 0
        self.last_report = now


class H264AccessUnitSplitter:
    def __init__(self):
        self.buffer = bytearray()

    def add(self, chunk):
        self.buffer.extend(chunk)
        units = []
        while True:
            first = self.find_aud(0)
            if first < 0:
                if len(self.buffer) > 1024 * 1024:
                    del self.buffer[:-5]
                break
            if first > 0:
                del self.buffer[:first]
            second = self.find_aud(5)
            if second < 0:
                break
            units.append(bytes(self.buffer[:second]))
            del self.buffer[:second]
        return units

    def find_aud(self, start):
        three = self.buffer.find(b"\x00\x00\x01\x09", start)
        four = self.buffer.find(b"\x00\x00\x00\x01\x09", start)
        if three < 0:
            return four
        if four < 0:
            return three
        return min(three, four)


class LatestFrameRelay:
    def __init__(self):
        self.condition = threading.Condition()
        self.latest = None
        self.closed = False

    def put(self, frame):
        with self.condition:
            self.latest = frame
            self.condition.notify()

    def get(self):
        with self.condition:
            while self.latest is None and not self.closed:
                self.condition.wait()
            frame = self.latest
            self.latest = None
            return frame

    def close(self):
        with self.condition:
            self.closed = True
            self.condition.notify_all()


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8094)
    parser.add_argument("--control-port", type=int, default=8095)
    parser.add_argument("--fps", type=int, default=30)
    parser.add_argument("--size", default="960x540")
    parser.add_argument("--bitrate", default="2500k")
    parser.add_argument("--fit", choices=("contain", "cover"), default="contain")
    parser.add_argument("--encoder", choices=("cpu", "videotoolbox"), default="cpu")
    parser.add_argument("--capture-backend", choices=("avfoundation", "screencapturekit"), default="avfoundation")
    parser.add_argument("--capture-cursor", choices=("0", "1"), default="0")
    parser.add_argument("--transport", choices=("raw", "frames"), default="raw")
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
        self.encoder = str(args.encoder)
        self.capture_backend = str(args.capture_backend)
        self.capture_cursor = str(args.capture_cursor)
        self.transport = str(args.transport)

    def snapshot(self):
        with self.lock:
            return {
                "width": self.width,
                "height": self.height,
                "fps": self.fps,
                "bitrate": self.bitrate,
                "fit": self.fit,
                "encoder": self.encoder,
                "capture_backend": self.capture_backend,
                "capture_cursor": self.capture_cursor,
                "transport": self.transport,
            }

    def update(self, payload):
        with self.lock:
            self.width = clamp_int(payload.get("width", self.width), 320, 2340)
            self.height = clamp_int(payload.get("height", self.height), 180, 2160)
            self.fps = clamp_int(payload.get("fps", self.fps), 10, 300)
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
    encoder = str(config.get("encoder", "cpu"))
    capture_backend = str(config.get("capture_backend", "avfoundation"))
    capture_cursor = str(config.get("capture_cursor", "0"))
    if config["fit"] == "cover":
        if encoder == "cpu":
            video_filter = (
                f"fps={fps},scale={width}:{height}:force_original_aspect_ratio=increase,"
                f"crop={width}:{height},setpts=N/({fps}*TB)"
            )
        else:
            video_filter = (
                f"scale={width}:{height}:force_original_aspect_ratio=increase,"
                f"crop={width}:{height}"
            )
    else:
        if encoder == "cpu":
            video_filter = (
                f"fps={fps},scale={width}:{height}:force_original_aspect_ratio=decrease,"
                f"pad={width}:{height}:(ow-iw)/2:(oh-ih)/2,setpts=N/({fps}*TB)"
            )
        else:
            video_filter = (
                f"scale={width}:{height}:force_original_aspect_ratio=decrease,"
                f"pad={width}:{height}:(ow-iw)/2:(oh-ih)/2"
            )
    if capture_backend == "screencapturekit":
        cmd = [
            ffmpeg_path(),
            "-hide_banner",
            "-loglevel",
            "warning",
            "-f",
            "rawvideo",
            "-pix_fmt",
            "bgra",
            "-s",
            f"{width}x{height}",
            "-framerate",
            str(fps),
            "-i",
            "pipe:0",
            "-an",
        ]
    else:
        cmd = [
        ffmpeg_path(),
        "-hide_banner",
        "-stats",
        "-stats_period",
        "1",
        "-loglevel",
        "warning",
        "-f",
        "avfoundation",
        "-framerate",
        str(fps),
        "-pixel_format",
        "nv12",
        "-capture_cursor",
        capture_cursor,
        "-i",
        "1:none",
        "-vf",
        video_filter,
        "-an",
        ]
    if encoder == "videotoolbox":
        cmd += [
            "-c:v",
            "h264_videotoolbox",
            "-profile:v",
            "baseline",
            "-realtime",
            "1",
            "-prio_speed",
            "1",
            "-pix_fmt",
            "nv12",
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
        ]
    else:
        cmd += [
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
        ]
    cmd += [
        "-bsf:v",
        "h264_metadata=aud=insert",
        "-f",
        "h264",
        "pipe:1",
    ]
    return cmd


def sck_cmd(config):
    ensure_sck_capture()
    return [
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "sck-capture"),
        "--width",
        str(config["width"]),
        "--height",
        str(config["height"]),
        "--fps",
        str(config["fps"]),
    ]


def ensure_sck_capture():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    source = os.path.join(script_dir, "sck-capture.swift")
    binary = os.path.join(script_dir, "sck-capture")
    if os.path.exists(binary) and os.path.getmtime(binary) >= os.path.getmtime(source):
        return
    swiftc = shutil.which("swiftc") or "/usr/bin/swiftc"
    subprocess.check_call([
        swiftc,
        source,
        "-framework",
        "ScreenCaptureKit",
        "-framework",
        "CoreMedia",
        "-framework",
        "CoreVideo",
        "-o",
        binary,
    ])


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
    global active_conn, active_procs
    with active_lock:
        if active_conn is not None:
            try:
                active_conn.close()
            except OSError:
                pass
            active_conn = None
        for proc in reversed(active_procs):
            stop_process(proc)
        active_procs = []


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
    global active_conn, active_procs
    config = config_store.snapshot()
    print(f"client connected: {config}", flush=True)
    close_active()
    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    conn.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 256 * 1024)
    capture_proc = None
    if config.get("capture_backend") == "screencapturekit":
        capture_proc = subprocess.Popen(sck_cmd(config), stdout=subprocess.PIPE, bufsize=0)
        proc = subprocess.Popen(ffmpeg_cmd(config), stdin=capture_proc.stdout, stdout=subprocess.PIPE, bufsize=0)
        if capture_proc.stdout is not None:
            capture_proc.stdout.close()
        procs = [capture_proc, proc]
    else:
        proc = subprocess.Popen(ffmpeg_cmd(config), stdout=subprocess.PIPE, bufsize=0)
        procs = [proc]
    with active_lock:
        active_conn = conn
        active_procs = procs
    stats = H264StreamStats()
    try:
        if config.get("transport") == "frames":
            pipe_frames_to_client(proc, conn, stats)
        else:
            while True:
                chunk = proc.stdout.read(32768)
                if not chunk:
                    break
                stats.add(chunk)
                conn.sendall(chunk)
    except OSError:
        pass
    finally:
        for active in reversed(procs):
            stop_process(active)
        conn.close()
        with active_lock:
            if active_conn is conn:
                active_conn = None
            if active_procs == procs:
                active_procs = []
        print("client disconnected", flush=True)


def pipe_frames_to_client(proc, conn, stats):
    relay = LatestFrameRelay()
    splitter = H264AccessUnitSplitter()

    def read_frames():
        try:
            while True:
                chunk = proc.stdout.read(32768)
                if not chunk:
                    break
                stats.add(chunk)
                for frame in splitter.add(chunk):
                    relay.put(frame)
        finally:
            relay.close()

    reader = threading.Thread(target=read_frames, daemon=True)
    reader.start()
    while True:
        frame = relay.get()
        if frame is None:
            break
        conn.sendall(len(frame).to_bytes(4, "big"))
        conn.sendall(frame)


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
    print(
        f"Serving raw H.264 on rawh264://{args.host}:{args.port}"
        f"?w={initial['width']}&h={initial['height']}&fps={initial['fps']}",
        flush=True,
    )
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
