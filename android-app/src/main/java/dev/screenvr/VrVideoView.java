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
        private SurfaceTexture surfaceTexture;
        private Surface surface;
        private int textureId;
        private int program;
        private int aPosition;
        private int aTexCoord;
        private int uTexture;
        private int uSTMatrix;
        private int width;
        private int height;
        private volatile boolean frameAvailable;
        private volatile boolean sbsMode;
        private volatile float videoAspect = 16f / 9f;

        VideoRenderer(GLSurfaceView view) {
            this.view = view;
        }

        void setVideoSurfaceListener(VideoSurfaceListener listener) {
            this.listener = listener;
            if (surface != null && listener != null) {
                mainHandler.post(() -> listener.onVideoSurfaceReady(surface));
            }
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

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            aPosition = GLES20.glGetAttribLocation(program, "aPosition");
            aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
            uTexture = GLES20.glGetUniformLocation(program, "uTexture");
            uSTMatrix = GLES20.glGetUniformLocation(program, "uSTMatrix");

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
            if (frameAvailable) {
                synchronized (this) {
                    frameAvailable = false;
                    surfaceTexture.updateTexImage();
                    surfaceTexture.getTransformMatrix(stMatrix);
                }
            }

            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (sbsMode) {
                drawFitted(0, 0, width / 2, height);
                drawFitted(width / 2, 0, width - width / 2, height);
            } else {
                drawFitted(0, 0, width, height);
            }
        }

        @Override
        public synchronized void onFrameAvailable(SurfaceTexture surfaceTexture) {
            frameAvailable = true;
            view.requestRender();
        }

        private void drawFitted(int x, int y, int viewportWidth, int viewportHeight) {
            int drawWidth = viewportWidth;
            int drawHeight = Math.round(viewportWidth / videoAspect);
            if (drawHeight > viewportHeight) {
                drawHeight = viewportHeight;
                drawWidth = Math.round(viewportHeight * videoAspect);
            }
            int drawX = x + (viewportWidth - drawWidth) / 2;
            int drawY = y + (viewportHeight - drawHeight) / 2;
            GLES20.glViewport(drawX, drawY, drawWidth, drawHeight);
            drawTexture();
        }

        private void drawTexture() {
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniform1i(uTexture, 0);
            GLES20.glUniformMatrix4fv(uSTMatrix, 1, false, stMatrix, 0);

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
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
                "}\n";
    }
}
