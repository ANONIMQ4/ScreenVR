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

public class MainActivity extends Activity implements VrVideoView.VideoSurfaceListener {
    private static final String PREFS = "screen_vr_client";
    private static final String URL_KEY = "stream_url";
    private static final String SBS_KEY = "sbs_enabled";
    private static final String WIDTH_KEY = "raw_width";
    private static final String HEIGHT_KEY = "raw_height";
    private static final String FPS_KEY = "raw_fps";
    private static final String BITRATE_KEY = "raw_bitrate";
    private static final String FIT_KEY = "raw_fit";
    private static final String DEFAULT_URL = "rawh264://127.0.0.1:8094?w=1170&h=1080";

    private RawH264Player h264Player;
    private VrVideoView videoView;
    private LinearLayout controls;
    private EditText urlInput;
    private EditText widthInput;
    private EditText heightInput;
    private EditText fpsInput;
    private EditText bitrateInput;
    private Button sbsButton;
    private Button fitButton;
    private Surface videoSurface;
    private String activeRawUrl;
    private Surface activeRawSurface;

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
        videoView.setSbsMode(prefs.getBoolean(SBS_KEY, false));
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

        TextView hint = new TextView(this);
        hint.setText("Tap video to show controls");
        hint.setTextColor(0x99ffffff);
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, 16, 0, 0);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
        );
        root.addView(hint, hintParams);

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
        urlInput.setHint("rawh264://127.0.0.1:8094?w=1170&h=1080");
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

        fitButton = new Button(this);
        fitButton.setText(prefs.getString(FIT_KEY, "contain"));
        fitButton.setOnClickListener(v -> {
            fitButton.setText("contain".contentEquals(fitButton.getText()) ? "cover" : "contain");
            hideSystemUi();
        });
        settingsRow.addView(fitButton);

        Button apply = new Button(this);
        apply.setText("Apply");
        apply.setOnClickListener(v -> applyRawH264Settings());
        settingsRow.addView(apply);

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
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        return input;
    }

    private void applyRawH264Settings() {
        int width = parseInt(widthInput.getText().toString(), 1170);
        int height = parseInt(heightInput.getText().toString(), 1080);
        int fps = parseInt(fpsInput.getText().toString(), 30);
        int bitrate = parseInt(bitrateInput.getText().toString(), 5000);
        String fit = fitButton.getText().toString().toLowerCase();
        String url = "rawh264://127.0.0.1:8094?w=" + width + "&h=" + height;

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(WIDTH_KEY, width)
                .putInt(HEIGHT_KEY, height)
                .putInt(FPS_KEY, fps)
                .putInt(BITRATE_KEY, bitrate)
                .putString(FIT_KEY, fit)
                .putString(URL_KEY, url)
                .apply();

        urlInput.setText(url);
        hideKeyboard();
        sendRawH264Config(width, height, fps, bitrate, fit, url);
    }

    private void sendRawH264Config(int width, int height, int fps, int bitrate, String fit, String streamUrl) {
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
                URL endpoint = new URL("http://127.0.0.1:8095/config");
                connection = (HttpURLConnection) endpoint.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(1000);
                connection.setReadTimeout(1000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] data = json.getBytes("UTF-8");
                connection.setFixedLengthStreamingMode(data.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(data);
                }
                connection.getResponseCode();
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
        startRawH264(url);
    }

    private void startRawH264(String url) {
        if (url.equals(activeRawUrl) && videoSurface != null && videoSurface == activeRawSurface) {
            return;
        }
        videoView.setVisibility(View.VISIBLE);
        if (h264Player == null) {
            h264Player = new RawH264Player();
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

    private void updateSbsButton() {
        if (sbsButton != null) {
            sbsButton.setText(videoView != null && videoView.isSbsMode() ? "Flat" : "SBS");
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
        controls.setVisibility(controls.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        hideSystemUi();
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
