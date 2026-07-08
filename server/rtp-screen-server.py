#!/usr/bin/env python3
import argparse
import json
import os
import shutil
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dest", required=True)
    parser.add_argument("--port", type=int, default=5004)
    parser.add_argument("--fps", type=int, default=60)
    parser.add_argument("--size", default="900x600")
    parser.add_argument("--bitrate", default="5000k")
    parser.add_argument("--fit", choices=("contain", "cover"), default="contain")
    parser.add_argument("--encoder", choices=("cpu", "videotoolbox"), default="cpu")
    parser.add_argument("--capture-backend", choices=("avfoundation", "screencapturekit"), default="avfoundation")
    parser.add_argument("--capture-cursor", choices=("0", "1"), default="0")
    parser.add_argument("--queue-depth", type=int, default=3)
    parser.add_argument("--payload-size", type=int, default=1200)
    parser.add_argument("--control-host", default="0.0.0.0")
    parser.add_argument("--control-port", type=int, default=8095)
    return parser.parse_args()


def ffmpeg_path():
    extra_paths = [
        "/opt/homebrew/bin",
        "/usr/local/bin",
        "/usr/bin",
        "/bin",
    ]
    path = os.environ.get("PATH", "")
    os.environ["PATH"] = ":".join(extra_paths + ([path] if path else []))
    return shutil.which("ffmpeg") or "ffmpeg"


def build_cmd(args):
    width, height = args.size.split("x", 1)
    if args.fit == "cover":
        video_filter = (
            f"fps={args.fps},scale={width}:{height}:force_original_aspect_ratio=increase,"
            f"crop={width}:{height},setpts=N/({args.fps}*TB)"
        )
    else:
        video_filter = (
            f"fps={args.fps},scale={width}:{height}:force_original_aspect_ratio=decrease,"
            f"pad={width}:{height}:(ow-iw)/2:(oh-ih)/2,setpts=N/({args.fps}*TB)"
        )
    if args.capture_backend == "screencapturekit":
        cmd = [
            ffmpeg_path(),
            "-hide_banner",
            "-stats",
            "-stats_period",
            "1",
            "-loglevel",
            "warning",
            "-f",
            "rawvideo",
            "-pix_fmt",
            "bgra",
            "-s",
            f"{width}x{height}",
            "-framerate",
            str(args.fps),
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
            str(args.fps),
            "-pixel_format",
            "nv12",
            "-capture_cursor",
            args.capture_cursor,
            "-i",
            "1:none",
            "-vf",
            video_filter,
            "-an",
        ]
    if args.encoder == "videotoolbox":
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
            args.bitrate,
            "-maxrate",
            args.bitrate,
            "-bufsize",
            args.bitrate,
            "-g",
            str(args.fps),
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
            args.bitrate,
            "-maxrate",
            args.bitrate,
            "-bufsize",
            args.bitrate,
            "-g",
            str(args.fps),
            "-bf",
            "0",
            "-x264-params",
            f"keyint={args.fps}:min-keyint={args.fps}:scenecut=0:repeat-headers=1",
        ]
    cmd += [
        "-payload_type",
        "96",
        "-f",
        "rtp",
        f"rtp://{args.dest}:{args.port}?pkt_size={args.payload_size}",
    ]
    return cmd


def sck_cmd(args):
    ensure_sck_capture()
    width, height = args.size.split("x", 1)
    return [
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "sck-capture"),
        "--width",
        width,
        "--height",
        height,
        "--fps",
        str(args.fps),
        "--queue-depth",
        str(args.queue_depth),
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


def clamp_int(value, minimum, maximum):
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        parsed = minimum
    if parsed % 2 == 1:
        parsed += 1
    return max(minimum, min(maximum, parsed))


class RtpStream:
    def __init__(self, args):
        self.args = args
        self.lock = threading.Lock()
        self.process = None
        self.capture_process = None

    def start(self):
        with self.lock:
            self._start_locked()

    def stop(self):
        with self.lock:
            self._stop_locked()

    def update(self, payload):
        with self.lock:
            width, height = self.args.size.split("x", 1)
            width = clamp_int(payload.get("width", width), 320, 2340)
            height = clamp_int(payload.get("height", height), 180, 2160)
            fps = clamp_int(payload.get("fps", self.args.fps), 10, 300)
            bitrate = clamp_int(payload.get("bitrate", str(self.args.bitrate).rstrip("k")), 300, 25000)
            fit = str(payload.get("fit", self.args.fit)).lower()
            encoder = str(payload.get("encoder", self.args.encoder)).lower()
            self.args.size = f"{width}x{height}"
            self.args.fps = fps
            self.args.bitrate = f"{bitrate}k"
            self.args.fit = fit if fit in ("contain", "cover") else "contain"
            self.args.encoder = encoder if encoder in ("cpu", "videotoolbox") else "cpu"
            self._stop_locked()
            self._start_locked()
            return {
                "width": width,
                "height": height,
                "fps": fps,
                "bitrate": bitrate,
                "fit": self.args.fit,
                "encoder": self.args.encoder,
            }

    def _start_locked(self):
        print(
            f"Sending RTP/H.264 to rtp://{self.args.dest}:{self.args.port} "
            f"size={self.args.size} fps={self.args.fps} bitrate={self.args.bitrate} "
            f"capture={self.args.capture_backend} encoder={self.args.encoder}",
            flush=True,
        )
        if self.args.capture_backend == "screencapturekit":
            self.capture_process = subprocess.Popen(sck_cmd(self.args), stdout=subprocess.PIPE)
            self.process = subprocess.Popen(build_cmd(self.args), stdin=self.capture_process.stdout)
            self.capture_process.stdout.close()
        else:
            self.capture_process = None
            self.process = subprocess.Popen(build_cmd(self.args))

    def _stop_locked(self):
        if self.process is None or self.process.poll() is not None:
            self.process = None
            self._stop_capture_locked()
            return
        self.process.terminate()
        try:
            self.process.wait(timeout=2)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=2)
        self.process = None
        self._stop_capture_locked()

    def _stop_capture_locked(self):
        if self.capture_process is None or self.capture_process.poll() is not None:
            self.capture_process = None
            return
        self.capture_process.terminate()
        try:
            self.capture_process.wait(timeout=2)
        except subprocess.TimeoutExpired:
            self.capture_process.kill()
            self.capture_process.wait(timeout=2)
        self.capture_process = None

    def wait(self):
        while True:
            with self.lock:
                process = self.process
            if process is None:
                return 0
            code = process.wait()
            with self.lock:
                if self.process is process:
                    self.process = None
                    return code


def start_control_server(host, port, stream):
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            if self.path != "/config":
                self.send_response(404)
                self.end_headers()
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                payload = json.loads(self.rfile.read(length).decode("utf-8"))
                response = stream.update(payload)
                data = json.dumps(response).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            except Exception as error:
                data = json.dumps({"error": str(error)}).encode("utf-8")
                self.send_response(400)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        def log_message(self, format, *args):
            return

    server = ThreadingHTTPServer((host, port), Handler)
    thread = threading.Thread(target=server.serve_forever, name="rtp-control", daemon=True)
    thread.start()
    print(f"Control endpoint on http://{host}:{port}/config", flush=True)
    return server


def main():
    args = parse_args()
    stream = RtpStream(args)
    start_control_server(args.control_host, args.control_port, stream)
    stream.start()
    try:
        stream.wait()
    finally:
        stream.stop()


if __name__ == "__main__":
    main()
