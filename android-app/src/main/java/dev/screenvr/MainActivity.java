package dev.screenvr;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.Surface;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public class MainActivity extends Activity implements VrVideoView.VideoSurfaceListener {
    private static final String PREFS = "screen_vr_client";
    private static final String URL_KEY = "stream_url";
    private static final String SBS_KEY = "sbs_enabled";
    private static final String WIDTH_KEY = "raw_width";
    private static final String HEIGHT_KEY = "raw_height";
    private static final String FPS_KEY = "raw_fps";
    private static final String BITRATE_KEY = "raw_bitrate";
    private static final String FIT_KEY = "raw_fit";
    private static final String CONTROL_URL_KEY = "control_url";
    private static final String EYE_OFFSET_KEY = "eye_offset";
    private static final String LENS_MODE_KEY = "lens_mode";
    private static final String LENS_STRENGTH_KEY = "lens_strength";
    private static final String LENS_ZOOM_KEY = "lens_zoom";
    private static final String LENS_CENTER_KEY = "lens_center";
    private static final String LENS_SIZE_X_KEY = "lens_size_x";
    private static final String LENS_SIZE_Y_KEY = "lens_size_y";
    private static final String LENS_MASK_KEY = "lens_mask";
    private static final int DEFAULT_LENS_MODE = 1;
    private static final float DEFAULT_LENS_STRENGTH = 35f;
    private static final float DEFAULT_LENS_ZOOM = 105f;
    private static final float DEFAULT_LENS_CENTER = 3f;
    private static final float DEFAULT_LENS_SIZE_X = 92f;
    private static final float DEFAULT_LENS_SIZE_Y = 92f;
    private static final int DEFAULT_LENS_MASK = 1;
    private static final String DEFAULT_URL = "rtph264://0.0.0.0:5004?w=900&h=600&fps=60";

    private RawH264Player h264Player;
    private VrVideoView videoView;
    private LinearLayout controls;
    private EditText urlInput;
    private EditText widthInput;
    private EditText heightInput;
    private EditText fpsInput;
    private EditText bitrateInput;
    private EditText eyeOffsetInput;
    private EditText lensStrengthInput;
    private EditText zoomInput;
    private EditText centerOffsetInput;
    private EditText sizeXInput;
    private EditText sizeYInput;
    private TextView hintView;
    private TextView statsView;
    private Button sbsButton;
    private Button fitButton;
    private Button lensButton;
    private Button maskButton;
    private Surface videoSurface;
    private String activeRawUrl;
    private Surface activeRawSurface;
    private final FrameLatencyTracker latencyTracker = new FrameLatencyTracker();
    private volatile float decoderReadFps;
    private volatile float decoderInputFps;
    private volatile float decoderOutputFps;
    private volatile float decoderDroppedFps;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String savedUrl = prefs.getString(URL_KEY, DEFAULT_URL);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xff050505);

        videoView = new VrVideoView(this);
        videoView.setVideoSurfaceListener(this);
        videoView.setLatencyTracker(latencyTracker);
        videoView.setFrameStatsListener((fps, latencyMs, queuedFrames) -> runOnUiThread(() -> {
            if (statsView != null) {
                String latencyText = latencyMs >= 0 ? latencyMs + "ms" : "--ms";
                statsView.setText(String.format(
                        Locale.US,
                        "gl %.1f  read %.1f  in %.1f  out %.1f  drop %.1f  lag %s  q %d",
                        fps,
                        decoderReadFps,
                        decoderInputFps,
                        decoderOutputFps,
                        decoderDroppedFps,
                        latencyText,
                        queuedFrames
                ));
            }
        }));
        videoView.setSbsMode(prefs.getBoolean(SBS_KEY, false));
        videoView.setEyeOffsetPercent(prefs.getInt(EYE_OFFSET_KEY, 0));
        videoView.setLensSettings(
                prefs.getInt(LENS_MODE_KEY, DEFAULT_LENS_MODE),
                prefs.getFloat(LENS_STRENGTH_KEY, DEFAULT_LENS_STRENGTH),
                prefs.getFloat(LENS_ZOOM_KEY, DEFAULT_LENS_ZOOM),
                prefs.getFloat(LENS_CENTER_KEY, DEFAULT_LENS_CENTER),
                prefs.getFloat(LENS_SIZE_X_KEY, DEFAULT_LENS_SIZE_X),
                prefs.getFloat(LENS_SIZE_Y_KEY, DEFAULT_LENS_SIZE_Y),
                prefs.getInt(LENS_MASK_KEY, DEFAULT_LENS_MASK)
        );
        root.addView(videoView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        controls = buildControls(savedUrl);
        FrameLayout.LayoutParams controlsParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        );
        root.addView(controls, controlsParams);

        hintView = new TextView(this);
        hintView.setText("Tap video to show controls");
        hintView.setTextColor(0x99ffffff);
        hintView.setTextSize(12);
        hintView.setGravity(Gravity.CENTER);
        hintView.setPadding(0, 16, 0, 0);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
        );
        root.addView(hintView, hintParams);

        statsView = new TextView(this);
        statsView.setText("fps --");
        statsView.setTextColor(0xccffffff);
        statsView.setTextSize(12);
        statsView.setGravity(Gravity.RIGHT);
        statsView.setPadding(12, 8, 12, 8);
        statsView.setBackgroundColor(0x66000000);
        FrameLayout.LayoutParams statsParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT
        );
        statsParams.setMargins(0, 12, 12, 0);
        root.addView(statsView, statsParams);

        videoView.setOnClickListener(v -> toggleControls());
        setContentView(root);
        hideSystemUi();
        startPlayer(savedUrl);
    }

    private LinearLayout buildControls(String savedUrl) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(18, 10, 18, 14);
        box.setBackgroundColor(0xcc111111);

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(topRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setText(savedUrl);
        urlInput.setTextColor(0xffffffff);
        urlInput.setHintTextColor(0x88ffffff);
        urlInput.setHint("rtph264://0.0.0.0:5004?w=900&h=600&fps=60");
        urlInput.setTextSize(14);
        urlInput.setSelectAllOnFocus(true);
        topRow.addView(urlInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button play = new Button(this);
        play.setText("Play");
        play.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(URL_KEY, url).apply();
            hideKeyboard();
            startPlayer(url);
        });
        topRow.addView(play);

        sbsButton = new Button(this);
        updateSbsButton();
        sbsButton.setOnClickListener(v -> {
            boolean enabled = !videoView.isSbsMode();
            videoView.setSbsMode(enabled);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(SBS_KEY, enabled).apply();
            updateSbsButton();
            hideSystemUi();
        });
        topRow.addView(sbsButton);

        Button hide = new Button(this);
        hide.setText("Hide");
        hide.setOnClickListener(v -> {
            hideKeyboard();
            controls.setVisibility(View.GONE);
            setOverlayVisible(false);
            hideSystemUi();
        });
        topRow.addView(hide);

        LinearLayout settingsRow = new LinearLayout(this);
        settingsRow.setOrientation(LinearLayout.HORIZONTAL);
        settingsRow.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(settingsRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        widthInput = smallInput(String.valueOf(prefs.getInt(WIDTH_KEY, 1170)), "W");
        heightInput = smallInput(String.valueOf(prefs.getInt(HEIGHT_KEY, 1080)), "H");
        fpsInput = smallInput(String.valueOf(prefs.getInt(FPS_KEY, 30)), "FPS");
        bitrateInput = smallInput(String.valueOf(prefs.getInt(BITRATE_KEY, 5000)), "Kbps");

        settingsRow.addView(widthInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        settingsRow.addView(heightInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        settingsRow.addView(fpsInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        settingsRow.addView(bitrateInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout viewRow = new LinearLayout(this);
        viewRow.setOrientation(LinearLayout.HORIZONTAL);
        viewRow.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(viewRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        eyeOffsetInput = smallInput(String.valueOf(prefs.getInt(EYE_OFFSET_KEY, 0)), "Eye %");
        viewRow.addView(eyeOffsetInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        fitButton = new Button(this);
        fitButton.setText(prefs.getString(FIT_KEY, "contain"));
        fitButton.setOnClickListener(v -> {
            fitButton.setText("contain".contentEquals(fitButton.getText()) ? "cover" : "contain");
            hideSystemUi();
        });
        viewRow.addView(fitButton);

        Button apply = new Button(this);
        apply.setText("Apply");
        apply.setOnClickListener(v -> applyRawH264Settings());
        viewRow.addView(apply);

        LinearLayout lensRow = new LinearLayout(this);
        lensRow.setOrientation(LinearLayout.HORIZONTAL);
        lensRow.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(lensRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        lensButton = new Button(this);
        updateLensButton(prefs.getInt(LENS_MODE_KEY, DEFAULT_LENS_MODE));
        lensButton.setOnClickListener(v -> {
            int next = (lensModeFromButton() + 1) % 6;
            updateLensButton(next);
            applyLensSettings();
            hideSystemUi();
        });
        lensRow.addView(lensButton);

        lensStrengthInput = smallInput(formatFloat(prefs.getFloat(LENS_STRENGTH_KEY, DEFAULT_LENS_STRENGTH)), "Lens");
        zoomInput = smallInput(formatFloat(prefs.getFloat(LENS_ZOOM_KEY, DEFAULT_LENS_ZOOM)), "Zoom");
        centerOffsetInput = smallInput(formatFloat(prefs.getFloat(LENS_CENTER_KEY, DEFAULT_LENS_CENTER)), "Center");
        lensRow.addView(lensStrengthInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        lensRow.addView(zoomInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        lensRow.addView(centerOffsetInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        maskButton = new Button(this);
        updateMaskButton(prefs.getInt(LENS_MASK_KEY, DEFAULT_LENS_MASK));
        maskButton.setOnClickListener(v -> {
            updateMaskButton(maskModeFromButton() == 0 ? 1 : 0);
            applyLensSettings();
            hideSystemUi();
        });
        lensRow.addView(maskButton);

        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        sizeRow.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(sizeRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        sizeXInput = smallInput(formatFloat(prefs.getFloat(LENS_SIZE_X_KEY, DEFAULT_LENS_SIZE_X)), "Size X");
        sizeYInput = smallInput(formatFloat(prefs.getFloat(LENS_SIZE_Y_KEY, DEFAULT_LENS_SIZE_Y)), "Size Y");
        sizeRow.addView(sizeXInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        sizeRow.addView(sizeYInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button lensApply = new Button(this);
        lensApply.setText("Lens apply");
        lensApply.setOnClickListener(v -> {
            applyLensSettings();
            hideKeyboard();
            hideSystemUi();
        });
        sizeRow.addView(lensApply);

        return box;
    }

    private EditText smallInput(String value, String hint) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(value);
        input.setHint(hint);
        input.setTextColor(0xffffffff);
        input.setHintTextColor(0x88ffffff);
        input.setTextSize(13);
        input.setSelectAllOnFocus(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        return input;
    }

    private void applyRawH264Settings() {
        int width = parseInt(widthInput.getText().toString(), 1170);
        int height = parseInt(heightInput.getText().toString(), 1080);
        int fps = clampInt(parseInt(fpsInput.getText().toString(), 30), 10, 300);
        int bitrate = parseInt(bitrateInput.getText().toString(), 5000);
        int eyeOffset = clampInt(parseInt(eyeOffsetInput.getText().toString(), 0), 0, 5);
        int lensMode = lensModeFromButton();
        float lensStrength = clampFloat(parseFloat(lensStrengthInput.getText().toString(), 0f), 0f, 100f);
        float zoom = clampFloat(parseFloat(zoomInput.getText().toString(), 100f), 50f, 200f);
        float centerOffset = clampFloat(parseFloat(centerOffsetInput.getText().toString(), 0f), -20f, 20f);
        float sizeX = clampFloat(parseFloat(sizeXInput.getText().toString(), 100f), 50f, 150f);
        float sizeY = clampFloat(parseFloat(sizeYInput.getText().toString(), 100f), 50f, 150f);
        int maskMode = maskModeFromButton();
        String fit = fitButton.getText().toString().toLowerCase();
        String currentUrl = urlInput.getText().toString().trim();
        String url = buildRawH264Url(currentUrl, width, height, fps);
        String controlEndpoint = controlEndpointFor(currentUrl);

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(WIDTH_KEY, width)
                .putInt(HEIGHT_KEY, height)
                .putInt(FPS_KEY, fps)
                .putInt(BITRATE_KEY, bitrate)
                .putInt(EYE_OFFSET_KEY, eyeOffset)
                .putInt(LENS_MODE_KEY, lensMode)
                .putFloat(LENS_STRENGTH_KEY, lensStrength)
                .putFloat(LENS_ZOOM_KEY, zoom)
                .putFloat(LENS_CENTER_KEY, centerOffset)
                .putFloat(LENS_SIZE_X_KEY, sizeX)
                .putFloat(LENS_SIZE_Y_KEY, sizeY)
                .putInt(LENS_MASK_KEY, maskMode)
                .putString(FIT_KEY, fit)
                .putString(URL_KEY, url)
                .apply();

        eyeOffsetInput.setText(String.valueOf(eyeOffset));
        fpsInput.setText(String.valueOf(fps));
        videoView.setEyeOffsetPercent(eyeOffset);
        lensStrengthInput.setText(formatFloat(lensStrength));
        zoomInput.setText(formatFloat(zoom));
        centerOffsetInput.setText(formatFloat(centerOffset));
        sizeXInput.setText(formatFloat(sizeX));
        sizeYInput.setText(formatFloat(sizeY));
        videoView.setLensSettings(lensMode, lensStrength, zoom, centerOffset, sizeX, sizeY, maskMode);
        urlInput.setText(url);
        hideKeyboard();
        sendRawH264Config(width, height, fps, bitrate, fit, url, controlEndpoint);
    }

    private void applyLensSettings() {
        int lensMode = lensModeFromButton();
        float lensStrength = clampFloat(parseFloat(lensStrengthInput.getText().toString(), 0f), 0f, 100f);
        float zoom = clampFloat(parseFloat(zoomInput.getText().toString(), 100f), 50f, 200f);
        float centerOffset = clampFloat(parseFloat(centerOffsetInput.getText().toString(), 0f), -20f, 20f);
        float sizeX = clampFloat(parseFloat(sizeXInput.getText().toString(), 100f), 50f, 150f);
        float sizeY = clampFloat(parseFloat(sizeYInput.getText().toString(), 100f), 50f, 150f);
        int maskMode = maskModeFromButton();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(LENS_MODE_KEY, lensMode)
                .putFloat(LENS_STRENGTH_KEY, lensStrength)
                .putFloat(LENS_ZOOM_KEY, zoom)
                .putFloat(LENS_CENTER_KEY, centerOffset)
                .putFloat(LENS_SIZE_X_KEY, sizeX)
                .putFloat(LENS_SIZE_Y_KEY, sizeY)
                .putInt(LENS_MASK_KEY, maskMode)
                .apply();
        lensStrengthInput.setText(formatFloat(lensStrength));
        zoomInput.setText(formatFloat(zoom));
        centerOffsetInput.setText(formatFloat(centerOffset));
        sizeXInput.setText(formatFloat(sizeX));
        sizeYInput.setText(formatFloat(sizeY));
        videoView.setLensSettings(lensMode, lensStrength, zoom, centerOffset, sizeX, sizeY, maskMode);
    }

    private String buildRawH264Url(String currentUrl, int width, int height, int fps) {
        Uri uri = Uri.parse(currentUrl);
        String scheme = uri.getScheme();
        if ("rtph264".equals(scheme)) {
            int port = uri.getPort() > 0 ? uri.getPort() : 5004;
            String host = uri.getHost() != null ? uri.getHost() : "0.0.0.0";
            return "rtph264://" + host + ":" + port + "?w=" + width + "&h=" + height + "&fps=" + fps;
        }
        if ("rawh264".equals(scheme)) {
            int port = uri.getPort() > 0 ? uri.getPort() : 8094;
            String host = uri.getHost() != null ? uri.getHost() : "127.0.0.1";
            return "rawh264://" + host + ":" + port + "?w=" + width + "&h=" + height + "&fps=" + fps;
        }
        return "rtph264://0.0.0.0:5004?w=" + width + "&h=" + height + "&fps=" + fps;
    }

    private String controlEndpointFor(String currentUrl) {
        Uri uri = Uri.parse(currentUrl);
        String embedded = uri.getQueryParameter("control");
        if (embedded != null && !embedded.isEmpty()) {
            return embedded;
        }
        if ("rtph264".equals(uri.getScheme())) {
            return getSharedPreferences(PREFS, MODE_PRIVATE).getString(CONTROL_URL_KEY, "");
        }
        return "http://127.0.0.1:8095/config";
    }

    private void sendRawH264Config(int width, int height, int fps, int bitrate, String fit, String streamUrl, String controlEndpoint) {
        String json = "{"
                + "\"width\":" + width + ","
                + "\"height\":" + height + ","
                + "\"fps\":" + fps + ","
                + "\"bitrate\":" + bitrate + ","
                + "\"fit\":\"" + fit + "\""
                + "}";
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                if (controlEndpoint != null && !controlEndpoint.isEmpty()) {
                    URL endpoint = new URL(controlEndpoint);
                    connection = (HttpURLConnection) endpoint.openConnection();
                    connection.setRequestMethod("POST");
                    connection.setConnectTimeout(1000);
                    connection.setReadTimeout(1500);
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json");
                    byte[] data = json.getBytes("UTF-8");
                    connection.setFixedLengthStreamingMode(data.length);
                    try (OutputStream output = connection.getOutputStream()) {
                        output.write(data);
                    }
                    connection.getResponseCode();
                }
            } catch (Exception ignored) {
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            runOnUiThread(() -> startPlayer(streamUrl));
        }, "raw-h264-config").start();
    }

    private void startPlayer(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        startRawH264(withFpsParameter(url));
    }

    private String withFpsParameter(String url) {
        Uri uri = Uri.parse(url);
        if (uri.getQueryParameter("fps") != null) {
            return url;
        }
        int fps = fpsInput != null
                ? clampInt(parseInt(fpsInput.getText().toString(), 30), 10, 300)
                : 30;
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "fps=" + fps;
    }

    private void startRawH264(String url) {
        if (url.equals(activeRawUrl) && videoSurface != null && videoSurface == activeRawSurface) {
            return;
        }
        videoView.setVisibility(View.VISIBLE);
        if (h264Player == null) {
            h264Player = new RawH264Player();
            h264Player.setLatencyTracker(latencyTracker);
            h264Player.setDecoderStatsListener((readFps, inputFps, outputFps, droppedFps) -> {
                decoderReadFps = readFps;
                decoderInputFps = inputFps;
                decoderOutputFps = outputFps;
                decoderDroppedFps = droppedFps;
            });
        }
        Uri uri = Uri.parse(url);
        int width = parseInt(uri.getQueryParameter("w"), 16);
        int height = parseInt(uri.getQueryParameter("h"), 9);
        videoView.setVideoAspect(width / (float) height);
        if (videoSurface != null) {
            h264Player.start(url, videoSurface);
            activeRawUrl = url;
            activeRawSurface = videoSurface;
        }
    }

    private void stopRawH264() {
        if (h264Player != null) {
            h264Player.stop();
        }
        activeRawUrl = null;
        activeRawSurface = null;
    }

    private int parseInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private float parseFloat(String value, float fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private int clampInt(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private float clampFloat(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private String formatFloat(float value) {
        if (Math.abs(value - Math.round(value)) < 0.05f) {
            return String.valueOf(Math.round(value));
        }
        return String.format(Locale.US, "%.1f", value);
    }

    private void updateSbsButton() {
        if (sbsButton != null) {
            sbsButton.setText(videoView != null && videoView.isSbsMode() ? "Flat" : "SBS");
        }
    }

    private int lensModeFromButton() {
        if (lensButton == null) {
            return 0;
        }
        CharSequence text = lensButton.getText();
        if ("Barrel".contentEquals(text)) {
            return 1;
        }
        if ("Barrel+".contentEquals(text)) {
            return 2;
        }
        if ("Pin".contentEquals(text)) {
            return 3;
        }
        if ("Fish".contentEquals(text)) {
            return 4;
        }
        if ("Soft".contentEquals(text)) {
            return 5;
        }
        return 0;
    }

    private void updateLensButton(int mode) {
        if (lensButton == null) {
            return;
        }
        if (mode == 1) {
            lensButton.setText("Barrel");
        } else if (mode == 2) {
            lensButton.setText("Barrel+");
        } else if (mode == 3) {
            lensButton.setText("Pin");
        } else if (mode == 4) {
            lensButton.setText("Fish");
        } else if (mode == 5) {
            lensButton.setText("Soft");
        } else {
            lensButton.setText("Lens off");
        }
    }

    private int maskModeFromButton() {
        if (maskButton == null) {
            return DEFAULT_LENS_MASK;
        }
        return "Mask YT".contentEquals(maskButton.getText()) ? 1 : 0;
    }

    private void updateMaskButton(int mode) {
        if (maskButton != null) {
            maskButton.setText(mode == 1 ? "Mask YT" : "Mask off");
        }
    }

    @Override
    public void onVideoSurfaceReady(Surface surface) {
        videoSurface = surface;
        String url = urlInput != null ? urlInput.getText().toString().trim() : "";
        startRawH264(url);
    }

    @Override
    public void onVideoSurfaceDestroyed(Surface surface) {
        stopRawH264();
        if (surface == videoSurface) {
            videoSurface = null;
        }
        activeRawUrl = null;
        activeRawSurface = null;
    }

    private void toggleControls() {
        boolean show = controls.getVisibility() != View.VISIBLE;
        controls.setVisibility(show ? View.VISIBLE : View.GONE);
        setOverlayVisible(show);
        hideSystemUi();
    }

    private void setOverlayVisible(boolean visible) {
        int visibility = visible ? View.VISIBLE : View.GONE;
        if (hintView != null) {
            hintView.setVisibility(visibility);
        }
        if (statsView != null) {
            statsView.setVisibility(visibility);
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(urlInput.getWindowToken(), 0);
        urlInput.clearFocus();
    }

    private void hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRawH264();
        videoSurface = null;
    }
}
