package com.tradexa.dukascopy.nativehist;

import com.dukascopy.charts.data.datacache.CandleData;
import com.tradexa.dukascopy.nativehist.NativeReferenceComparator.Finding;
import com.tradexa.dukascopy.nativehist.NativeReferencePlan.Day;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline replay of the reference dates against cached H1 and D1 files
 * and the corrected comparison. It does not open a network connection.
 */
public final class NativeReferenceRevalidation {
    private NativeReferenceRevalidation() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            System.out.println("FAIL: Usage: revalidate-native-reference");
            System.exit(2);
            return;
        }
        Path staging = Paths.get(args[0]).toAbsolutePath().normalize();
        Path cache = Paths.get(args[1]).toAbsolutePath().normalize();
        Path output = Paths.get(args[2]).toAbsolutePath().normalize();
        Path retryCsv = args.length == 4 ? Paths.get(args[3]).toAbsolutePath().normalize() : null;
        if (output.toString().contains("EURUSD_native_timeframes_3y")) {
            System.out.println("FAIL: Refusing to overwrite the forensic report");
            System.exit(2);
            return;
        }
        List<Finding> findings = revalidate(staging, cache, retryCsv);
        StringBuilder csv = new StringBuilder();
        csv.append("timeframe,role,date,native_time_utc,classification,field,detail\n");
        for (int i = 0; i < findings.size(); i++) {
            Finding finding = findings.get(i);
            csv.append(quote(finding.timeframe)).append(',');
            csv.append(quote(finding.role)).append(',');
            csv.append(quote(finding.date)).append(',');
            csv.append(quote(finding.nativeTimeUtc)).append(',');
            csv.append(quote(finding.classification)).append(',');
            csv.append(quote(finding.field)).append(',');
            csv.append(quote(finding.detail)).append('\n');
        }
        StringBuilder summary = new StringBuilder();
        summary.append("source=cached-reference-data\n");
        summary.append(counts(findings));
        summary.append(NativeReferenceComparator.d1Summary(findings));
        summary.append("m5_m15_m30=Replay of the verified flat-candle and HALF_UP rules. ");
        summary.append("2024-01-14 M5 uses the single retry response when that file is supplied.\n");
        summary.append("h1=Compared with the cached native hour file.\n");
        summary.append("h4=Native side is aggregated from cached native H1 candles.\n");
        summary.append("d1_volume_rule=")
            .append(NativeReferenceComparator.D1_VOLUME_LIMITATION)
            .append('\n');
        M1StagingSource.writeText(output.resolve("reference-validation-report.csv"), csv.toString());
        M1StagingSource.writeText(output.resolve("reference-validation-summary.txt"), summary.toString());
        System.out.println(summary.toString());
    }

    static List<Finding> revalidate(Path staging, Path cache, Path retryCsv) throws IOException {
        List<Finding> findings = new ArrayList<Finding>();
        List<Day> days = NativeReferencePlan.productionDays();
        List<NativeCandle> retry = retryCsv != null && Files.isRegularFile(retryCsv)
            ? NativeCsv.parse(Files.readAllBytes(retryCsv))
            : null;
        for (int i = 0; i < days.size(); i++) {
            Day day = days.get(i);
            List<NativeCandle> m1 = NativeCsv.parse(Files.readAllBytes(staging.resolve(day.date + ".csv")));
            Map<Long, NativeCandle> minutes = index(m1);
            findings.addAll(client(NativeTimeframe.M5, day, minutes, retry));
            findings.addAll(client(NativeTimeframe.M15, day, minutes, null));
            findings.addAll(client(NativeTimeframe.M30, day, minutes, null));
            List<NativeCandle> hours = hours(cache, day.date);
            findings.addAll(NativeReferenceComparator.compare(
                NativeTimeframe.H1, day.role, day.date, CLOCK,
                NativeWindow.START_MILLIS, NativeWindow.END_MILLIS, m1, hours
            ));
            findings.addAll(NativeReferenceComparator.compare(
                NativeTimeframe.H4, day.role, day.date, CLOCK,
                NativeWindow.START_MILLIS, NativeWindow.END_MILLIS, m1, h4(hours)
            ));
            findings.addAll(NativeReferenceComparator.compare(
                NativeTimeframe.D1, day.role, day.date, CLOCK,
                NativeWindow.START_MILLIS, NativeWindow.END_MILLIS, m1,
                daily(cache, day.date)
            ));
        }
        return findings;
    }

    private static List<Finding> client(
            NativeTimeframe timeframe,
            Day day,
            Map<Long, NativeCandle> minutes,
            List<NativeCandle> retry) {
        List<NativeCandle> m1 = new ArrayList<NativeCandle>(minutes.values());
        if (timeframe == NativeTimeframe.M5 && "2024-01-14".equals(day.date)) {
            List<NativeCandle> nativeBars = retry == null
                ? new ArrayList<NativeCandle>()
                : retry;
            return NativeReferenceComparator.compare(
                timeframe, day.role, day.date, CLOCK,
                NativeWindow.START_MILLIS, NativeWindow.END_MILLIS, m1, nativeBars
            );
        }
        List<Long> expected = NativeGrid.barsOnDate(
            CLOCK, timeframe, day.date, NativeWindow.START_MILLIS, NativeWindow.END_MILLIS
        );
        List<NativeCandle> nativeBars = new ArrayList<NativeCandle>();
        for (int i = 0; i < expected.size(); i++) {
            long start = expected.get(i).longValue();
            long next = CLOCK.nextBarStart(timeframe, start);
            List<NativeCandle> part = new ArrayList<NativeCandle>();
            boolean complete = true;
            for (long time = start; time < next; time += 60000L) {
                NativeCandle candle = minutes.get(Long.valueOf(time));
                if (candle == null) {
                    complete = false;
                    break;
                }
                part.add(candle);
            }
            if (complete) {
                nativeBars.add(replay(part, start, true));
            }
        }
        return NativeReferenceComparator.compare(
            timeframe, day.role, day.date, CLOCK,
            NativeWindow.START_MILLIS, NativeWindow.END_MILLIS, m1, nativeBars
        );
    }

    private static List<NativeCandle> h4(List<NativeCandle> hours) {
        List<NativeCandle> bars = new ArrayList<NativeCandle>();
        for (int i = 0; i + 4 <= hours.size(); ) {
            long start = hours.get(i).timeMillis;
            if (start % 14400000L != 0) {
                i++;
                continue;
            }
            List<NativeCandle> part = hours.subList(i, i + 4);
            bars.add(replay(part, start, true));
            i += 4;
        }
        return bars;
    }

    private static NativeCandle replay(List<NativeCandle> candles, long start, boolean roundVolume) {
        NativeCandle lastFlat = null;
        double open = 0.0;
        double high = 0.0;
        double low = 0.0;
        double close = 0.0;
        double volume = 0.0;
        double raw = 0.0;
        boolean priced = false;
        for (int i = 0; i < candles.size(); i++) {
            NativeCandle candle = candles.get(i);
            raw += candle.volume;
            if (Double.compare(candle.open, candle.high) == 0
                    && Double.compare(candle.high, candle.low) == 0
                    && Double.compare(candle.low, candle.close) == 0
                    && Double.compare(candle.volume, 0.0) == 0) {
                lastFlat = candle;
                continue;
            }
            if (!priced) {
                open = candle.open;
                high = candle.high;
                low = candle.low;
                volume = candle.volume;
                priced = true;
            } else {
                if (Double.compare(candle.high, high) > 0) {
                    high = candle.high;
                }
                if (Double.compare(candle.low, low) < 0) {
                    low = candle.low;
                }
                if (roundVolume) {
                    volume = round8(volume + candle.volume);
                }
            }
            close = candle.close;
        }
        if (!priced) {
            return new NativeCandle(
                start, lastFlat.open, lastFlat.high, lastFlat.low, lastFlat.close, 0.0
            );
        }
        return new NativeCandle(start, open, high, low, close, roundVolume ? volume : raw);
    }

    private static double round8(double value) {
        double scale = 100000000.0;
        return ((long) (value * scale + 0.5)) / scale;
    }

    private static List<NativeCandle> hours(Path cache, String date) throws IOException {
        LocalDate parsed = LocalDate.parse(date);
        long chunk = parsed.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        Path file = cache.resolve("EURUSD")
            .resolve(Integer.toString(parsed.getYear()))
            .resolve(two(parsed.getMonthValue() - 1))
            .resolve("BID_candles_hour_1.bi5");
        long day = parsed.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        List<NativeCandle> selected = new ArrayList<NativeCandle>();
        List<NativeCandle> all = decode(file, chunk);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).timeMillis >= day && all.get(i).timeMillis < day + 86400000L) {
                selected.add(all.get(i));
            }
        }
        return selected;
    }

    private static List<NativeCandle> daily(Path cache, String date) throws IOException {
        LocalDate parsed = LocalDate.parse(date);
        long chunk = LocalDate.of(parsed.getYear(), 1, 1)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli();
        Path file = cache.resolve("EURUSD")
            .resolve(Integer.toString(parsed.getYear()))
            .resolve("BID_candles_day_1.bi5");
        long day = parsed.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        List<NativeCandle> all = decode(file, chunk);
        List<NativeCandle> selected = new ArrayList<NativeCandle>();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).timeMillis == day) {
                selected.add(all.get(i));
            }
        }
        return selected;
    }

    private static List<NativeCandle> decode(Path file, long chunk) throws IOException {
        byte[] data = Files.readAllBytes(file);
        List<NativeCandle> candles = new ArrayList<NativeCandle>();
        for (int offset = 0; offset + 24 <= data.length; offset += 24) {
            CandleData candle = new CandleData();
            candle.fromBytes(5, chunk, 0.0001, data, offset);
            if (candle.getTime() == Long.MIN_VALUE) {
                continue;
            }
            candles.add(new NativeCandle(
                candle.getTime(),
                candle.getOpen(),
                candle.getHigh(),
                candle.getLow(),
                candle.getClose(),
                candle.getVolume()
            ));
        }
        return candles;
    }

    private static Map<Long, NativeCandle> index(List<NativeCandle> candles) {
        Map<Long, NativeCandle> minutes = new LinkedHashMap<Long, NativeCandle>();
        for (int i = 0; i < candles.size(); i++) {
            minutes.put(Long.valueOf(candles.get(i).timeMillis), candles.get(i));
        }
        return minutes;
    }

    private static String counts(List<Finding> findings) {
        StringBuilder text = new StringBuilder();
        NativeTimeframe[] timeframes = NativeTimeframe.values();
        for (int t = 0; t < timeframes.length; t++) {
            int exact = 0;
            int volume = 0;
            int ohlc = 0;
            int missing = 0;
            int other = 0;
            for (int i = 0; i < findings.size(); i++) {
                Finding finding = findings.get(i);
                if (!timeframes[t].name().equals(finding.timeframe)) {
                    continue;
                }
                if (NativeReferenceComparator.EXACT.equals(finding.classification)) {
                    exact++;
                } else if (NativeReferenceComparator.UNVERIFIED_SOURCE_SEMANTICS
                        .equals(finding.classification)) {
                    volume++;
                } else if ("volume".equals(finding.field)) {
                    volume++;
                } else if ("open".equals(finding.field) || "high".equals(finding.field)
                        || "low".equals(finding.field) || "close".equals(finding.field)) {
                    ohlc++;
                } else if (NativeReferenceComparator.MISSING_NATIVE.equals(finding.classification)) {
                    missing++;
                } else {
                    other++;
                }
            }
            text.append(timeframes[t].name())
                .append(" exact=").append(exact)
                .append(" volume=").append(volume)
                .append(" ohlc=").append(ohlc)
                .append(" missing=").append(missing)
                .append(" other=").append(other)
                .append('\n');
        }
        return text.toString();
    }

    private static String two(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }

    private static String quote(String value) {
        String text = value == null ? "" : value;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static final NativeBarClock CLOCK = new NativeBarClock() {
        public long barStart(NativeTimeframe timeframe, long timeMillis) {
            long duration = timeframe.durationMillis();
            return Math.floorDiv(timeMillis, duration) * duration;
        }

        public long nextBarStart(NativeTimeframe timeframe, long barTimeMillis) {
            return barTimeMillis + timeframe.durationMillis();
        }
    };
}
