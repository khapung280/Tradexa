package com.tradexa.dukascopy.aggregate;

/**
 * One higher-timeframe bucket built from M1 candles.
 * Missing minutes stay missing: the bucket is complete only when every
 * expected M1 slot has been accepted.
 */
public final class OhlcvBucket {
    private static final long MINUTE_MILLIS = 60000L;

    private final long startMillis;
    private final long endExclusiveMillis;
    private final int expectedCount;

    private int receivedCount;
    private long lastTimestampMillis;
    private boolean started;
    private double open;
    private double high;
    private double low;
    private double close;
    private double volume;

    public OhlcvBucket(long startMillis, int expectedCount) {
        if (expectedCount < 1) {
            throw new IllegalArgumentException(
                "Expected M1 count must be positive"
            );
        }
        if (startMillis % MINUTE_MILLIS != 0) {
            throw new IllegalArgumentException(
                "Bucket start is not an M1 boundary"
            );
        }
        long durationMillis;
        try {
            durationMillis = Math.multiplyExact(expectedCount, MINUTE_MILLIS);
            this.endExclusiveMillis = Math.addExact(startMillis, durationMillis);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(
                "Bucket range overflows the representable range"
            );
        }
        this.startMillis = startMillis;
        this.expectedCount = expectedCount;
    }

    public void add(
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
            throw new IllegalArgumentException(
                "Timestamp is not an M1 boundary"
            );
        }
        if (receivedCount >= expectedCount) {
            throw new IllegalArgumentException(
                "Bucket already contains the expected number of candles"
            );
        }
        if (timestampMillis < startMillis || timestampMillis >= endExclusiveMillis) {
            throw new IllegalArgumentException("Timestamp is outside the bucket");
        }
        if (started && timestampMillis == lastTimestampMillis) {
            throw new IllegalArgumentException("Duplicate timestamp");
        }
        if (started && timestampMillis < lastTimestampMillis) {
            throw new IllegalArgumentException("Backward timestamp");
        }
        double volumeSum = this.volume + volume;
        if (!isFinite(volumeSum)) {
            throw new IllegalArgumentException("Volume sum is not finite");
        }

        if (!started) {
            this.open = open;
            this.high = high;
            this.low = low;
        } else {
            if (high > this.high) {
                this.high = high;
            }
            if (low < this.low) {
                this.low = low;
            }
        }
        this.close = close;
        this.volume = volumeSum;
        this.lastTimestampMillis = timestampMillis;
        this.started = true;
        this.receivedCount++;
    }

    public long startMillis() {
        return startMillis;
    }

    public int expectedCount() {
        return expectedCount;
    }

    public int receivedCount() {
        return receivedCount;
    }

    public boolean isComplete() {
        return receivedCount == expectedCount;
    }

    public double open() {
        requireCandle();
        return open;
    }

    public double high() {
        requireCandle();
        return high;
    }

    public double low() {
        requireCandle();
        return low;
    }

    public double close() {
        requireCandle();
        return close;
    }

    public double volume() {
        requireCandle();
        return volume;
    }

    private void requireCandle() {
        if (!started) {
            throw new IllegalStateException("Bucket has no candles");
        }
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
