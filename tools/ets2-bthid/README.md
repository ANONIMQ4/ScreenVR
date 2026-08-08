# ETS2 Bluetooth HID Head Tracking

Experimental helper for mapping the Android Bluetooth HID head tracker into
ETS2 controls.

Flow:

1. Start ScreenVR on Android.
2. Tap `BT HID`.
3. Allow Bluetooth discoverability.
4. Pair/connect the phone from macOS Bluetooth settings.
5. Start ETS2 once so it records the phone in `global_controls.sii`.
6. Quit ETS2.
7. Run:

```sh
python3 tools/ets2-bthid/apply_controls.py
```

The script maps the latest phone HID device to `joy2` in every ETS2 Steam
profile and routes:

- `joy2.x` -> TrackIR yaw
- `joy2.rx` -> TrackIR pitch
- `0` -> TrackIR roll

It writes a timestamped backup next to each edited `controls_osx.sii`.
