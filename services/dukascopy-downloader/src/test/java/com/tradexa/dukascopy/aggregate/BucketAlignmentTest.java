package com.tradexa.dukascopy.aggregate;

import com.tradexa.dukascopy.aggregate.BucketAlignment.Timeframe;
import java.util.Calendar;
import java.util.TimeZone;
import org.junit.Assert;
import org.junit.Test;

public class BucketAlignmentTest {
    @Test
    public void m5Alignment() {
        long timestamp = utc(2023, 11, 16, 6, 43, 0);
        Assert.assertEquals(utc(2023, 11, 16, 6, 40, 0), start(Timeframe.M5, timestamp));
    }

    @Test
    public void m15Alignment() {
        long timestamp = utc(2023, 11, 16, 6, 46, 0);
        Assert.assertEquals(utc(2023, 11, 16, 6, 45, 0), start(Timeframe.M15, timestamp));
    }

    @Test
    public void m30Alignment() {
        long timestamp = utc(2023, 11, 16, 6, 59, 0);
        Assert.assertEquals(utc(2023, 11, 16, 6, 30, 0), start(Timeframe.M30, timestamp));
    }

    @Test
    public void h1Alignment() {
        long timestamp = utc(2023, 11, 16, 6, 41, 0);
        Assert.assertEquals(utc(2023, 11, 16, 6, 0, 0), start(Timeframe.H1, timestamp));
    }

    @Test
    public void h4Alignment() {
        long timestamp = utc(2023, 11, 16, 6, 41, 0);
        Assert.assertEquals(utc(2023, 11, 16, 4, 0, 0), start(Timeframe.H4, timestamp));
        Assert.assertEquals(utc(2023, 11, 16, 8, 0, 0), start(Timeframe.H4, utc(2023, 11, 16, 11, 59, 0)));
        Assert.assertEquals(utc(2023, 11, 16, 20, 0, 0), start(Timeframe.H4, utc(2023, 11, 16, 23, 59, 0)));
    }

    @Test
    public void d1Alignment() {
        long timestamp = utc(2023, 11, 16, 6, 41, 0);
        Assert.assertEquals(utc(2023, 11, 16, 0, 0, 0), start(Timeframe.D1, timestamp));
        Assert.assertEquals(utc(2023, 11, 16, 0, 0, 0), start(Timeframe.D1, utc(2023, 11, 16, 23, 59, 0)));
    }

    @Test
    public void timestampExactlyOnABucketBoundary() {
        long boundary = utc(2023, 11, 16, 6, 45, 0);
        Assert.assertEquals(boundary, start(Timeframe.M5, boundary));
        Assert.assertEquals(boundary, start(Timeframe.M15, boundary));
        Assert.assertEquals(utc(2023, 11, 16, 0, 0, 0), start(Timeframe.D1, utc(2023, 11, 16, 0, 0, 0)));
    }

    @Test
    public void timestampImmediatelyBeforeAndAfterABoundary() {
        long boundary = utc(2023, 11, 16, 8, 0, 0);
        Assert.assertEquals(
            utc(2023, 11, 16, 4, 0, 0),
            start(Timeframe.H4, boundary - 1L)
        );
        Assert.assertEquals(boundary, start(Timeframe.H4, boundary));
        Assert.assertEquals(boundary, start(Timeframe.H4, boundary + 1L));
    }

    @Test
    public void pre1970TimestampUsesFloorDivision() {
        long duration = Timeframe.M5.durationMillis();
        Assert.assertEquals(0L, (-1L / duration) * duration);
        Assert.assertEquals(-duration, start(Timeframe.M5, -1L));
        Assert.assertEquals(-duration, start(Timeframe.M5, -duration));
        Assert.assertEquals(-2L * duration, start(Timeframe.M5, -duration - 1L));
        Assert.assertEquals(
            -Timeframe.D1.durationMillis(),
            start(Timeframe.D1, -1L)
        );
    }

    @Test
    public void alignmentIsIndependentOfLocalTimezone() {
        long timestamp = utc(2023, 11, 16, 6, 43, 0);
        long utcBucket = utc(2023, 11, 16, 6, 30, 0);
        long offset = (5 * 60 + 45) * MINUTE;
        long localMillis = timestamp + offset;
        long localBucket = Math.floorDiv(localMillis, Timeframe.M30.durationMillis())
            * Timeframe.M30.durationMillis();
        long localBucketAsUtc = localBucket - offset;

        Assert.assertEquals(utcBucket, start(Timeframe.M30, timestamp));
        Assert.assertEquals(utc(2023, 11, 16, 6, 15, 0), localBucketAsUtc);
        Assert.assertTrue(localBucketAsUtc != utcBucket);
        Assert.assertEquals(
            Math.floorDiv(timestamp, Timeframe.M30.durationMillis())
                * Timeframe.M30.durationMillis(),
            start(Timeframe.M30, timestamp)
        );
    }

    @Test
    public void invalidTimeframeLengthIsRejected() {
        int[] invalid = new int[] {1, 0, 7, -5, 1441};
        for (int i = 0; i < invalid.length; i++) {
            try {
                Timeframe.fromMinutes(invalid[i]);
                Assert.fail("Expected rejection for " + invalid[i]);
            } catch (IllegalArgumentException ex) {
                Assert.assertEquals(
                    "Unsupported timeframe length: " + invalid[i],
                    ex.getMessage()
                );
            }
        }
        try {
            BucketAlignment.bucketStart(null, 0L);
            Assert.fail("Expected rejection for a null timeframe");
        } catch (IllegalArgumentException ex) {
            Assert.assertEquals("Timeframe is required", ex.getMessage());
        }
    }

    private static long start(Timeframe timeframe, long timestampMillis) {
        return BucketAlignment.bucketStart(timeframe, timestampMillis);
    }

    private static long utc(
            int year,
            int month,
            int day,
            int hour,
            int minute,
            int second) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.setLenient(false);
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, day);
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, second);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static final long MINUTE = 60000L;
}
