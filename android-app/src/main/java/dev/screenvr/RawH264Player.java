package dev.screenvr;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.view.Surface;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class RawH264Player {
    private static final long INPUT_BUFFER_TIMEOUT_US = 1_000;
    private static final long POST_QUEUE_DRAIN_TIMEOUT_US = 1_500;

    public interface DecoderStatsListener {
        void onDecoderStats(float readFps, float inputFps, float outputFps, float droppedOutputFps);
    }

    private volatile boolean running;
    private Thread worker;
    private Socket socket;
    private DatagramSocket datagramSocket;
    private MediaCodec codec;
    private FrameLatencyTracker latencyTracker;
    private DecoderStatsListener decoderStatsListener;
    private long statsStartedMs;
    private int readFramesInWindow;
    private int inputFramesInWindow;
    private int outputFramesInWindow;
    private int droppedFramesInWindow;

    public void setLatencyTracker(FrameLatencyTracker tracker) {
        latencyTracker = tracker;
    }

    public void setDecoderStatsListener(DecoderStatsListener listener) {
        decoderStatsListener = listener;
    }

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
        if (latencyTracker != null) {
            latencyTracker.clear();
        }
    }

    private void playLoop(String url, Surface surface) {
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        int port = uri.getPort();
        int width = parseInt(uri.getQueryParameter("w"), 960);
        int height = parseInt(uri.getQueryParameter("h"), 540);
        int fps = clampInt(parseInt(uri.getQueryParameter("fps"), 30), 10, 300);
        if ("rtph264".equals(uri.getScheme())) {
            playRtpLoop(port, surface, width, height, fps);
            return;
        }
        while (running) {
            try {
                socket = new Socket(host, port);
                socket.setTcpNoDelay(true);
                socket.setReceiveBufferSize(256 * 1024);
                readAnnexB(new BufferedInputStream(socket.getInputStream(), 256 * 1024), surface, width, height, fps);
            } catch (Exception ignored) {
                releaseCodec();
                closeSocket();
                sleepQuietly(200);
            }
        }
    }

    private void playRtpLoop(int port, Surface surface, int width, int height, int fps) {
        while (running) {
            try {
                datagramSocket = new DatagramSocket(port);
                datagramSocket.setReceiveBufferSize(512 * 1024);
                readRtpH264(datagramSocket, surface, width, height, fps);
            } catch (Exception ignored) {
                releaseCodec();
                closeSocket();
                sleepQuietly(200);
            }
        }
    }

    private MediaCodec createCodec(Surface surface, int width, int height, int fps, byte[] sps, byte[] pps) throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024);
        if (Build.VERSION.SDK_INT >= 23) {
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, fps);
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        }
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

    private void readAnnexB(InputStream input, Surface surface, int width, int height, int fps) throws IOException {
        NalReader reader = new NalReader(input);
        long ptsUs = 0;
        long frameDurationUs = 1_000_000L / Math.max(1, fps);
        byte[] sps = null;
        byte[] pps = null;
        ByteAccumulator accessUnit = new ByteAccumulator(512 * 1024);
        while (running) {
            byte[] nal = reader.nextNal();
            if (nal == null) {
                throw new IOException("stream ended");
            }
            int type = nalType(nal);
            if (type == 9 && accessUnit.size() > 0) {
                readFramesInWindow++;
                publishDecoderStatsIfNeeded();
                if (codec != null) {
                    drainOutput(0);
                    if (queueAccessUnit(accessUnit.data(), accessUnit.size(), ptsUs)) {
                        ptsUs += frameDurationUs;
                        drainOutput(POST_QUEUE_DRAIN_TIMEOUT_US);
                    }
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
                    codec = createCodec(surface, width, height, fps, sps, pps);
                } else {
                    continue;
                }
            }
            accessUnit.write(nal);
        }
    }

    private void readRtpH264(DatagramSocket udp, Surface surface, int width, int height, int fps) throws IOException {
        byte[] packetData = new byte[2048];
        DatagramPacket packet = new DatagramPacket(packetData, packetData.length);
        ByteAccumulator accessUnit = new ByteAccumulator(512 * 1024);
        byte[] sps = null;
        byte[] pps = null;
        long ptsUs = 0;
        long frameDurationUs = 1_000_000L / Math.max(1, fps);
        int expectedSeq = -1;
        boolean corruptedAccessUnit = false;

        while (running) {
            udp.receive(packet);
            int length = packet.getLength();
            if (length < 13) {
                continue;
            }
            int offset = packet.getOffset();
            int version = (packetData[offset] >> 6) & 0x03;
            if (version != 2) {
                continue;
            }
            int csrcCount = packetData[offset] & 0x0f;
            boolean hasExtension = (packetData[offset] & 0x10) != 0;
            boolean marker = (packetData[offset + 1] & 0x80) != 0;
            int seq = ((packetData[offset + 2] & 0xff) << 8) | (packetData[offset + 3] & 0xff);
            int payloadOffset = offset + 12 + csrcCount * 4;
            if (payloadOffset >= offset + length) {
                continue;
            }
            if (hasExtension) {
                if (payloadOffset + 4 > offset + length) {
                    continue;
                }
                int extensionWords = ((packetData[payloadOffset + 2] & 0xff) << 8) | (packetData[payloadOffset + 3] & 0xff);
                payloadOffset += 4 + extensionWords * 4;
                if (payloadOffset >= offset + length) {
                    continue;
                }
            }
            if (expectedSeq >= 0 && seq != expectedSeq) {
                corruptedAccessUnit = true;
                accessUnit.reset();
            }
            expectedSeq = (seq + 1) & 0xffff;

            int payloadLength = offset + length - payloadOffset;
            int nalType = packetData[payloadOffset] & 0x1f;
            if (nalType >= 1 && nalType <= 23) {
                writeNal(accessUnit, packetData, payloadOffset, payloadLength);
                if (nalType == 7) {
                    sps = annexBNal(packetData, payloadOffset, payloadLength);
                } else if (nalType == 8) {
                    pps = annexBNal(packetData, payloadOffset, payloadLength);
                }
            } else if (nalType == 24) {
                int stapOffset = payloadOffset + 1;
                int stapEnd = payloadOffset + payloadLength;
                while (stapOffset + 2 <= stapEnd) {
                    int nalLength = ((packetData[stapOffset] & 0xff) << 8) | (packetData[stapOffset + 1] & 0xff);
                    stapOffset += 2;
                    if (nalLength <= 0 || stapOffset + nalLength > stapEnd) {
                        corruptedAccessUnit = true;
                        break;
                    }
                    int stapNalType = packetData[stapOffset] & 0x1f;
                    writeNal(accessUnit, packetData, stapOffset, nalLength);
                    if (stapNalType == 7) {
                        sps = annexBNal(packetData, stapOffset, nalLength);
                    } else if (stapNalType == 8) {
                        pps = annexBNal(packetData, stapOffset, nalLength);
                    }
                    stapOffset += nalLength;
                }
            } else if (nalType == 28 && payloadLength >= 3) {
                int fuIndicator = packetData[payloadOffset] & 0xff;
                int fuHeader = packetData[payloadOffset + 1] & 0xff;
                boolean start = (fuHeader & 0x80) != 0;
                int reconstructedHeader = (fuIndicator & 0xe0) | (fuHeader & 0x1f);
                if (start) {
                    writeStartCode(accessUnit);
                    accessUnit.write((byte) reconstructedHeader);
                } else if (accessUnit.size() == 0) {
                    corruptedAccessUnit = true;
                }
                accessUnit.write(packetData, payloadOffset + 2, payloadLength - 2);
            } else {
                corruptedAccessUnit = true;
            }

            if (marker) {
                readFramesInWindow++;
                publishDecoderStatsIfNeeded();
                if (!corruptedAccessUnit && accessUnit.size() > 0) {
                    if (codec == null && sps != null && pps != null) {
                        codec = createCodec(surface, width, height, fps, sps, pps);
                    }
                    if (codec != null) {
                        drainOutput(0);
                        if (queueAccessUnit(accessUnit.data(), accessUnit.size(), ptsUs)) {
                            ptsUs += frameDurationUs;
                            drainOutput(POST_QUEUE_DRAIN_TIMEOUT_US);
                        }
                    }
                }
                accessUnit.reset();
                corruptedAccessUnit = false;
            }
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

    private boolean queueAccessUnit(byte[] data, int length, long ptsUs) {
        MediaCodec current = codec;
        if (current == null) {
            return false;
        }
        try {
            int index = current.dequeueInputBuffer(INPUT_BUFFER_TIMEOUT_US);
            if (index < 0) {
                drainOutput(POST_QUEUE_DRAIN_TIMEOUT_US);
                index = current.dequeueInputBuffer(0);
                if (index < 0) {
                    return false;
                }
            }
            ByteBuffer inputBuffer = current.getInputBuffer(index);
            if (inputBuffer == null) {
                return false;
            }
            inputBuffer.clear();
            inputBuffer.put(data, 0, length);
            current.queueInputBuffer(index, 0, length, ptsUs, 0);
            inputFramesInWindow++;
            publishDecoderStatsIfNeeded();
            FrameLatencyTracker tracker = latencyTracker;
            if (tracker != null) {
                tracker.recordQueued(ptsUs, System.currentTimeMillis());
            }
            return true;
        } catch (Exception ignored) {
            running = false;
            return false;
        }
    }

    private void drainOutput(long firstTimeoutUs) {
        MediaCodec current = codec;
        if (current == null) {
            return;
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int newestIndex = -1;
        long timeoutUs = firstTimeoutUs;
        while (running) {
            int index;
            try {
                index = current.dequeueOutputBuffer(info, timeoutUs);
            } catch (Exception ignored) {
                running = false;
                return;
            }
            timeoutUs = 0;
            if (index >= 0) {
                if (newestIndex >= 0) {
                    current.releaseOutputBuffer(newestIndex, false);
                    droppedFramesInWindow++;
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
            outputFramesInWindow++;
            publishDecoderStatsIfNeeded();
        }
    }

    private void publishDecoderStatsIfNeeded() {
        long now = System.currentTimeMillis();
        if (statsStartedMs == 0) {
            statsStartedMs = now;
            return;
        }
        long elapsed = now - statsStartedMs;
        if (elapsed < 1000) {
            return;
        }
        DecoderStatsListener listener = decoderStatsListener;
        if (listener != null) {
            float readFps = readFramesInWindow * 1000f / elapsed;
            float inputFps = inputFramesInWindow * 1000f / elapsed;
            float outputFps = outputFramesInWindow * 1000f / elapsed;
            float droppedFps = droppedFramesInWindow * 1000f / elapsed;
            listener.onDecoderStats(readFps, inputFps, outputFps, droppedFps);
        }
        readFramesInWindow = 0;
        inputFramesInWindow = 0;
        outputFramesInWindow = 0;
        droppedFramesInWindow = 0;
        statsStartedMs = now;
    }

    private void closeSocket() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        socket = null;
        if (datagramSocket != null) {
            datagramSocket.close();
        }
        datagramSocket = null;
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

    private int clampInt(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
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
        private final byte[] inputBuffer = new byte[256 * 1024];
        private byte[] nal = new byte[256 * 1024];
        private int inputPosition;
        private int inputLimit;
        private int nalSize;
        private int zeroCount;
        private boolean started;

        NalReader(InputStream input) {
            this.input = input;
        }

        byte[] nextNal() throws IOException {
            while (true) {
                int value = readByte();
                if (value == -1) {
                    return null;
                }

                if (started) {
                    appendNalByte(value);
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
                    resetNal();
                    appendNalByte(0);
                    appendNalByte(0);
                    appendNalByte(0);
                    appendNalByte(1);
                    continue;
                }

                int prefixLength = nalSize >= 4 && nal[nalSize - 4] == 0 ? 4 : 3;
                int nextStart = nalSize - prefixLength;
                byte[] result = Arrays.copyOf(nal, nextStart);

                resetNal();
                appendNalByte(0);
                appendNalByte(0);
                appendNalByte(0);
                appendNalByte(1);
                return result;
            }
        }

        private int readByte() throws IOException {
            if (inputPosition >= inputLimit) {
                inputLimit = input.read(inputBuffer);
                inputPosition = 0;
                if (inputLimit <= 0) {
                    return -1;
                }
            }
            return inputBuffer[inputPosition++] & 0xff;
        }

        private void appendNalByte(int value) {
            if (nalSize >= nal.length) {
                nal = Arrays.copyOf(nal, nal.length * 2);
            }
            nal[nalSize++] = (byte) value;
        }

        private void resetNal() {
            nalSize = 0;
        }
    }

    private static final class ByteAccumulator {
        private byte[] data;
        private int size;

        ByteAccumulator(int capacity) {
            data = new byte[capacity];
        }

        byte[] data() {
            return data;
        }

        int size() {
            return size;
        }

        void write(byte[] bytes) {
            ensureCapacity(size + bytes.length);
            System.arraycopy(bytes, 0, data, size, bytes.length);
            size += bytes.length;
        }

        void write(byte[] bytes, int offset, int length) {
            ensureCapacity(size + length);
            System.arraycopy(bytes, offset, data, size, length);
            size += length;
        }

        void write(byte value) {
            ensureCapacity(size + 1);
            data[size++] = value;
        }

        void reset() {
            size = 0;
        }

        private void ensureCapacity(int required) {
            if (required <= data.length) {
                return;
            }
            int next = data.length;
            while (next < required) {
                next *= 2;
            }
            data = Arrays.copyOf(data, next);
        }
    }

    private static byte[] annexBNal(byte[] data, int offset, int length) {
        byte[] nal = new byte[length + 4];
        nal[0] = 0;
        nal[1] = 0;
        nal[2] = 0;
        nal[3] = 1;
        System.arraycopy(data, offset, nal, 4, length);
        return nal;
    }

    private static void writeNal(ByteAccumulator accessUnit, byte[] data, int offset, int length) {
        writeStartCode(accessUnit);
        accessUnit.write(data, offset, length);
    }

    private static void writeStartCode(ByteAccumulator accessUnit) {
        accessUnit.write((byte) 0);
        accessUnit.write((byte) 0);
        accessUnit.write((byte) 0);
        accessUnit.write((byte) 1);
    }
}
