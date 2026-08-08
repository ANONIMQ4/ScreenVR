package dev.screenvr;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.util.Log;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Set;

final class BluetoothHidHeadTracker implements SensorEventListener {
    interface StatusListener {
        void onStatus(String status);
    }

    private static final String TAG = "ScreenVR-BT-HID";
    private static final int REPORT_ID = 1;
    private static final float FULL_SCALE_DEGREES = 90f;

    private final Context context;
    private final SensorManager sensorManager;
    private final BluetoothAdapter adapter;
    private final Executor callbackExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService reportExecutor = Executors.newSingleThreadScheduledExecutor();
    private final float[] sensorRotationMatrix = new float[9];
    private final float[] latestRotationMatrix = new float[9];
    private final float[] centerInverseMatrix = new float[9];
    private final float[] relativeMatrix = new float[9];
    private final float[] orientation = new float[3];

    private StatusListener statusListener;
    private BluetoothHidDevice hidDevice;
    private BluetoothDevice connectedDevice;
    private Sensor rotationSensor;
    private ScheduledFuture<?> reportTask;
    private boolean registered;
    private boolean sensorRunning;
    private boolean hasRotation;
    private boolean centered;
    private boolean centerPending = true;

    BluetoothHidHeadTracker(Context context) {
        this.context = context.getApplicationContext();
        sensorManager = (SensorManager) this.context.getSystemService(Context.SENSOR_SERVICE);
        BluetoothManager bluetoothManager = (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
    }

    boolean hasRuntimePermission() {
        if (Build.VERSION.SDK_INT < 31) {
            return true;
        }
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
    }

    boolean isRunning() {
        return registered;
    }

    void setStatusListener(StatusListener statusListener) {
        this.statusListener = statusListener;
    }

    void start() {
        if (!hasRuntimePermission()) {
            report("Bluetooth permission required");
            return;
        }
        if (adapter == null) {
            report("Bluetooth adapter missing");
            return;
        }
        if (!adapter.isEnabled()) {
            report("Bluetooth is off");
            return;
        }
        if (registered) {
            center();
            report("BT HID recentered");
            return;
        }

        boolean requested = adapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile != BluetoothProfile.HID_DEVICE) {
                    return;
                }
                hidDevice = (BluetoothHidDevice) proxy;
                registerApp();
            }

            @Override
            public void onServiceDisconnected(int profile) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = null;
                    connectedDevice = null;
                    registered = false;
                    stopSensors();
                    report("BT HID disconnected");
                }
            }
        }, BluetoothProfile.HID_DEVICE);
        report(requested ? "BT HID proxy requested" : "BT HID proxy failed");
    }

    void stop() {
        stopSensors();
        connectedDevice = null;
        registered = false;
        if (hidDevice != null) {
            try {
                hidDevice.unregisterApp();
            } catch (SecurityException ignored) {
            }
            if (adapter != null) {
                adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice);
            }
            hidDevice = null;
        }
        report("BT HID stopped");
    }

    void center() {
        boolean centeredNow = false;
        synchronized (latestRotationMatrix) {
            if (hasRotation) {
                captureCenterLocked();
                centeredNow = true;
            } else {
                centerPending = true;
                centered = false;
            }
        }
        sendCurrentReport();
        report(centeredNow ? "BT HID center set" : "BT HID center pending");
    }

    private void registerApp() {
        if (hidDevice == null) {
            return;
        }
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "ScreenVR Head Tracker",
                "ScreenVR phone head tracker",
                "ScreenVR",
                (byte) (BluetoothHidDevice.SUBCLASS1_NONE | BluetoothHidDevice.SUBCLASS2_JOYSTICK),
                reportDescriptor()
        );
        BluetoothHidDeviceAppQosSettings qos = new BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                800,
                9,
                0,
                11250,
                BluetoothHidDeviceAppQosSettings.MAX
        );
        boolean ok = hidDevice.registerApp(sdp, qos, qos, callbackExecutor, new BluetoothHidDevice.Callback() {
            @Override
            public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registered) {
                BluetoothHidHeadTracker.this.registered = registered;
                if (registered) {
                    report("BT HID registered; pair Mac with ScreenVR Head Tracker");
                    startSensors();
                    connectBondedHosts();
                } else {
                    report("BT HID app unregistered");
                    stopSensors();
                }
            }

            @Override
            public void onConnectionStateChanged(BluetoothDevice device, int state) {
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    connectedDevice = device;
                    center();
                    report("BT HID connected: " + safeName(device));
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    if (device != null && device.equals(connectedDevice)) {
                        connectedDevice = null;
                    }
                    report("BT HID waiting for Mac");
                }
            }

            @Override
            public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
                if (hidDevice != null) {
                    hidDevice.replyReport(device, type, id, currentReport());
                }
            }
        });
        report(ok ? "BT HID registering" : "BT HID register failed");
    }

    private void startSensors() {
        if (sensorRunning) {
            return;
        }
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rotationSensor == null) {
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }
        if (rotationSensor == null) {
            report("No rotation vector sensor");
            return;
        }
        centered = false;
        centerPending = true;
        hasRotation = false;
        sensorRunning = sensorManager.registerListener(
                this,
                rotationSensor,
                SensorManager.SENSOR_DELAY_GAME
        );
        if (reportTask == null || reportTask.isCancelled()) {
            reportTask = reportExecutor.scheduleAtFixedRate(this::sendCurrentReport, 0, 16, TimeUnit.MILLISECONDS);
        }
    }

    private void connectBondedHosts() {
        if (adapter == null || hidDevice == null) {
            return;
        }
        try {
            Set<BluetoothDevice> bondedDevices = adapter.getBondedDevices();
            if (bondedDevices == null || bondedDevices.isEmpty()) {
                report("BT HID registered; no bonded hosts");
                return;
            }
            for (BluetoothDevice device : bondedDevices) {
                String name = safeName(device);
                if (name.toLowerCase().contains("mac") || name.toLowerCase().contains("qwerty")) {
                    boolean requested = hidDevice.connect(device);
                    report("BT HID connect " + name + ": " + requested);
                    return;
                }
            }
            for (BluetoothDevice device : bondedDevices) {
                boolean requested = hidDevice.connect(device);
                report("BT HID connect " + safeName(device) + ": " + requested);
                if (requested) {
                    return;
                }
            }
        } catch (SecurityException error) {
            report("BT HID connect permission denied");
        }
    }

    private void stopSensors() {
        if (reportTask != null) {
            reportTask.cancel(false);
            reportTask = null;
        }
        if (sensorRunning) {
            sensorManager.unregisterListener(this);
            sensorRunning = false;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        SensorManager.getRotationMatrixFromVector(sensorRotationMatrix, event.values);
        synchronized (latestRotationMatrix) {
            System.arraycopy(sensorRotationMatrix, 0, latestRotationMatrix, 0, latestRotationMatrix.length);
            hasRotation = true;
            if (!centered || centerPending) {
                captureCenterLocked();
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void sendCurrentReport() {
        BluetoothDevice device = connectedDevice;
        BluetoothHidDevice hid = hidDevice;
        if (!registered || device == null || hid == null) {
            return;
        }
        try {
            hid.sendReport(device, REPORT_ID, currentReport());
        } catch (SecurityException error) {
            report("BT HID permission lost");
        }
    }

    private byte[] currentReport() {
        float yaw;
        float pitch;
        float roll;
        synchronized (latestRotationMatrix) {
            if (!hasRotation || !centered) {
                return reportFromAxes((short) 0, (short) 0, (short) 0);
            }
            multiply3x3(centerInverseMatrix, latestRotationMatrix, relativeMatrix);
            SensorManager.getOrientation(relativeMatrix, orientation);
            yaw = orientation[0];
            pitch = orientation[1];
            roll = orientation[2];
        }

        return reportFromAngles(yaw, pitch, roll);
    }

    private void captureCenterLocked() {
        transpose3x3(latestRotationMatrix, centerInverseMatrix);
        centered = true;
        centerPending = false;
    }

    private static byte[] reportFromAxes(short yawAxis, short pitchAxis, short rollAxis) {
        return reportFromAxes(yawAxis, pitchAxis, (short) 0, rollAxis, (short) 0, (short) 0);
    }

    private static byte[] reportFromAxes(short xAxis, short yAxis, short zAxis,
                                         short rxAxis, short ryAxis, short rzAxis) {
        byte[] report = new byte[13];
        report[0] = 0x08; // centered hat switch, no buttons.
        putShort(report, 1, xAxis);
        putShort(report, 3, yAxis);
        putShort(report, 5, zAxis);
        putShort(report, 7, rxAxis);
        putShort(report, 9, ryAxis);
        putShort(report, 11, rzAxis);
        return report;
    }

    private byte[] reportFromAngles(float yaw, float pitch, float roll) {
        return reportFromAxes(axisFromRadians(-pitch), (short) 0, axisFromRadians(-roll));
    }

    private static short axisFromRadians(float radians) {
        float degrees = (float) Math.toDegrees(radians);
        float normalized = Math.max(-1f, Math.min(1f, degrees / FULL_SCALE_DEGREES));
        return (short) Math.round(normalized * 32767f);
    }

    private static void transpose3x3(float[] source, float[] destination) {
        destination[0] = source[0];
        destination[1] = source[3];
        destination[2] = source[6];
        destination[3] = source[1];
        destination[4] = source[4];
        destination[5] = source[7];
        destination[6] = source[2];
        destination[7] = source[5];
        destination[8] = source[8];
    }

    private static void multiply3x3(float[] left, float[] right, float[] destination) {
        float d0 = left[0] * right[0] + left[1] * right[3] + left[2] * right[6];
        float d1 = left[0] * right[1] + left[1] * right[4] + left[2] * right[7];
        float d2 = left[0] * right[2] + left[1] * right[5] + left[2] * right[8];
        float d3 = left[3] * right[0] + left[4] * right[3] + left[5] * right[6];
        float d4 = left[3] * right[1] + left[4] * right[4] + left[5] * right[7];
        float d5 = left[3] * right[2] + left[4] * right[5] + left[5] * right[8];
        float d6 = left[6] * right[0] + left[7] * right[3] + left[8] * right[6];
        float d7 = left[6] * right[1] + left[7] * right[4] + left[8] * right[7];
        float d8 = left[6] * right[2] + left[7] * right[5] + left[8] * right[8];
        destination[0] = d0;
        destination[1] = d1;
        destination[2] = d2;
        destination[3] = d3;
        destination[4] = d4;
        destination[5] = d5;
        destination[6] = d6;
        destination[7] = d7;
        destination[8] = d8;
    }

    private static void putShort(byte[] data, int offset, short value) {
        data[offset] = (byte) (value & 0xff);
        data[offset + 1] = (byte) ((value >> 8) & 0xff);
    }

    private static String safeName(BluetoothDevice device) {
        if (device == null) {
            return "unknown";
        }
        try {
            String name = device.getName();
            return name != null ? name : device.getAddress();
        } catch (SecurityException ignored) {
            return "unknown";
        }
    }

    private void report(String status) {
        Log.i(TAG, status);
        StatusListener listener = statusListener;
        if (listener != null) {
            listener.onStatus(status);
        }
    }

    private static byte[] reportDescriptor() {
        return new byte[] {
                0x05, 0x01,             // Usage Page (Generic Desktop)
                0x09, 0x04,             // Usage (Joystick)
                (byte) 0xA1, 0x01,      // Collection (Application)
                (byte) 0x85, REPORT_ID, //   Report ID (1)
                0x15, 0x01,             //   Logical Minimum (1)
                0x25, 0x08,             //   Logical Maximum (8)
                0x35, 0x01,             //   Physical Minimum (1)
                0x45, 0x08,             //   Physical Maximum (8)
                0x75, 0x04,             //   Report Size (4)
                (byte) 0x95, 0x01,      //   Report Count (1)
                0x65, 0x00,             //   Unit (None)
                0x09, 0x39,             //   Usage (Hat switch)
                (byte) 0x81, 0x42,      //   Input (Data, Variable, Absolute, Null)
                0x05, 0x09,             //   Usage Page (Button)
                0x19, 0x01,             //   Usage Minimum (Button 1)
                0x29, 0x04,             //   Usage Maximum (Button 4)
                0x15, 0x00,             //   Logical Minimum (0)
                0x25, 0x01,             //   Logical Maximum (1)
                0x75, 0x01,             //   Report Size (1)
                (byte) 0x95, 0x04,      //   Report Count (4)
                (byte) 0x81, 0x02,      //   Input (Data, Variable, Absolute)
                0x05, 0x01,             //   Usage Page (Generic Desktop)
                0x16, 0x01, (byte) 0x80,//   Logical Minimum (-32767)
                0x26, (byte) 0xff, 0x7f,//   Logical Maximum (32767)
                0x75, 0x10,             //   Report Size (16)
                (byte) 0x95, 0x06,      //   Report Count (6)
                0x09, 0x30,             //   Usage (X)
                0x09, 0x31,             //   Usage (Y)
                0x09, 0x32,             //   Usage (Z)
                0x09, 0x33,             //   Usage (Rx)
                0x09, 0x34,             //   Usage (Ry)
                0x09, 0x35,             //   Usage (Rz)
                (byte) 0x81, 0x02,      //   Input (Data, Variable, Absolute)
                (byte) 0xC0             // End Collection
        };
    }
}
