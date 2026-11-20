package com.tradexa.dukascopy.nativehist;

import com.tradexa.dukascopy.diagnostic.NativeTransportLog;
import com.tradexa.dukascopy.nativehist.NativeReferenceComparator.Finding;
import com.tradexa.dukascopy.nativehist.NativeReferencePlan.Day;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reference check, then sequential 100-date batches for every timeframe.
 * Verified dates and verified files are left untouched.
 */
public final class NativeDownloadEngine {
    public interface History {
        long barStart(NativeTimeframe timeframe, long timeMillis) throws IOException;

        long nextBarStart(NativeTimeframe timeframe, long barTimeMillis) throws IOException;

        List<NativeCandle> request(
                NativeTimeframe timeframe,
                long fromInclusive,
                long toInclusive) throws IOException;
    }

    public interface Minutes {
        List<NativeCandle> readRange(long startInclusive, long endInclusive) throws IOException;
    }

    public interface Guard {
        void beforeRequest() throws IOException;
    }

    public interface Pause {
        void pause(long millis) throws InterruptedException;
    }

    public interface RetryCleanup {
        void beforeRetry() throws IOException;
    }

    public static final class Request {
        public Path datasetRoot;
        public History history;
        public Minutes minutes;
        public Guard guard;
        public Pause pause;
        public RetryCleanup cleanup;
        public NativeTransportLog transport;
        public long windowStart;
        public long windowEnd;
        public int batchSize = NativeBatchPlanner.MAX_BATCH_DATES;
        public int maxAttempts = 3;
        public boolean m1IntegrityPassed;
        public List<Day> referenceDays;
        public NativeTimeframe[] timeframes = NativeTimeframe.values();
    }

    public static final class Report {
        public boolean referencePassed;
        public boolean ready;
        public String stopped = "";
        public final Map<String, Long> rows = new LinkedHashMap<String, Long>();
        public final Map<String, String> firstUtc = new LinkedHashMap<String, String>();
        public final Map<String, String> lastUtc = new LinkedHashMap<String, String>();
        public final Map<String, Integer> saved = new LinkedHashMap<String, Integer>();
        public final Map<String, Integer> unresolved = new LinkedHashMap<String, Integer>();
        public final Map<String, Integer> failed = new LinkedHashMap<String, Integer>();
        public final Map<String, String> integrity = new LinkedHashMap<String, String>();
        public long bytes;
    }

    private static final long[] RETRY_DELAYS_MS = {2000L, 5000L};

    private NativeDownloadEngine() {
    }

    public static Report run(Request request) throws IOException, InterruptedException {
        if (request.datasetRoot == null || request.history == null || request.minutes == null) {
            throw new IOException("Native download request is incomplete");
        }
        String rootText = request.datasetRoot.toAbsolutePath().normalize().toString().toLowerCase();
        if (rootText.contains("eurusd_m1_3y_production")) {
            throw new IOException("Native output must stay outside the M1 production dataset");
        }
        NativeBatchPlanner.requireBatchSize(request.batchSize);
        if (request.maxAttempts < 1 || request.maxAttempts > 3) {
            throw new IOException("Retry bound must be from 1 through 3");
        }
        if (request.referenceDays == null || request.referenceDays.isEmpty()) {
            throw new IOException("Reference dates are required");
        }
        Files.createDirectories(request.datasetRoot);
        Report report = new Report();
        Clock clock = new Clock(request.history);
        if (!referencePassed(request.datasetRoot)) {
            List<Finding> findings = reference(request, clock);
            String failure = NativeReferenceComparator.failureReason(
                findings, request.referenceDays, request.timeframes
            );
            writeReference(request.datasetRoot, findings, failure);
            report.referencePassed = failure == null;
            if (failure != null) {
                report.stopped = failure;
                writeDiagnostic(request.datasetRoot, failure);
                writeManifest(request, report);
                System.out.println("reference=FAIL " + failure);
                return report;
            }
            System.out.println("reference=PASS");
        } else {
            report.referencePassed = true;
            System.out.println("reference=PASS");
        }
        if (requestsD1(request.timeframes) && !request.m1IntegrityPassed) {
            report.stopped = "M1 integrity is not PASS";
            writeDiagnostic(request.datasetRoot, report.stopped);
            writeManifest(request, report);
            System.out.println("stopped=M1 integrity is not PASS");
            return report;
        }
        List<Frame> frames = new ArrayList<Frame>();
        for (int t = 0; t < request.timeframes.length; t++) {
            Frame frame = prepare(request, clock, request.timeframes[t]);
            if (frame.problem != null) {
                report.stopped = frame.problem;
                writeDiagnostic(request.datasetRoot, frame.problem);
                writeManifest(request, report);
                return report;
            }
            frames.add(frame);
        }
        int batches = 0;
        for (int i = 0; i < frames.size(); i++) {
            int count = NativeBatchPlanner.batchCount(frames.get(i).planned.size(), request.batchSize);
            if (count > batches) {
                batches = count;
            }
        }
        StringBuilder blockedReasons = new StringBuilder();
        for (int batch = 0; batch < batches; batch++) {
            for (int i = 0; i < frames.size(); i++) {
                Frame frame = frames.get(i);
                if (frame.blocked) {
                    continue;
                }
                String blocked = downloadBatch(request, clock, frame, batch, report);
                if (blocked != null) {
                    frame.blocked = true;
                    if (blockedReasons.length() > 0) {
                        blockedReasons.append("; ");
                    }
                    blockedReasons.append(blocked);
                }
            }
        }
        if (blockedReasons.length() > 0) {
            report.stopped = blockedReasons.toString();
            writeDiagnostic(request.datasetRoot, report.stopped);
            writeManifest(request, report);
            System.out.println("stopped=" + report.stopped);
            return report;
        }
        for (int i = 0; i < frames.size(); i++) {
            snapshot(report, frames.get(i));
        }
        boolean allPass = true;
        for (int t = 0; t < request.timeframes.length; t++) {
            NativeTimeframe timeframe = request.timeframes[t];
            String verdict = audit(request, clock, timeframe, report);
            report.integrity.put(timeframe.name(), verdict);
            if (!"PASS".equals(verdict)) {
                allPass = false;
            }
        }
        if (!allPass) {
            report.stopped = "Integrity audit failed";
            writeDiagnostic(request.datasetRoot, report.stopped);
            writeManifest(request, report);
            return report;
        }
        writeManifest(request, report);
        if (requestsD1(request.timeframes)) {
            String summary = referenceSummary(request.datasetRoot);
            String readiness = NativeReadiness.report(
                request.m1IntegrityPassed, report.integrity, summary
            );
            M1StagingSource.writeText(
                request.datasetRoot.resolve("readiness-report.txt"), readiness
            );
            if (!NativeReadiness.allows(request.m1IntegrityPassed, report.integrity, summary)) {
                report.stopped = "Readiness policy is not satisfied";
                writeDiagnostic(request.datasetRoot, report.stopped);
                System.out.println("ready=NO");
                return report;
            }
            NativeCsv.atomicWrite(
                request.datasetRoot.resolve("READY"),
                readiness.getBytes(StandardCharsets.UTF_8)
            );
        } else {
            NativeCsv.atomicWrite(
                request.datasetRoot.resolve("READY"),
                "PASS\n".getBytes(StandardCharsets.UTF_8)
            );
        }
        report.ready = true;
        System.out.println("ready=PASS");
        return report;
    }

    private static boolean requestsD1(NativeTimeframe[] timeframes) {
        for (int i = 0; i < timeframes.length; i++) {
            if (timeframes[i] == NativeTimeframe.D1) {
                return true;
            }
        }
        return false;
    }

    private static String referenceSummary(Path root) throws IOException {
        Path summary = root.resolve("reference-validation-summary.txt");
        if (!Files.isRegularFile(summary)) {
            return "";
        }
        return new String(Files.readAllBytes(summary), StandardCharsets.UTF_8);
    }

    private static Frame prepare(
            Request request,
            Clock clock,
            NativeTimeframe timeframe) throws IOException {
        Path directory = request.datasetRoot.resolve(timeframe.name());
        Files.createDirectories(directory);
        if (M1StagingSource.containsTemp(directory)) {
            return Frame.problem("Interrupted staging update " + timeframe.name());
        }
        List<Long> bars = NativeGrid.barsInWindow(
            clock, timeframe, request.windowStart, request.windowEnd
        );
        if (bars.isEmpty()) {
            return Frame.problem("Native grid is empty " + timeframe.name());
        }
        Map<String, List<Long>> groups = new LinkedHashMap<String, List<Long>>();
        for (int i = 0; i < bars.size(); i++) {
            long bar = bars.get(i).longValue();
            String date = NativeGrid.formatDate(bar);
            List<Long> group = groups.get(date);
            if (group == null) {
                group = new ArrayList<Long>();
                groups.put(date, group);
            }
            group.add(Long.valueOf(bar));
        }
        List<String> planned = new ArrayList<String>(groups.keySet());
        NativeLedger ledger = NativeLedger.open(
            directory, timeframe, request.windowStart, request.windowEnd, request.batchSize
        );
        ledger.putPlanned(
            planned,
            bars.get(0).longValue(),
            bars.get(bars.size() - 1).longValue()
        );
        return new Frame(timeframe, directory, ledger, groups, planned);
    }

    private static String downloadBatch(
            Request request,
            Clock clock,
            Frame frame,
            int batch,
            Report report) throws IOException, InterruptedException {
        int batches = NativeBatchPlanner.batchCount(frame.planned.size(), request.batchSize);
        if (batch >= batches) {
            return null;
        }
        List<String> dates = NativeBatchPlanner.batch(frame.planned, batch, request.batchSize);
        if (dates.size() > NativeBatchPlanner.MAX_BATCH_DATES) {
            return "Batch exceeded 100 dates";
        }
        int unresolved = 0;
        for (int i = 0; i < dates.size(); i++) {
            String date = dates.get(i);
            String outcome = oneDate(
                request, clock, frame.timeframe, frame.directory, frame.ledger,
                frame.groups.get(date), date
            );
            if (!"SAVED".equals(outcome)) {
                unresolved++;
            }
        }
        frame.ledger.touchCheckpoint();
        frame.ledger.write(frame.directory);
        long candles = 0L;
        long bytes = 0L;
        int saved = 0;
        for (int i = 0; i < dates.size(); i++) {
            String date = dates.get(i);
            if (frame.ledger.saved(date)) {
                saved++;
                NativeLedger.Day day = frame.ledger.days.get(date);
                candles += day.rows;
                Path file = frame.directory.resolve(date + ".csv");
                if (Files.isRegularFile(file)) {
                    bytes += Files.size(file);
                }
            }
        }
        String next = unresolved > 0
            ? "stop"
            : (batch + 1 < batches ? "continue" : "next");
        System.out.println(
            "timeframe=" + frame.timeframe.name()
            + " batch=" + (batch + 1) + "/" + batches
            + " dates=" + dates.get(0) + ".." + dates.get(dates.size() - 1)
            + " saved=" + saved
            + " unresolved=" + unresolved
            + " candles=" + candles
            + " bytes=" + bytes
            + " next=" + next
        );
        snapshot(report, frame);
        if (unresolved > 0) {
            return "Unresolved dates remain in " + frame.timeframe.name()
                + " batch " + (batch + 1);
        }
        return null;
    }

    private static void snapshot(Report report, Frame frame) {
        long rows = 0L;
        for (NativeLedger.Day day : frame.ledger.days.values()) {
            if ("DATA_SAVED".equals(day.status)) {
                rows += day.rows;
            }
        }
        report.rows.put(frame.timeframe.name(), Long.valueOf(rows));
        report.saved.put(frame.timeframe.name(), Integer.valueOf(frame.ledger.count("DATA_SAVED")));
        report.unresolved.put(frame.timeframe.name(), Integer.valueOf(frame.ledger.count("UNRESOLVED")));
        report.failed.put(frame.timeframe.name(), Integer.valueOf(frame.ledger.count("FAILED")));
        if (frame.ledger.expectedFirst >= 0L) {
            report.firstUtc.put(frame.timeframe.name(), NativeGrid.formatStamp(frame.ledger.expectedFirst));
            report.lastUtc.put(frame.timeframe.name(), NativeGrid.formatStamp(frame.ledger.expectedLast));
        }
    }

    private static final class Frame {
        private final NativeTimeframe timeframe;
        private final Path directory;
        private final NativeLedger ledger;
        private final Map<String, List<Long>> groups;
        private final List<String> planned;
        private final String problem;
        private boolean blocked;

        private Frame(
                NativeTimeframe timeframe,
                Path directory,
                NativeLedger ledger,
                Map<String, List<Long>> groups,
                List<String> planned) {
            this.timeframe = timeframe;
            this.directory = directory;
            this.ledger = ledger;
            this.groups = groups;
            this.planned = planned;
            this.problem = null;
        }

        private Frame(String problem) {
            this.timeframe = null;
            this.directory = null;
            this.ledger = null;
            this.groups = null;
            this.planned = null;
            this.problem = problem;
        }

        private static Frame problem(String problem) {
            return new Frame(problem);
        }
    }

    private static String oneDate(
            Request request,
            Clock clock,
            NativeTimeframe timeframe,
            Path directory,
            NativeLedger ledger,
            List<Long> expected,
            String date) throws IOException, InterruptedException {
        Path csv = directory.resolve(date + ".csv");
        if (ledger.saved(date)) {
            if (!Files.isRegularFile(csv)) {
                throw new IOException("Verified native CSV is missing: " + date);
            }
            byte[] existing = Files.readAllBytes(csv);
            NativeLedger.Day day = ledger.days.get(date);
            if (!day.sha256.equals(NativeCsv.sha256(existing))) {
                throw new IOException("Verified native CSV hash changed: " + date);
            }
            return "SAVED";
        }
        if (Files.exists(csv)) {
            throw new IOException("Orphan staging file: " + date);
        }
        long from = expected.get(0).longValue();
        long to = expected.get(expected.size() - 1).longValue();
        String detail = "Request failed";
        boolean invalid = false;
        List<NativeCandle> candles = null;
        int attemptsUsed = 0;
        for (int attempt = 1; attempt <= request.maxAttempts; attempt++) {
            attemptsUsed = attempt;
            if (attempt > 1 && request.cleanup != null) {
                request.cleanup.beforeRetry();
            }
            if (request.guard != null) {
                request.guard.beforeRequest();
            }
            if (request.transport != null) {
                request.transport.begin(timeframe.name(), date);
            }
            try {
                candles = request.history.request(timeframe, from, to);
                detail = inspect(clock, timeframe, expected, candles);
                invalid = detail != null && detail.startsWith("Invalid");
            } catch (IOException ex) {
                candles = null;
                detail = "Request failed";
            } finally {
                if (request.transport != null) {
                    request.transport.end(timeframe.name(), date);
                }
            }
            if (detail == null) {
                byte[] payload = NativeCsv.bytes(candles);
                NativeCsv.atomicWrite(csv, payload);
                ledger.recordSaved(date, attemptsUsed, candles, NativeCsv.sha256(payload));
                return "SAVED";
            }
            if (attempt < request.maxAttempts && request.pause != null) {
                request.pause.pause(RETRY_DELAYS_MS[attempt - 1]);
            }
        }
        ledger.recordUnresolved(date, attemptsUsed, detail, invalid);
        return invalid ? "FAILED" : "UNRESOLVED";
    }

    static String inspect(
            NativeBarClock clock,
            NativeTimeframe timeframe,
            List<Long> expected,
            List<NativeCandle> candles) {
        if (candles == null) {
            return "Request failed";
        }
        if (candles.isEmpty()) {
            return "Empty response is not a confirmed market closure";
        }
        if (candles.size() != expected.size()) {
            return "Partial native response";
        }
        for (int i = 0; i < candles.size(); i++) {
            NativeCandle candle = candles.get(i);
            if (candle.timeMillis != expected.get(i).longValue()) {
                return "Native timestamp does not match the bar grid";
            }
            if (clock.barStart(timeframe, candle.timeMillis) != candle.timeMillis) {
                return "Invalid native bar start";
            }
            if (NativeReferenceComparator.invalidOhlc(candle) != null) {
                return "Invalid candle";
            }
        }
        return null;
    }

    private static List<Finding> reference(Request request, Clock clock)
            throws IOException, InterruptedException {
        List<Finding> findings = new ArrayList<Finding>();
        for (int t = 0; t < request.timeframes.length; t++) {
            NativeTimeframe timeframe = request.timeframes[t];
            for (int d = 0; d < request.referenceDays.size(); d++) {
                Day day = request.referenceDays.get(d);
                List<Long> expected = NativeGrid.barsOnDate(
                    clock, timeframe, day.date, request.windowStart, request.windowEnd
                );
                if (expected.isEmpty()) {
                    findings.add(new Finding(
                        timeframe.name(), day.role, day.date, "",
                        NativeReferenceComparator.MISSING_NATIVE, "timestamp",
                        "No native bar starts on this UTC date"
                    ));
                    continue;
                }
                long from = expected.get(0).longValue();
                long to = expected.get(expected.size() - 1).longValue();
                long coverageEnd = clock.nextBarStart(timeframe, to) - 60000L;
                List<NativeCandle> m1 = request.minutes.readRange(from, coverageEnd);
                List<NativeCandle> nativeBars = new ArrayList<NativeCandle>();
                for (int attempt = 1; attempt <= request.maxAttempts; attempt++) {
                    if (attempt > 1 && request.cleanup != null) {
                        request.cleanup.beforeRetry();
                    }
                    if (request.guard != null) {
                        request.guard.beforeRequest();
                    }
                    if (request.transport != null) {
                        request.transport.begin(timeframe.name(), day.date);
                    }
                    try {
                        nativeBars = request.history.request(timeframe, from, to);
                    } catch (IOException ex) {
                        nativeBars = new ArrayList<NativeCandle>();
                    } finally {
                        if (request.transport != null) {
                            request.transport.end(timeframe.name(), day.date);
                        }
                    }
                    if (nativeBars != null && nativeBars.size() == expected.size()) {
                        break;
                    }
                    if (nativeBars == null) {
                        nativeBars = new ArrayList<NativeCandle>();
                    }
                    if (attempt < request.maxAttempts && request.pause != null) {
                        request.pause.pause(RETRY_DELAYS_MS[attempt - 1]);
                    }
                }
                findings.addAll(NativeReferenceComparator.compare(
                    timeframe,
                    day.role,
                    day.date,
                    clock,
                    request.windowStart,
                    request.windowEnd,
                    m1,
                    nativeBars
                ));
                System.out.println(
                    "reference timeframe=" + timeframe.name()
                    + " role=" + day.role
                    + " date=" + day.date
                    + " candles=" + nativeBars.size()
                );
            }
        }
        return findings;
    }

    private static String audit(
            Request request,
            Clock clock,
            NativeTimeframe timeframe,
            Report report) throws IOException {
        Path directory = request.datasetRoot.resolve(timeframe.name());
        NativeLedger ledger = NativeLedger.open(
            directory, timeframe, request.windowStart, request.windowEnd, request.batchSize
        );
        List<String> problems = new ArrayList<String>();
        List<Long> bars = NativeGrid.barsInWindow(
            clock, timeframe, request.windowStart, request.windowEnd
        );
        Map<String, List<Long>> groups = new LinkedHashMap<String, List<Long>>();
        for (int i = 0; i < bars.size(); i++) {
            String date = NativeGrid.formatDate(bars.get(i).longValue());
            List<Long> group = groups.get(date);
            if (group == null) {
                group = new ArrayList<Long>();
                groups.put(date, group);
            }
            group.add(bars.get(i));
        }
        List<String> calendar = NativeGrid.calendarDates(request.windowStart, request.windowEnd);
        for (int i = 0; i < calendar.size(); i++) {
            if (!groups.containsKey(calendar.get(i))) {
                problems.add("GRID_DATE_WITHOUT_BAR " + calendar.get(i));
            }
        }
        long rows = 0L;
        long previous = Long.MIN_VALUE;
        List<String> planned = new ArrayList<String>(groups.keySet());
        for (int i = 0; i < planned.size(); i++) {
            String date = planned.get(i);
            if (!ledger.saved(date)) {
                problems.add("UNRESOLVED " + date);
                continue;
            }
            Path csv = directory.resolve(date + ".csv");
            byte[] payload = Files.readAllBytes(csv);
            NativeLedger.Day day = ledger.days.get(date);
            if (!day.sha256.equals(NativeCsv.sha256(payload)) || day.rows != groups.get(date).size()) {
                problems.add("HASH_OR_COUNT " + date);
                continue;
            }
            List<NativeCandle> candles = NativeCsv.parse(payload);
            List<Long> expected = groups.get(date);
            if (candles.size() != expected.size()) {
                problems.add("ROW_COUNT " + date);
                continue;
            }
            for (int c = 0; c < candles.size(); c++) {
                NativeCandle candle = candles.get(c);
                if (candle.timeMillis != expected.get(c).longValue()
                        || candle.timeMillis <= previous
                        || NativeReferenceComparator.invalidOhlc(candle) != null) {
                    problems.add("CANDLE " + date);
                    break;
                }
                previous = candle.timeMillis;
            }
            rows += candles.size();
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory, "*.csv")) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                String date = name.substring(0, name.length() - 4);
                if (!groups.containsKey(date)) {
                    problems.add("UNEXPECTED_FILE " + name);
                }
            }
        }
        long first = bars.isEmpty() ? -1L : bars.get(0).longValue();
        long last = bars.isEmpty() ? -1L : bars.get(bars.size() - 1).longValue();
        boolean firstPartial = first >= 0L && first < request.windowStart;
        boolean lastPartial = false;
        if (last >= 0L) {
            long next = clock.nextBarStart(timeframe, last);
            lastPartial = next - 60000L > request.windowEnd;
        }
        String verdict = problems.isEmpty() ? "PASS" : "FAIL";
        StringBuilder text = new StringBuilder();
        text.append("timeframe=").append(timeframe.name()).append('\n');
        text.append("verdict=").append(verdict).append('\n');
        text.append("planned=").append(planned.size()).append('\n');
        text.append("data_saved=").append(ledger.count("DATA_SAVED")).append('\n');
        text.append("unresolved=").append(ledger.count("UNRESOLVED")).append('\n');
        text.append("failed=").append(ledger.count("FAILED")).append('\n');
        text.append("rows=").append(rows).append('\n');
        text.append("first_utc=").append(first < 0L ? "" : NativeGrid.formatStamp(first)).append('\n');
        text.append("last_utc=").append(last < 0L ? "" : NativeGrid.formatStamp(last)).append('\n');
        text.append("first_partial=").append(firstPartial).append('\n');
        text.append("last_partial=").append(lastPartial).append('\n');
        text.append("first_partial_reason=");
        text.append(firstPartial
            ? "Included because getBarStart of the window start is this candle. It is not trimmed."
            : "The first native bar starts at or after the window start and contains that instant.");
        text.append('\n');
        text.append("last_partial_reason=");
        text.append(lastPartial
            ? "Included because getBarStart of the window end is this candle. Minutes after the window remain inside it and are not trimmed."
            : "The last native bar contains the window end and does not extend past it.");
        text.append('\n');
        text.append("problems=").append(problems.size()).append('\n');
        for (int i = 0; i < problems.size() && i < 50; i++) {
            text.append(problems.get(i)).append('\n');
        }
        M1StagingSource.writeText(directory.resolve("integrity-report.txt"), text.toString());
        report.rows.put(timeframe.name(), Long.valueOf(rows));
        report.saved.put(timeframe.name(), Integer.valueOf(ledger.count("DATA_SAVED")));
        report.unresolved.put(timeframe.name(), Integer.valueOf(ledger.count("UNRESOLVED")));
        report.failed.put(timeframe.name(), Integer.valueOf(ledger.count("FAILED")));
        if (first >= 0L) {
            report.firstUtc.put(timeframe.name(), NativeGrid.formatStamp(first));
            report.lastUtc.put(timeframe.name(), NativeGrid.formatStamp(last));
        }
        return verdict;
    }

    private static boolean referencePassed(Path root) throws IOException {
        Path summary = root.resolve("reference-validation-summary.txt");
        if (!Files.isRegularFile(summary)) {
            return false;
        }
        String text = new String(Files.readAllBytes(summary), StandardCharsets.UTF_8);
        return text.startsWith("verdict=PASS\n");
    }

    private static void writeReference(Path root, List<Finding> findings, String failure)
            throws IOException {
        StringBuilder csv = new StringBuilder();
        csv.append("timeframe,role,date,native_time_utc,classification,field,detail\n");
        Map<String, String> deltas = new LinkedHashMap<String, String>();
        for (int i = 0; i < findings.size(); i++) {
            Finding finding = findings.get(i);
            csv.append(quote(finding.timeframe)).append(',');
            csv.append(quote(finding.role)).append(',');
            csv.append(quote(finding.date)).append(',');
            csv.append(quote(finding.nativeTimeUtc)).append(',');
            csv.append(quote(finding.classification)).append(',');
            csv.append(quote(finding.field)).append(',');
            csv.append(quote(finding.detail)).append('\n');
            int marker = finding.detail.indexOf("utc_floor_delta_millis=");
            if (marker >= 0) {
                String delta = finding.detail.substring(marker + "utc_floor_delta_millis=".length());
                String existing = deltas.get(finding.timeframe);
                if (existing == null) {
                    deltas.put(finding.timeframe, delta);
                } else if (!existing.contains(delta)) {
                    deltas.put(finding.timeframe, existing + "|" + delta);
                }
            }
        }
        M1StagingSource.writeText(root.resolve("reference-validation-report.csv"), csv.toString());
        StringBuilder summary = new StringBuilder();
        summary.append("verdict=").append(failure == null ? "PASS" : "FAIL").append('\n');
        summary.append("findings=").append(findings.size()).append('\n');
        if (failure != null) {
            summary.append("reason=").append(failure).append('\n');
        }
        for (Map.Entry<String, String> entry : deltas.entrySet()) {
            summary.append("utc_floor_delta_millis.").append(entry.getKey())
                .append('=').append(entry.getValue()).append('\n');
        }
        summary.append("alignment=Native intervals come from the bar clock. ");
        summary.append("A non-zero UTC-floor delta is recorded and is not itself a pass.\n");
        summary.append(NativeReferenceComparator.d1Summary(findings));
        M1StagingSource.writeText(root.resolve("reference-validation-summary.txt"), summary.toString());
    }

    private static void writeManifest(Request request, Report report) throws IOException {
        long bytes = 0L;
        for (int i = 0; i < request.timeframes.length; i++) {
            Path directory = request.datasetRoot.resolve(request.timeframes[i].name());
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.csv")) {
                for (Path file : files) {
                    bytes += Files.size(file);
                }
            }
        }
        report.bytes = bytes;
        StringBuilder csv = new StringBuilder();
        csv.append("timeframe,saved,unresolved,failed,rows,first_utc,last_utc,integrity\n");
        for (int i = 0; i < request.timeframes.length; i++) {
            String name = request.timeframes[i].name();
            csv.append(name).append(',');
            csv.append(value(report.saved.get(name))).append(',');
            csv.append(value(report.unresolved.get(name))).append(',');
            csv.append(value(report.failed.get(name))).append(',');
            csv.append(value(report.rows.get(name))).append(',');
            csv.append(text(report.firstUtc.get(name))).append(',');
            csv.append(text(report.lastUtc.get(name))).append(',');
            csv.append(text(report.integrity.get(name))).append('\n');
        }
        M1StagingSource.writeText(request.datasetRoot.resolve("download-manifest.csv"), csv.toString());
    }

    private static void writeDiagnostic(Path root, String reason) throws IOException {
        M1StagingSource.writeText(
            root.resolve("diagnostic-report.txt"),
            "stopped=" + reason + "\n"
        );
    }

    private static String quote(String value) {
        String text = value == null ? "" : value;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static String value(Object number) {
        return number == null ? "0" : number.toString();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static final class Clock implements NativeBarClock {
        private final History history;

        private Clock(History history) {
            this.history = history;
        }

        public long barStart(NativeTimeframe timeframe, long timeMillis) {
            try {
                return history.barStart(timeframe, timeMillis);
            } catch (IOException ex) {
                throw new IllegalStateException("Native bar clock failed");
            }
        }

        public long nextBarStart(NativeTimeframe timeframe, long barTimeMillis) {
            try {
                return history.nextBarStart(timeframe, barTimeMillis);
            } catch (IOException ex) {
                throw new IllegalStateException("Native bar clock failed");
            }
        }
    }
}
