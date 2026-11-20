package com.tradexa.dukascopy.aggregate;

import com.tradexa.dukascopy.aggregate.M1DatasetReader.CandleConsumer;
import com.tradexa.dukascopy.aggregate.M1DatasetReader.DatasetFingerprint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import org.junit.Assert;
import org.junit.Test;

public class M1DatasetReaderTest {
    @Test
    public void validLedgerAndDailyFilesStreamInOrder() throws Exception {
        Path directory = newDirectory();
        try {
            long start = utc(2024, 5, 1, 12, 0, 0);
            long end = utc(2024, 5, 1, 12, 4, 0);
            byte[] ledgerBefore = writeSavedDays(directory, start, end, candles(start, end));
            M1DatasetReader reader = M1DatasetReader.open(directory, start, end);
            final List<Long> times = new ArrayList<Long>();
            reader.forEachCandle(new CandleConsumer() {
                public void accept(
                        long timestampMillis,
                        double open,
                        double high,
                        double low,
                        double close,
                        double volume) {
                    times.add(Long.valueOf(timestampMillis));
                    Assert.assertEquals(1.0, open, 0.0);
                    Assert.assertEquals(1.0, volume, 0.0);
                }
            });
            Assert.assertEquals(5, times.size());
            Assert.assertEquals(start, times.get(0).longValue());
            Assert.assertEquals(end, times.get(times.size() - 1).longValue());
            DatasetFingerprint fingerprint = reader.fingerprint();
            Assert.assertEquals(5L, fingerprint.sourceRowCount());
            Assert.assertEquals(start, fingerprint.firstUtcMillis());
            Assert.assertEquals(end, fingerprint.lastUtcMillis());
            Assert.assertArrayEquals(
                ledgerBefore,
                Files.readAllBytes(directory.resolve(M1DatasetReader.LEDGER_FILE_NAME))
            );
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void sha256MismatchIsRejected() throws Exception {
        Path directory = savedWindow(utc(2024, 5, 2, 12, 0, 0), utc(2024, 5, 2, 12, 2, 0));
        try {
            replaceLedgerField(directory, "sha256", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
            assertOpenFails(directory, utc(2024, 5, 2, 12, 0, 0), utc(2024, 5, 2, 12, 2, 0), "SHA-256 does not match the ledger");
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void rowCountMismatchIsRejected() throws Exception {
        Path directory = savedWindow(utc(2024, 5, 3, 12, 0, 0), utc(2024, 5, 3, 12, 2, 0));
        try {
            replaceLedgerField(directory, "rowCount", "2");
            assertOpenFails(directory, utc(2024, 5, 3, 12, 0, 0), utc(2024, 5, 3, 12, 2, 0), "Row count does not match the ledger");
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void missingStagingFileIsRejected() throws Exception {
        long start = utc(2024, 5, 4, 12, 0, 0);
        long end = utc(2024, 5, 4, 12, 1, 0);
        Path directory = newDirectory();
        try {
            writeSavedDays(directory, start, end, candles(start, end));
            Files.delete(directory.resolve(M1DatasetReader.STAGING_DIRECTORY_NAME).resolve("2024-05-04.csv"));
            assertOpenFails(directory, start, end, "Missing staging file");
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void unknownLedgerDateIsRejected() throws Exception {
        assertStatusRejected("UNKNOWN");
    }

    @Test
    public void failedLedgerDateIsRejected() throws Exception {
        assertStatusRejected("FAILED");
    }

    @Test
    public void missingPlannedDateIsRejected() throws Exception {
        long start = utc(2024, 5, 6, 23, 59, 0);
        long end = utc(2024, 5, 7, 0, 0, 0);
        Path directory = newDirectory();
        try {
            writeSavedDays(directory, start, start, candles(start, start));
            widenWindowEnd(directory, start, end);
            assertOpenFails(directory, start, end, "Missing planned date");
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void duplicateTimestampIsRejected() throws Exception {
        long start = utc(2024, 5, 8, 12, 0, 0);
        long end = utc(2024, 5, 8, 12, 1, 0);
        Path directory = newDirectory();
        try {
            List<Long> times = new ArrayList<Long>();
            times.add(Long.valueOf(start));
            times.add(Long.valueOf(start));
            writeClaimedDay(directory, start, end, times);
            assertOpenFails(directory, start, end, "Duplicate timestamp");
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void backwardTimestampIsRejected() throws Exception {
        long start = utc(2024, 5, 9, 12, 0, 0);
        long end = utc(2024, 5, 9, 12, 1, 0);
        Path directory = newDirectory();
        try {
            List<Long> times = new ArrayList<Long>();
            times.add(Long.valueOf(start));
            times.add(Long.valueOf(start - MINUTE));
            writeClaimedDay(directory, start, end, times);
            assertOpenFails(directory, start, end, "Backward timestamp");
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void missingMinuteIsRejected() throws Exception {
        long start = utc(2024, 5, 10, 12, 0, 0);
        long end = utc(2024, 5, 10, 12, 3, 0);
        Path directory = newDirectory();
        try {
            List<Long> times = new ArrayList<Long>();
            times.add(Long.valueOf(start));
            times.add(Long.valueOf(start + MINUTE));
            times.add(Long.valueOf(end));
            writeSavedDays(directory, start, end, times);
            assertOpenFails(directory, start, end, "Missing M1 minute");
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void invalidOhlcvIsRejected() throws Exception {
        long start = utc(2024, 5, 11, 12, 0, 0);
        long end = utc(2024, 5, 11, 12, 1, 0);
        Path directory = newDirectory();
        try {
            writeSavedDays(directory, start, end, candles(start, end));
            Path csv = directory.resolve(M1DatasetReader.STAGING_DIRECTORY_NAME).resolve("2024-05-11.csv");
            String text = new String(Files.readAllBytes(csv), StandardCharsets.UTF_8);
            text = text.replace("12:01:00,1.0,1.0,1.0,1.0,1.0", "12:01:00,1.2,1.0,1.1,1.0,1.0");
            Files.write(csv, text.getBytes(StandardCharsets.UTF_8));
            replaceLedgerField(directory, "sha256", sha256(Files.readAllBytes(csv)));
            assertOpenFails(directory, start, end, "Invalid OHLC");
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void incorrectUtcDateIsRejected() throws Exception {
        long start = utc(2024, 5, 12, 12, 0, 0);
        long end = utc(2024, 5, 12, 12, 1, 0);
        Path directory = newDirectory();
        try {
            writeSavedDays(directory, start, end, candles(start, end));
            Path csv = directory.resolve(M1DatasetReader.STAGING_DIRECTORY_NAME).resolve("2024-05-12.csv");
            String text = new String(Files.readAllBytes(csv), StandardCharsets.UTF_8);
            text = text.replace("2024-05-12 12:01:00", "2024-05-13 12:01:00");
            Files.write(csv, text.getBytes(StandardCharsets.UTF_8));
            replaceLedgerField(directory, "sha256", sha256(Files.readAllBytes(csv)));
            assertOpenFails(directory, start, end, "Candle is outside the requested UTC day");
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void incorrectWindowBoundaryIsRejected() throws Exception {
        long start = utc(2024, 5, 13, 6, 41, 0);
        long end = utc(2024, 5, 13, 6, 44, 0);
        Path directory = newDirectory();
        try {
            writeBoundary(directory, start, end, start + MINUTE, end);
            assertOpenFails(directory, start, end, "Incorrect source-window boundary");
        } finally {
            deleteRecursively(directory);
        }
        directory = newDirectory();
        try {
            writeBoundary(directory, start, end, start, end - MINUTE);
            assertOpenFails(directory, start, end, "Incorrect source-window boundary");
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void invalidCsvHeaderIsRejected() throws Exception {
        long start = utc(2024, 5, 14, 12, 0, 0);
        long end = utc(2024, 5, 14, 12, 1, 0);
        Path directory = newDirectory();
        try {
            writeSavedDays(directory, start, end, candles(start, end));
            Path csv = directory.resolve(M1DatasetReader.STAGING_DIRECTORY_NAME).resolve("2024-05-14.csv");
            byte[] bytes = Files.readAllBytes(csv);
            String text = new String(bytes, StandardCharsets.UTF_8).replace(M1DatasetReader.CSV_HEADER, "time,open,high,low,close,volume");
            Files.write(csv, text.getBytes(StandardCharsets.UTF_8));
            replaceLedgerField(directory, "sha256", sha256(Files.readAllBytes(csv)));
            assertOpenFails(directory, start, end, "Invalid CSV header");
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void fingerprintUsesCanonicalSerialization() throws Exception {
        long start = utc(2024, 5, 15, 23, 50, 0);
        long end = utc(2024, 5, 16, 0, 0, 0);
        Path directory = savedWindow(start, end);
        try {
            DatasetFingerprint fingerprint = M1DatasetReader.open(directory, start, end).fingerprint();
            String expected = "TRADEXA_M1_DATASET_V1\n"
                + "2024-05-15 10 " + fileSha(directory, "2024-05-15.csv") + "\n"
                + "2024-05-16 1 " + fileSha(directory, "2024-05-16.csv") + "\n";
            byte[] canonical = fingerprint.canonicalUtf8();
            Assert.assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), canonical);
            Assert.assertEquals(sha256(canonical), fingerprint.sha256());
            Assert.assertEquals(11L, fingerprint.sourceRowCount());
            Assert.assertEquals(start, fingerprint.firstUtcMillis());
            Assert.assertEquals(end, fingerprint.lastUtcMillis());
            Assert.assertTrue(expected.contains(" 10 "));
            Assert.assertTrue(expected.contains(" 1 "));
            Assert.assertFalse(expected.contains(" 01 "));
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void fingerprintBytesAreUtf8LfWithoutBom() throws Exception {
        long start = utc(2024, 5, 16, 12, 0, 0);
        long end = utc(2024, 5, 16, 12, 1, 0);
        Path directory = savedWindow(start, end);
        try {
            byte[] canonical = M1DatasetReader.open(directory, start, end).fingerprint().canonicalUtf8();
            Assert.assertEquals((byte) 'T', canonical[0]);
            Assert.assertEquals((byte) '\n', canonical[canonical.length - 1]);
            for (int i = 0; i < canonical.length; i++) {
                Assert.assertFalse(canonical[i] == '\r');
            }
            Assert.assertEquals(
                new String(canonical, StandardCharsets.UTF_8),
                new String(canonical, StandardCharsets.US_ASCII)
            );
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void fingerprintIsDeterministic() throws Exception {
        long start = utc(2024, 5, 17, 12, 0, 0);
        long end = utc(2024, 5, 17, 12, 2, 0);
        Path directory = savedWindow(start, end);
        try {
            M1DatasetReader reader = M1DatasetReader.open(directory, start, end);
            DatasetFingerprint first = reader.fingerprint();
            DatasetFingerprint second = reader.fingerprint();
            Assert.assertEquals(first.sha256(), second.sha256());
            Assert.assertArrayEquals(first.canonicalUtf8(), second.canonicalUtf8());
            Assert.assertEquals(first.sourceRowCount(), second.sourceRowCount());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void incompleteDatasetRefusesFingerprint() throws Exception {
        long start = utc(2024, 5, 18, 12, 0, 0);
        long end = utc(2024, 5, 18, 12, 1, 0);
        Path directory = newDirectory();
        try {
            writeStatus(directory, start, end, "UNKNOWN");
            try {
                M1DatasetReader.open(directory, start, end).fingerprint();
                Assert.fail("Expected an incomplete dataset to be refused");
            } catch (IOException ex) {
                Assert.assertEquals("Ledger status is UNKNOWN", ex.getMessage());
            }
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void candlesAreDeliveredBeforeALaterDayIsValidated() throws Exception {
        long firstStart = utc(2024, 5, 19, 23, 58, 0);
        long firstEnd = utc(2024, 5, 19, 23, 59, 0);
        long secondStart = utc(2024, 5, 20, 0, 0, 0);
        long secondEnd = utc(2024, 5, 20, 0, 1, 0);
        Path directory = newDirectory();
        try {
            List<Long> first = candles(firstStart, firstEnd);
            List<Long> second = candles(secondStart, secondEnd);
            List<Long> all = new ArrayList<Long>();
            all.addAll(first);
            all.addAll(second);
            writeSavedDays(directory, firstStart, secondEnd, all);
            Path secondFile = directory.resolve(M1DatasetReader.STAGING_DIRECTORY_NAME).resolve("2024-05-20.csv");
            String text = new String(Files.readAllBytes(secondFile), StandardCharsets.UTF_8);
            text = text.replace("00:00:00,1.0,1.0,1.0,1.0,1.0", "00:00:00,1.2,1.0,1.1,1.0,1.0");
            Files.write(secondFile, text.getBytes(StandardCharsets.UTF_8));
            replaceLedgerField(directory, "2024-05-20.sha256", sha256(Files.readAllBytes(secondFile)));
            M1DatasetReader reader = M1DatasetReader.open(directory, firstStart, secondEnd);
            final int[] seen = new int[1];
            try {
                reader.forEachCandle(new CandleConsumer() {
                    public void accept(
                            long timestampMillis,
                            double open,
                            double high,
                            double low,
                            double close,
                            double volume) {
                        seen[0]++;
                    }
                });
                Assert.fail("Expected the second day to be rejected");
            } catch (IOException ex) {
                Assert.assertEquals("Invalid OHLC", ex.getMessage());
            }
            Assert.assertEquals(first.size(), seen[0]);
        } finally {
            deleteRecursively(directory);
        }
    }

    private static void assertStatusRejected(String status) throws Exception {
        long start = utc(2024, 5, 5, 12, 0, 0);
        long end = utc(2024, 5, 5, 12, 1, 0);
        Path directory = newDirectory();
        try {
            writeStatus(directory, start, end, status);
            assertOpenFails(directory, start, end, "Ledger status is " + status);
        } finally {
            deleteRecursively(directory);
        }
    }

    private static void assertOpenFails(
            Path directory,
            long start,
            long end,
            String message) throws Exception {
        try {
            M1DatasetReader.open(directory, start, end).forEachCandle(new CandleConsumer() {
                public void accept(
                        long timestampMillis,
                        double open,
                        double high,
                        double low,
                        double close,
                        double volume) {
                    Assert.fail("Candle delivered from an invalid dataset");
                }
            });
            Assert.fail("Expected rejection: " + message);
        } catch (IOException ex) {
            Assert.assertEquals(message, ex.getMessage());
        }
    }

    private static Path savedWindow(long start, long end) throws Exception {
        Path directory = newDirectory();
        writeSavedDays(directory, start, end, candles(start, end));
        return directory;
    }

    private static void writeBoundary(
            Path directory,
            long windowStart,
            long windowEnd,
            long firstCandle,
            long lastCandle) throws Exception {
        Files.createDirectories(directory.resolve(M1DatasetReader.STAGING_DIRECTORY_NAME));
        byte[] csv = csvBytes(candles(firstCandle, lastCandle));
        String date = date(windowStart);
        Files.write(
            directory.resolve(M1DatasetReader.STAGING_DIRECTORY_NAME).resolve(date + ".csv"),
            csv
        );
        StringBuilder ledger = new StringBuilder();
        ledger.append("windowStart=").append(windowStart).append('\n');
        ledger.append("windowEnd=").append(windowEnd).append('\n');
        ledger.append("schemaVersion=2\n");
        appendDay(
            ledger,
            date,
            "DATA_SAVED",
            ((lastCandle - firstCandle) / MINUTE) + 1L,
            firstCandle,
            lastCandle,
            sha256(csv)
        );
        Files.write(
            directory.resolve(M1DatasetReader.LEDGER_FILE_NAME),
            ledger.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void writeClaimedDay(
            Path directory,
            long windowStart,
            long windowEnd,
            List<Long> timestamps) throws Exception {
        Files.createDirectories(directory.resolve(M1DatasetReader.STAGING_DIRECTORY_NAME));
        byte[] csv = csvBytes(timestamps);
        String date = date(windowStart);
        Files.write(
            directory.resolve(M1DatasetReader.STAGING_DIRECTORY_NAME).resolve(date + ".csv"),
            csv
        );
        StringBuilder ledger = new StringBuilder();
        ledger.append("windowStart=").append(windowStart).append('\n');
        ledger.append("windowEnd=").append(windowEnd).append('\n');
        ledger.append("schemaVersion=2\n");
        appendDay(
            ledger,
            date,
            "DATA_SAVED",
            timestamps.size(),
            windowStart,
            windowEnd,
            sha256(csv)
        );
        Files.write(
            directory.resolve(M1DatasetReader.LEDGER_FILE_NAME),
            ledger.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void widenWindowEnd(
            Path directory,
            long currentEnd,
            long widenedEnd) throws Exception {
        Path ledger = directory.resolve(M1DatasetReader.LEDGER_FILE_NAME);
        String text = new String(Files.readAllBytes(ledger), StandardCharsets.UTF_8);
        String updated = text.replace(
            "windowEnd=" + currentEnd + "\n",
            "windowEnd=" + widenedEnd + "\n"
        );
        Assert.assertFalse(updated.equals(text));
        Files.write(ledger, updated.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] writeSavedDays(
            Path directory,
            long windowStart,
            long windowEnd,
            List<Long> timestamps) throws Exception {
        Files.createDirectories(directory.resolve(M1DatasetReader.STAGING_DIRECTORY_NAME));
        List<long[]> spans = spans(windowStart, windowEnd);
        StringBuilder ledger = new StringBuilder();
        ledger.append("windowStart=").append(windowStart).append('\n');
        ledger.append("windowEnd=").append(windowEnd).append('\n');
        ledger.append("schemaVersion=2\n");
        int cursor = 0;
        for (int i = 0; i < spans.size(); i++) {
            long spanStart = spans.get(i)[0];
            long spanEnd = spans.get(i)[1];
            String date = date(spanStart);
            List<Long> day = new ArrayList<Long>();
            while (cursor < timestamps.size()
                    && timestamps.get(cursor).longValue() <= spanEnd
                    && date(timestamps.get(cursor).longValue()).equals(date)) {
                day.add(timestamps.get(cursor));
                cursor++;
            }
            byte[] csv = csvBytes(day);
            Files.write(
                directory.resolve(M1DatasetReader.STAGING_DIRECTORY_NAME).resolve(date + ".csv"),
                csv
            );
            long first = day.isEmpty() ? -1L : day.get(0).longValue();
            long last = day.isEmpty() ? -1L : day.get(day.size() - 1).longValue();
            appendDay(
                ledger,
                date,
                "DATA_SAVED",
                day.size(),
                first,
                last,
                sha256(csv)
            );
        }
        byte[] ledgerBytes = ledger.toString().getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve(M1DatasetReader.LEDGER_FILE_NAME), ledgerBytes);
        return ledgerBytes;
    }

    private static void writeStatus(
            Path directory,
            long windowStart,
            long windowEnd,
            String status) throws Exception {
        Files.createDirectories(directory.resolve(M1DatasetReader.STAGING_DIRECTORY_NAME));
        StringBuilder ledger = new StringBuilder();
        ledger.append("windowStart=").append(windowStart).append('\n');
        ledger.append("windowEnd=").append(windowEnd).append('\n');
        ledger.append("schemaVersion=2\n");
        appendDay(ledger, date(windowStart), status, 0, -1L, -1L, "");
        Files.write(
            directory.resolve(M1DatasetReader.LEDGER_FILE_NAME),
            ledger.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void appendDay(
            StringBuilder ledger,
            String date,
            String status,
            long rowCount,
            long first,
            long last,
            String sha) {
        String prefix = "day." + date + ".";
        ledger.append(prefix).append("status=").append(status).append('\n');
        ledger.append(prefix).append("attempts=1\n");
        ledger.append(prefix).append("lastAttempt=0\n");
        ledger.append(prefix).append("rowCount=").append(rowCount).append('\n');
        ledger.append(prefix).append("firstCandle=").append(first).append('\n');
        ledger.append(prefix).append("lastCandle=").append(last).append('\n');
        ledger.append(prefix).append("detail=\n");
        ledger.append(prefix).append("sha256=").append(sha).append('\n');
        ledger.append(prefix).append("confirmAt=-1\n");
        ledger.append(prefix).append("confirmReason=\n");
        ledger.append(prefix).append("confirmMethod=\n");
    }

    private static byte[] csvBytes(List<Long> timestamps) {
        StringBuilder csv = new StringBuilder();
        csv.append(M1DatasetReader.CSV_HEADER).append('\n');
        for (int i = 0; i < timestamps.size(); i++) {
            csv.append(formatMinute(timestamps.get(i).longValue()));
            csv.append(",1.0,1.0,1.0,1.0,1.0\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static List<Long> candles(long start, long end) {
        List<Long> timestamps = new ArrayList<Long>();
        for (long timestamp = start; timestamp <= end; timestamp += MINUTE) {
            timestamps.add(Long.valueOf(timestamp));
        }
        return timestamps;
    }

    private static List<long[]> spans(long windowStart, long windowEnd) {
        List<long[]> spans = new ArrayList<long[]>();
        long cursor = windowStart;
        while (cursor <= windowEnd) {
            long dayEnd = endOfDay(cursor);
            if (dayEnd > windowEnd) {
                dayEnd = windowEnd;
            }
            spans.add(new long[] {cursor, dayEnd});
            if (dayEnd == windowEnd) {
                break;
            }
            cursor = dayEnd + MINUTE;
        }
        return spans;
    }

    private static void replaceLedgerField(Path directory, String field, String value) throws Exception {
        Path ledger = directory.resolve(M1DatasetReader.LEDGER_FILE_NAME);
        String text = new String(Files.readAllBytes(ledger), StandardCharsets.UTF_8);
        String[] lines = text.split("\n", -1);
        StringBuilder updated = new StringBuilder();
        boolean replaced = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int dot = line.indexOf("." + field + "=");
            if (!replaced && dot > 0 && line.startsWith("day.")) {
                updated.append(line.substring(0, dot + field.length() + 2)).append(value);
                replaced = true;
            } else {
                updated.append(line);
            }
            if (i < lines.length - 1) {
                updated.append('\n');
            }
        }
        Assert.assertTrue(replaced);
        Files.write(ledger, updated.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String fileSha(Path directory, String name) throws Exception {
        return sha256(Files.readAllBytes(
            directory.resolve(M1DatasetReader.STAGING_DIRECTORY_NAME).resolve(name)
        ));
    }

    private static String sha256(byte[] content) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (int i = 0; i < digest.length; i++) {
            int value = digest[i] & 0xff;
            hex.append(Character.forDigit(value >>> 4, 16));
            hex.append(Character.forDigit(value & 0x0f, 16));
        }
        return hex.toString();
    }

    private static Path newDirectory() throws Exception {
        return Files.createTempDirectory("m1-dataset-reader-test");
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (path == null || !Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    private static String date(long millis) {
        return formatMinute(millis).substring(0, 10);
    }

    private static String formatMinute(long millis) {
        Calendar calendar = utcCalendar(millis);
        return String.format(
            "%04d-%02d-%02d %02d:%02d:%02d",
            Integer.valueOf(calendar.get(Calendar.YEAR)),
            Integer.valueOf(calendar.get(Calendar.MONTH) + 1),
            Integer.valueOf(calendar.get(Calendar.DAY_OF_MONTH)),
            Integer.valueOf(calendar.get(Calendar.HOUR_OF_DAY)),
            Integer.valueOf(calendar.get(Calendar.MINUTE)),
            Integer.valueOf(calendar.get(Calendar.SECOND))
        );
    }

    private static long endOfDay(long millis) {
        Calendar calendar = utcCalendar(millis);
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static long utc(int year, int month, int day, int hour, int minute, int second) {
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

    private static Calendar utcCalendar(long millis) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.setTimeInMillis(millis);
        return calendar;
    }

    private static final long MINUTE = 60000L;
}
