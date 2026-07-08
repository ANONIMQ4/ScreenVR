package dev.screenvr;

import java.util.ArrayDeque;

final class FrameLatencyTracker {
    static final class Stats {
        final long latencyMs;
        final int queuedFrames;

        Stats(long latencyMs, int queuedFrames) {
            this.latencyMs = latencyMs;
            this.queuedFrames = queuedFrames;
        }
    }

    private static final int MAX_ENTRIES = 180;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    synchronized void recordQueued(long ptsUs, long queuedAtMs) {
        entries.addLast(new Entry(ptsUs, queuedAtMs));
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
    }

    synchronized int queuedFrames() {
        return entries.size();
    }

    synchronized Stats recordDrawn(long timestampNs, long drawnAtMs) {
        if (timestampNs <= 0 || entries.isEmpty()) {
            return new Stats(-1, entries.size());
        }
        long ptsUs = timestampNs / 1000L;
        Entry best = null;
        long bestDistance = Long.MAX_VALUE;
        for (Entry entry : entries) {
            long distance = Math.abs(entry.ptsUs - ptsUs);
            if (distance < bestDistance) {
                best = entry;
                bestDistance = distance;
            }
        }
        if (best == null || bestDistance > 20000L) {
            return new Stats(-1, entries.size());
        }
        while (!entries.isEmpty()) {
            Entry first = entries.removeFirst();
            if (first == best) {
                break;
            }
        }
        return new Stats(Math.max(0, drawnAtMs - best.queuedAtMs), entries.size());
    }

    synchronized void clear() {
        entries.clear();
    }

    private static final class Entry {
        final long ptsUs;
        final long queuedAtMs;

        Entry(long ptsUs, long queuedAtMs) {
            this.ptsUs = ptsUs;
            this.queuedAtMs = queuedAtMs;
        }
    }
}
