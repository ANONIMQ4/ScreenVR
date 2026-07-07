package dev.screenvr;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.view.Surface;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class RawH264Player {
    private volatile boolean running;
    private Thread worker;
    private Socket socket;
    private MediaCodec codec;

    public synchronized void start(String url, Surface surface) {
        stop();
        running = true;
        worker = new Thread(() -> playLoop(url, surface), "raw-h264-player");
        worker.start();
    }

    public synchronized void stop() {
        running = false;
        closeSocket();
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        releaseCodec();
    }

    private void playLoop(String url, Surface surface) {
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        int port = uri.getPort();
        int width = parseInt(uri.getQueryParameter("w"), 960);
        int height = parseInt(uri.getQueryParameter("h"), 540);
        while (running) {
            try {
                socket = new Socket(host, port);
                socket.setTcpNoDelay(true);
                socket.setReceiveBufferSize(128 * 1024);
                readAnnexB(new BufferedInputStream(socket.getInputStream(), 128 * 1024), surface, width, height);
            } catch (Exception ignored) {
                releaseCodec();
                closeSocket();
                sleepQuietly(200);
            }
        }
    }

    private MediaCodec createCodec(Surface surface, int width, int height, byte[] sps, byte[] pps) throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
        format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
        if (Build.VERSION.SDK_INT >= 30) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        } else {
            format.setInteger("low-latency", 1);
        }
        MediaCodec mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        mediaCodec.configure(format, surface, null, 0);
        mediaCodec.start();
        return mediaCodec;
    }

    private void readAnnexB(InputStream input, Surface surface, int width, int height) throws IOException {
        NalReader reader = new NalReader(input);
        long ptsUs = 0;
        byte[] sps = null;
        byte[] pps = null;
        ByteArrayOutputStream accessUnit = new ByteArrayOutputStream(512 * 1024);
        while (running) {
            byte[] nal = reader.nextNal();
            if (nal == null) {
                throw new IOException("stream ended");
            }
            int type = nalType(nal);
            if (type == 9 && accessUnit.size() > 0) {
                if (codec != null) {
                    queueAccessUnit(accessUnit.toByteArray(), ptsUs);
                    drainOutput();
                    ptsUs += 33333;
                }
                accessUnit.reset();
            }
            if (type == 7) {
                sps = Arrays.copyOf(nal, nal.length);
            } else if (type == 8) {
                pps = Arrays.copyOf(nal, nal.length);
            }
            if (codec == null) {
                if (sps != null && pps != null) {
                    codec = createCodec(surface, width, height, sps, pps);
                } else {
                    continue;
                }
            }
            accessUnit.write(nal);
        }
    }

    private int nalType(byte[] nal) {
        int offset = startCodeLength(nal);
        if (offset < 0 || offset >= nal.length) {
            return -1;
        }
        return nal[offset] & 0x1f;
    }

    private int startCodeLength(byte[] nal) {
        if (nal.length >= 4 && nal[0] == 0 && nal[1] == 0 && nal[2] == 0 && nal[3] == 1) {
            return 4;
        }
        if (nal.length >= 3 && nal[0] == 0 && nal[1] == 0 && nal[2] == 1) {
            return 3;
        }
        return -1;
    }

    private void queueAccessUnit(byte[] data, long ptsUs) {
        MediaCodec current = codec;
        if (current == null) {
            return;
        }
        try {
            int index = current.dequeueInputBuffer(2000);
            if (index < 0) {
                drainOutput();
                return;
            }
            ByteBuffer inputBuffer = current.getInputBuffer(index);
            if (inputBuffer == null) {
                return;
            }
            inputBuffer.clear();
            inputBuffer.put(data);
            current.queueInputBuffer(index, 0, data.length, ptsUs, 0);
        } catch (Exception ignored) {
            running = false;
        }
    }

    private void drainOutput() {
        MediaCodec current = codec;
        if (current == null) {
            return;
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int newestIndex = -1;
        while (running) {
            int index;
            try {
                index = current.dequeueOutputBuffer(info, 0);
            } catch (Exception ignored) {
                running = false;
                return;
            }
            if (index >= 0) {
                if (newestIndex >= 0) {
                    current.releaseOutputBuffer(newestIndex, false);
                }
                newestIndex = index;
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                continue;
            } else {
                break;
            }
        }
        if (newestIndex >= 0) {
            current.releaseOutputBuffer(newestIndex, true);
        }
    }

    private void closeSocket() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        socket = null;
    }

    private void releaseCodec() {
        try {
            if (codec != null) {
                codec.stop();
                codec.release();
            }
        } catch (Exception ignored) {
        }
        codec = null;
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

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class NalReader {
        private final InputStream input;
        private final ByteArrayOutputStream nal = new ByteArrayOutputStream(256 * 1024);
        private int zeroCount;
        private boolean started;

        NalReader(InputStream input) {
            this.input = input;
        }

        byte[] nextNal() throws IOException {
            while (true) {
                int value = input.read();
                if (value == -1) {
                    return null;
                }

                if (started) {
                    nal.write(value);
                }

                if (value == 0) {
                    zeroCount++;
                    continue;
                }

                boolean startCode = value == 1 && zeroCount >= 2;
                zeroCount = 0;

                if (!startCode) {
                    continue;
                }

                if (!started) {
                    started = true;
                    nal.reset();
                    nal.write(0);
                    nal.write(0);
                    nal.write(0);
                    nal.write(1);
                    continue;
                }

                byte[] full = nal.toByteArray();
                int prefixLength = full.length >= 4 && full[full.length - 4] == 0 ? 4 : 3;
                int nextStart = full.length - prefixLength;
                byte[] result = new byte[nextStart];
                System.arraycopy(full, 0, result, 0, nextStart);

                nal.reset();
                nal.write(0);
                nal.write(0);
                nal.write(0);
                nal.write(1);
                return result;
            }
        }
    }
}
