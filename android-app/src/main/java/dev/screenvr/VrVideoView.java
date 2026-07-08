package dev.screenvr;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class VrVideoView extends GLSurfaceView {
    public interface VideoSurfaceListener {
        void onVideoSurfaceReady(Surface surface);
        void onVideoSurfaceDestroyed(Surface surface);
    }

    public interface FrameStatsListener {
        void onFrameStats(float fps, long latencyMs, int queuedFrames);
    }

    private final VideoRenderer renderer;

    public VrVideoView(Context context) {
        super(context);
        setEGLContextClientVersion(2);
        renderer = new VideoRenderer(this);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        setPreserveEGLContextOnPause(true);
    }

    public void setVideoSurfaceListener(VideoSurfaceListener listener) {
        renderer.setVideoSurfaceListener(listener);
    }

    public void setFrameStatsListener(FrameStatsListener listener) {
        renderer.setFrameStatsListener(listener);
    }

    public void setLatencyTracker(FrameLatencyTracker tracker) {
        renderer.setLatencyTracker(tracker);
    }

    public void setSbsMode(boolean enabled) {
        renderer.setSbsMode(enabled);
        requestRender();
    }

    public boolean isSbsMode() {
        return renderer.isSbsMode();
    }

    public void setVideoAspect(float aspect) {
        renderer.setVideoAspect(aspect);
        requestRender();
    }

    public void setEyeOffsetPercent(float percent) {
        renderer.setEyeOffsetPercent(percent);
        requestRender();
    }

    public void setLensSettings(
            int mode,
            float strengthPercent,
            float zoomPercent,
            float centerOffsetPercent,
            float sizeXPercent,
            float sizeYPercent,
            int maskMode
    ) {
        renderer.setLensSettings(
                mode,
                strengthPercent,
                zoomPercent,
                centerOffsetPercent,
                sizeXPercent,
                sizeYPercent,
                maskMode
        );
        requestRender();
    }

    private static final class VideoRenderer implements Renderer, SurfaceTexture.OnFrameAvailableListener {
        private final GLSurfaceView view;
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final float[] stMatrix = new float[16];
        private final FloatBuffer vertexBuffer = floatBuffer(new float[]{
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f
        });
        private final FloatBuffer texBuffer = floatBuffer(new float[]{
                0f, 0f,
                1f, 0f,
                0f, 1f,
                1f, 1f
        });

        private VideoSurfaceListener listener;
        private FrameStatsListener frameStatsListener;
        private FrameLatencyTracker latencyTracker;
        private SurfaceTexture surfaceTexture;
        private Surface surface;
        private int textureId;
        private int program;
        private int aPosition;
        private int aTexCoord;
        private int uTexture;
        private int uSTMatrix;
        private int uEyeOffset;
        private int uEyeSide;
        private int uLensMode;
        private int uLensStrength;
        private int uZoom;
        private int uCenterOffset;
        private int uSize;
        private int uMaskMode;
        private int width;
        private int height;
        private volatile boolean frameAvailable;
        private volatile boolean sbsMode;
        private volatile float videoAspect = 16f / 9f;
        private volatile float eyeOffsetPercent;
        private volatile int lensMode;
        private volatile float lensStrength;
        private volatile float zoom = 1f;
        private volatile float centerOffset;
        private volatile float sizeX = 1f;
        private volatile float sizeY = 1f;
        private volatile int maskMode = 1;
        private long statsWindowStartedMs;
        private int drawnInWindow;
        private long latestLatencyMs = -1;
        private int latestQueuedFrames;

        VideoRenderer(GLSurfaceView view) {
            this.view = view;
        }

        void setVideoSurfaceListener(VideoSurfaceListener listener) {
            this.listener = listener;
            if (surface != null && listener != null) {
                mainHandler.post(() -> listener.onVideoSurfaceReady(surface));
            }
        }

        void setFrameStatsListener(FrameStatsListener listener) {
            frameStatsListener = listener;
        }

        void setLatencyTracker(FrameLatencyTracker tracker) {
            latencyTracker = tracker;
        }

        void setSbsMode(boolean enabled) {
            sbsMode = enabled;
        }

        boolean isSbsMode() {
            return sbsMode;
        }

        void setVideoAspect(float aspect) {
            if (aspect > 0f) {
                videoAspect = aspect;
            }
        }

        void setEyeOffsetPercent(float percent) {
            eyeOffsetPercent = Math.max(0f, Math.min(5f, percent));
        }

        void setLensSettings(
                int mode,
                float strengthPercent,
                float zoomPercent,
                float centerOffsetPercent,
                float sizeXPercent,
                float sizeYPercent,
                int maskMode
        ) {
            lensMode = Math.max(0, Math.min(5, mode));
            lensStrength = Math.max(0f, Math.min(100f, strengthPercent)) / 100f;
            zoom = Math.max(0.5f, Math.min(2.0f, zoomPercent / 100f));
            centerOffset = Math.max(-20f, Math.min(20f, centerOffsetPercent)) / 100f;
            sizeX = Math.max(0.5f, Math.min(1.5f, sizeXPercent / 100f));
            sizeY = Math.max(0.5f, Math.min(1.5f, sizeYPercent / 100f));
            this.maskMode = Math.max(0, Math.min(1, maskMode));
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            aPosition = GLES20.glGetAttribLocation(program, "aPosition");
            aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
            uTexture = GLES20.glGetUniformLocation(program, "uTexture");
            uSTMatrix = GLES20.glGetUniformLocation(program, "uSTMatrix");
            uEyeOffset = GLES20.glGetUniformLocation(program, "uEyeOffset");
            uEyeSide = GLES20.glGetUniformLocation(program, "uEyeSide");
            uLensMode = GLES20.glGetUniformLocation(program, "uLensMode");
            uLensStrength = GLES20.glGetUniformLocation(program, "uLensStrength");
            uZoom = GLES20.glGetUniformLocation(program, "uZoom");
            uCenterOffset = GLES20.glGetUniformLocation(program, "uCenterOffset");
            uSize = GLES20.glGetUniformLocation(program, "uSize");
            uMaskMode = GLES20.glGetUniformLocation(program, "uMaskMode");

            textureId = createExternalTexture();
            surfaceTexture = new SurfaceTexture(textureId);
            surfaceTexture.setOnFrameAvailableListener(this);
            surface = new Surface(surfaceTexture);
            VideoSurfaceListener currentListener = listener;
            if (currentListener != null) {
                mainHandler.post(() -> currentListener.onVideoSurfaceReady(surface));
            }
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            if (surfaceTexture == null) {
                return;
            }
            boolean textureUpdated = false;
            if (frameAvailable) {
                synchronized (this) {
                    frameAvailable = false;
                    surfaceTexture.updateTexImage();
                    surfaceTexture.getTransformMatrix(stMatrix);
                    FrameLatencyTracker tracker = latencyTracker;
                    if (tracker != null) {
                        FrameLatencyTracker.Stats stats = tracker.recordDrawn(
                                surfaceTexture.getTimestamp(),
                                System.currentTimeMillis()
                        );
                        latestLatencyMs = stats.latencyMs;
                        latestQueuedFrames = stats.queuedFrames;
                    }
                    textureUpdated = true;
                }
            }

            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (sbsMode) {
                float offset = eyeOffsetPercent / 100f;
                if (maskMode == 1) {
                    drawYoutubeEye(0, 0, width / 2, height, -offset, -1f);
                    drawYoutubeEye(width / 2, 0, width - width / 2, height, offset, 1f);
                } else {
                    drawFitted(0, 0, width / 2, height, -offset, -1f);
                    drawFitted(width / 2, 0, width - width / 2, height, offset, 1f);
                }
            } else {
                drawFitted(0, 0, width, height, 0f, 0f);
            }
            if (textureUpdated) {
                recordDrawnFrame();
            }
        }

        @Override
        public synchronized void onFrameAvailable(SurfaceTexture surfaceTexture) {
            frameAvailable = true;
            view.requestRender();
        }

        private void drawFitted(int x, int y, int viewportWidth, int viewportHeight, float eyeOffset, float eyeSide) {
            int drawWidth = viewportWidth;
            int drawHeight = Math.round(viewportWidth / videoAspect);
            if (drawHeight > viewportHeight) {
                drawHeight = viewportHeight;
                drawWidth = Math.round(viewportHeight * videoAspect);
            }
            int drawX = x + (viewportWidth - drawWidth) / 2;
            int drawY = y + (viewportHeight - drawHeight) / 2;
            GLES20.glViewport(drawX, drawY, drawWidth, drawHeight);
            drawTexture(eyeOffset, eyeSide);
        }

        private void drawYoutubeEye(int x, int y, int viewportWidth, int viewportHeight, float eyeOffset, float eyeSide) {
            int drawWidth = Math.round(viewportWidth * 0.864f);
            int drawHeight = Math.round(viewportHeight * 0.972f);
            int inward = Math.round(viewportWidth * 0.142f);
            int drawX = eyeSide < 0f
                    ? x + inward
                    : x + viewportWidth - drawWidth - inward;
            int drawY = y + Math.round(viewportHeight * 0.026f);
            GLES20.glViewport(drawX, drawY, drawWidth, drawHeight);
            drawTexture(eyeOffset, eyeSide);
        }

        private void drawTexture(float eyeOffset, float eyeSide) {
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniform1i(uTexture, 0);
            GLES20.glUniformMatrix4fv(uSTMatrix, 1, false, stMatrix, 0);
            GLES20.glUniform1f(uEyeOffset, eyeOffset);
            GLES20.glUniform1f(uEyeSide, eyeSide);
            GLES20.glUniform1i(uLensMode, lensMode);
            GLES20.glUniform1f(uLensStrength, lensStrength);
            GLES20.glUniform1f(uZoom, zoom);
            GLES20.glUniform1f(uCenterOffset, centerOffset);
            GLES20.glUniform2f(uSize, sizeX, sizeY);
            GLES20.glUniform1i(uMaskMode, maskMode);

            vertexBuffer.position(0);
            GLES20.glEnableVertexAttribArray(aPosition);
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);

            texBuffer.position(0);
            GLES20.glEnableVertexAttribArray(aTexCoord);
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(aPosition);
            GLES20.glDisableVertexAttribArray(aTexCoord);
        }

        private void recordDrawnFrame() {
            long now = System.currentTimeMillis();
            if (statsWindowStartedMs == 0) {
                statsWindowStartedMs = now;
            }
            drawnInWindow++;
            long elapsed = now - statsWindowStartedMs;
            if (elapsed >= 1000) {
                FrameStatsListener listener = frameStatsListener;
                if (listener != null) {
                    listener.onFrameStats(drawnInWindow * 1000f / elapsed, latestLatencyMs, latestQueuedFrames);
                }
                drawnInWindow = 0;
                statsWindowStartedMs = now;
            }
        }

        private static int createExternalTexture() {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            return textures[0];
        }

        private static int createProgram(String vertexSource, String fragmentSource) {
            int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
            int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
            int program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertexShader);
            GLES20.glAttachShader(program, fragmentShader);
            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] == 0) {
                String message = GLES20.glGetProgramInfoLog(program);
                GLES20.glDeleteProgram(program);
                throw new IllegalStateException(message);
            }
            return program;
        }

        private static int compileShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compileStatus = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
            if (compileStatus[0] == 0) {
                String message = GLES20.glGetShaderInfoLog(shader);
                GLES20.glDeleteShader(shader);
                throw new IllegalStateException(message);
            }
            return shader;
        }

        private static FloatBuffer floatBuffer(float[] values) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(values.length * 4);
            buffer.order(ByteOrder.nativeOrder());
            FloatBuffer floatBuffer = buffer.asFloatBuffer();
            floatBuffer.put(values);
            floatBuffer.position(0);
            return floatBuffer;
        }

        private static final String VERTEX_SHADER =
                "attribute vec4 aPosition;\n" +
                "attribute vec2 aTexCoord;\n" +
                "uniform mat4 uSTMatrix;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "  gl_Position = aPosition;\n" +
                "  vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n" +
                "}\n";

        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "uniform samplerExternalOES uTexture;\n" +
                "uniform float uEyeOffset;\n" +
                "uniform float uEyeSide;\n" +
                "uniform int uLensMode;\n" +
                "uniform float uLensStrength;\n" +
                "uniform float uZoom;\n" +
                "uniform float uCenterOffset;\n" +
                "uniform vec2 uSize;\n" +
                "uniform int uMaskMode;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "  vec2 maskP = vTexCoord - vec2(0.5, 0.5);\n" +
                "  if (uMaskMode == 1) {\n" +
                "    float ay = abs(maskP.y) / 0.5;\n" +
                "    float ax = abs(maskP.x) / 0.5;\n" +
                "    float halfW = 0.485 - (0.105 * pow(ay, 1.65));\n" +
                "    float halfH = 0.462 - (0.050 * pow(ax, 1.85));\n" +
                "    float edgeX = halfW - abs(maskP.x);\n" +
                "    float edgeY = halfH - abs(maskP.y);\n" +
                "    float alpha = smoothstep(0.0, 0.012, min(edgeX, edgeY));\n" +
                "    if (alpha <= 0.001) {\n" +
                "      gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);\n" +
                "      return;\n" +
                "    }\n" +
                "  }\n" +
                "  vec2 p = (vTexCoord - vec2(0.5, 0.5)) / max(uSize, vec2(0.01, 0.01));\n" +
                "  p = p / max(uZoom, 0.01);\n" +
                "  float r2 = dot(p, p);\n" +
                "  float factor = 1.0;\n" +
                "  if (uLensMode == 1) {\n" +
                "    factor = 1.0 + (0.55 * uLensStrength * r2) + (0.20 * uLensStrength * r2 * r2);\n" +
                "  } else if (uLensMode == 2) {\n" +
                "    factor = 1.0 + (0.85 * uLensStrength * r2) + (0.55 * uLensStrength * r2 * r2);\n" +
                "  } else if (uLensMode == 3) {\n" +
                "    factor = max(0.2, 1.0 - (0.45 * uLensStrength * r2));\n" +
                "  } else if (uLensMode == 4) {\n" +
                "    float r = sqrt(r2);\n" +
                "    factor = 1.0 + (sin(min(r, 1.5708)) / max(r, 0.001) - 1.0) * uLensStrength;\n" +
                "  } else if (uLensMode == 5) {\n" +
                "    factor = 1.0 + (0.25 * uLensStrength * r2);\n" +
                "  }\n" +
                "  vec2 coord = vec2(0.5, 0.5) + p * factor;\n" +
                "  coord.x += uEyeOffset - (uEyeSide * uCenterOffset);\n" +
                "  coord = clamp(coord, vec2(0.0, 0.0), vec2(1.0, 1.0));\n" +
                "  vec4 color = texture2D(uTexture, coord);\n" +
                "  if (uMaskMode == 1) {\n" +
                "    float ay = abs(maskP.y) / 0.5;\n" +
                "    float ax = abs(maskP.x) / 0.5;\n" +
                "    float halfW = 0.485 - (0.105 * pow(ay, 1.65));\n" +
                "    float halfH = 0.462 - (0.050 * pow(ax, 1.85));\n" +
                "    float edgeX = halfW - abs(maskP.x);\n" +
                "    float edgeY = halfH - abs(maskP.y);\n" +
                "    float alpha = smoothstep(0.0, 0.012, min(edgeX, edgeY));\n" +
                "    color.rgb *= alpha;\n" +
                "  }\n" +
                "  gl_FragColor = color;\n" +
                "}\n";
    }
}
