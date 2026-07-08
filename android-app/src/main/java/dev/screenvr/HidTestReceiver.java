package dev.screenvr;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class HidTestReceiver extends BroadcastReceiver {
    public static final String ACTION = "dev.screenvr.HID_TEST";
    private static final String TAG = "ScreenVR-BT-HID";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        BluetoothHidHeadTracker tracker = BluetoothHidHeadTracker.activeTracker();
        if (tracker == null || !tracker.isRunning()) {
            Log.i(TAG, "BT HID debug ignored: tracker is not running");
            return;
        }
        if (intent.hasExtra("mode")) {
            tracker.setAxisMode(intent.getIntExtra("mode", tracker.axisMode()));
            return;
        }
        short x = clampAxis(intent.getIntExtra("x", intent.getIntExtra("yaw", 0)));
        short y = clampAxis(intent.getIntExtra("y", intent.getIntExtra("pitch", 0)));
        short z = clampAxis(intent.getIntExtra("z", 0));
        short rx = clampAxis(intent.getIntExtra("rx", intent.getIntExtra("roll", 0)));
        short ry = clampAxis(intent.getIntExtra("ry", 0));
        short rz = clampAxis(intent.getIntExtra("rz", 0));
        long durationMs = intent.getLongExtra("duration", 1200L);
        tracker.setDebugAxes(x, y, z, rx, ry, rz, durationMs);
    }

    private static short clampAxis(int value) {
        if (value > 32767) {
            return 32767;
        }
        if (value < -32767) {
            return -32767;
        }
        return (short) value;
    }
}
