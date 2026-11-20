package com.tradexa.dukascopy.aggregate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * Read-only view of a production M1 ledger and its daily staging CSVs.
 * One staging file is hashed and parsed at a time. Candles are not retained.
 */
public final class M1DatasetReader {
    static final String LEDGER_FILE_NAME = "EURUSD_M1_3y.ledger.properties";
    static final String STAGING_DIRECTORY_NAME = "EURUSD_M1_3y_staging";
    static final String CSV_HEADER = "time_utc,open,high,low,close,volume";
    private static final String FINGERPRINT_HEADER = "TRADEXA_M1_DATASET_V1";
    private static final String STATUS_DATA_SAVED = "DATA_SAVED";
    private static final long MINUTE_MILLIS = BucketAlignment.MINUTE_MILLIS;
    private static final int LEDGER_SCHEMA_VERSION = 2;

    private final long windowStartMillis;
    private final long windowEndMillis;
    private final List<PlannedDay> days;

    private M1DatasetReader(
            long windowStartMillis,
            long windowEndMillis,
            List<PlannedDay> days) {
        this.windowStartMillis = windowStartMillis;
        this.windowEndMillis = windowEndMillis;
        this.days = days;
    }

    public static M1DatasetReader open(
            Path directory,
            long windowStartMillis,
            long windowEndMillis) throws IOException {
        if (directory == null) {
            throw new IllegalArgumentException("Dataset directory is required");
        }
        requireMinute(windowStartMillis, "Source window start");
        requireMinute(windowEndMillis, "Source window end");
        if (windowEndMillis < windowStartMillis) {
            throw new IllegalArgumentException(
                "Source window end is before the start"
            );
        }
        Path ledgerPath = directory.resolve(LEDGER_FILE_NAME);
        Path stagingDirectory = directory.resolve(STAGING_DIRECTORY_NAME);
        if (!Files.isRegularFile(ledgerPath) || !Files.isDirectory(stagingDirectory)) {
            throw new IOException("Dataset directory is incomplete");
        }
        TreeMap<String, LedgerDay> ledgerDays = readLedger(
            ledgerPath,
            windowStartMillis,
            windowEndMillis
        );
        List<UtcInterval> intervals = planDailyIntervals(
            windowStartMillis,
            windowEndMillis
        );
        List<PlannedDay> planned = new ArrayList<PlannedDay>();
        TreeMap<String, Boolean> expected = new TreeMap<String, Boolean>();
        for (int i = 0; i < intervals.size(); i++) {
            UtcInterval interval = intervals.get(i);
            String date = utcDate(interval.startMillis);
            expected.put(date, Boolean.TRUE);
            LedgerDay record = ledgerDays.get(date);
            if (record == null) {
                throw new IOException("Missing planned date");
            }
            if (!STATUS_DATA_SAVED.equals(record.status)) {
                throw new IOException("Ledger status is " + record.status);
            }
            if (record.firstCandle != interval.startMillis
                    || record.lastCandle != interval.endMillis) {
                throw new IOException("Incorrect source-window boundary");
            }
            Path file = stagingDirectory.resolve(date + ".csv");
            if (!Files.isRegularFile(file)) {
                throw new IOException("Missing staging file");
            }
            planned.add(new PlannedDay(
                date,
                interval.startMillis,
                interval.endMillis,
                record.rowCount,
                record.firstCandle,
                record.lastCandle,
                record.sha256,
                file
            ));
        }
        for (String date : ledgerDays.keySet()) {
            if (!expected.containsKey(date)) {
                throw new IOException("Unexpected ledger date");
            }
        }
        rejectUnexpectedStagingFiles(stagingDirectory, expected);
        return new M1DatasetReader(
            windowStartMillis,
            windowEndMillis,
            planned
        );
    }

    public long windowStartMillis() {
        return windowStartMillis;
    }

    public long windowEndMillis() {
        return windowEndMillis;
    }

    public void forEachCandle(CandleConsumer consumer) throws IOException {
        if (consumer == null) {
            throw new IllegalArgumentException("Candle consumer is required");
        }
        scan(consumer);
    }

    public DatasetFingerprint fingerprint() throws IOException {
        return scan(null).fingerprint;
    }

    /**
     * Validates every saved day and builds the fingerprint.
     * Content errors are prefixed with the UTC date. This does not write.
     */
    static DatasetFingerprint fingerprintIfValid(
            Path directory,
            long windowStartMillis,
            long windowEndMillis) throws IOException {
        M1DatasetReader reader = open(directory, windowStartMillis, windowEndMillis);
        ByteArrayOutputStream canonical = new ByteArrayOutputStream();
        writeUtf8(canonical, FINGERPRINT_HEADER);
        writeUtf8(canonical, "\n");
        long sourceRows = 0L;
        long first = Long.MIN_VALUE;
        long last = Long.MIN_VALUE;
        for (int i = 0; i < reader.days.size(); i++) {
            PlannedDay day = reader.days.get(i);
            DayResult result;
            try {
                result = readDay(day, null);
            } catch (IOException ex) {
                throw new IOException(day.date + ": " + ex.getMessage(), ex);
            }
            writeUtf8(
                canonical,
                day.date
                    + " "
                    + Long.toString(result.rows)
                    + " "
                    + day.sha256
                    + "\n"
            );
            sourceRows += result.rows;
            if (first == Long.MIN_VALUE) {
                first = result.first;
            }
            last = result.last;
        }
        byte[] canonicalBytes = canonical.toByteArray();
        return new DatasetFingerprint(
            sha256Hex(canonicalBytes),
            sourceRows,
            first,
            last,
            canonicalBytes
        );
    }

    static int plannedDateCount(long windowStartMillis, long windowEndMillis) {
        requireMinute(windowStartMillis, "Source window start");
        requireMinute(windowEndMillis, "Source window end");
        if (windowEndMillis < windowStartMillis) {
            throw new IllegalArgumentException(
                "Source window end is before the start"
            );
        }
        return planDailyIntervals(windowStartMillis, windowEndMillis).size();
    }

    static DatasetInspection inspect(
            Path directory,
            long windowStartMillis,
            long windowEndMillis) throws IOException {
        if (directory == null) {
            throw new IllegalArgumentException("Dataset directory is required");
        }
        requireMinute(windowStartMillis, "Source window start");
        requireMinute(windowEndMillis, "Source window end");
        if (windowEndMillis < windowStartMillis) {
            throw new IllegalArgumentException(
                "Source window end is before the start"
            );
        }
        Path ledgerPath = directory.resolve(LEDGER_FILE_NAME);
        Path stagingDirectory = directory.resolve(STAGING_DIRECTORY_NAME);
        if (!Files.isRegularFile(ledgerPath) || !Files.isDirectory(stagingDirectory)) {
            return DatasetInspection.fatalOnly("Dataset directory is incomplete");
        }
        TreeMap<String, LedgerDay> ledgerDays;
        try {
            ledgerDays = readLedger(ledgerPath, windowStartMillis, windowEndMillis);
        } catch (IOException ex) {
            return DatasetInspection.fatalOnly(ex.getMessage());
        }
        List<UtcInterval> intervals = planDailyIntervals(
            windowStartMillis,
            windowEndMillis
        );
        List<InspectedDay> planned = new ArrayList<InspectedDay>();
        TreeMap<String, Boolean> expected = new TreeMap<String, Boolean>();
        for (int i = 0; i < intervals.size(); i++) {
            UtcInterval interval = intervals.get(i);
            String date = utcDate(interval.startMillis);
            expected.put(date, Boolean.TRUE);
            LedgerDay record = ledgerDays.get(date);
            boolean filePresent = Files.isRegularFile(
                stagingDirectory.resolve(date + ".csv")
            );
            if (record == null) {
                planned.add(new InspectedDay(
                    date,
                    interval.startMillis,
                    interval.endMillis,
                    null,
                    -1L,
                    Long.MIN_VALUE,
                    Long.MIN_VALUE,
                    null,
                    filePresent
                ));
            } else {
                planned.add(new InspectedDay(
                    date,
                    interval.startMillis,
                    interval.endMillis,
                    record.status,
                    record.rowCount,
                    record.firstCandle,
                    record.lastCandle,
                    record.sha256,
                    filePresent
                ));
            }
        }
        List<String> unexpectedLedgerDates = new ArrayList<String>();
        for (String date : ledgerDays.keySet()) {
            if (!expected.containsKey(date)) {
                unexpectedLedgerDates.add(date);
            }
        }
        List<String> unexpectedStagingFiles = new ArrayList<String>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(stagingDirectory)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                if (!Files.isRegularFile(child)
                        || !name.matches("\\d{4}-\\d{2}-\\d{2}\\.csv")
                        || !expected.containsKey(name.substring(0, 10))) {
                    unexpectedStagingFiles.add(name);
                }
            }
        }
        Collections.sort(unexpectedStagingFiles);
        return new DatasetInspection(
            null,
            planned,
            unexpectedLedgerDates,
            unexpectedStagingFiles
        );
    }

    private Scan scan(CandleConsumer consumer) throws IOException {
        ByteArrayOutputStream canonical = new ByteArrayOutputStream();
        writeUtf8(canonical, FINGERPRINT_HEADER);
        writeUtf8(canonical, "\n");
        long sourceRows = 0L;
        long first = Long.MIN_VALUE;
        long last = Long.MIN_VALUE;
        for (int i = 0; i < days.size(); i++) {
            PlannedDay day = days.get(i);
            DayResult result = consumeDay(day, consumer);
            writeUtf8(
                canonical,
                day.date
                    + " "
                    + Long.toString(result.rows)
                    + " "
                    + day.sha256
                    + "\n"
            );
            sourceRows += result.rows;
            if (first == Long.MIN_VALUE) {
                first = result.first;
            }
            last = result.last;
        }
        byte[] canonicalBytes = canonical.toByteArray();
        return new Scan(
            new DatasetFingerprint(
                sha256Hex(canonicalBytes),
                sourceRows,
                first,
                last,
                canonicalBytes
            )
        );
    }

    private static DayResult consumeDay(
            PlannedDay day,
            CandleConsumer consumer) throws IOException {
        DayResult validated = readDay(day, null);
        if (consumer != null) {
            DayResult delivered = readDay(day, consumer);
            if (delivered.rows != validated.rows
                    || delivered.first != validated.first
                    || delivered.last != validated.last
                    || !delivered.sha256.equals(validated.sha256)) {
                throw new IOException("Staging file changed while reading");
            }
        }
        return validated;
    }

    private static DayResult readDay(
            PlannedDay day,
            CandleConsumer consumer) throws IOException {
        MessageDigest digest = newDigest();
        ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(96);
        boolean sawHeader = false;
        boolean lineOpen = false;
        int rows = 0;
        long previous = Long.MIN_VALUE;
        long first = Long.MIN_VALUE;
        long last = Long.MIN_VALUE;
        try (InputStream input = Files.newInputStream(day.file)) {
            int value;
            while ((value = input.read()) != -1) {
                digest.update((byte) value);
                if (value == '\r') {
                    throw new IOException("Invalid staging file");
                }
                if (value != '\n') {
                    lineBuffer.write(value);
                    lineOpen = true;
                    continue;
                }
                String line = new String(
                    lineBuffer.toByteArray(),
                    StandardCharsets.UTF_8
                );
                lineBuffer.reset();
                lineOpen = false;
                if (!sawHeader) {
                    if (!CSV_HEADER.equals(line)) {
                        throw new IOException("Invalid CSV header");
                    }
                    sawHeader = true;
                    continue;
                }
                if (line.length() == 0) {
                    throw new IOException("Invalid staging file");
                }
                ParsedCandle candle = parseLine(line);
                acceptCandle(day, candle, previous, rows);
                if (consumer != null) {
                    consumer.accept(
                        candle.timeMillis,
                        candle.open,
                        candle.high,
                        candle.low,
                        candle.close,
                        candle.volume
                    );
                }
                if (rows == 0) {
                    first = candle.timeMillis;
                }
                last = candle.timeMillis;
                previous = candle.timeMillis;
                rows++;
            }
        }
        if (lineOpen || !sawHeader || rows < 1) {
            throw new IOException("Invalid staging file");
        }
        String fileHash = toHex(digest.digest());
        if (!fileHash.equals(day.sha256)) {
            throw new IOException("SHA-256 does not match the ledger");
        }
        if (rows != day.rowCount) {
            throw new IOException("Row count does not match the ledger");
        }
        if (first != day.firstCandle || last != day.lastCandle) {
            throw new IOException("Staging file does not match the ledger");
        }
        if (last != day.intervalEnd) {
            throw new IOException("Incorrect source-window boundary");
        }
        return new DayResult(rows, fileHash, first, last);
    }

    private static void acceptCandle(
            PlannedDay day,
            ParsedCandle candle,
            long previous,
            int rows) throws IOException {
        if (!isFinite(candle.open)
                || !isFinite(candle.high)
                || !isFinite(candle.low)
                || !isFinite(candle.close)
                || candle.high < candle.open
                || candle.high < candle.close
                || candle.low > candle.open
                || candle.low > candle.close
                || candle.high < candle.low) {
            throw new IOException("Invalid OHLC");
        }
        if (!isFinite(candle.volume) || candle.volume < 0) {
            throw new IOException("Invalid volume");
        }
        if (!day.date.equals(utcDate(candle.timeMillis))) {
            throw new IOException("Candle is outside the requested UTC day");
        }
        if (previous != Long.MIN_VALUE && candle.timeMillis == previous) {
            throw new IOException("Duplicate timestamp");
        }
        if (previous != Long.MIN_VALUE && candle.timeMillis < previous) {
            throw new IOException("Backward timestamp");
        }
        if (candle.timeMillis < day.intervalStart
                || candle.timeMillis > day.intervalEnd) {
            throw new IOException("Incorrect source-window boundary");
        }
        if (rows == 0 && candle.timeMillis != day.intervalStart) {
            throw new IOException("Incorrect source-window boundary");
        }
        long expected = day.intervalStart + (long) rows * MINUTE_MILLIS;
        if (candle.timeMillis != expected) {
            throw new IOException("Missing M1 minute");
        }
    }

    public interface CandleConsumer {
        void accept(
                long timestampMillis,
                double open,
                double high,
                double low,
                double close,
                double volume) throws IOException;
    }

    public static final class DatasetFingerprint {
        private final String sha256;
        private final long sourceRowCount;
        private final long firstUtcMillis;
        private final long lastUtcMillis;
        private final byte[] canonicalUtf8;

        DatasetFingerprint(
                String sha256,
                long sourceRowCount,
                long firstUtcMillis,
                long lastUtcMillis,
                byte[] canonicalUtf8) {
            this.sha256 = sha256;
            this.sourceRowCount = sourceRowCount;
            this.firstUtcMillis = firstUtcMillis;
            this.lastUtcMillis = lastUtcMillis;
            this.canonicalUtf8 = canonicalUtf8;
        }

        public String sha256() {
            return sha256;
        }

        public long sourceRowCount() {
            return sourceRowCount;
        }

        public long firstUtcMillis() {
            return firstUtcMillis;
        }

        public long lastUtcMillis() {
            return lastUtcMillis;
        }

        public byte[] canonicalUtf8() {
            return Arrays.copyOf(canonicalUtf8, canonicalUtf8.length);
        }
    }

    private static void rejectUnexpectedStagingFiles(
            Path stagingDirectory,
            TreeMap<String, Boolean> expected) throws IOException {
        try (DirectoryStream<Path> children = Files.newDirectoryStream(stagingDirectory)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                if (!Files.isRegularFile(child)
                        || !name.matches("\\d{4}-\\d{2}-\\d{2}\\.csv")
                        || !expected.containsKey(name.substring(0, 10))) {
                    throw new IOException("Unexpected staging file");
                }
            }
        }
    }

    private static TreeMap<String, LedgerDay> readLedger(
            Path ledgerPath,
            long windowStartMillis,
            long windowEndMillis) throws IOException {
        byte[] bytes = Files.readAllBytes(ledgerPath);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\r') {
                throw new IOException("Malformed ledger");
            }
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.length() == 0 || text.charAt(text.length() - 1) != '\n') {
            throw new IOException("Malformed ledger");
        }
        String[] lines = text.split("\n", -1);
        long windowStart = Long.MIN_VALUE;
        long windowEnd = Long.MIN_VALUE;
        boolean sawStart = false;
        boolean sawEnd = false;
        boolean sawSchema = false;
        TreeMap<String, MutableDay> builders = new TreeMap<String, MutableDay>();
        for (int i = 0; i < lines.length - 1; i++) {
            String line = lines[i];
            int separator = line.indexOf('=');
            if (line.length() == 0 || separator <= 0) {
                throw new IOException("Malformed ledger");
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if ("windowStart".equals(key)) {
                if (sawStart) {
                    throw new IOException("Malformed ledger");
                }
                windowStart = parseLong(value);
                sawStart = true;
            } else if ("windowEnd".equals(key)) {
                if (sawEnd) {
                    throw new IOException("Malformed ledger");
                }
                windowEnd = parseLong(value);
                sawEnd = true;
            } else if ("schemaVersion".equals(key)) {
                if (sawSchema) {
                    throw new IOException("Malformed ledger");
                }
                if (parseLong(value) != LEDGER_SCHEMA_VERSION) {
                    throw new IOException("Unsupported ledger schema");
                }
                sawSchema = true;
            } else if (key.startsWith("day.")) {
                int dot = key.indexOf('.', 4);
                if (dot <= 4) {
                    throw new IOException("Malformed ledger");
                }
                String date = key.substring(4, dot);
                String field = key.substring(dot + 1);
                if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    throw new IOException("Malformed ledger");
                }
                MutableDay builder = builders.get(date);
                if (builder == null) {
                    builder = new MutableDay();
                    builders.put(date, builder);
                }
                assignField(builder, field, value);
            } else {
                throw new IOException("Malformed ledger");
            }
        }
        if (!sawSchema) {
            throw new IOException("Unsupported ledger schema");
        }
        if (!sawStart || !sawEnd
                || windowStart != windowStartMillis
                || windowEnd != windowEndMillis) {
            throw new IOException("Ledger window does not match");
        }
        TreeMap<String, LedgerDay> days = new TreeMap<String, LedgerDay>();
        for (String date : builders.keySet()) {
            days.put(date, builders.get(date).toDay(date));
        }
        return days;
    }

    private static void assignField(
            MutableDay builder,
            String field,
            String value) throws IOException {
        if ("status".equals(field)) {
            if (builder.status != null) {
                throw new IOException("Malformed ledger");
            }
            builder.status = value;
        } else if ("attempts".equals(field)) {
            builder.sawAttempts = markOnce(builder.sawAttempts);
            builder.attempts = parseLong(value);
        } else if ("lastAttempt".equals(field)) {
            builder.sawLastAttempt = markOnce(builder.sawLastAttempt);
            builder.lastAttempt = parseLong(value);
        } else if ("rowCount".equals(field)) {
            builder.sawRowCount = markOnce(builder.sawRowCount);
            builder.rowCount = parseLong(value);
        } else if ("firstCandle".equals(field)) {
            builder.sawFirst = markOnce(builder.sawFirst);
            builder.firstCandle = parseLong(value);
        } else if ("lastCandle".equals(field)) {
            builder.sawLast = markOnce(builder.sawLast);
            builder.lastCandle = parseLong(value);
        } else if ("detail".equals(field)) {
            builder.sawDetail = markOnce(builder.sawDetail);
            builder.detail = value;
        } else if ("sha256".equals(field)) {
            builder.sawSha = markOnce(builder.sawSha);
            builder.sha256 = value;
        } else if ("confirmAt".equals(field)) {
            builder.sawConfirmAt = markOnce(builder.sawConfirmAt);
            builder.confirmAt = parseLong(value);
        } else if ("confirmReason".equals(field)) {
            builder.sawConfirmReason = markOnce(builder.sawConfirmReason);
            builder.confirmReason = value;
        } else if ("confirmMethod".equals(field)) {
            builder.sawConfirmMethod = markOnce(builder.sawConfirmMethod);
            builder.confirmMethod = value;
        } else {
            throw new IOException("Malformed ledger");
        }
    }

    private static boolean markOnce(boolean seen) throws IOException {
        if (seen) {
            throw new IOException("Malformed ledger");
        }
        return true;
    }

    private static ParsedCandle parseLine(String line) throws IOException {
        String[] parts = line.split(",", -1);
        if (parts.length != 6) {
            throw new IOException("Invalid staging file");
        }
        long time;
        try {
            time = parseUtcMinute(parts[0]);
        } catch (ParseException ex) {
            throw new IOException("Invalid staging file");
        } catch (IllegalArgumentException ex) {
            throw new IOException("Timestamp is not an M1 boundary");
        }
        try {
            return new ParsedCandle(
                time,
                Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2]),
                Double.parseDouble(parts[3]),
                Double.parseDouble(parts[4]),
                Double.parseDouble(parts[5])
            );
        } catch (NumberFormatException ex) {
            throw new IOException("Invalid staging file");
        }
    }

    private static List<UtcInterval> planDailyIntervals(
            long windowStart,
            long windowEnd) {
        List<UtcInterval> intervals = new ArrayList<UtcInterval>();
        long cursor = windowStart;
        while (cursor <= windowEnd) {
            long dayEnd = endOfUtcMinuteDay(cursor);
            if (dayEnd > windowEnd) {
                dayEnd = windowEnd;
            }
            intervals.add(new UtcInterval(cursor, dayEnd));
            if (dayEnd == windowEnd) {
                break;
            }
            cursor = dayEnd + MINUTE_MILLIS;
        }
        return intervals;
    }

    private static long parseLong(String value) throws IOException {
        if (value == null || !value.matches("-?\\d+")) {
            throw new IOException("Malformed ledger");
        }
        try {
            long parsed = Long.parseLong(value);
            if (!Long.toString(parsed).equals(value)) {
                throw new IOException("Malformed ledger");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IOException("Malformed ledger");
        }
    }

    private static long parseUtcMinute(String text) throws ParseException {
        if (text == null || !text.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) {
            throw new ParseException("Invalid UTC timestamp", 0);
        }
        SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        parser.setLenient(false);
        parser.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date parsed = parser.parse(text);
        requireMinute(parsed.getTime(), text);
        if (!formatUtcMinute(parsed.getTime()).equals(text)) {
            throw new ParseException("Invalid UTC timestamp", 0);
        }
        return parsed.getTime();
    }

    private static void requireMinute(long millis, String label) {
        if (millis % MINUTE_MILLIS != 0) {
            throw new IllegalArgumentException(label + " is not an M1 boundary");
        }
    }

    private static String formatUtcMinute(long millis) {
        Calendar utc = utcCalendar(millis);
        return String.format(
            "%04d-%02d-%02d %02d:%02d:%02d",
            Integer.valueOf(utc.get(Calendar.YEAR)),
            Integer.valueOf(utc.get(Calendar.MONTH) + 1),
            Integer.valueOf(utc.get(Calendar.DAY_OF_MONTH)),
            Integer.valueOf(utc.get(Calendar.HOUR_OF_DAY)),
            Integer.valueOf(utc.get(Calendar.MINUTE)),
            Integer.valueOf(utc.get(Calendar.SECOND))
        );
    }

    private static String utcDate(long millis) {
        return formatUtcMinute(millis).substring(0, 10);
    }

    private static long endOfUtcMinuteDay(long millis) {
        Calendar utc = utcCalendar(millis);
        utc.set(Calendar.HOUR_OF_DAY, 23);
        utc.set(Calendar.MINUTE, 59);
        utc.set(Calendar.SECOND, 0);
        utc.set(Calendar.MILLISECOND, 0);
        return utc.getTimeInMillis();
    }

    private static Calendar utcCalendar(long millis) {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.setTimeInMillis(millis);
        return utc;
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static void writeUtf8(ByteArrayOutputStream output, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        output.write(bytes, 0, bytes.length);
    }

    private static String sha256Hex(byte[] content) throws IOException {
        return toHex(newDigest().digest(content));
    }

    private static MessageDigest newDigest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("SHA-256 is not available");
        }
    }

    private static String toHex(byte[] digest) {
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (int i = 0; i < digest.length; i++) {
            int value = digest[i] & 0xff;
            hex.append(Character.forDigit(value >>> 4, 16));
            hex.append(Character.forDigit(value & 0x0f, 16));
        }
        return hex.toString();
    }

    static final class DatasetInspection {
        final String fatal;
        final List<InspectedDay> planned;
        final List<String> unexpectedLedgerDates;
        final List<String> unexpectedStagingFiles;

        private DatasetInspection(
                String fatal,
                List<InspectedDay> planned,
                List<String> unexpectedLedgerDates,
                List<String> unexpectedStagingFiles) {
            this.fatal = fatal;
            this.planned = planned;
            this.unexpectedLedgerDates = unexpectedLedgerDates;
            this.unexpectedStagingFiles = unexpectedStagingFiles;
        }

        private static DatasetInspection fatalOnly(String fatal) {
            return new DatasetInspection(
                fatal,
                new ArrayList<InspectedDay>(),
                new ArrayList<String>(),
                new ArrayList<String>()
            );
        }
    }

    static final class InspectedDay {
        final String date;
        final long intervalStart;
        final long intervalEnd;
        final String status;
        final long rowCount;
        final long firstCandle;
        final long lastCandle;
        final String sha256;
        final boolean stagingFilePresent;

        private InspectedDay(
                String date,
                long intervalStart,
                long intervalEnd,
                String status,
                long rowCount,
                long firstCandle,
                long lastCandle,
                String sha256,
                boolean stagingFilePresent) {
            this.date = date;
            this.intervalStart = intervalStart;
            this.intervalEnd = intervalEnd;
            this.status = status;
            this.rowCount = rowCount;
            this.firstCandle = firstCandle;
            this.lastCandle = lastCandle;
            this.sha256 = sha256;
            this.stagingFilePresent = stagingFilePresent;
        }
    }

    private static final class PlannedDay {
        private final String date;
        private final long intervalStart;
        private final long intervalEnd;
        private final long rowCount;
        private final long firstCandle;
        private final long lastCandle;
        private final String sha256;
        private final Path file;

        private PlannedDay(
                String date,
                long intervalStart,
                long intervalEnd,
                long rowCount,
                long firstCandle,
                long lastCandle,
                String sha256,
                Path file) {
            this.date = date;
            this.intervalStart = intervalStart;
            this.intervalEnd = intervalEnd;
            this.rowCount = rowCount;
            this.firstCandle = firstCandle;
            this.lastCandle = lastCandle;
            this.sha256 = sha256;
            this.file = file;
        }
    }

    private static final class LedgerDay {
        private final String status;
        private final long rowCount;
        private final long firstCandle;
        private final long lastCandle;
        private final String sha256;

        private LedgerDay(
                String status,
                long rowCount,
                long firstCandle,
                long lastCandle,
                String sha256) {
            this.status = status;
            this.rowCount = rowCount;
            this.firstCandle = firstCandle;
            this.lastCandle = lastCandle;
            this.sha256 = sha256;
        }
    }

    private static final class MutableDay {
        private String status;
        private boolean sawAttempts;
        private long attempts = Long.MIN_VALUE;
        private boolean sawLastAttempt;
        private long lastAttempt = Long.MIN_VALUE;
        private boolean sawRowCount;
        private long rowCount = Long.MIN_VALUE;
        private boolean sawFirst;
        private long firstCandle = Long.MIN_VALUE;
        private boolean sawLast;
        private long lastCandle = Long.MIN_VALUE;
        private boolean sawDetail;
        private String detail;
        private boolean sawSha;
        private String sha256;
        private boolean sawConfirmAt;
        private long confirmAt = Long.MIN_VALUE;
        private boolean sawConfirmReason;
        private String confirmReason;
        private boolean sawConfirmMethod;
        private String confirmMethod;

        private LedgerDay toDay(String date) throws IOException {
            if (status == null
                    || !sawAttempts
                    || !sawLastAttempt
                    || !sawRowCount
                    || !sawFirst
                    || !sawLast
                    || !sawDetail
                    || !sawSha
                    || !sawConfirmAt
                    || !sawConfirmReason
                    || !sawConfirmMethod
                    || detail == null
                    || sha256 == null
                    || confirmReason == null
                    || confirmMethod == null) {
                throw new IOException("Malformed ledger");
            }
            if (STATUS_DATA_SAVED.equals(status)) {
                if (rowCount < 1L
                        || !sha256.matches("[0-9a-f]{64}")
                        || firstCandle % MINUTE_MILLIS != 0
                        || lastCandle % MINUTE_MILLIS != 0
                        || firstCandle > lastCandle
                        || !date.equals(utcDate(firstCandle))
                        || !date.equals(utcDate(lastCandle))) {
                    throw new IOException("Malformed saved-day record");
                }
            }
            return new LedgerDay(status, rowCount, firstCandle, lastCandle, sha256);
        }
    }

    private static final class ParsedCandle {
        private final long timeMillis;
        private final double open;
        private final double high;
        private final double low;
        private final double close;
        private final double volume;

        private ParsedCandle(
                long timeMillis,
                double open,
                double high,
                double low,
                double close,
                double volume) {
            this.timeMillis = timeMillis;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
        }
    }

    private static final class UtcInterval {
        private final long startMillis;
        private final long endMillis;

        private UtcInterval(long startMillis, long endMillis) {
            this.startMillis = startMillis;
            this.endMillis = endMillis;
        }
    }

    private static final class DayResult {
        private final long rows;
        private final String sha256;
        private final long first;
        private final long last;

        private DayResult(long rows, String sha256, long first, long last) {
            this.rows = rows;
            this.sha256 = sha256;
            this.first = first;
            this.last = last;
        }
    }

    private static final class Scan {
        private final DatasetFingerprint fingerprint;

        private Scan(DatasetFingerprint fingerprint) {
            this.fingerprint = fingerprint;
        }
    }
}
