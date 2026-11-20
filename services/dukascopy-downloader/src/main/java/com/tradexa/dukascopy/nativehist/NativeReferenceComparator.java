package com.tradexa.dukascopy.nativehist;

import com.tradexa.dukascopy.aggregate.BucketAlignment;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares native candles with M1 candles aggregated over each native
 * bar interval. M5, M15, M30, and H1 round volume after every addition.
 * H4 is built from those H1 bars. Native D1 volume is retained as decoded
 * from the daily source file. A constituent sum is recorded only as a
 * residual and never replaces that source value. No verified formula
 * equates the two, so a structurally valid D1 volume is
 * UNVERIFIED_SOURCE_SEMANTICS rather than a pass or a generic volume failure.
 * Invalid D1 volume still fails. Equality elsewhere is exact.
 * Empty input is not a market closure.
 */
public final class NativeReferenceComparator {
    public static final String EXACT = "EXACT";
    public static final String PRICE_MISMATCH = "PRICE_MISMATCH";
    public static final String MISSING_NATIVE = "MISSING_NATIVE";
    public static final String EXTRA_NATIVE = "EXTRA_NATIVE";
    public static final String TIMESTAMP_OFF_GRID = "TIMESTAMP_OFF_GRID";
    public static final String DUPLICATE_TIMESTAMP = "DUPLICATE_TIMESTAMP";
    public static final String INVALID_OHLC = "INVALID_OHLC";
    public static final String INCOMPLETE_COVERAGE = "INCOMPLETE_COVERAGE";
    public static final String NOT_ASCENDING = "NOT_ASCENDING";
    public static final String PARTIAL_EDGE = "PARTIAL_EDGE";
    public static final String UNVERIFIED_SOURCE_SEMANTICS = "UNVERIFIED_SOURCE_SEMANTICS";
    public static final String D1_VOLUME_LIMITATION =
        "Native D1 volume is retained as decoded from BID_candles_day_1.bi5. "
            + "It is not calculated from M1 or H1. Cross-timeframe equivalence "
            + "remains UNVERIFIED_SOURCE_SEMANTICS and is not PASS.";

    private NativeReferenceComparator() {
    }

    public static final class Finding {
        public final String timeframe;
        public final String role;
        public final String date;
        public final String nativeTimeUtc;
        public final String classification;
        public final String field;
        public final String detail;

        Finding(
                String timeframe,
                String role,
                String date,
                String nativeTimeUtc,
                String classification,
                String field,
                String detail) {
            this.timeframe = timeframe;
            this.role = role;
            this.date = date;
            this.nativeTimeUtc = nativeTimeUtc;
            this.classification = classification;
            this.field = field;
            this.detail = detail;
        }
    }

    public static List<Finding> compare(
            NativeTimeframe timeframe,
            String role,
            String date,
            NativeBarClock clock,
            long windowStart,
            long windowEnd,
            List<NativeCandle> m1,
            List<NativeCandle> nativeBars) {
        List<Finding> findings = new ArrayList<Finding>();
        Map<Long, NativeCandle> minutes = new LinkedHashMap<Long, NativeCandle>();
        for (int i = 0; i < m1.size(); i++) {
            NativeCandle candle = m1.get(i);
            if (minutes.put(Long.valueOf(candle.timeMillis), candle) != null) {
                findings.add(finding(
                    timeframe, role, date, "",
                    DUPLICATE_TIMESTAMP, "m1", "Duplicate M1 timestamp"
                ));
                return findings;
            }
        }
        List<Long> expected = NativeGrid.barsOnDate(
            clock, timeframe, date, windowStart, windowEnd
        );
        Map<Long, NativeCandle> received = new LinkedHashMap<Long, NativeCandle>();
        long previous = Long.MIN_VALUE;
        for (int i = 0; i < nativeBars.size(); i++) {
            NativeCandle candle = nativeBars.get(i);
            String stamp = NativeGrid.formatStamp(candle.timeMillis);
            if (candle.timeMillis <= previous) {
                findings.add(finding(
                    timeframe, role, date, stamp,
                    NOT_ASCENDING, "timestamp", "Native candles are not strictly ascending"
                ));
            }
            previous = candle.timeMillis;
            if (received.put(Long.valueOf(candle.timeMillis), candle) != null) {
                findings.add(finding(
                    timeframe, role, date, stamp,
                    DUPLICATE_TIMESTAMP, "timestamp", "Duplicate native timestamp"
                ));
            }
        }
        for (int i = 0; i < expected.size(); i++) {
            long start = expected.get(i).longValue();
            String stamp = NativeGrid.formatStamp(start);
            NativeCandle candle = received.remove(Long.valueOf(start));
            if (candle == null) {
                String detail = nativeBars.isEmpty()
                    ? "Empty response is not a confirmed market closure"
                    : "Expected native bar was not returned";
                findings.add(finding(
                    timeframe, role, date, stamp, MISSING_NATIVE, "timestamp", detail
                ));
                continue;
            }
            findings.addAll(compareOne(
                timeframe, role, date, clock, windowStart, windowEnd, minutes, candle
            ));
        }
        for (Map.Entry<Long, NativeCandle> extra : received.entrySet()) {
            findings.add(finding(
                timeframe,
                role,
                date,
                NativeGrid.formatStamp(extra.getKey().longValue()),
                EXTRA_NATIVE,
                "timestamp",
                "Native bar is outside the bars that start on this UTC date"
            ));
        }
        return findings;
    }

    public static String failureReason(
            List<Finding> findings,
            List<NativeReferencePlan.Day> required,
            NativeTimeframe[] timeframes) {
        for (int i = 0; i < findings.size(); i++) {
            Finding finding = findings.get(i);
            if (EXACT.equals(finding.classification) || unverifiedD1Volume(finding)) {
                continue;
            }
            return finding.classification + " " + finding.timeframe
                + " " + finding.date + " " + finding.field;
        }
        for (int t = 0; t < timeframes.length; t++) {
            for (int d = 0; d < required.size(); d++) {
                if (exactCount(findings, timeframes[t], required.get(d)) < 1) {
                    return "INSUFFICIENT_EXACT " + timeframes[t].name()
                        + " " + required.get(d).role + " " + required.get(d).date;
                }
            }
        }
        return null;
    }

    private static int exactCount(
            List<Finding> findings,
            NativeTimeframe timeframe,
            NativeReferencePlan.Day day) {
        int count = 0;
        for (int i = 0; i < findings.size(); i++) {
            Finding finding = findings.get(i);
            if (EXACT.equals(finding.classification)
                    && timeframe.name().equals(finding.timeframe)
                    && day.role.equals(finding.role)
                    && day.date.equals(finding.date)) {
                count++;
            }
        }
        return count;
    }

    private static List<Finding> compareOne(
            NativeTimeframe timeframe,
            String role,
            String date,
            NativeBarClock clock,
            long windowStart,
            long windowEnd,
            Map<Long, NativeCandle> minutes,
            NativeCandle candle) {
        List<Finding> findings = new ArrayList<Finding>();
        String stamp = NativeGrid.formatStamp(candle.timeMillis);
        if (clock.barStart(timeframe, candle.timeMillis) != candle.timeMillis) {
            findings.add(finding(
                timeframe, role, date, stamp,
                TIMESTAMP_OFF_GRID, "timestamp",
                "Candle time is not the native bar start"
            ));
            return findings;
        }
        if (timeframe != NativeTimeframe.D1) {
            String ohlc = invalidOhlc(candle);
            if (ohlc != null) {
                findings.add(finding(
                    timeframe, role, date, stamp, INVALID_OHLC, ohlc, "Invalid native OHLC"
                ));
                return findings;
            }
        } else if (!finite(candle.open) || !finite(candle.high)
                || !finite(candle.low) || !finite(candle.close)
                || candle.high < candle.open || candle.high < candle.close
                || candle.low > candle.open || candle.low > candle.close
                || candle.high < candle.low) {
            findings.add(finding(
                timeframe, role, date, stamp, INVALID_OHLC, "ohlc", "Invalid native OHLC"
            ));
            return findings;
        }
        long next = clock.nextBarStart(timeframe, candle.timeMillis);
        if (next <= candle.timeMillis || (next - candle.timeMillis) % 60000L != 0) {
            findings.add(finding(
                timeframe, role, date, stamp,
                TIMESTAMP_OFF_GRID, "timestamp",
                "Native bar interval is not a positive whole number of minutes"
            ));
            return findings;
        }
        boolean partial = candle.timeMillis < windowStart || (next - 60000L) > windowEnd;
        NativeCandle aggregated = aggregate(timeframe, minutes, candle.timeMillis, next);
        if (aggregated == null) {
            findings.add(finding(
                timeframe, role, date, stamp,
                partial ? PARTIAL_EDGE : INCOMPLETE_COVERAGE,
                "coverage",
                partial
                    ? "Native bar extends outside the verified M1 window and is not fully covered"
                    : "M1 minutes are missing inside the native bar"
            ));
            if (timeframe == NativeTimeframe.D1
                    && (!finite(candle.volume) || candle.volume < 0.0)) {
                findings.add(finding(
                    timeframe, role, date, stamp, INVALID_OHLC, "volume",
                    "D1 volume is missing, non-finite, or negative"
                ));
            }
            return findings;
        }
        long delta = candle.timeMillis - BucketAlignment.bucketStart(
            BucketAlignment.Timeframe.valueOf(timeframe.name()),
            candle.timeMillis
        );
        String detail = "utc_floor_delta_millis=" + delta;
        if (timeframe == NativeTimeframe.D1) {
            String ohlcField = ohlcMismatch(candle, aggregated);
            boolean volumeValid = finite(candle.volume) && candle.volume >= 0.0;
            if (ohlcField != null) {
                findings.add(finding(
                    timeframe, role, date, stamp, PRICE_MISMATCH, ohlcField,
                    "Native OHLC differs from M1 aggregated on the native interval; " + detail
                ));
            }
            if (!volumeValid) {
                findings.add(finding(
                    timeframe, role, date, stamp, INVALID_OHLC, "volume",
                    "D1 volume is missing, non-finite, or negative; " + detail
                ));
            } else {
                findings.add(finding(
                    timeframe, role, date, stamp, UNVERIFIED_SOURCE_SEMANTICS, "volume",
                    d1VolumeDetail(candle.volume, aggregated.volume, detail)
                ));
            }
            if (ohlcField == null && volumeValid) {
                findings.add(finding(
                    timeframe, role, date, stamp,
                    partial ? PARTIAL_EDGE : EXACT,
                    partial ? "coverage" : "ohlc",
                    detail
                ));
            }
            return findings;
        }
        String mismatch = mismatchField(candle, aggregated);
        if (mismatch != null) {
            findings.add(finding(
                timeframe, role, date, stamp, PRICE_MISMATCH, mismatch,
                "Native value differs from M1 aggregated on the native interval; " + detail
            ));
            return findings;
        }
        findings.add(finding(
            timeframe, role, date, stamp,
            partial ? PARTIAL_EDGE : EXACT,
            partial ? "coverage" : "",
            detail
        ));
        return findings;
    }

    public static String d1Summary(List<Finding> findings) {
        boolean seen = false;
        boolean ohlcFailed = false;
        boolean sourceFailed = false;
        for (int i = 0; i < findings.size(); i++) {
            Finding finding = findings.get(i);
            if (!"D1".equals(finding.timeframe)) {
                continue;
            }
            seen = true;
            if (EXACT.equals(finding.classification)
                    || PARTIAL_EDGE.equals(finding.classification)
                    || unverifiedD1Volume(finding)) {
                continue;
            }
            if ("open".equals(finding.field) || "high".equals(finding.field)
                    || "low".equals(finding.field) || "close".equals(finding.field)) {
                ohlcFailed = true;
            } else if ("volume".equals(finding.field) || "ohlc".equals(finding.field)) {
                sourceFailed = true;
                if ("ohlc".equals(finding.field)) {
                    ohlcFailed = true;
                }
            } else {
                ohlcFailed = true;
                sourceFailed = true;
            }
        }
        String ohlc = !seen ? "NOT_RUN" : (ohlcFailed ? "FAIL" : "PASS");
        String source = !seen ? "NOT_RUN" : (sourceFailed ? "FAIL" : "PASS");
        String semantics = !seen ? "NOT_RUN" : (sourceFailed ? "FAIL" : UNVERIFIED_SOURCE_SEMANTICS);
        if ("PASS".equals(semantics)) {
            throw new IllegalStateException("D1 volume semantics cannot be PASS");
        }
        return "d1_ohlc_verdict=" + ohlc + "\n"
            + "d1_volume_source_integrity=" + source + "\n"
            + "d1_volume_cross_timeframe_verdict=" + semantics + "\n"
            + "d1_volume_semantics=" + semantics + "\n"
            + "d1_volume_limitation=" + D1_VOLUME_LIMITATION + "\n";
    }

    private static boolean unverifiedD1Volume(Finding finding) {
        return UNVERIFIED_SOURCE_SEMANTICS.equals(finding.classification)
            && "D1".equals(finding.timeframe)
            && "volume".equals(finding.field);
    }

    private static String d1VolumeDetail(double nativeVolume, double constituentSum, String alignment) {
        return "native_volume=" + Double.toString(nativeVolume)
            + " constituent_sum=" + Double.toString(constituentSum)
            + " delta=" + Double.toString(nativeVolume - constituentSum)
            + "; " + D1_VOLUME_LIMITATION + " " + alignment;
    }

    private static NativeCandle aggregate(
            NativeTimeframe timeframe,
            Map<Long, NativeCandle> minutes,
            long start,
            long next) {
        if (timeframe == NativeTimeframe.H4) {
            List<NativeCandle> hours = new ArrayList<NativeCandle>();
            for (long hour = start; hour < next; hour += 3600000L) {
                NativeCandle hourBar = aggregateMinutes(minutes, hour, hour + 3600000L, true);
                if (hourBar == null) {
                    return null;
                }
                hours.add(hourBar);
            }
            return aggregateCandles(hours, start, true);
        }
        return aggregateMinutes(minutes, start, next, timeframe != NativeTimeframe.D1);
    }

    private static NativeCandle aggregateMinutes(
            Map<Long, NativeCandle> minutes,
            long start,
            long next,
            boolean roundVolume) {
        List<NativeCandle> candles = new ArrayList<NativeCandle>();
        for (long time = start; time < next; time += 60000L) {
            NativeCandle candle = minutes.get(Long.valueOf(time));
            if (candle == null) {
                return null;
            }
            candles.add(candle);
        }
        if (candles.size() != (next - start) / 60000L) {
            return null;
        }
        return aggregateCandles(candles, start, roundVolume);
    }

    static NativeCandle aggregateCandles(
            List<NativeCandle> candles,
            long start,
            boolean roundVolume) {
        NativeCandle lastFlat = null;
        NativeCandle openCandle = null;
        NativeCandle closeCandle = null;
        double high = 0.0;
        double low = 0.0;
        BigDecimal rounded = null;
        double rawVolume = 0.0;
        boolean priced = false;
        for (int i = 0; i < candles.size(); i++) {
            NativeCandle candle = candles.get(i);
            rawVolume += candle.volume;
            if (flatZero(candle)) {
                lastFlat = candle;
                continue;
            }
            if (!priced) {
                openCandle = candle;
                high = candle.high;
                low = candle.low;
                rounded = BigDecimal.valueOf(candle.volume);
                priced = true;
            } else {
                if (Double.compare(candle.high, high) > 0) {
                    high = candle.high;
                }
                if (Double.compare(candle.low, low) < 0) {
                    low = candle.low;
                }
                if (roundVolume) {
                    rounded = rounded.add(BigDecimal.valueOf(candle.volume))
                        .setScale(8, RoundingMode.HALF_UP);
                }
            }
            closeCandle = candle;
        }
        if (!priced) {
            if (lastFlat == null) {
                return null;
            }
            return new NativeCandle(
                start, lastFlat.open, lastFlat.high, lastFlat.low, lastFlat.close, 0.0
            );
        }
        double volume = roundVolume ? rounded.doubleValue() : rawVolume;
        return new NativeCandle(start, openCandle.open, high, low, closeCandle.close, volume);
    }

    private static boolean flatZero(NativeCandle candle) {
        return Double.compare(candle.open, candle.high) == 0
            && Double.compare(candle.high, candle.low) == 0
            && Double.compare(candle.low, candle.close) == 0
            && Double.compare(candle.volume, 0.0) == 0;
    }

    private static String ohlcMismatch(NativeCandle nativeCandle, NativeCandle aggregated) {
        if (Double.compare(nativeCandle.open, aggregated.open) != 0) {
            return "open";
        }
        if (Double.compare(nativeCandle.high, aggregated.high) != 0) {
            return "high";
        }
        if (Double.compare(nativeCandle.low, aggregated.low) != 0) {
            return "low";
        }
        if (Double.compare(nativeCandle.close, aggregated.close) != 0) {
            return "close";
        }
        return null;
    }

    private static String mismatchField(NativeCandle nativeCandle, NativeCandle aggregated) {
        String ohlc = ohlcMismatch(nativeCandle, aggregated);
        if (ohlc != null) {
            return ohlc;
        }
        if (Double.compare(nativeCandle.volume, aggregated.volume) != 0) {
            return "volume";
        }
        return null;
    }

    static String invalidOhlc(NativeCandle candle) {
        if (!finite(candle.open) || !finite(candle.high)
                || !finite(candle.low) || !finite(candle.close)) {
            return "ohlc";
        }
        if (!finite(candle.volume) || candle.volume < 0.0) {
            return "volume";
        }
        if (candle.high < candle.open || candle.high < candle.close
                || candle.low > candle.open || candle.low > candle.close
                || candle.high < candle.low) {
            return "ohlc";
        }
        return null;
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static Finding finding(
            NativeTimeframe timeframe,
            String role,
            String date,
            String stamp,
            String classification,
            String field,
            String detail) {
        return new Finding(
            timeframe.name(), role, date, stamp, classification, field, detail
        );
    }
}
