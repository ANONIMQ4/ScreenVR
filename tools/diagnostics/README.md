# ScreenVR Diagnostics

These scripts isolate likely bottlenecks in the Mac -> Android streaming path.

Run from the repository root:

```sh
python3 tools/diagnostics/pipeline_diagnostics.py processes
python3 tools/diagnostics/pipeline_diagnostics.py encoder --size 900x600 --fps 60
python3 tools/diagnostics/pipeline_diagnostics.py avfoundation --size 900x600 --fps 60
python3 tools/diagnostics/pipeline_diagnostics.py sck --size 900x600 --fps 60
python3 tools/diagnostics/pipeline_diagnostics.py sck-encode --size 900x600 --fps 60 --bitrate 3000k
python3 tools/diagnostics/pipeline_diagnostics.py rtp --dest 192.168.0.202 --size 900x600 --fps 60 --bitrate 3000k --payload-size 900
python3 tools/diagnostics/pipeline_diagnostics.py android-decode --fps 60
```

`sck` and `rtp` are isolated tests. Stop the normal stream first, otherwise they will refuse to run so they do not create a second capture or a second RTP sender.

What each test checks:

- `processes`: detects old ffmpeg/RTP senders still writing to the same UDP port.
- `encoder`: isolates x264 by encoding a generated test pattern.
- `avfoundation`: measures AVFoundation capture plus ffmpeg scale/encode.
- `sck`: measures ScreenCaptureKit capture FPS before encoding/network.
- `rtp`: runs the real RTP server for a short window and parses ffmpeg/SCK stats.
- `android-decode`: reads Android MediaCodec work-rate from logcat.

Useful stress probes:

```sh
python3 tools/diagnostics/pipeline_diagnostics.py encoder --size 450x300 --fps 300
python3 tools/diagnostics/pipeline_diagnostics.py avfoundation --size 450x300 --fps 300
python3 tools/diagnostics/pipeline_diagnostics.py sck --size 900x600 --fps 60
```

`sck` and `sck-encode` also report capture pacing:

- `avg_interval_ms`: average time between captured frames.
- `avg_p95_interval_ms`: typical worst-case capture interval; high values feel like micro-stutter.
- `worst_interval_ms`: largest captured-frame gap seen in the run.
- `late_frames`: frames above 1.5x the expected interval.

If `encoder` is fast but `avfoundation` is slow, capture/scale is the bottleneck.
If `rtp` is fast but `android-decode` is slow, the phone decoder/render path is the bottleneck.
If `processes` reports more than one RTP sender, stop old streams before judging quality.
