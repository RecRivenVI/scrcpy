package io.github.recrivenvi.connectorx.runtime;

/** Monotonic wheel deadlines, independent of Android and the ConnectorX protocol module. */
public final class WheelTimeline {
    public static final float DEFAULT_UNITS_PER_SECOND = 320f;
    private long periodNanos;
    private long nextDeadlineNanos;
    private long lastSampleNanos;
    private boolean first;

    public static float clampRate(float rate) {
        return Float.isFinite(rate) && rate > 0 ? Math.max(24f, Math.min(240f, rate)) : 60f;
    }

    public void start(long nowNanos, float rate) {
        periodNanos = Math.round(1_000_000_000d / clampRate(rate));
        nextDeadlineNanos = nowNanos + periodNanos;
        lastSampleNanos = nowNanos;
        first = true;
    }

    public long nextDeadlineNanos() { return nextDeadlineNanos; }
    public long periodNanos() { return periodNanos; }

    /** A late wake produces one sample and skips obsolete deadlines, never a catch-up burst. */
    public Tick poll(long nowNanos) {
        if (periodNanos == 0 || nowNanos < nextDeadlineNanos) return null;
        long lateness = nowNanos - nextDeadlineNanos;
        long skipped = lateness / periodNanos;
        long elapsed = first ? periodNanos : Math.min(nowNanos - lastSampleNanos, 2 * periodNanos);
        nextDeadlineNanos += (skipped + 1) * periodNanos;
        lastSampleNanos = nowNanos;
        first = false;
        return new Tick(elapsed, lateness, skipped);
    }

    public static final class Tick {
        public final long elapsedNanos;
        public final long latenessNanos;
        public final long skippedTicks;
        private Tick(long elapsedNanos, long latenessNanos, long skippedTicks) {
            this.elapsedNanos = elapsedNanos;
            this.latenessNanos = latenessNanos;
            this.skippedTicks = skippedTicks;
        }
        public float units(float unitsPerSecond) { return (float) (unitsPerSecond * (elapsedNanos / 1_000_000_000d)); }
    }
}
