package com.tradexa.dukascopy.aggregate;

import com.tradexa.dukascopy.aggregate.BucketAlignment.Timeframe;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Single-pass aggregation of chronological M1 candles into M5, M15, M30,
 * H1, H4, and D1. Only the open bucket and the sealed results are retained.
 */
public final class M1StreamAggregator {
    private static final long MINUTE_MILLIS = BucketAlignment.MINUTE_MILLIS;

    public enum IncompleteReason {
        WINDOW_BOUNDARY_START,
        WINDOW_BOUNDARY_END,
        WINDOW_BOUNDARY_BOTH,
        MISSING_INSIDE_WINDOW
    }

    public static final class CompletedBucket {
        private final Timeframe timeframe;
        private final OhlcvBucket bucket;

        CompletedBucket(Timeframe timeframe, OhlcvBucket bucket) {
            this.timeframe = timeframe;
            this.bucket = bucket;
        }

        public Timeframe timeframe() {
            return timeframe;
        }

        public long startMillis() {
            return bucket.startMillis();
        }

        public int receivedCount() {
            return bucket.receivedCount();
        }

        public double open() {
            return bucket.open();
        }

        public double high() {
            return bucket.high();
        }

        public double low() {
            return bucket.low();
        }

        public double close() {
            return bucket.close();
        }

        public double volume() {
            return bucket.volume();
        }
    }

    public static final class IncompleteBucket {
        private final Timeframe timeframe;
        private final long bucketStartMillis;
        private final int expectedCount;
        private final int receivedCount;
        private final int missingCount;
        private final int missingInsideWindow;
        private final IncompleteReason reason;

        IncompleteBucket(
                Timeframe timeframe,
                long bucketStartMillis,
                int expectedCount,
                int receivedCount,
                int missingInsideWindow,
                IncompleteReason reason) {
            this.timeframe = timeframe;
            this.bucketStartMillis = bucketStartMillis;
            this.expectedCount = expectedCount;
            this.receivedCount = receivedCount;
            this.missingCount = expectedCount - receivedCount;
            this.missingInsideWindow = missingInsideWindow;
            this.reason = reason;
        }

        public Timeframe timeframe() {
            return timeframe;
        }

        public long bucketStartMillis() {
            return bucketStartMillis;
        }

        public int expectedCount() {
            return expectedCount;
        }

        public int receivedCount() {
            return receivedCount;
        }

        public int missingCount() {
            return missingCount;
        }

        public int missingInsideWindow() {
            return missingInsideWindow;
        }

        public IncompleteReason reason() {
            return reason;
        }
    }

    private final long windowStartMillis;
    private final long windowEndMillis;
    private final Track[] tracks;
    private boolean hasCandle;
    private long lastTimestampMillis;
    private boolean finished;
    private boolean failed;
    private String failureMessage;

    public M1StreamAggregator(long windowStartMillis, long windowEndMillis) {
        if (windowStartMillis % MINUTE_MILLIS != 0) {
            throw new IllegalArgumentException(
                "Source window start is not an M1 boundary"
            );
        }
        if (windowEndMillis % MINUTE_MILLIS != 0) {
            throw new IllegalArgumentException(
                "Source window end is not an M1 boundary"
            );
        }
        if (windowEndMillis < windowStartMillis) {
            throw new IllegalArgumentException(
                "Source window end is before the start"
            );
        }
        this.windowStartMillis = windowStartMillis;
        this.windowEndMillis = windowEndMillis;
        Timeframe[] timeframes = Timeframe.values();
        this.tracks = new Track[timeframes.length];
        for (int i = 0; i < timeframes.length; i++) {
            tracks[i] = new Track(timeframes[i]);
        }
    }

    public long windowStartMillis() {
        return windowStartMillis;
    }

    public long windowEndMillis() {
        return windowEndMillis;
    }

    public void add(
            long timestampMillis,
            double open,
            double high,
            double low,
            double close,
            double volume) {
        ensureAccepting();
        validateCandle(timestampMillis, open, high, low, close, volume);
        if (hasCandle && timestampMillis == lastTimestampMillis) {
            fail("Duplicate timestamp");
        }
        if (hasCandle && timestampMillis < lastTimestampMillis) {
            fail("Backward timestamp");
        }
        if (timestampMillis < windowStartMillis || timestampMillis > windowEndMillis) {
            throw new IllegalArgumentException(
                "Timestamp is outside the source window"
            );
        }
        rejectNonFiniteVolumeSum(timestampMillis, volume);
        if (!hasCandle) {
            if (timestampMillis > windowStartMillis) {
                markGap(windowStartMillis, timestampMillis - MINUTE_MILLIS);
            }
        } else if (timestampMillis > lastTimestampMillis + MINUTE_MILLIS) {
            markGap(lastTimestampMillis + MINUTE_MILLIS, timestampMillis - MINUTE_MILLIS);
        }
        for (int i = 0; i < tracks.length; i++) {
            tracks[i].accept(timestampMillis, open, high, low, close, volume);
        }
        lastTimestampMillis = timestampMillis;
        hasCandle = true;
    }

    public void finish() {
        if (finished) {
            return;
        }
        if (!failed) {
            if (!hasCandle) {
                markGap(windowStartMillis, windowEndMillis);
            } else if (lastTimestampMillis < windowEndMillis) {
                markGap(lastTimestampMillis + MINUTE_MILLIS, windowEndMillis);
            }
        }
        for (int i = 0; i < tracks.length; i++) {
            tracks[i].finalizeBucket();
        }
        finished = true;
    }

    public boolean isFinished() {
        return finished;
    }

    public boolean isFailed() {
        return failed;
    }

    public String failureMessage() {
        return failureMessage;
    }

    public List<CompletedBucket> completed(Timeframe timeframe) {
        return Collections.unmodifiableList(track(timeframe).completed);
    }

    public List<IncompleteBucket> incomplete(Timeframe timeframe) {
        return Collections.unmodifiableList(track(timeframe).incomplete);
    }

    private void ensureAccepting() {
        if (failed) {
            throw new IllegalStateException("Aggregator has failed");
        }
        if (finished) {
            throw new IllegalStateException("Aggregator is finished");
        }
    }

    private void fail(String message) {
        failed = true;
        failureMessage = message;
        throw new IllegalArgumentException(message);
    }

    private Track track(Timeframe timeframe) {
        if (timeframe == null) {
            throw new IllegalArgumentException("Timeframe is required");
        }
        for (int i = 0; i < tracks.length; i++) {
            if (tracks[i].timeframe == timeframe) {
                return tracks[i];
            }
        }
        throw new IllegalArgumentException("Unsupported timeframe: " + timeframe);
    }

    private void markGap(long fromInclusive, long toInclusive) {
        long from = fromInclusive < windowStartMillis ? windowStartMillis : fromInclusive;
        long to = toInclusive > windowEndMillis ? windowEndMillis : toInclusive;
        if (from > to) {
            return;
        }
        for (int i = 0; i < tracks.length; i++) {
            tracks[i].markMissingRange(from, to);
        }
    }

    private void rejectNonFiniteVolumeSum(long timestampMillis, double volume) {
        for (int i = 0; i < tracks.length; i++) {
            Track track = tracks[i];
            if (!track.active || track.current == null || track.current.receivedCount() == 0) {
                continue;
            }
            long bucketStart = BucketAlignment.bucketStart(track.timeframe, timestampMillis);
            if (bucketStart != track.currentStart) {
                continue;
            }
            if (!isFinite(track.current.volume() + volume)) {
                throw new IllegalArgumentException("Volume sum is not finite");
            }
        }
    }

    private static void validateCandle(
            long timestampMillis,
            double open,
            double high,
            double low,
            double close,
            double volume) {
        if (!isFinite(open) || !isFinite(high) || !isFinite(low) || !isFinite(close)) {
            throw new IllegalArgumentException("OHLC values must be finite");
        }
        if (!isFinite(volume)) {
            throw new IllegalArgumentException("Volume must be finite");
        }
        if (volume < 0) {
            throw new IllegalArgumentException("Volume must be non-negative");
        }
        if (high < open || high < close || low > open || low > close || high < low) {
            throw new IllegalArgumentException("Invalid OHLC");
        }
        if (timestampMillis % MINUTE_MILLIS != 0) {
            throw new IllegalArgumentException("Timestamp is not an M1 boundary");
        }
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private int inWindowCount(long bucketStartMillis, int minutes) {
        long lastMinute = bucketStartMillis + (minutes - 1L) * MINUTE_MILLIS;
        long first = bucketStartMillis > windowStartMillis
            ? bucketStartMillis
            : windowStartMillis;
        long last = lastMinute < windowEndMillis ? lastMinute : windowEndMillis;
        if (last < first) {
            return 0;
        }
        return (int) ((last - first) / MINUTE_MILLIS) + 1;
    }

    private final class Track {
        private final Timeframe timeframe;
        private final List<CompletedBucket> completed;
        private final List<IncompleteBucket> incomplete;
        private OhlcvBucket current;
        private long currentStart;
        private int missingInsideWindow;
        private boolean active;

        private Track(Timeframe timeframe) {
            this.timeframe = timeframe;
            this.completed = new ArrayList<CompletedBucket>();
            this.incomplete = new ArrayList<IncompleteBucket>();
        }

        private void accept(
                long timestampMillis,
                double open,
                double high,
                double low,
                double close,
                double volume) {
            long bucketStart = BucketAlignment.bucketStart(timeframe, timestampMillis);
            openAt(bucketStart);
            if (current == null) {
                current = new OhlcvBucket(bucketStart, timeframe.minutes());
            }
            current.add(timestampMillis, open, high, low, close, volume);
            if (current.isComplete() && missingInsideWindow == 0) {
                completed.add(new CompletedBucket(timeframe, current));
                current = null;
                active = false;
                missingInsideWindow = 0;
            }
        }

        private void markMissingRange(long fromInclusive, long toInclusive) {
            long timestamp = fromInclusive;
            while (timestamp <= toInclusive) {
                long bucketStart = BucketAlignment.bucketStart(timeframe, timestamp);
                long lastMinute = bucketStart
                    + timeframe.durationMillis()
                    - MINUTE_MILLIS;
                long missEnd = lastMinute < toInclusive ? lastMinute : toInclusive;
                openAt(bucketStart);
                missingInsideWindow += (int) ((missEnd - timestamp) / MINUTE_MILLIS) + 1;
                timestamp = missEnd + MINUTE_MILLIS;
            }
        }

        private void openAt(long bucketStart) {
            if (active && currentStart == bucketStart) {
                return;
            }
            if (active) {
                finalizeBucket();
            }
            active = true;
            currentStart = bucketStart;
            current = null;
            missingInsideWindow = 0;
        }

        private void finalizeBucket() {
            if (!active) {
                return;
            }
            int expected = timeframe.minutes();
            int received = current == null ? 0 : current.receivedCount();
            int inWindow = inWindowCount(currentStart, expected);
            if (received + missingInsideWindow < inWindow) {
                missingInsideWindow += inWindow - received - missingInsideWindow;
            }
            if (current != null && current.isComplete() && missingInsideWindow == 0) {
                completed.add(new CompletedBucket(timeframe, current));
            } else if (inWindow > 0) {
                incomplete.add(new IncompleteBucket(
                    timeframe,
                    currentStart,
                    expected,
                    received,
                    missingInsideWindow,
                    reason(currentStart, expected, missingInsideWindow)
                ));
            }
            current = null;
            active = false;
            missingInsideWindow = 0;
        }

        private IncompleteReason reason(
                long bucketStart,
                int minutes,
                int missingInside) {
            if (missingInside > 0) {
                return IncompleteReason.MISSING_INSIDE_WINDOW;
            }
            long lastMinute = bucketStart + (minutes - 1L) * MINUTE_MILLIS;
            boolean startsBefore = bucketStart < windowStartMillis;
            boolean endsAfter = lastMinute > windowEndMillis;
            if (startsBefore && endsAfter) {
                return IncompleteReason.WINDOW_BOUNDARY_BOTH;
            }
            if (startsBefore) {
                return IncompleteReason.WINDOW_BOUNDARY_START;
            }
            if (endsAfter) {
                return IncompleteReason.WINDOW_BOUNDARY_END;
            }
            return IncompleteReason.MISSING_INSIDE_WINDOW;
        }
    }
}
