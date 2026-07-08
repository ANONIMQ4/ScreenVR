#!/usr/bin/env python3
import argparse
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time


ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SERVER = os.path.join(ROOT, "server")


def parse_args():
    parser = argparse.ArgumentParser(description="ScreenVR pipeline bottleneck diagnostics")
    parser.add_argument(
        "test",
        choices=(
            "all",
            "processes",
            "encoder",
            "source",
            "synthetic",
            "avfoundation",
            "sck",
            "sck-encode",
            "rtp",
            "android-decode",
            "bisect-size",
        ),
    )
    parser.add_argument("--dest", default="", help="Android phone IP for RTP tests")
    parser.add_argument("--port", type=int, default=5004)
    parser.add_argument("--size", default="900x600")
    parser.add_argument("--fps", type=int, default=60)
    parser.add_argument("--bitrate", default="3000k")
    parser.add_argument("--duration", type=int, default=8)
    parser.add_argument("--payload-size", type=int, default=900)
    parser.add_argument("--queue-depth", type=int, default=3)
    parser.add_argument("--capture-backend", choices=("avfoundation", "screencapturekit"), default="screencapturekit")
    parser.add_argument("--encoder", choices=("cpu", "videotoolbox"), default="cpu")
    parser.add_argument("--motion", action="store_true", help="Animate the synthetic raw source")
    parser.add_argument("--min-width", type=int, default=600)
    parser.add_argument("--max-width", type=int, default=1800)
    parser.add_argument("--aspect", default="3:2")
    parser.add_argument("--passes", type=int, default=8)
    parser.add_argument("--json", action="store_true", help="Print machine-readable summary")
    return parser.parse_args()


def ffmpeg_path():
    extra_paths = ["/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin"]
    path = os.environ.get("PATH", "")
    os.environ["PATH"] = ":".join(extra_paths + ([path] if path else []))
    return shutil.which("ffmpeg") or "ffmpeg"


def run(cmd, timeout=None):
    return subprocess.run(
        cmd,
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
        check=False,
    )


def start(cmd):
    return subprocess.Popen(
        cmd,
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        preexec_fn=os.setsid,
    )


def stop_process(process):
    if process.poll() is not None:
        return
    try:
        os.killpg(os.getpgid(process.pid), signal.SIGTERM)
        process.wait(timeout=2)
    except Exception:
        try:
            os.killpg(os.getpgid(process.pid), signal.SIGKILL)
        except Exception:
            pass


def collect_process_output(process, duration):
    deadline = time.time() + duration
    lines = []
    while time.time() < deadline and process.poll() is None:
        line = process.stdout.readline()
        if line:
            lines.append(line.rstrip())
        else:
            time.sleep(0.05)
    stop_process(process)
    rest = process.stdout.read() if process.stdout else ""
    if rest:
        lines.extend(rest.splitlines())
    return "\n".join(lines)


def parse_ffmpeg_stats(text):
    frames = [int(match) for match in re.findall(r"frame=\s*(\d+)", text)]
    fps_values = [float(match) for match in re.findall(r"fps=\s*([0-9.]+)", text)]
    speeds = [float(match) for match in re.findall(r"speed=\s*([0-9.]+)x", text)]
    bitrates = [float(match) for match in re.findall(r"bitrate=\s*([0-9.]+)kbits/s", text)]
    return {
        "last_frame": frames[-1] if frames else None,
        "last_fps": fps_values[-1] if fps_values else None,
        "avg_fps": round(sum(fps_values) / len(fps_values), 2) if fps_values else None,
        "last_speed": speeds[-1] if speeds else None,
        "avg_speed": round(sum(speeds) / len(speeds), 3) if speeds else None,
        "last_bitrate_kbps": bitrates[-1] if bitrates else None,
    }


def parse_sck_stats(text):
    values = [float(match) for match in re.findall(r"sck fps ([0-9.]+)", text)]
    avg_intervals = [float(match) for match in re.findall(r"interval_avg_ms ([0-9.]+)", text)]
    p95_intervals = [float(match) for match in re.findall(r"interval_p95_ms ([0-9.]+)", text)]
    max_intervals = [float(match) for match in re.findall(r"interval_max_ms ([0-9.]+)", text)]
    late_frames = [int(match) for match in re.findall(r"late (\d+)", text)]
    if len(values) > 2:
        values = values[1:]
        avg_intervals = avg_intervals[1:]
        p95_intervals = p95_intervals[1:]
        max_intervals = max_intervals[1:]
        late_frames = late_frames[1:]
    return {
        "last_sck_fps": values[-1] if values else None,
        "avg_sck_fps": round(sum(values) / len(values), 2) if values else None,
        "avg_interval_ms": round(sum(avg_intervals) / len(avg_intervals), 2) if avg_intervals else None,
        "avg_p95_interval_ms": round(sum(p95_intervals) / len(p95_intervals), 2) if p95_intervals else None,
        "worst_interval_ms": max(max_intervals) if max_intervals else None,
        "late_frames": sum(late_frames) if late_frames else None,
    }


def parse_source_stats(text):
    values = [float(match) for match in re.findall(r"source fps ([0-9.]+)", text)]
    done = [float(match) for match in re.findall(r"source done fps ([0-9.]+)", text)]
    late = [int(match) for match in re.findall(r"late (\d+)", text)]
    return {
        "last_source_fps": values[-1] if values else None,
        "avg_source_fps": round(sum(values) / len(values), 2) if values else None,
        "done_source_fps": done[-1] if done else None,
        "late_frames": late[-1] if late else None,
    }


def parse_android_decode(text):
    matches = re.findall(
        r"Work-Rate: Q\(([0-9.]+)/s Avg=([0-9.]+)/s\) Done\(([0-9.]+)/s Avg=([0-9.]+)/s\).*?Stream: ([0-9.]+)fps ([0-9.]+)([KMG])?bps",
        text,
    )
    if not matches:
        return {}
    q_rate, q_avg, done_rate, done_avg, stream_fps, stream_rate, unit = matches[-1]
    return {
        "queue_rate_fps": float(q_rate),
        "queue_avg_fps": float(q_avg),
        "done_rate_fps": float(done_rate),
        "done_avg_fps": float(done_avg),
        "stream_fps": float(stream_fps),
        "stream_bitrate": float(stream_rate),
        "stream_bitrate_unit": unit or "",
    }


def process_conflicts(port):
    result = run(["ps", "-axo", "pid,pcpu,pmem,command"])
    lines = []
    for line in result.stdout.splitlines():
        if (
            "rtp-screen-server.py" in line
            or f"rtp://" in line and f":{port}" in line
            or "sck-capture" in line
        ):
            if "pipeline_diagnostics.py" not in line:
                lines.append(line.strip())
    rtp_senders = [line for line in lines if "ffmpeg" in line and f":{port}" in line]
    return {
        "running_pipeline_processes": lines,
        "rtp_sender_count": len(rtp_senders),
        "ok": len(rtp_senders) <= 1,
    }


def read_process_cpu(pids):
    result = run(["ps", "-axo", "pid,pcpu,comm,args"])
    samples = {
        "windowserver_cpu": [],
        "sck_cpu": [],
        "ffmpeg_cpu": [],
    }
    wanted_pids = {str(pid) for pid in pids if pid}
    for line in result.stdout.splitlines()[1:]:
        columns = line.strip().split(None, 3)
        if len(columns) < 4:
            continue
        pid, cpu, _comm, command = columns
        try:
            value = float(cpu)
        except ValueError:
            continue
        if "WindowServer" in command:
            samples["windowserver_cpu"].append(value)
        if pid in wanted_pids and "sck-capture" in command:
            samples["sck_cpu"].append(value)
        if pid in wanted_pids and "ffmpeg" in command:
            samples["ffmpeg_cpu"].append(value)
    return {key: (sum(values) if values else None) for key, values in samples.items()}


def summarize_cpu_samples(samples):
    result = {}
    for key in ("windowserver_cpu", "sck_cpu", "ffmpeg_cpu"):
        values = [sample[key] for sample in samples if sample.get(key) is not None]
        if not values:
            continue
        result[f"avg_{key}"] = round(sum(values) / len(values), 1)
        result[f"max_{key}"] = round(max(values), 1)
    return result


def encoder_test(args):
    width, height = args.size.split("x", 1)
    cmd = [
        ffmpeg_path(),
        "-hide_banner",
        "-stats",
        "-stats_period",
        "1",
        "-loglevel",
        "warning",
        "-f",
        "lavfi",
        "-i",
        f"testsrc2=size={width}x{height}:rate={args.fps}",
        "-t",
        str(args.duration),
        "-an",
        *encoder_args(args),
        "-f",
        "null",
        "-",
    ]
    result = run(cmd, timeout=args.duration + 10)
    stats = parse_ffmpeg_stats(result.stderr)
    stats["ok"] = (stats.get("avg_speed") or 0) >= 0.98
    return stats


def raw_source_binary():
    source = os.path.join(ROOT, "tools", "diagnostics", "raw-frame-source.swift")
    binary = os.path.join(ROOT, "tools", "diagnostics", "raw-frame-source")
    if os.path.exists(binary) and os.path.getmtime(binary) >= os.path.getmtime(source):
        return binary
    run(["swiftc", source, "-o", binary], timeout=30)
    return binary


def source_test(args):
    width, height = args.size.split("x", 1)
    cmd = [
        raw_source_binary(),
        "--width",
        width,
        "--height",
        height,
        "--fps",
        str(args.fps),
        "--duration",
        str(args.duration),
    ]
    if args.motion:
        cmd.extend(["--motion", "1"])
    with open(os.devnull, "wb") as devnull:
        process = subprocess.Popen(
            cmd,
            cwd=ROOT,
            text=True,
            stdout=devnull,
            stderr=subprocess.PIPE,
            preexec_fn=os.setsid,
        )
        try:
            _, stderr = process.communicate(timeout=args.duration + 15)
        except subprocess.TimeoutExpired:
            stop_process(process)
            stderr = process.stderr.read() if process.stderr else ""
            stats = parse_source_stats(stderr)
            stats["ok"] = False
            stats["error"] = "raw source did not finish before timeout"
            return stats
    stats = parse_source_stats(stderr)
    max_late = max(2, int(args.fps * args.duration * 0.03))
    stats["ok"] = (stats.get("done_source_fps") or 0) >= args.fps * 0.98 and (stats.get("late_frames") or 0) <= max_late
    return stats


def synthetic_test(args):
    width, height = args.size.split("x", 1)
    source_cmd = [
        raw_source_binary(),
        "--width",
        width,
        "--height",
        height,
        "--fps",
        str(args.fps),
        "--duration",
        str(args.duration),
    ]
    if args.motion:
        source_cmd.extend(["--motion", "1"])
    source = subprocess.Popen(
        source_cmd,
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        preexec_fn=os.setsid,
    )
    ffmpeg = subprocess.Popen(
        [
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
            args.size,
            "-framerate",
            str(args.fps),
            "-i",
            "pipe:0",
            "-an",
            *encoder_args(args),
            "-f",
            "null",
            "-",
        ],
        cwd=ROOT,
        stdin=source.stdout,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        preexec_fn=os.setsid,
    )
    source.stdout.close()
    try:
        _, ffmpeg_stderr = ffmpeg.communicate(timeout=args.duration + 20)
        _, source_stderr = source.communicate(timeout=5)
    except subprocess.TimeoutExpired:
        stop_process(ffmpeg)
        stop_process(source)
        return {"ok": False, "error": "synthetic pipeline did not finish before timeout"}
    text = source_stderr.decode("utf-8", errors="replace") + "\n" + ffmpeg_stderr.decode("utf-8", errors="replace")
    stats = parse_source_stats(text)
    stats.update(parse_ffmpeg_stats(text))
    max_late = max(2, int(args.fps * args.duration * 0.03))
    stats["ok"] = (stats.get("avg_speed") or 0) >= 0.98 and (stats.get("late_frames") or 0) <= max_late
    return stats


def avfoundation_test(args):
    width, height = args.size.split("x", 1)
    vf = (
        f"fps={args.fps},scale={width}:{height}:force_original_aspect_ratio=decrease,"
        f"pad={width}:{height}:(ow-iw)/2:(oh-ih)/2,setpts=N/({args.fps}*TB)"
    )
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
        "0",
        "-i",
        "1:none",
        "-vf",
        vf,
        "-t",
        str(args.duration),
        "-an",
        *encoder_args(args),
        "-f",
        "null",
        "-",
    ]
    try:
        result = run(cmd, timeout=args.duration + 10)
    except subprocess.TimeoutExpired as error:
        stderr = ""
        if error.stderr:
            stderr = error.stderr.decode("utf-8", errors="replace") if isinstance(error.stderr, bytes) else error.stderr
        stats = parse_ffmpeg_stats(stderr)
        stats["ok"] = False
        stats["error"] = "avfoundation test did not finish before timeout"
        return stats
    stats = parse_ffmpeg_stats(result.stderr)
    stats["ok"] = (stats.get("avg_speed") or 0) >= 0.98
    return stats


def sck_test(args):
    conflicts = process_conflicts(args.port)
    active_sck = [line for line in conflicts["running_pipeline_processes"] if "sck-capture" in line]
    if active_sck:
        return {
            "ok": False,
            "error": "sck-capture is already running; stop the stream before the isolated sck test",
            "running_pipeline_processes": active_sck,
        }
    width, height = args.size.split("x", 1)
    cmd = [
        os.path.join(SERVER, "sck-capture"),
        "--width",
        width,
        "--height",
        height,
        "--fps",
        str(args.fps),
        "--queue-depth",
        str(args.queue_depth),
    ]
    if not os.path.exists(cmd[0]):
        run([
            "swiftc",
            os.path.join(SERVER, "sck-capture.swift"),
            "-framework",
            "ScreenCaptureKit",
            "-framework",
            "CoreMedia",
            "-framework",
            "CoreVideo",
            "-o",
            cmd[0],
        ], timeout=30)
    process = subprocess.Popen(
        cmd,
        cwd=ROOT,
        text=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
        preexec_fn=os.setsid,
    )
    deadline = time.time() + args.duration
    lines = []
    cpu_samples = []
    next_cpu_sample = time.time()
    while time.time() < deadline and process.poll() is None:
        line = process.stderr.readline()
        if line:
            lines.append(line.rstrip())
        else:
            time.sleep(0.05)
        if time.time() >= next_cpu_sample:
            cpu_samples.append(read_process_cpu([process.pid]))
            next_cpu_sample = time.time() + 1.0
    stop_process(process)
    rest = process.stderr.read() if process.stderr else ""
    if rest:
        lines.extend(rest.splitlines())
    text = "\n".join(lines)
    stats = parse_sck_stats(text)
    stats.update(summarize_cpu_samples(cpu_samples))
    expected = args.fps * 0.9
    stats["ok"] = (stats.get("avg_sck_fps") or 0) >= expected
    return stats


def sck_encode_test(args):
    conflicts = process_conflicts(args.port)
    active_sck = [line for line in conflicts["running_pipeline_processes"] if "sck-capture" in line]
    if active_sck:
        return {
            "ok": False,
            "error": "sck-capture is already running; stop the stream before the isolated sck-encode test",
            "running_pipeline_processes": active_sck,
        }
    width, height = args.size.split("x", 1)
    source = subprocess.Popen(
        [
            os.path.join(SERVER, "sck-capture"),
            "--width",
            width,
            "--height",
            height,
            "--fps",
            str(args.fps),
            "--queue-depth",
            str(args.queue_depth),
        ],
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        preexec_fn=os.setsid,
    )
    ffmpeg = subprocess.Popen(
        [
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
            args.size,
            "-framerate",
            str(args.fps),
            "-i",
            "pipe:0",
            "-an",
            *encoder_args(args),
            "-f",
            "null",
            "-",
        ],
        cwd=ROOT,
        stdin=source.stdout,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        preexec_fn=os.setsid,
    )
    source.stdout.close()
    cpu_samples = []
    deadline = time.time() + args.duration
    while time.time() < deadline and source.poll() is None and ffmpeg.poll() is None:
        cpu_samples.append(read_process_cpu([source.pid, ffmpeg.pid]))
        time.sleep(1)
    stop_process(source)
    try:
        _, ffmpeg_stderr = ffmpeg.communicate(timeout=10)
    except subprocess.TimeoutExpired:
        stop_process(ffmpeg)
        return {"ok": False, "error": "sck-encode ffmpeg did not finish after source stop"}
    source_stderr = source.stderr.read() if source.stderr else b""
    text = source_stderr.decode("utf-8", errors="replace") + "\n" + ffmpeg_stderr.decode("utf-8", errors="replace")
    stats = parse_sck_stats(text)
    stats.update(parse_ffmpeg_stats(text))
    stats.update(summarize_cpu_samples(cpu_samples))
    expected = args.fps * 0.9
    steady_sck_fps = stats.get("last_sck_fps") or stats.get("avg_sck_fps") or 0
    stats["ok"] = steady_sck_fps >= expected and (stats.get("avg_speed") or 0) >= 0.95
    return stats


def rtp_test(args):
    if not args.dest:
        return {"ok": False, "error": "--dest is required for rtp test"}
    conflicts = process_conflicts(args.port)
    if conflicts["rtp_sender_count"] > 0:
        return {
            "ok": False,
            "error": "an RTP sender is already running; stop the stream before the rtp test",
            "running_pipeline_processes": conflicts["running_pipeline_processes"],
        }
    cmd = [
        sys.executable,
        os.path.join(SERVER, "rtp-screen-server.py"),
        "--dest",
        args.dest,
        "--port",
        str(args.port),
        "--fps",
        str(args.fps),
        "--size",
        args.size,
        "--bitrate",
        args.bitrate,
        "--capture-backend",
        args.capture_backend,
        "--payload-size",
        str(args.payload_size),
    ]
    process = start(cmd)
    text = collect_process_output(process, args.duration)
    stats = parse_ffmpeg_stats(text)
    stats.update(parse_sck_stats(text))
    stats["ok"] = (stats.get("avg_speed") or 0) >= 0.95
    return stats


def android_decode_test(args):
    run(["adb", "logcat", "-c"])
    time.sleep(args.duration)
    result = run(["adb", "logcat", "-d", "-t", "500"])
    stats = parse_android_decode(result.stdout + result.stderr)
    stats["ok"] = bool(stats) and stats.get("done_rate_fps", 0) >= args.fps * 0.85
    return stats


def parse_aspect(value):
    left, right = value.split(":", 1)
    return float(left) / float(right)


def even(value):
    parsed = int(round(value))
    return parsed if parsed % 2 == 0 else parsed + 1


def encoder_args(args):
    if args.encoder == "videotoolbox":
        return [
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
    return [
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
    ]


def bisect_size_test(args):
    aspect = parse_aspect(args.aspect)
    low = args.min_width
    high = args.max_width
    best = None
    attempts = []
    for _ in range(args.passes):
        if low > high:
            break
        mid = even((low + high) / 2)
        height = even(mid / aspect)
        probe_args = argparse.Namespace(**vars(args))
        probe_args.size = f"{mid}x{height}"
        result = sck_encode_test(probe_args)
        attempts.append({"size": probe_args.size, **result})
        if result.get("ok"):
            best = attempts[-1]
            low = mid + 2
        else:
            high = mid - 2
    return {
        "ok": best is not None,
        "best": best,
        "attempts": attempts,
    }


def print_result(name, result, as_json):
    if as_json:
        print(json.dumps({name: result}, ensure_ascii=False, indent=2))
        return
    status = "OK" if result.get("ok") else "CHECK"
    print(f"\n[{status}] {name}")
    for key, value in result.items():
        if key != "ok":
            print(f"  {key}: {value}")


def main():
    args = parse_args()
    tests = {
        "processes": lambda: process_conflicts(args.port),
        "encoder": lambda: encoder_test(args),
        "source": lambda: source_test(args),
        "synthetic": lambda: synthetic_test(args),
        "avfoundation": lambda: avfoundation_test(args),
        "sck": lambda: sck_test(args),
        "sck-encode": lambda: sck_encode_test(args),
        "rtp": lambda: rtp_test(args),
        "android-decode": lambda: android_decode_test(args),
        "bisect-size": lambda: bisect_size_test(args),
    }
    order = ["processes", "encoder", "source", "synthetic", "avfoundation", "sck", "sck-encode", "rtp", "android-decode"]
    if args.test == "all":
        summary = {}
        for name in order:
            summary[name] = tests[name]()
            print_result(name, summary[name], False)
        if args.json:
            print(json.dumps(summary, ensure_ascii=False, indent=2))
        return
    print_result(args.test, tests[args.test](), args.json)


if __name__ == "__main__":
    main()
