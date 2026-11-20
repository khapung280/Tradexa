package com.tradexa.dukascopy.aggregate;

import org.junit.Assert;
import org.junit.Test;

public class OhlcvBucketTest {
    private static final long START = 1_700_116_800_000L;

    @Test
    public void openIsTheFirstM1Open() {
        OhlcvBucket bucket = threeCandles();
        Assert.assertEquals(1.20, bucket.open(), 0.0);
    }

    @Test
    public void highIsTheMaximumHigh() {
        OhlcvBucket bucket = threeCandles();
        Assert.assertEquals(1.40, bucket.high(), 0.0);
    }

    @Test
    public void lowIsTheMinimumLow() {
        OhlcvBucket bucket = threeCandles();
        Assert.assertEquals(1.00, bucket.low(), 0.0);
    }

    @Test
    public void closeIsTheLastM1Close() {
        OhlcvBucket bucket = threeCandles();
        Assert.assertEquals(1.12, bucket.close(), 0.0);
    }

    @Test
    public void volumeIsTheSumOfM1Volumes() {
        OhlcvBucket bucket = threeCandles();
        Assert.assertEquals(7.0, bucket.volume(), 0.0);
    }

    @Test
    public void incompleteBucketIsDetected() {
        OhlcvBucket bucket = new OhlcvBucket(START, 3);
        add(bucket, 0, 1.20, 1.25, 1.10, 1.15, 1.0);
        Assert.assertEquals(1, bucket.receivedCount());
        Assert.assertFalse(bucket.isComplete());
    }

    @Test
    public void completeBucketIsDetected() {
        OhlcvBucket bucket = threeCandles();
        Assert.assertEquals(3, bucket.receivedCount());
        Assert.assertTrue(bucket.isComplete());
        Assert.assertEquals(START, bucket.startMillis());
    }

    @Test
    public void duplicateTimestampIsRejected() {
        OhlcvBucket bucket = new OhlcvBucket(START, 3);
        add(bucket, 0, 1.20, 1.25, 1.10, 1.15, 1.0);
        try {
            add(bucket, 0, 1.15, 1.40, 1.00, 1.30, 2.0);
            Assert.fail("Expected a duplicate timestamp to be rejected");
        } catch (IllegalArgumentException ex) {
            Assert.assertEquals("Duplicate timestamp", ex.getMessage());
        }
        Assert.assertEquals(1, bucket.receivedCount());
        Assert.assertEquals(1.20, bucket.open(), 0.0);
    }

    @Test
    public void backwardTimestampIsRejected() {
        OhlcvBucket bucket = new OhlcvBucket(START, 3);
        add(bucket, 2, 1.30, 1.32, 1.08, 1.12, 4.0);
        try {
            add(bucket, 1, 1.15, 1.40, 1.00, 1.30, 2.0);
            Assert.fail("Expected a backward timestamp to be rejected");
        } catch (IllegalArgumentException ex) {
            Assert.assertEquals("Backward timestamp", ex.getMessage());
        }
        Assert.assertEquals(1, bucket.receivedCount());
        Assert.assertFalse(bucket.isComplete());
    }

    @Test
    public void timestampOutsideTheBucketIsRejected() {
        OhlcvBucket bucket = new OhlcvBucket(START, 3);
        add(bucket, 0, 1.20, 1.25, 1.10, 1.15, 1.0);
        assertRejected(
            bucket,
            -1,
            1.15, 1.40, 1.00, 1.30, 2.0,
            "Timestamp is outside the bucket"
        );
        assertRejected(
            bucket,
            3,
            1.15, 1.40, 1.00, 1.30, 2.0,
            "Timestamp is outside the bucket"
        );
        Assert.assertEquals(1, bucket.receivedCount());
    }

    @Test
    public void invalidOhlcIsRejected() {
        OhlcvBucket bucket = new OhlcvBucket(START, 3);
        assertRejected(bucket, 0, 1.20, 1.10, 1.00, 1.15, 1.0, "Invalid OHLC");
        assertRejected(bucket, 0, 1.20, 1.25, 1.10, 1.30, 1.0, "Invalid OHLC");
        assertRejected(bucket, 0, 1.20, 1.25, 1.21, 1.15, 1.0, "Invalid OHLC");
        assertRejected(bucket, 0, 1.20, 1.25, 1.16, 1.15, 1.0, "Invalid OHLC");
        assertRejected(bucket, 0, 1.20, 1.05, 1.10, 1.15, 1.0, "Invalid OHLC");
        Assert.assertEquals(0, bucket.receivedCount());
    }

    @Test
    public void nonFiniteValuesAreRejected() {
        OhlcvBucket bucket = new OhlcvBucket(START, 3);
        assertRejected(bucket, 0, Double.NaN, 1.25, 1.10, 1.15, 1.0, "OHLC values must be finite");
        assertRejected(bucket, 0, 1.20, Double.POSITIVE_INFINITY, 1.10, 1.15, 1.0, "OHLC values must be finite");
        assertRejected(bucket, 0, 1.20, 1.25, Double.NEGATIVE_INFINITY, 1.15, 1.0, "OHLC values must be finite");
        assertRejected(bucket, 0, 1.20, 1.25, 1.10, Double.NaN, 1.0, "OHLC values must be finite");
        assertRejected(bucket, 0, 1.20, 1.25, 1.10, 1.15, Double.NaN, "Volume must be finite");
        assertRejected(bucket, 0, 1.20, 1.25, 1.10, 1.15, Double.POSITIVE_INFINITY, "Volume must be finite");
        Assert.assertEquals(0, bucket.receivedCount());
    }

    @Test
    public void negativeVolumeIsRejected() {
        OhlcvBucket bucket = new OhlcvBucket(START, 3);
        assertRejected(bucket, 0, 1.20, 1.25, 1.10, 1.15, -0.01, "Volume must be non-negative");
        Assert.assertEquals(0, bucket.receivedCount());
    }

    @Test
    public void excessCandleCountIsRejected() {
        OhlcvBucket bucket = new OhlcvBucket(START, 2);
        add(bucket, 0, 1.20, 1.25, 1.10, 1.15, 1.0);
        add(bucket, 1, 1.15, 1.40, 1.00, 1.30, 2.0);
        Assert.assertTrue(bucket.isComplete());
        assertRejected(
            bucket,
            2,
            1.30, 1.32, 1.08, 1.12, 4.0,
            "Bucket already contains the expected number of candles"
        );
        Assert.assertEquals(2, bucket.receivedCount());
        Assert.assertEquals(1.30, bucket.close(), 0.0);
    }

    private static OhlcvBucket threeCandles() {
        OhlcvBucket bucket = new OhlcvBucket(START, 3);
        add(bucket, 0, 1.20, 1.25, 1.10, 1.15, 1.0);
        add(bucket, 1, 1.15, 1.40, 1.00, 1.30, 2.0);
        add(bucket, 2, 1.30, 1.32, 1.08, 1.12, 4.0);
        return bucket;
    }

    private static void add(
            OhlcvBucket bucket,
            int minuteOffset,
            double open,
            double high,
            double low,
            double close,
            double volume) {
        bucket.add(START + minuteOffset * 60000L, open, high, low, close, volume);
    }

    private static void assertRejected(
            OhlcvBucket bucket,
            int minuteOffset,
            double open,
            double high,
            double low,
            double close,
            double volume,
            String message) {
        try {
            add(bucket, minuteOffset, open, high, low, close, volume);
            Assert.fail("Expected rejection: " + message);
        } catch (IllegalArgumentException ex) {
            Assert.assertEquals(message, ex.getMessage());
        }
    }
}
