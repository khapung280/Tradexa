package com.tradexa.dukascopy.nativehist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-timeframe ledger and scheduler. DATA_SAVED rows are never removed.
 */
public final class NativeLedger {
    static final int SCHEMA = 1;
    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    public static final class Day {
        public String status = "";
        public int attempts;
        public long rows = -1L;
        public long firstCandle = -1L;
        public long lastCandle = -1L;
        public String sha256 = "";
        public String detail = "";
    }

    public final NativeTimeframe timeframe;
    public final long windowStart;
    public final long windowEnd;
    public final int batchSize;
    public long expectedFirst = -1L;
    public long expectedLast = -1L;
    public final List<String> planned = new ArrayList<String>();
    public final Map<String, Day> days = new LinkedHashMap<String, Day>();
    public int nextBatch;
    public String lastCheckpointUtc = "";

    public NativeLedger(
            NativeTimeframe timeframe,
            long windowStart,
            long windowEnd,
            int batchSize) {
        this.timeframe = timeframe;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.batchSize = batchSize;
    }

    public static NativeLedger open(
            Path directory,
            NativeTimeframe timeframe,
            long windowStart,
            long windowEnd,
            int batchSize) throws IOException {
        Files.createDirectories(directory);
        Path ledgerPath = ledgerPath(directory, timeframe);
        NativeLedger ledger = new NativeLedger(timeframe, windowStart, windowEnd, batchSize);
        if (Files.exists(ledgerPath)) {
            ledger.read(ledgerPath);
        }
        Path schedulerPath = schedulerPath(directory, timeframe);
        if (Files.exists(schedulerPath)) {
            ledger.readScheduler(schedulerPath);
        }
        return ledger;
    }

    public void putPlanned(List<String> dates, long first, long last) throws IOException {
        if (expectedFirst >= 0L && (expectedFirst != first || expectedLast != last)) {
            throw new IOException("Native bar grid changed");
        }
        if (!planned.isEmpty() && !planned.equals(dates)) {
            throw new IOException("Planned native dates changed");
        }
        expectedFirst = first;
        expectedLast = last;
        if (planned.isEmpty()) {
            planned.addAll(dates);
        }
    }

    public boolean saved(String date) {
        Day day = days.get(date);
        return day != null && "DATA_SAVED".equals(day.status);
    }

    public void recordSaved(String date, int attempts, List<NativeCandle> candles, String sha)
            throws IOException {
        Day previous = days.get(date);
        if (previous != null && "DATA_SAVED".equals(previous.status)) {
            throw new IOException("Verified native output already exists");
        }
        Day day = new Day();
        day.status = "DATA_SAVED";
        day.attempts = attempts;
        day.rows = candles.size();
        day.firstCandle = candles.isEmpty() ? -1L : candles.get(0).timeMillis;
        day.lastCandle = candles.isEmpty() ? -1L : candles.get(candles.size() - 1).timeMillis;
        day.sha256 = sha;
        day.detail = "saved";
        days.put(date, day);
    }

    public void recordUnresolved(String date, int attempts, String detail, boolean failed) {
        Day previous = days.get(date);
        if (previous != null && "DATA_SAVED".equals(previous.status)) {
            return;
        }
        Day day = new Day();
        day.status = failed ? "FAILED" : "UNRESOLVED";
        day.attempts = attempts;
        day.rows = -1L;
        day.firstCandle = -1L;
        day.lastCandle = -1L;
        day.sha256 = "";
        day.detail = detail;
        days.put(date, day);
    }

    public int count(String status) {
        int count = 0;
        for (Day day : days.values()) {
            if (status.equals(day.status)) {
                count++;
            }
        }
        return count;
    }

    public void write(Path directory) throws IOException {
        StringBuilder ledger = new StringBuilder();
        ledger.append("windowStart=").append(windowStart).append('\n');
        ledger.append("windowEnd=").append(windowEnd).append('\n');
        ledger.append("schemaVersion=").append(SCHEMA).append('\n');
        ledger.append("timeframe=").append(timeframe.name()).append('\n');
        ledger.append("batchSize=").append(batchSize).append('\n');
        ledger.append("expectedFirst=").append(expectedFirst).append('\n');
        ledger.append("expectedLast=").append(expectedLast).append('\n');
        for (int i = 0; i < planned.size(); i++) {
            ledger.append("planned=").append(planned.get(i)).append('\n');
        }
        for (int i = 0; i < planned.size(); i++) {
            String date = planned.get(i);
            Day day = days.get(date);
            if (day == null) {
                continue;
            }
            ledger.append(date).append(".status=").append(day.status).append('\n');
            ledger.append(date).append(".attempts=").append(day.attempts).append('\n');
            ledger.append(date).append(".rows=").append(day.rows).append('\n');
            ledger.append(date).append(".firstCandle=").append(day.firstCandle).append('\n');
            ledger.append(date).append(".lastCandle=").append(day.lastCandle).append('\n');
            ledger.append(date).append(".sha256=").append(day.sha256).append('\n');
            ledger.append(date).append(".detail=").append(sanitize(day.detail)).append('\n');
        }
        NativeCsv.atomicWrite(
            ledgerPath(directory, timeframe),
            ledger.toString().getBytes(StandardCharsets.UTF_8)
        );
        StringBuilder scheduler = new StringBuilder();
        scheduler.append("schemaVersion=").append(SCHEMA).append('\n');
        scheduler.append("timeframe=").append(timeframe.name()).append('\n');
        scheduler.append("windowStart=").append(windowStart).append('\n');
        scheduler.append("windowEnd=").append(windowEnd).append('\n');
        scheduler.append("batchSize=").append(batchSize).append('\n');
        scheduler.append("nextBatch=").append(nextBatch).append('\n');
        scheduler.append("lastCheckpointUtc=").append(lastCheckpointUtc).append('\n');
        NativeCsv.atomicWrite(
            schedulerPath(directory, timeframe),
            scheduler.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    public void touchCheckpoint() {
        lastCheckpointUtc = STAMP.format(Instant.now());
        int batchCount = NativeBatchPlanner.batchCount(planned.size(), batchSize);
        int next = batchCount;
        for (int batch = 0; batch < batchCount; batch++) {
            List<String> dates = NativeBatchPlanner.batch(planned, batch, batchSize);
            boolean complete = true;
            for (int i = 0; i < dates.size(); i++) {
                if (!saved(dates.get(i))) {
                    complete = false;
                    break;
                }
            }
            if (!complete) {
                next = batch;
                break;
            }
        }
        nextBatch = next;
    }

    public static Path ledgerPath(Path directory, NativeTimeframe timeframe) {
        return directory.resolve(timeframe.name() + ".ledger.properties");
    }

    public static Path schedulerPath(Path directory, NativeTimeframe timeframe) {
        return directory.resolve(timeframe.name() + ".scheduler.properties");
    }

    private void read(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        long start = Long.MIN_VALUE;
        long end = Long.MIN_VALUE;
        int schema = Integer.MIN_VALUE;
        String name = "";
        int size = Integer.MIN_VALUE;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.length() == 0) {
                continue;
            }
            int split = line.indexOf('=');
            if (split <= 0) {
                throw new IOException("Malformed native ledger");
            }
            String key = line.substring(0, split);
            String value = line.substring(split + 1);
            if ("windowStart".equals(key)) {
                start = Long.parseLong(value);
            } else if ("windowEnd".equals(key)) {
                end = Long.parseLong(value);
            } else if ("schemaVersion".equals(key)) {
                schema = Integer.parseInt(value);
            } else if ("timeframe".equals(key)) {
                name = value;
            } else if ("batchSize".equals(key)) {
                size = Integer.parseInt(value);
            } else if ("expectedFirst".equals(key)) {
                expectedFirst = Long.parseLong(value);
            } else if ("expectedLast".equals(key)) {
                expectedLast = Long.parseLong(value);
            } else if ("planned".equals(key)) {
                planned.add(value);
            } else {
                int dot = key.indexOf('.');
                if (dot != 10) {
                    throw new IOException("Malformed native ledger");
                }
                String date = key.substring(0, dot);
                String field = key.substring(dot + 1);
                Day day = days.get(date);
                if (day == null) {
                    day = new Day();
                    days.put(date, day);
                }
                if ("status".equals(field)) {
                    day.status = value;
                } else if ("attempts".equals(field)) {
                    day.attempts = Integer.parseInt(value);
                } else if ("rows".equals(field)) {
                    day.rows = Long.parseLong(value);
                } else if ("firstCandle".equals(field)) {
                    day.firstCandle = Long.parseLong(value);
                } else if ("lastCandle".equals(field)) {
                    day.lastCandle = Long.parseLong(value);
                } else if ("sha256".equals(field)) {
                    day.sha256 = value;
                } else if ("detail".equals(field)) {
                    day.detail = value;
                } else {
                    throw new IOException("Malformed native ledger");
                }
            }
        }
        if (start != windowStart || end != windowEnd || schema != SCHEMA
                || !timeframe.name().equals(name) || size != batchSize) {
            throw new IOException("Native ledger does not match the fixed window");
        }
    }

    private void readScheduler(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int split = line.indexOf('=');
            if (split <= 0) {
                throw new IOException("Malformed native scheduler");
            }
            String key = line.substring(0, split);
            String value = line.substring(split + 1);
            if ("nextBatch".equals(key)) {
                nextBatch = Integer.parseInt(value);
            } else if ("lastCheckpointUtc".equals(key)) {
                lastCheckpointUtc = value;
            } else if ("windowStart".equals(key) && Long.parseLong(value) != windowStart) {
                throw new IOException("Native scheduler does not match the fixed window");
            } else if ("windowEnd".equals(key) && Long.parseLong(value) != windowEnd) {
                throw new IOException("Native scheduler does not match the fixed window");
            }
        }
    }

    private static String sanitize(String detail) {
        if (detail == null) {
            return "";
        }
        String cleaned = detail.replace('\n', ' ').replace('\r', ' ').replace('=', ' ');
        if (cleaned.length() > 180) {
            return cleaned.substring(0, 180);
        }
        return cleaned;
    }
}
