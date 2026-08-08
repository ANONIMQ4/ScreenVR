# ScreenVR HID Bridge

Experimental macOS virtual HID bridge for ETS2 head tracking.

The first test binary creates a virtual joystick named `ScreenVR Head Tracker`
with six absolute axes:

- `X`: yaw
- `Y`: pitch
- `Z`: reserved
- `Rx`: roll
- `Ry`: reserved
- `Rz`: reserved

Build:

```sh
clang screenvr-hid-bridge.c -framework IOKit -framework CoreFoundation -lm -o screenvr-hid-bridge
```

Run:

```sh
./screenvr-hid-bridge 120
```

If macOS allows `IOHIDUserDevice` from this process, ETS2 should log a detected
HID gamepad after the game starts. Newer macOS SDK headers say this API requires
the `com.apple.developer.hid.virtual.device` entitlement, so the no-signing path
must be verified on the target machine before Android sensor input is attached.
