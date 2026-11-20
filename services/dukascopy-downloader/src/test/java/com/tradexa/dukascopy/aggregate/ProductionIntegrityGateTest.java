package com.tradexa.dukascopy.aggregate;

import com.tradexa.dukascopy.aggregate.ProductionIntegrityGate.Diagnostic;
import com.tradexa.dukascopy.aggregate.ProductionIntegrityGate.GateResult;
import com.tradexa.dukascopy.aggregate.ProductionIntegrityGate.Verdict;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import org.junit.Assert;
import org.junit.Test;

public class ProductionIntegrityGateTest {
    @Test
    public void productionWindowPlans1097Dates() {
        Assert.assertEquals(
            ProductionIntegrityGate.PRODUCTION_PLANNED_DATES,
            ProductionIntegrityGate.productionPlannedDateCount()
        );
        Assert.assertEquals(1097, ProductionIntegrityGate.productionPlannedDateCount());
    }

    @Test
    public void validDatasetPassesAndRepeatsTheFingerprint() throws Exception {
        long start = utc(2024, 5, 1, 23, 58, 0);
        long end = utc(2024, 5, 2, 0, 1, 0);
        Path directory = savedWindow(start, end);
        try {
            GateResult result = ProductionIntegrityGate.evaluate(directory, start, end, null);
            assertPassed(result);
            String expected = M1DatasetReader.open(directory, start, end).fingerprint().sha256();
            Assert.assertEquals(expected, result.fingerprintSha256());
            Assert.assertEquals(4L, result.sourceRowCount());
            Assert.assertEquals(start, result.firstUtcMillis());
            Assert.assertEquals(end, result.lastUtcMillis());
            GateResult repeated = ProductionIntegrityGate.evaluate(
                directory,
                start,
                end,
                result.fingerprintSha256()
            );
            assertPassed(repeated);
            Assert.assertEquals(result.fingerprintSha256(), repeated.fingerprintSha256());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void unknownDatesAreBlockedWithoutAFingerprint() throws Exception {
        long start = utc(2024, 5, 3, 23, 59, 0);
        long end = utc(2024, 5, 4, 0, 0, 0);
        Path directory = newDirectory();
        try {
            writeStatuses(directory, start, end, "UNKNOWN");
            GateResult result = ProductionIntegrityGate.evaluate(directory, start, end, null);
            Assert.assertEquals(Verdict.BLOCKED, result.verdict());
            Assert.assertNull(result.fingerprintSha256());
            Assert.assertEquals(-1L, result.sourceRowCount());
            Assert.assertEquals(2, result.diagnostics().size());
            Assert.assertEquals("2024-05-03", result.diagnostics().get(0).date());
            Assert.assertEquals("2024-05-04", result.diagnostics().get(1).date());
            Assert.assertEquals(ProductionIntegrityGate.UNKNOWN_DATE, result.diagnostics().get(0).code());
            Assert.assertEquals(ProductionIntegrityGate.UNKNOWN_DATE, result.diagnostics().get(1).code());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void failedDateIsBlockedBecauseTheLedgerLeavesItUnresolved() throws Exception {
        long start = utc(2024, 5, 5, 12, 0, 0);
        long end = utc(2024, 5, 5, 12, 1, 0);
        Path directory = newDirectory();
        try {
            writeStatuses(directory, start, end, "FAILED");
            GateResult result = ProductionIntegrityGate.evaluate(directory, start, end, null);
            Assert.assertEquals(Verdict.BLOCKED, result.verdict());
            Assert.assertNull(result.fingerprintSha256());
            Assert.assertEquals(1, result.diagnostics().size());
            Diagnostic diagnostic = result.diagnostics().get(0);
            Assert.assertEquals(Verdict.BLOCKED, diagnostic.severity());
            Assert.assertEquals(ProductionIntegrityGate.FAILED_DATE, diagnostic.code());
            Assert.assertEquals("2024-05-05", diagnostic.date());
            Assert.assertTrue(diagnostic.message().contains("unresolved"));
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void confirmedEmptyIsBlockedSeparatelyFromUnknown() throws Exception {
        long start = utc(2024, 5, 6, 12, 0, 0);
        long end = utc(2024, 5, 6, 12, 1, 0);
        Path directory = newDirectory();
        try {
            writeStatuses(directory, start, end, "CONFIRMED_EMPTY");
            GateResult result = ProductionIntegrityGate.evaluate(directory, start, end, null);
            Assert.assertEquals(Verdict.BLOCKED, result.verdict());
            Assert.assertNull(result.fingerprintSha256());
            Assert.assertEquals(ProductionIntegrityGate.CONFIRMED_EMPTY, result.diagnostics().get(0).code());
            Assert.assertTrue(result.diagnostics().get(0).message().contains("not UNKNOWN"));
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void missingStagingFileFails() throws Exception {
        long start = utc(2024, 5, 7, 12, 0, 0);
        long end = utc(2024, 5, 7, 12, 1, 0);
        Path directory = savedWindow(start, end);
        try {
            Files.delete(directory.resolve(M1DatasetReader.STAGING_DIRECTORY_NAME).resolve("2024-05-07.csv"));
            GateResult result = ProductionIntegrityGate.evaluate(directory, start, end, null);
            assertFailed(result, ProductionIntegrityGate.MISSING_STAGING_FILE, "2024-05-07");
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void hashMismatchFails() throws Exception {
        long start = utc(2024, 5, 8, 12, 0, 0);
        long end = utc(2024, 5, 8, 12, 2, 0);
        Path directory = savedWindow(start, end);
        try {
            replaceLedgerField(
                directory,
                "sha256",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            );
            GateResult result = ProductionIntegrityGate.evaluate(directory, start, end, null);
            assertFailed(result, ProductionIntegrityGate.HASH_MISMATCH, "2024-05-08");
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void rowCountMismatchFails() throws Exception {
        long start = utc(2024, 5, 9, 12, 0, 0);
        long end = utc(2024, 5, 9, 12, 2, 0);
        Path directory = savedWindow(start, end);
        try {
            replaceLedgerField(directory, "rowCount", "2");
            GateResult result = ProductionIntegrityGate.evaluate(directory, start, end, null);
            assertFailed(result, ProductionIntegrityGate.ROW_COUNT_MISMATCH, "2024-05-09");
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void missingPlannedDateFails() throws Exception {
        long start = utc(2024, 5, 10, 23, 59, 0);
        long end = utc(2024, 5, 11, 0, 0, 0);
        Path directory = newDirectory();
        try {
            writeSavedDays(directory, start, start, candles(start, start));
            widenWindowEnd(directory, start, end);
            GateResult result = ProductionIntegrityGate.evaluate(directory, start, end, null);
            assertFailed(result, ProductionIntegrityGate.MISSING_PLANNED_DATE, "2024-05-11");
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void duplicateTimestampFails() throws Exception {
        long start = utc(2024, 5, 12, 12, 0, 0);
        long end = utc(2024, 5, 12, 12, 1, 0);
        Path directory = newDirectory();
        try {
            List<Long> times = new ArrayList<Long>();
            times.add(Long.valueOf(start));
            times.add(Long.valueOf(start));
            writeClaimedDay(directory, start, end, times);
            GateResult result = ProductionIntegrityGate.evaluate(directory, start, end, null);
            assertFailed(result, ProductionIntegrityGate.DUPLICATE_TIMESTAMP, "2024-05-12");
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void missingMinuteFails() throws Exception {
        long start = utc(2024, 5, 13, 12, 0, 0);
        long end = utc(2024, 5, 13, 12, 3, 0);
        Path directory = newDirectory();
        try {
            List<Long> times = new ArrayList<Long>();
            times.add(Long.valueOf(start));
            times.add(Long.valueOf(start + MINUTE));
            times.add(Long.valueOf(end));
            writeSavedDays(directory, start, end, times);
            GateResult result = ProductionIntegrityGate.evaluate(directory, start, end, null);
            assertFailed(result, ProductionIntegrityGate.MISSING_MINUTE, "2024-05-13");
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void invalidOhlcvFails() throws Exception {
        long start = utc(2024, 5, 14, 12, 0, 0);
        long end = utc(2024, 5, 14, 12, 1, 0);
        Path directory = savedWindow(start, end);
        try {
            Path csv = directory.resolve(M1DatasetReader.STAGING_DIRECTORY_NAME).resolve("2024-05-14.csv");
            String text = new String(Files.readAllBytes(csv), StandardCharsets.UTF_8);
            text = text.replace("12:01:00,1.0,1.0,1.0,1.0,1.0", "12:01:00,1.2,1.0,1.1,1.0,1.0");
            Files.write(csv, text.getBytes(StandardCharsets.UTF_8));
            replaceLedgerField(directory, "sha256", sha256(Files.readAllBytes(csv)));
            GateResult result = ProductionIntegrityGate.evaluate(directory, start, end, null);
            assertFailed(result, ProductionIntegrityGate.INVALID_OHLC, "2024-05-14");
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void incorrectWindowMetadataFails() throws Exception {
        long start = utc(2024, 5, 15, 12, 0, 0);
        long end = utc(2024, 5, 15, 12, 1, 0);
        Path directory = savedWindow(start, end);
        try {
            widenWindowEnd(directory, end, end + MINUTE);
            GateResult result = ProductionIntegrityGate.evaluate(directory, start, end, null);
            assertFailed(result, ProductionIntegrityGate.WINDOW_MISMATCH, null);
            Assert.assertEquals("Ledger window does not match", result.diagnostics().get(0).message());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void fingerprintMismatchFails() throws Exception {
        long start = utc(2024, 5, 16, 12, 0, 0);
        long end = utc(2024, 5, 16, 12, 1, 0);
        Path directory = savedWindow(start, end);
        try {
            String wrong = "0000000000000000000000000000000000000000000000000000000000000000";
            GateResult result = ProductionIntegrityGate.evaluate(directory, start, end, wrong);
            assertFailed(result, ProductionIntegrityGate.FINGERPRINT_MISMATCH, null);
            Assert.assertNull(result.fingerprintSha256());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void productionDatasetPassesWithoutWriting() throws Exception {
        Path directory = Paths.get("output", "EURUSD_M1_3y_production");
        Path ledger = directory.resolve(M1DatasetReader.LEDGER_FILE_NAME);
        Path staging = directory.resolve(M1DatasetReader.STAGING_DIRECTORY_NAME);
        Assert.assertTrue(Files.isRegularFile(ledger));
        byte[] before = Files.readAllBytes(ledger);
        FileTime modified = Files.getLastModifiedTime(ledger);
        int stagingCount = fileCount(staging);
        long stagingBytes = fileBytes(staging);
        GateResult result = ProductionIntegrityGate.evaluateProduction(directory);
        Assert.assertEquals(Verdict.PASS, result.verdict());
        Assert.assertEquals("Integrity gate passed", result.summary());
        Assert.assertEquals(0, result.diagnostics().size());
        Assert.assertEquals(0, unknownDates(result).size());
        Assert.assertEquals(1097, result.plannedDateCount());
        Assert.assertEquals(1097, result.dataSavedCount());
        Assert.assertEquals(1578241L, result.sourceRowCount());
        Assert.assertEquals(1700116860000L, result.firstUtcMillis());
        Assert.assertEquals(1794811260000L, result.lastUtcMillis());
        Assert.assertEquals(
            "92540b5a473769a828644c8493368052a9480897517985f502b0cbf1800cdbc6",
            result.fingerprintSha256()
        );
        Assert.assertArrayEquals(before, Files.readAllBytes(ledger));
        Assert.assertEquals(modified, Files.getLastModifiedTime(ledger));
        Assert.assertEquals(stagingCount, fileCount(staging));
        Assert.assertEquals(stagingBytes, fileBytes(staging));
    }

    private static void assertPassed(GateResult result) {
        Assert.assertEquals(Verdict.PASS, result.verdict());
        Assert.assertEquals("Integrity gate passed", result.summary());
        Assert.assertEquals(0, result.diagnostics().size());
        Assert.assertNotNull(result.fingerprintSha256());
        Assert.assertEquals(64, result.fingerprintSha256().length());
    }

    private static void assertFailed(GateResult result, String code, String date) {
        Assert.assertEquals(Verdict.FAIL, result.verdict());
        Assert.assertNull(result.fingerprintSha256());
        Assert.assertEquals(-1L, result.sourceRowCount());
        boolean found = false;
        for (int i = 0; i < result.diagnostics().size(); i++) {
            Diagnostic diagnostic = result.diagnostics().get(i);
            if (code.equals(diagnostic.code())) {
                Assert.assertEquals(Verdict.FAIL, diagnostic.severity());
                Assert.assertEquals(date, diagnostic.date());
                found = true;
            }
        }
        Assert.assertTrue(found);
    }

    private static List<String> unknownDates(GateResult result) {
        List<String> dates = new ArrayList<String>();
        for (int i = 0; i < result.diagnostics().size(); i++) {
            Diagnostic diagnostic = result.diagnostics().get(i);
            if (ProductionIntegrityGate.UNKNOWN_DATE.equals(diagnostic.code())) {
                dates.add(diagnostic.date());
            }
        }
        return dates;
    }

    private static Path savedWindow(long start, long end) throws Exception {
        Path directory = newDirectory();
        writeSavedDays(directory, start, end, candles(start, end));
        return directory;
    }

    private static void writeSavedDays(
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
            appendDay(ledger, date, "DATA_SAVED", day.size(), first, last, sha256(csv));
        }
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

    private static void writeStatuses(
            Path directory,
            long windowStart,
            long windowEnd,
            String status) throws Exception {
        Files.createDirectories(directory.resolve(M1DatasetReader.STAGING_DIRECTORY_NAME));
        StringBuilder ledger = new StringBuilder();
        ledger.append("windowStart=").append(windowStart).append('\n');
        ledger.append("windowEnd=").append(windowEnd).append('\n');
        ledger.append("schemaVersion=2\n");
        List<long[]> spans = spans(windowStart, windowEnd);
        for (int i = 0; i < spans.size(); i++) {
            appendDay(ledger, date(spans.get(i)[0]), status, 0, -1L, -1L, "");
        }
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

    private static int fileCount(Path directory) throws Exception {
        int count = 0;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                if (Files.isRegularFile(child)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static long fileBytes(Path directory) throws Exception {
        long total = 0L;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                if (Files.isRegularFile(child)) {
                    total += Files.size(child);
                }
            }
        }
        return total;
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
        return Files.createTempDirectory("integrity-gate-test");
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
