package com.tradexa.dukascopy.aggregate;

import com.tradexa.dukascopy.aggregate.M1DatasetReader.DatasetFingerprint;
import com.tradexa.dukascopy.aggregate.M1DatasetReader.DatasetInspection;
import com.tradexa.dukascopy.aggregate.M1DatasetReader.InspectedDay;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Read-only gate that must pass before production M1 aggregation.
 *
 * <p>UNKNOWN and FAILED are unresolved ledger outcomes. The downloader's
 * completeness check counts both as unresolved, and neither one is saved
 * candle data. The gate reports them as BLOCKED. CONFIRMED_EMPTY is a
 * resolved confirmation that the day has no candles. It is not UNKNOWN,
 * and it is still BLOCKED because aggregation requires DATA_SAVED candles.
 * A missing ledger row, a bad hash, a malformed record, or any other
 * contradiction in saved data is FAIL.
 *
 * <p>The fingerprint is built only after every planned date is DATA_SAVED
 * and the staging bytes validate. An unresolved day never produces one.
 */
public final class ProductionIntegrityGate {
    public static final long PRODUCTION_WINDOW_START_MILLIS = 1700116860000L;
    public static final long PRODUCTION_WINDOW_END_MILLIS = 1794811260000L;
    public static final int PRODUCTION_PLANNED_DATES = 1097;

    public static final String UNKNOWN_DATE = "UNKNOWN_DATE";
    public static final String FAILED_DATE = "FAILED_DATE";
    public static final String CONFIRMED_EMPTY = "CONFIRMED_EMPTY";
    public static final String MISSING_PLANNED_DATE = "MISSING_PLANNED_DATE";
    public static final String MISSING_STAGING_FILE = "MISSING_STAGING_FILE";
    public static final String HASH_MISMATCH = "HASH_MISMATCH";
    public static final String ROW_COUNT_MISMATCH = "ROW_COUNT_MISMATCH";
    public static final String DUPLICATE_TIMESTAMP = "DUPLICATE_TIMESTAMP";
    public static final String MISSING_MINUTE = "MISSING_MINUTE";
    public static final String INVALID_OHLC = "INVALID_OHLC";
    public static final String WINDOW_MISMATCH = "WINDOW_MISMATCH";
    public static final String FINGERPRINT_MISMATCH = "FINGERPRINT_MISMATCH";
    public static final String MALFORMED_LEDGER = "MALFORMED_LEDGER";
    public static final String UNEXPECTED_LEDGER_DATE = "UNEXPECTED_LEDGER_DATE";
    public static final String UNEXPECTED_STAGING_FILE = "UNEXPECTED_STAGING_FILE";
    public static final String PLANNED_DATE_COUNT = "PLANNED_DATE_COUNT";

    private static final String STATUS_DATA_SAVED = "DATA_SAVED";
    private static final String STATUS_CONFIRMED_EMPTY = "CONFIRMED_EMPTY";
    private static final String STATUS_UNKNOWN = "UNKNOWN";
    private static final String STATUS_FAILED = "FAILED";
    private static final String FINGERPRINT_HEADER = "TRADEXA_M1_DATASET_V1";

    private ProductionIntegrityGate() {
    }

    public enum Verdict {
        PASS,
        BLOCKED,
        FAIL
    }

    public static final class Diagnostic {
        private final Verdict severity;
        private final String code;
        private final String date;
        private final String message;

        Diagnostic(Verdict severity, String code, String date, String message) {
            this.severity = severity;
            this.code = code;
            this.date = date;
            this.message = message;
        }

        public Verdict severity() {
            return severity;
        }

        public String code() {
            return code;
        }

        public String date() {
            return date;
        }

        public String message() {
            return message;
        }
    }

    public static final class GateResult {
        private final Verdict verdict;
        private final String summary;
        private final List<Diagnostic> diagnostics;
        private final String fingerprintSha256;
        private final long sourceRowCount;
        private final long firstUtcMillis;
        private final long lastUtcMillis;
        private final int plannedDateCount;
        private final int dataSavedCount;

        GateResult(
                Verdict verdict,
                String summary,
                List<Diagnostic> diagnostics,
                String fingerprintSha256,
                long sourceRowCount,
                long firstUtcMillis,
                long lastUtcMillis,
                int plannedDateCount,
                int dataSavedCount) {
            this.verdict = verdict;
            this.summary = summary;
            this.diagnostics = Collections.unmodifiableList(
                new ArrayList<Diagnostic>(diagnostics)
            );
            this.fingerprintSha256 = fingerprintSha256;
            this.sourceRowCount = sourceRowCount;
            this.firstUtcMillis = firstUtcMillis;
            this.lastUtcMillis = lastUtcMillis;
            this.plannedDateCount = plannedDateCount;
            this.dataSavedCount = dataSavedCount;
        }

        public Verdict verdict() {
            return verdict;
        }

        public String summary() {
            return summary;
        }

        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }

        public String fingerprintSha256() {
            return fingerprintSha256;
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

        public int plannedDateCount() {
            return plannedDateCount;
        }

        public int dataSavedCount() {
            return dataSavedCount;
        }
    }

    public static int productionPlannedDateCount() {
        return M1DatasetReader.plannedDateCount(
            PRODUCTION_WINDOW_START_MILLIS,
            PRODUCTION_WINDOW_END_MILLIS
        );
    }

    public static GateResult evaluateProduction(Path directory) {
        return evaluate(
            directory,
            PRODUCTION_WINDOW_START_MILLIS,
            PRODUCTION_WINDOW_END_MILLIS,
            null
        );
    }

    public static GateResult evaluate(
            Path directory,
            long windowStartMillis,
            long windowEndMillis,
            String expectedFingerprintSha256) {
        DatasetInspection inspection;
        try {
            inspection = M1DatasetReader.inspect(
                directory,
                windowStartMillis,
                windowEndMillis
            );
        } catch (IOException ex) {
            return fromException(ex, null);
        } catch (IllegalArgumentException ex) {
            throw ex;
        }
        List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
        if (windowStartMillis == PRODUCTION_WINDOW_START_MILLIS
                && windowEndMillis == PRODUCTION_WINDOW_END_MILLIS
                && inspection.fatal == null
                && inspection.planned.size() != PRODUCTION_PLANNED_DATES) {
            diagnostics.add(diagnostic(
                Verdict.FAIL,
                PLANNED_DATE_COUNT,
                null,
                "Production window plans "
                    + inspection.planned.size()
                    + " UTC dates"
            ));
        }
        classify(inspection, diagnostics);
        Verdict structural = verdictOf(diagnostics);
        if (structural != Verdict.PASS) {
            return result(structural, diagnostics, inspection, null);
        }
        DatasetFingerprint fingerprint;
        try {
            fingerprint = M1DatasetReader.fingerprintIfValid(
                directory,
                windowStartMillis,
                windowEndMillis
            );
        } catch (IOException ex) {
            return fromException(ex, inspection);
        }
        diagnostics.addAll(verifyFingerprint(
            fingerprint,
            inspection,
            expectedFingerprintSha256
        ));
        if (!diagnostics.isEmpty()) {
            return result(Verdict.FAIL, diagnostics, inspection, null);
        }
        return result(Verdict.PASS, diagnostics, inspection, fingerprint);
    }

    private static void classify(
            DatasetInspection inspection,
            List<Diagnostic> diagnostics) {
        if (inspection.fatal != null) {
            diagnostics.add(diagnostic(
                Verdict.FAIL,
                fatalCode(inspection.fatal),
                null,
                inspection.fatal
            ));
            return;
        }
        for (int i = 0; i < inspection.unexpectedLedgerDates.size(); i++) {
            String date = inspection.unexpectedLedgerDates.get(i);
            diagnostics.add(diagnostic(
                Verdict.FAIL,
                UNEXPECTED_LEDGER_DATE,
                date,
                "Unexpected ledger date " + date
            ));
        }
        for (int i = 0; i < inspection.unexpectedStagingFiles.size(); i++) {
            String name = inspection.unexpectedStagingFiles.get(i);
            diagnostics.add(diagnostic(
                Verdict.FAIL,
                UNEXPECTED_STAGING_FILE,
                null,
                "Unexpected staging file " + name
            ));
        }
        for (int i = 0; i < inspection.planned.size(); i++) {
            InspectedDay day = inspection.planned.get(i);
            if (day.status == null) {
                diagnostics.add(diagnostic(
                    Verdict.FAIL,
                    MISSING_PLANNED_DATE,
                    day.date,
                    "Planned date " + day.date + " is missing from the ledger"
                ));
                continue;
            }
            if (STATUS_UNKNOWN.equals(day.status)) {
                diagnostics.add(diagnostic(
                    Verdict.BLOCKED,
                    UNKNOWN_DATE,
                    day.date,
                    day.date + " is UNKNOWN and has no saved M1 candles"
                ));
                continue;
            }
            if (STATUS_FAILED.equals(day.status)) {
                diagnostics.add(diagnostic(
                    Verdict.BLOCKED,
                    FAILED_DATE,
                    day.date,
                    day.date
                        + " is FAILED. FAILED is an unresolved download outcome, so aggregation stays blocked"
                ));
                continue;
            }
            if (STATUS_CONFIRMED_EMPTY.equals(day.status)) {
                diagnostics.add(diagnostic(
                    Verdict.BLOCKED,
                    CONFIRMED_EMPTY,
                    day.date,
                    day.date
                        + " is CONFIRMED_EMPTY. That confirmation is not UNKNOWN, and it supplies no M1 candles"
                ));
                continue;
            }
            if (!STATUS_DATA_SAVED.equals(day.status)) {
                diagnostics.add(diagnostic(
                    Verdict.FAIL,
                    MALFORMED_LEDGER,
                    day.date,
                    "Malformed ledger status " + day.status
                ));
                continue;
            }
            if (day.firstCandle != day.intervalStart
                    || day.lastCandle != day.intervalEnd) {
                diagnostics.add(diagnostic(
                    Verdict.FAIL,
                    "WINDOW_BOUNDARY",
                    day.date,
                    "Incorrect source-window boundary"
                ));
                continue;
            }
            if (!day.stagingFilePresent) {
                diagnostics.add(diagnostic(
                    Verdict.FAIL,
                    MISSING_STAGING_FILE,
                    day.date,
                    "Missing staging file"
                ));
            }
        }
    }

    private static List<Diagnostic> verifyFingerprint(
            DatasetFingerprint fingerprint,
            DatasetInspection inspection,
            String expectedFingerprintSha256) {
        List<Diagnostic> problems = new ArrayList<Diagnostic>();
        byte[] canonical = fingerprint.canonicalUtf8();
        String digest;
        try {
            digest = sha256Hex(canonical);
        } catch (IOException ex) {
            problems.add(diagnostic(
                Verdict.FAIL,
                FINGERPRINT_MISMATCH,
                null,
                ex.getMessage()
            ));
            return problems;
        }
        if (!digest.equals(fingerprint.sha256())) {
            problems.add(diagnostic(
                Verdict.FAIL,
                FINGERPRINT_MISMATCH,
                null,
                "Canonical fingerprint bytes do not match the reported digest"
            ));
        }
        if (!canonicalFormIsValid(canonical, inspection)) {
            problems.add(diagnostic(
                Verdict.FAIL,
                FINGERPRINT_MISMATCH,
                null,
                "Canonical fingerprint serialization is invalid"
            ));
        }
        if (expectedFingerprintSha256 != null
                && !expectedFingerprintSha256.equals(fingerprint.sha256())) {
            problems.add(diagnostic(
                Verdict.FAIL,
                FINGERPRINT_MISMATCH,
                null,
                "Dataset fingerprint does not match the expected digest"
            ));
        }
        return problems;
    }

    private static boolean canonicalFormIsValid(
            byte[] canonical,
            DatasetInspection inspection) {
        if (canonical.length == 0
                || canonical[0] != 'T'
                || canonical[canonical.length - 1] != '\n') {
            return false;
        }
        for (int i = 0; i < canonical.length; i++) {
            if (canonical[i] == '\r') {
                return false;
            }
        }
        String text = new String(canonical, StandardCharsets.UTF_8);
        String[] lines = text.split("\n", -1);
        if (lines.length != inspection.planned.size() + 2) {
            return false;
        }
        if (!FINGERPRINT_HEADER.equals(lines[0])
                || lines[lines.length - 1].length() != 0) {
            return false;
        }
        for (int i = 0; i < inspection.planned.size(); i++) {
            InspectedDay day = inspection.planned.get(i);
            String expected = day.date
                + " "
                + Long.toString(day.rowCount)
                + " "
                + day.sha256;
            if (!expected.equals(lines[i + 1])) {
                return false;
            }
        }
        return true;
    }

    private static GateResult fromException(
            IOException ex,
            DatasetInspection inspection) {
        String message = ex.getMessage() == null
            ? "Dataset read failed"
            : ex.getMessage();
        String date = null;
        String detail = message;
        if (message.length() > 12
                && message.charAt(10) == ':'
                && message.charAt(11) == ' '
                && message.substring(0, 10).matches("\\d{4}-\\d{2}-\\d{2}")) {
            date = message.substring(0, 10);
            detail = message.substring(12);
        }
        Verdict severity = severityFor(detail);
        List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
        diagnostics.add(diagnostic(severity, codeFor(detail), date, message));
        return result(severity, diagnostics, inspection, null);
    }

    private static Verdict severityFor(String detail) {
        if (detail.equals("Ledger status is " + STATUS_UNKNOWN)
                || detail.equals("Ledger status is " + STATUS_FAILED)
                || detail.equals("Ledger status is " + STATUS_CONFIRMED_EMPTY)) {
            return Verdict.BLOCKED;
        }
        return Verdict.FAIL;
    }

    private static String codeFor(String detail) {
        if (detail.equals("Ledger status is " + STATUS_UNKNOWN)) {
            return UNKNOWN_DATE;
        }
        if (detail.equals("Ledger status is " + STATUS_FAILED)) {
            return FAILED_DATE;
        }
        if (detail.equals("Ledger status is " + STATUS_CONFIRMED_EMPTY)) {
            return CONFIRMED_EMPTY;
        }
        if (detail.equals("SHA-256 does not match the ledger")) {
            return HASH_MISMATCH;
        }
        if (detail.equals("Row count does not match the ledger")) {
            return ROW_COUNT_MISMATCH;
        }
        if (detail.equals("Duplicate timestamp")) {
            return DUPLICATE_TIMESTAMP;
        }
        if (detail.equals("Missing M1 minute")) {
            return MISSING_MINUTE;
        }
        if (detail.equals("Invalid OHLC") || detail.equals("Invalid volume")) {
            return INVALID_OHLC;
        }
        if (detail.equals("Ledger window does not match")) {
            return WINDOW_MISMATCH;
        }
        if (detail.equals("Missing planned date")) {
            return MISSING_PLANNED_DATE;
        }
        if (detail.equals("Missing staging file")) {
            return MISSING_STAGING_FILE;
        }
        if (detail.equals("Dataset directory is incomplete")
                || detail.startsWith("Malformed")
                || detail.startsWith("Unsupported ledger")) {
            return MALFORMED_LEDGER;
        }
        return "CONTENT_INTEGRITY";
    }

    private static String fatalCode(String message) {
        if ("Ledger window does not match".equals(message)) {
            return WINDOW_MISMATCH;
        }
        if ("Dataset directory is incomplete".equals(message)) {
            return "INCOMPLETE_DATASET";
        }
        return MALFORMED_LEDGER;
    }

    private static Verdict verdictOf(List<Diagnostic> diagnostics) {
        boolean blocked = false;
        for (int i = 0; i < diagnostics.size(); i++) {
            if (diagnostics.get(i).severity() == Verdict.FAIL) {
                return Verdict.FAIL;
            }
            if (diagnostics.get(i).severity() == Verdict.BLOCKED) {
                blocked = true;
            }
        }
        return blocked ? Verdict.BLOCKED : Verdict.PASS;
    }

    private static GateResult result(
            Verdict verdict,
            List<Diagnostic> diagnostics,
            DatasetInspection inspection,
            DatasetFingerprint fingerprint) {
        int planned = inspection == null ? 0 : inspection.planned.size();
        int saved = 0;
        if (inspection != null) {
            for (int i = 0; i < inspection.planned.size(); i++) {
                if (STATUS_DATA_SAVED.equals(inspection.planned.get(i).status)) {
                    saved++;
                }
            }
        }
        String fingerprintSha = null;
        long rows = -1L;
        long first = Long.MIN_VALUE;
        long last = Long.MIN_VALUE;
        if (verdict == Verdict.PASS && fingerprint != null) {
            fingerprintSha = fingerprint.sha256();
            rows = fingerprint.sourceRowCount();
            first = fingerprint.firstUtcMillis();
            last = fingerprint.lastUtcMillis();
        }
        return new GateResult(
            verdict,
            summarize(verdict, diagnostics),
            diagnostics,
            fingerprintSha,
            rows,
            first,
            last,
            planned,
            saved
        );
    }

    private static String summarize(Verdict verdict, List<Diagnostic> diagnostics) {
        if (verdict == Verdict.PASS) {
            return "Integrity gate passed";
        }
        if (verdict == Verdict.BLOCKED) {
            return "Integrity gate blocked: "
                + diagnostics.size()
                + " date(s) are unresolved or have no M1 candles";
        }
        String detail = diagnostics.isEmpty()
            ? "integrity violation"
            : diagnostics.get(0).message();
        return "Integrity gate failed: " + detail;
    }

    private static Diagnostic diagnostic(
            Verdict severity,
            String code,
            String date,
            String message) {
        return new Diagnostic(severity, code, date, message);
    }

    private static String sha256Hex(byte[] content) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (int i = 0; i < digest.length; i++) {
                int value = digest[i] & 0xff;
                hex.append(Character.forDigit(value >>> 4, 16));
                hex.append(Character.forDigit(value & 0x0f, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("SHA-256 is not available");
        }
    }
}
