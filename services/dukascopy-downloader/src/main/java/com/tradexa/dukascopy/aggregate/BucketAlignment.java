package com.tradexa.dukascopy.aggregate;

/**
 * UTC bucket starts for the higher timeframes.
 * Alignment is floor division of epoch milliseconds, including instants
 * before the Unix epoch. No local timezone is consulted.
 */
public final class BucketAlignment {
    public static final long MINUTE_MILLIS = 60000L;

    public enum Timeframe {
        M5(5),
        M15(15),
        M30(30),
        H1(60),
        H4(240),
        D1(1440);

        private final int minutes;
        private final long durationMillis;

        Timeframe(int minutes) {
            this.minutes = minutes;
            this.durationMillis = minutes * MINUTE_MILLIS;
        }

        public int minutes() {
            return minutes;
        }

        public long durationMillis() {
            return durationMillis;
        }

        public static Timeframe fromMinutes(int minutes) {
            Timeframe[] values = values();
            for (int i = 0; i < values.length; i++) {
                if (values[i].minutes == minutes) {
                    return values[i];
                }
            }
            throw new IllegalArgumentException(
                "Unsupported timeframe length: " + minutes
            );
        }
    }

    private BucketAlignment() {
    }

    public static long bucketStart(Timeframe timeframe, long timestampMillis) {
        if (timeframe == null) {
            throw new IllegalArgumentException("Timeframe is required");
        }
        long durationMillis = timeframe.durationMillis();
        long quotient = Math.floorDiv(timestampMillis, durationMillis);
        try {
            return Math.multiplyExact(quotient, durationMillis);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(
                "Bucket start overflows the representable range"
            );
        }
    }
}
