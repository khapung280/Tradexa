package com.tradexa.dukascopy.aggregate;

import com.tradexa.dukascopy.aggregate.BucketAlignment.Timeframe;
import com.tradexa.dukascopy.aggregate.M1StreamAggregator.CompletedBucket;
import com.tradexa.dukascopy.aggregate.M1StreamAggregator.IncompleteBucket;
import com.tradexa.dukascopy.aggregate.M1StreamAggregator.IncompleteReason;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import org.junit.Assert;
import org.junit.Test;

public class M1StreamAggregatorTest {
    @Test
    public void fiveM1CandlesProduceOneM5Bucket() {
        long start = utc(2024, 1, 2, 12, 0, 0);
        M1StreamAggregator aggregator = feedAligned(start, 5);
        aggregator.finish();

        List<CompletedBucket> buckets = aggregator.completed(Timeframe.M5);
        Assert.assertEquals(1, buckets.size());
        Assert.assertEquals(start, buckets.get(0).startMillis());
        Assert.assertEquals(5, buckets.get(0).receivedCount());
        Assert.assertTrue(aggregator.incomplete(Timeframe.M5).isEmpty());
    }

    @Test
    public void oneFullUtcDayProducesEveryTimeframeCount() {
        M1StreamAggregator aggregator = fullDay();
        Assert.assertEquals(288, aggregator.completed(Timeframe.M5).size());
        Assert.assertEquals(96, aggregator.completed(Timeframe.M15).size());
        Assert.assertEquals(48, aggregator.completed(Timeframe.M30).size());
        Assert.assertEquals(24, aggregator.completed(Timeframe.H1).size());
        Assert.assertEquals(6, aggregator.completed(Timeframe.H4).size());
        Assert.assertEquals(1, aggregator.completed(Timeframe.D1).size());
        Timeframe[] timeframes = Timeframe.values();
        for (int i = 0; i < timeframes.length; i++) {
            Assert.assertTrue(aggregator.incomplete(timeframes[i]).isEmpty());
        }
    }

    @Test
    public void ohlcvMatchesTheSourceCandles() {
        long start = utc(2024, 1, 2, 12, 0, 0);
        M1StreamAggregator aggregator = newAggregator(start, start + 4 * MINUTE);
        add(aggregator, start, 1.10, 1.20, 1.00, 1.15, 1);
        add(aggregator, start + MINUTE, 1.15, 1.40, 1.05, 1.25, 2);
        add(aggregator, start + 2 * MINUTE, 1.25, 1.30, 0.90, 1.10, 3);
        add(aggregator, start + 3 * MINUTE, 1.10, 1.15, 1.00, 1.05, 4);
        add(aggregator, start + 4 * MINUTE, 1.05, 1.10, 0.95, 0.99, 5);
        aggregator.finish();

        CompletedBucket bucket = aggregator.completed(Timeframe.M5).get(0);
        Assert.assertEquals(1.10, bucket.open(), 0.0);
        Assert.assertEquals(1.40, bucket.high(), 0.0);
        Assert.assertEquals(0.90, bucket.low(), 0.0);
        Assert.assertEquals(0.99, bucket.close(), 0.0);
        Assert.assertEquals(15.0, bucket.volume(), 0.0);

        CompletedBucket day = fullDay().completed(Timeframe.D1).get(0);
        Assert.assertEquals(1.0, day.open(), 0.0);
        Assert.assertEquals(1.5, day.high(), 0.0);
        Assert.assertEquals(0.5, day.low(), 0.0);
        Assert.assertEquals(1.4, day.close(), 0.0);
        Assert.assertEquals(1440.0, day.volume(), 0.0);
    }

    @Test
    public void missingMinuteDropsTheAffectedM5Bucket() {
        long start = utc(2024, 1, 2, 10, 0, 0);
        M1StreamAggregator aggregator = gapAt(start, 3);
        aggregator.finish();

        Assert.assertTrue(aggregator.completed(Timeframe.M5).isEmpty());
        IncompleteBucket incomplete = oneIncomplete(aggregator, Timeframe.M5);
        Assert.assertEquals(start, incomplete.bucketStartMillis());
        Assert.assertEquals(4, incomplete.receivedCount());
        Assert.assertEquals(1, incomplete.missingInsideWindow());
        Assert.assertEquals(IncompleteReason.MISSING_INSIDE_WINDOW, incomplete.reason());
    }

    @Test
    public void missingMinuteAffectsEachTimeframeIndependently() {
        long start = utc(2024, 1, 2, 12, 0, 0);
        long missing = start + 3 * MINUTE;
        M1StreamAggregator aggregator = gapAt(start, 3);
        aggregator.finish();

        Timeframe[] timeframes = Timeframe.values();
        for (int i = 0; i < timeframes.length; i++) {
            Timeframe timeframe = timeframes[i];
            Assert.assertTrue(aggregator.completed(timeframe).isEmpty());
            IncompleteBucket incomplete = oneIncomplete(aggregator, timeframe);
            Assert.assertEquals(
                BucketAlignment.bucketStart(timeframe, missing),
                incomplete.bucketStartMillis()
            );
            Assert.assertEquals(1, incomplete.missingInsideWindow());
            Assert.assertEquals(
                IncompleteReason.MISSING_INSIDE_WINDOW,
                incomplete.reason()
            );
        }
    }

    @Test
    public void laterCompleteBucketIsEmittedAfterAnInternalGap() {
        long start = utc(2024, 1, 2, 10, 0, 0);
        M1StreamAggregator aggregator = newAggregator(start, start + 9 * MINUTE);
        addFlat(aggregator, start, 3);
        addFlat(aggregator, start + 4 * MINUTE, 1);
        addFlat(aggregator, start + 5 * MINUTE, 5);
        aggregator.finish();

        List<CompletedBucket> m5 = aggregator.completed(Timeframe.M5);
        Assert.assertEquals(1, m5.size());
        Assert.assertEquals(start + 5 * MINUTE, m5.get(0).startMillis());
        Assert.assertEquals(priceAt(start + 5 * MINUTE), m5.get(0).open(), 0.0);
        Assert.assertEquals(priceAt(start + 9 * MINUTE), m5.get(0).close(), 0.0);
        Assert.assertTrue(aggregator.completed(Timeframe.M15).isEmpty());
        Assert.assertEquals(
            IncompleteReason.MISSING_INSIDE_WINDOW,
            oneIncomplete(aggregator, Timeframe.M15).reason()
        );
    }

    @Test
    public void duplicateTimestampIsFatal() {
        long start = utc(2024, 1, 2, 12, 0, 0);
        M1StreamAggregator aggregator = newAggregator(start, start + 4 * MINUTE);
        addFlat(aggregator, start, 1);
        try {
            addFlat(aggregator, start, 1);
            Assert.fail("Expected a duplicate timestamp to fail the stream");
        } catch (IllegalArgumentException ex) {
            Assert.assertEquals("Duplicate timestamp", ex.getMessage());
        }
        Assert.assertTrue(aggregator.isFailed());
        Assert.assertEquals("Duplicate timestamp", aggregator.failureMessage());
        Assert.assertEquals(0, aggregator.completed(Timeframe.M5).size());
    }

    @Test
    public void backwardTimestampIsFatal() {
        long start = utc(2024, 1, 2, 12, 0, 0);
        M1StreamAggregator aggregator = newAggregator(start, start + 4 * MINUTE);
        addFlat(aggregator, start + MINUTE, 1);
        try {
            addFlat(aggregator, start, 1);
            Assert.fail("Expected a backward timestamp to fail the stream");
        } catch (IllegalArgumentException ex) {
            Assert.assertEquals("Backward timestamp", ex.getMessage());
        }
        Assert.assertTrue(aggregator.isFailed());
        Assert.assertFalse(aggregator.isFinished());
    }

    @Test
    public void nonMinuteTimestampIsRejectedWithoutFailingTheStream() {
        long start = utc(2024, 1, 2, 12, 0, 0);
        M1StreamAggregator aggregator = newAggregator(start, start + 4 * MINUTE);
        try {
            aggregator.add(start + 1L, 1.0, 1.0, 1.0, 1.0, 1.0);
            Assert.fail("Expected a non-minute timestamp to be rejected");
        } catch (IllegalArgumentException ex) {
            Assert.assertEquals("Timestamp is not an M1 boundary", ex.getMessage());
        }
        Assert.assertFalse(aggregator.isFailed());
        addFlat(aggregator, start, 5);
        aggregator.finish();
        Assert.assertEquals(1, aggregator.completed(Timeframe.M5).size());
    }

    @Test
    public void invalidOhlcvIsRejectedWithoutFailingTheStream() {
        long start = utc(2024, 1, 2, 12, 0, 0);
        M1StreamAggregator aggregator = newAggregator(start, start + 4 * MINUTE);
        try {
            aggregator.add(start, 1.20, 1.10, 1.00, 1.15, 1.0);
            Assert.fail("Expected invalid OHLC to be rejected");
        } catch (IllegalArgumentException ex) {
            Assert.assertEquals("Invalid OHLC", ex.getMessage());
        }
        try {
            aggregator.add(start, Double.NaN, 1.0, 1.0, 1.0, 1.0);
            Assert.fail("Expected a non-finite price to be rejected");
        } catch (IllegalArgumentException ex) {
            Assert.assertEquals("OHLC values must be finite", ex.getMessage());
        }
        Assert.assertFalse(aggregator.isFailed());
        addFlat(aggregator, start, 5);
        aggregator.finish();
        Assert.assertEquals(1, aggregator.completed(Timeframe.M5).size());
    }

    @Test
    public void endOfStreamIncompleteBucketIsDetected() {
        long start = utc(2024, 1, 2, 12, 0, 0);
        M1StreamAggregator aggregator = newAggregator(start, start + 4 * MINUTE);
        addFlat(aggregator, start, 3);
        aggregator.finish();

        Assert.assertTrue(aggregator.completed(Timeframe.M5).isEmpty());
        IncompleteBucket incomplete = oneIncomplete(aggregator, Timeframe.M5);
        Assert.assertEquals(start, incomplete.bucketStartMillis());
        Assert.assertEquals(3, incomplete.receivedCount());
        Assert.assertEquals(2, incomplete.missingInsideWindow());
        Assert.assertEquals(IncompleteReason.MISSING_INSIDE_WINDOW, incomplete.reason());
        Assert.assertTrue(aggregator.isFinished());
    }

    @Test
    public void partialWindowBoundariesAreClassifiedSeparately() {
        long firstBucket = utc(2024, 1, 2, 6, 40, 0);
        long windowStart = utc(2024, 1, 2, 6, 41, 0);
        long windowEnd = utc(2024, 1, 2, 6, 47, 0);
        M1StreamAggregator aggregator = newAggregator(windowStart, windowEnd);
        addFlat(aggregator, windowStart, 7);
        aggregator.finish();

        List<IncompleteBucket> m5 = aggregator.incomplete(Timeframe.M5);
        Assert.assertEquals(2, m5.size());
        Assert.assertEquals(firstBucket, m5.get(0).bucketStartMillis());
        Assert.assertEquals(IncompleteReason.WINDOW_BOUNDARY_START, m5.get(0).reason());
        Assert.assertEquals(0, m5.get(0).missingInsideWindow());
        Assert.assertEquals(4, m5.get(0).receivedCount());
        Assert.assertEquals(firstBucket + 5 * MINUTE, m5.get(1).bucketStartMillis());
        Assert.assertEquals(IncompleteReason.WINDOW_BOUNDARY_END, m5.get(1).reason());
        Assert.assertEquals(0, m5.get(1).missingInsideWindow());
        Assert.assertEquals(3, m5.get(1).receivedCount());
        Assert.assertTrue(aggregator.completed(Timeframe.M5).isEmpty());

        IncompleteBucket halfHour = oneIncomplete(aggregator, Timeframe.M30);
        Assert.assertEquals(IncompleteReason.WINDOW_BOUNDARY_BOTH, halfHour.reason());
        Assert.assertEquals(0, halfHour.missingInsideWindow());
    }

    @Test
    public void missingMinutesAreNotSynthesized() {
        long start = utc(2024, 1, 2, 10, 0, 0);
        M1StreamAggregator aggregator = gapAt(start, 3);
        aggregator.finish();

        IncompleteBucket incomplete = oneIncomplete(aggregator, Timeframe.M5);
        Assert.assertEquals(4, incomplete.receivedCount());
        Assert.assertEquals(5, incomplete.expectedCount());
        Assert.assertEquals(1, incomplete.missingCount());
        Assert.assertTrue(aggregator.completed(Timeframe.M5).isEmpty());
    }

    @Test
    public void repeatedFinishIsStable() {
        long start = utc(2024, 1, 2, 12, 0, 0);
        M1StreamAggregator aggregator = feedAligned(start, 5);
        aggregator.finish();
        int completed = aggregator.completed(Timeframe.M5).size();
        int incomplete = aggregator.incomplete(Timeframe.M15).size();
        long bucketStart = aggregator.completed(Timeframe.M5).get(0).startMillis();
        aggregator.finish();

        Assert.assertEquals(completed, aggregator.completed(Timeframe.M5).size());
        Assert.assertEquals(incomplete, aggregator.incomplete(Timeframe.M15).size());
        Assert.assertEquals(bucketStart, aggregator.completed(Timeframe.M5).get(0).startMillis());
        Assert.assertTrue(aggregator.isFinished());
    }

    @Test
    public void noFurtherInputAfterFatalErrorOrFinish() {
        long start = utc(2024, 1, 2, 12, 0, 0);
        M1StreamAggregator failed = newAggregator(start, start + 4 * MINUTE);
        addFlat(failed, start, 1);
        try {
            addFlat(failed, start, 1);
            Assert.fail("Expected the duplicate to fail the stream");
        } catch (IllegalArgumentException ex) {
            Assert.assertEquals("Duplicate timestamp", ex.getMessage());
        }
        try {
            addFlat(failed, start + MINUTE, 1);
            Assert.fail("Expected input to stop after failure");
        } catch (IllegalStateException ex) {
            Assert.assertEquals("Aggregator has failed", ex.getMessage());
        }

        M1StreamAggregator finished = feedAligned(start, 5);
        finished.finish();
        try {
            addFlat(finished, start + 5 * MINUTE, 1);
            Assert.fail("Expected input to stop after finish");
        } catch (IllegalStateException ex) {
            Assert.assertEquals("Aggregator is finished", ex.getMessage());
        }
    }

    @Test
    public void nonFiniteVolumeSumIsRejectedWithoutChangingTheBucket() {
        long start = utc(2024, 1, 2, 12, 0, 0);
        M1StreamAggregator aggregator = newAggregator(start, start + 4 * MINUTE);
        aggregator.add(start, 1.0, 1.0, 1.0, 1.0, Double.MAX_VALUE);
        try {
            aggregator.add(start + MINUTE, 1.0, 1.0, 1.0, 1.0, Double.MAX_VALUE);
            Assert.fail("Expected a non-finite volume sum to be rejected");
        } catch (IllegalArgumentException ex) {
            Assert.assertEquals("Volume sum is not finite", ex.getMessage());
        }
        Assert.assertFalse(aggregator.isFailed());
        aggregator.finish();
        IncompleteBucket incomplete = oneIncomplete(aggregator, Timeframe.M5);
        Assert.assertEquals(1, incomplete.receivedCount());
        Assert.assertEquals(4, incomplete.missingInsideWindow());
        Assert.assertEquals(IncompleteReason.MISSING_INSIDE_WINDOW, incomplete.reason());
    }

    private static M1StreamAggregator fullDay() {
        long start = utc(2024, 1, 1, 0, 0, 0);
        long end = utc(2024, 1, 1, 23, 59, 0);
        M1StreamAggregator aggregator = newAggregator(start, end);
        for (int i = 0; i < 1440; i++) {
            double high = 1.0;
            double low = 1.0;
            double close = 1.0;
            if (i == 100) {
                high = 1.5;
            }
            if (i == 200) {
                low = 0.5;
            }
            if (i == 1439) {
                high = 1.4;
                close = 1.4;
            }
            aggregator.add(start + i * MINUTE, 1.0, high, low, close, 1.0);
        }
        aggregator.finish();
        return aggregator;
    }

    private static M1StreamAggregator feedAligned(long start, int candles) {
        M1StreamAggregator aggregator = newAggregator(start, start + (candles - 1) * MINUTE);
        addFlat(aggregator, start, candles);
        return aggregator;
    }

    private static M1StreamAggregator gapAt(long start, int missingOffset) {
        M1StreamAggregator aggregator = newAggregator(start, start + 4 * MINUTE);
        addFlat(aggregator, start, missingOffset);
        addFlat(aggregator, start + (missingOffset + 1) * MINUTE, 4 - missingOffset);
        return aggregator;
    }

    private static void addFlat(M1StreamAggregator aggregator, long start, int candles) {
        for (int i = 0; i < candles; i++) {
            aggregator.add(
                start + i * MINUTE,
                priceAt(start + i * MINUTE),
                priceAt(start + i * MINUTE),
                priceAt(start + i * MINUTE),
                priceAt(start + i * MINUTE),
                1.0
            );
        }
    }

    private static void add(
            M1StreamAggregator aggregator,
            long timestamp,
            double open,
            double high,
            double low,
            double close,
            double volume) {
        aggregator.add(timestamp, open, high, low, close, volume);
    }

    private static IncompleteBucket oneIncomplete(
            M1StreamAggregator aggregator,
            Timeframe timeframe) {
        List<IncompleteBucket> incomplete = aggregator.incomplete(timeframe);
        Assert.assertEquals(1, incomplete.size());
        return incomplete.get(0);
    }

    private static M1StreamAggregator newAggregator(long windowStart, long windowEnd) {
        return new M1StreamAggregator(windowStart, windowEnd);
    }

    private static double priceAt(long timestamp) {
        long dayStart = utc(2024, 1, 2, 0, 0, 0);
        return 1.0 + ((timestamp - dayStart) / MINUTE) * 0.01;
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
