package com.tradexa.dukascopy.diagnostic;

import com.dukascopy.api.Filter;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IHistory;
import com.dukascopy.api.IMessage;
import com.dukascopy.api.IStrategy;
import com.dukascopy.api.ITick;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.JFException;
import com.dukascopy.api.OfferSide;
import com.dukascopy.api.Period;
import com.dukascopy.api.system.ClientFactory;
import com.dukascopy.api.system.IClient;
import com.dukascopy.api.system.ISystemListener;
import com.dukascopy.dds2.greed.util.FilePathManager;
import com.dukascopy.dds2.greed.util.IFilePathManager;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One session, two history requests: 2023-11-19 then 2023-11-18.
 * Writes only a new EURUSD_M1_diagnostic_control directory.
 * An empty bar list is not a market closure.
 */
public class ControlDateProbe {
    static final String[] DATES = {
        "2023-11-19",
        "2023-11-18"
    };
    static final byte[] VERSION_BYTES = new byte[] {0x35};
    static final String DIRECTORY_PREFIX = "EURUSD_M1_diagnostic_control_";
    static final String ISOLATION_FAILED = "Cache isolation failed";
    private static final String JNLP_URL =
        "http://platform.dukascopy.com/demo_3/jforex_3.jnlp";
    private static final String PRODUCTION_CACHE =
        "C:\\Users\\Admin\\AppData\\Local\\JForex\\.cache";
    private static final String[] FORBIDDEN_SEGMENTS = {
        "eurusd_m1_3y_production",
        "eurusd_m1_cache_quarantine_20261118t122750z",
        "eurusd_m1_cache_quarantine_20261118t173558z",
        "eurusd_m1_diagnostic_recovery_20261119t043032z",
        "eurusd_m1_diagnostic_recovery_20261119t043338z",
        "eurusd_m1_diagnostic",
        "cache-getbars",
        "cache-readbars"
    };

    interface DatedRequest {
        CandleSpan request(String date) throws Exception;
    }

    interface IsolationCheck {
        void beforeRequest(String date) throws IOException;
    }

    interface UtcClock {
        long now();
    }

    static final class CandleSpan {
        final int count;
        final long firstMillis;
        final long lastMillis;

        CandleSpan(int count, long firstMillis, long lastMillis) {
            this.count = count;
            this.firstMillis = firstMillis;
            this.lastMillis = lastMillis;
        }

        static CandleSpan fromBars(List<IBar> bars) {
            if (bars == null) {
                return new CandleSpan(-1, -1L, -1L);
            }
            if (bars.isEmpty()) {
                return new CandleSpan(0, -1L, -1L);
            }
            return new CandleSpan(
                bars.size(),
                bars.get(0).getTime(),
                bars.get(bars.size() - 1).getTime()
            );
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            System.out.println("FAIL: Usage: ControlDateProbe");
            System.exit(2);
            return;
        }
        String stamp = utcStamp();
        Path experiment = experimentDirectory(stamp).toAbsolutePath().normalize();
        try {
            create(experiment.getParent(), stamp);
            configure(experiment);
        } catch (IOException ex) {
            System.out.println("FAIL: " + ISOLATION_FAILED);
            System.exit(2);
            return;
        }
        System.out.println("Diagnostic directory: " + experiment);
        System.out.println("Cache isolation verified.");

        String username = System.getenv("JFOREX_USERNAME");
        String password = System.getenv("JFOREX_PASSWORD");
        if (username == null || username.trim().isEmpty()
                || password == null || password.isEmpty()) {
            System.out.println("FAIL: Credentials not found.");
            System.exit(2);
            return;
        }

        HttpObservation observation = new HttpObservation();
        ObservingURLStreamHandlerFactory.install(observation);
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final CountDownLatch stopped = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        final AtomicInteger exitCode = new AtomicInteger(1);
        final long[] strategyId = new long[] {-1L};
        final IClient client = ClientFactory.getDefaultInstance();
        final Path report = experiment.resolve("control-report.csv");

        client.setSystemListener(new ISystemListener() {
            public void onStart(long processId) {}

            public void onStop(long processId) {
                stopped.countDown();
            }

            public void onConnect() {
                System.out.println("Connected to Dukascopy!");
                connected.countDown();
            }

            public void onDisconnect() {
                System.out.println("Disconnected.");
                disconnected.countDown();
            }
        });

        try {
            System.out.println("Connecting to Dukascopy Demo...");
            client.connect(JNLP_URL, username, password);
            if (!connected.await(90, TimeUnit.SECONDS) || !client.isConnected()) {
                System.out.println("FAIL: Connection timeout.");
                exitCode.set(1);
                return;
            }
            try {
                revalidate(experiment);
            } catch (IOException ex) {
                System.out.println("FAIL: " + ISOLATION_FAILED);
                exitCode.set(1);
                return;
            }
            client.setSubscribedInstruments(Collections.singleton(Instrument.EURUSD));
            strategyId[0] = client.startStrategy(new ControlStrategy(
                observation,
                experiment,
                report,
                finished,
                exitCode
            ));
            if (!finished.await(8, TimeUnit.MINUTES)) {
                System.out.println("FAIL: Probe timed out.");
                exitCode.set(1);
            }
        } finally {
            shutdown(client, strategyId[0], stopped, disconnected, exitCode);
        }
    }

    static Path experimentDirectory(String stamp) {
        if (stamp == null || !stamp.matches("\\d{8}T\\d{6}Z")) {
            throw new IllegalArgumentException("Bad diagnostic timestamp");
        }
        return Paths.get("output", DIRECTORY_PREFIX + stamp);
    }

    static Path create(Path outputDirectory, String stamp) throws IOException {
        if (stamp == null || !stamp.matches("\\d{8}T\\d{6}Z")) {
            throw new IOException(ISOLATION_FAILED);
        }
        Path cacheRoot = experimentDirectory(stamp);
        if (outputDirectory != null) {
            cacheRoot = outputDirectory.resolve(DIRECTORY_PREFIX + stamp)
                .toAbsolutePath()
                .normalize();
        }
        String problem = isolationProblem(
            cacheRoot,
            cacheRoot.toString(),
            cacheRoot.resolve("jftemp").toString()
        );
        if (problem != null || Files.exists(cacheRoot)) {
            throw new IOException(ISOLATION_FAILED);
        }
        Files.createDirectories(cacheRoot.resolve("jftemp"));
        Path version = cacheRoot.resolve("version.txt");
        Files.write(
            version,
            VERSION_BYTES,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        );
        byte[] stored = Files.readAllBytes(version);
        if (stored.length != 1 || stored[0] != VERSION_BYTES[0]) {
            throw new IOException(ISOLATION_FAILED);
        }
        return cacheRoot;
    }

    static void configure(Path cacheRoot) throws IOException {
        IFilePathManager paths = FilePathManager.getInstance();
        paths.setCacheFolderPath(cacheRoot.toAbsolutePath().normalize().toString());
        paths.setCacheTempFolderPath(
            cacheRoot.resolve("jftemp").toAbsolutePath().normalize().toString()
        );
        paths.setFoldersAccessible(true);
        revalidate(cacheRoot);
    }

    static void revalidate(Path cacheRoot) throws IOException {
        IFilePathManager paths = FilePathManager.getInstance();
        String problem = isolationProblem(
            cacheRoot,
            paths.getCacheDirectory(),
            paths.getCacheTempDirectory()
        );
        Path version = cacheRoot.resolve("version.txt");
        if (!Files.isRegularFile(version)) {
            problem = "version";
        } else {
            byte[] stored = Files.readAllBytes(version);
            if (stored.length != 1 || stored[0] != VERSION_BYTES[0]) {
                problem = "version";
            }
        }
        if (problem != null) {
            throw new IOException(ISOLATION_FAILED);
        }
    }

    static String isolationProblem(
            Path cacheRoot,
            String resolvedCache,
            String resolvedTemp) {
        if (cacheRoot == null || resolvedCache == null || resolvedTemp == null) {
            return "missing-path";
        }
        Path root = canonical(cacheRoot);
        String name = root.getFileName() == null ? "" : root.getFileName().toString();
        if (!name.matches(DIRECTORY_PREFIX + "\\d{8}T\\d{6}Z")) {
            return "bad-diagnostic-directory";
        }
        if (forbidden(root) != null) {
            return forbidden(root);
        }
        Path cache = canonical(strip(resolvedCache));
        Path temp = canonical(strip(resolvedTemp));
        if (!same(cache, root)) {
            return forbidden(cache) != null ? forbidden(cache) : "outside-diagnostic-root";
        }
        if (!same(temp, root.resolve("jftemp"))) {
            return forbidden(temp) != null ? forbidden(temp) : "outside-diagnostic-root";
        }
        return null;
    }

    static String classify(
            int controlCode,
            int controlBars,
            int targetCode,
            int targetBars) {
        boolean controlOk = controlCode == 200 && controlBars == 1440;
        boolean targetOk = targetCode == 200 && targetBars == 1440;
        if (targetOk) {
            return "C";
        }
        if (controlOk && targetCode == 503 && targetBars == 0) {
            return "A";
        }
        if (!controlOk && !(targetCode == 200 && targetBars == 1440)) {
            return "B";
        }
        return "D";
    }

    static List<String> capture(
            HttpObservation observation,
            Path experiment,
            IsolationCheck check,
            UtcClock clock,
            DatedRequest request) throws Exception {

        List<String> rows = new ArrayList<String>();
        for (int i = 0; i < DATES.length; i++) {
            String date = DATES[i];
            observation.drain();
            try {
                check.beforeRequest(date);
            } catch (IOException ex) {
                rows.add(resultRow(
                    date,
                    clock.now(),
                    clock.now(),
                    false,
                    "",
                    "",
                    "IOException",
                    ISOLATION_FAILED,
                    "",
                    false,
                    new CandleSpan(0, -1L, -1L),
                    experiment
                ));
                break;
            }
            long started = clock.now();
            observation.setRecording(true);
            CandleSpan span = new CandleSpan(0, -1L, -1L);
            String callException = "";
            String callMessage = "";
            boolean completed = false;
            try {
                span = request.request(date);
                if (span == null) {
                    span = new CandleSpan(-1, -1L, -1L);
                }
                completed = true;
            } catch (Exception error) {
                callException = error.getClass().getSimpleName();
                callMessage = AttemptRecorder.redact(error.getMessage());
            } finally {
                observation.setRecording(false);
            }
            long ended = clock.now();
            List<HttpAttempt> attempts = observation.drain();
            if (attempts.isEmpty()) {
                rows.add(resultRow(
                    date, started, ended, false, "", "",
                    callException, callMessage, "", completed, span, experiment
                ));
            } else {
                for (int attemptIndex = 0; attemptIndex < attempts.size(); attemptIndex++) {
                    HttpAttempt attempt = attempts.get(attemptIndex);
                    String exception = attempt.exceptionClass;
                    String message = attempt.exceptionMessage;
                    if (exception.length() == 0 && attemptIndex == attempts.size() - 1) {
                        exception = callException;
                        message = callMessage;
                    }
                    String code = attempt.responseCode < 0
                        ? ""
                        : Integer.toString(attempt.responseCode);
                    rows.add(resultRow(
                        date,
                        started,
                        ended,
                        true,
                        Integer.toString(attemptIndex + 1),
                        code,
                        exception,
                        message,
                        Long.toString(attempt.bytes),
                        completed,
                        span,
                        experiment
                    ));
                }
            }
            int code = primaryCode(attempts);
            System.out.println(
                "date=" + date
                + " code=" + (code < 0 ? "" : Integer.toString(code))
                + " bars=" + span.count
                + " first=" + formatMinute(span.firstMillis)
                + " last=" + formatMinute(span.lastMillis)
                + " completed=" + completed
            );
        }
        return rows;
    }

    static int primaryCode(List<HttpAttempt> attempts) {
        if (attempts == null || attempts.isEmpty()) {
            return -1;
        }
        return attempts.get(attempts.size() - 1).responseCode;
    }

    static void writeReport(Path report, List<String> rows) throws IOException {
        BufferedWriter writer = Files.newBufferedWriter(report, StandardCharsets.UTF_8);
        try {
            writer.write(
                "date,range_start_utc,range_end_utc,request_start_utc,request_end_utc,"
                + "http_opened,attempt,response_code,exception_class,exception_message,"
                + "bytes,getbars_completed,bar_count,first_candle_utc,last_candle_utc,"
                + "cache_relative,cache_exists,cache_bytes,cache_sha256\n"
            );
            for (int i = 0; i < rows.size(); i++) {
                writer.write(rows.get(i));
                writer.write('\n');
            }
        } finally {
            writer.close();
        }
    }

    private static String resultRow(
            String date,
            long started,
            long ended,
            boolean httpOpened,
            String attempt,
            String code,
            String exceptionClass,
            String exceptionMessage,
            String bytes,
            boolean completed,
            CandleSpan span,
            Path experiment) throws IOException {
        Path chunk = experiment.resolve(FinalTwoDatesProbe.chunkRelative(date));
        boolean cacheExists = Files.isRegularFile(chunk);
        long cacheBytes = cacheExists ? Files.size(chunk) : -1L;
        String cacheHash = cacheExists ? sha256(chunk) : "";
        String relative = chunkRelativeText(date);
        return csv(date) + ","
            + csv(FinalTwoDatesProbe.startText(date)) + ","
            + csv(FinalTwoDatesProbe.endText(date)) + ","
            + csv(formatUtc(started)) + ","
            + csv(formatUtc(ended)) + ","
            + csv(Boolean.toString(httpOpened)) + ","
            + csv(attempt) + ","
            + csv(code) + ","
            + csv(exceptionClass) + ","
            + csv(exceptionMessage) + ","
            + csv(bytes) + ","
            + csv(Boolean.toString(completed)) + ","
            + csv(Integer.toString(span.count)) + ","
            + csv(formatMinute(span.firstMillis)) + ","
            + csv(formatMinute(span.lastMillis)) + ","
            + csv(relative) + ","
            + csv(Boolean.toString(cacheExists)) + ","
            + csv(cacheExists ? Long.toString(cacheBytes) : "") + ","
            + csv(cacheHash);
    }

    static String chunkRelativeText(String date) {
        return FinalTwoDatesProbe.chunkRelative(date).toString().replace('\\', '/');
    }

    private static String forbidden(Path path) {
        String text = path.toAbsolutePath().normalize().toString().toLowerCase();
        if (text.contains(PRODUCTION_CACHE.toLowerCase())
                || text.contains("\\jforex\\.cache")
                || text.contains("/jforex/.cache")) {
            return "forbidden-production-cache";
        }
        for (Path part : path.toAbsolutePath().normalize()) {
            String name = part.toString().toLowerCase();
            if (name.startsWith("eurusd_m1_diagnostic_recovery_")) {
                return "forbidden-recovery-cache";
            }
            for (int i = 0; i < FORBIDDEN_SEGMENTS.length; i++) {
                if (name.equals(FORBIDDEN_SEGMENTS[i])) {
                    if ("eurusd_m1_diagnostic".equals(name)) {
                        return "forbidden-existing-diagnostic";
                    }
                    if (name.startsWith("eurusd_m1_cache_quarantine")) {
                        return "forbidden-quarantine";
                    }
                    if ("eurusd_m1_3y_production".equals(name)) {
                        return "forbidden-production-dataset";
                    }
                    return "forbidden-existing-diagnostic";
                }
            }
        }
        return null;
    }

    private static Path strip(String value) {
        String trimmed = value;
        while (trimmed.endsWith("/") || trimmed.endsWith("\\")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return Paths.get(trimmed);
    }

    private static boolean same(Path left, Path right) {
        return left.toAbsolutePath().normalize().toString().equalsIgnoreCase(
            right.toAbsolutePath().normalize().toString()
        );
    }

    private static Path canonical(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            if (Files.exists(absolute)) {
                return absolute.toRealPath();
            }
        } catch (IOException ex) {
            return absolute;
        }
        return absolute;
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            InputStream input = Files.newInputStream(file);
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            } finally {
                input.close();
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (int i = 0; i < hash.length; i++) {
                hex.append(String.format("%02x", Integer.valueOf(hash[i] & 0xFF)));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 unavailable");
        }
    }

    private static String csv(String value) {
        String text = value == null ? "" : value;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static String formatUtc(long millis) {
        if (millis < 0L) {
            return "";
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }

    private static String formatMinute(long millis) {
        if (millis < 0L) {
            return "";
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }

    private static String utcStamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static long parseUtc(String text) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            return format.parse(text).getTime();
        } catch (ParseException error) {
            throw new IllegalStateException("Bad experiment timestamp");
        }
    }

    private static void shutdown(
            IClient client,
            long strategyId,
            CountDownLatch stopped,
            CountDownLatch disconnected,
            AtomicInteger exitCode) {
        try {
            if (strategyId >= 0
                    && client.getStartedStrategies().containsKey(Long.valueOf(strategyId))) {
                client.stopStrategy(strategyId);
            }
        } catch (Exception error) {
            System.out.println("Strategy stop error: " + error.getClass().getSimpleName());
        }
        if (strategyId >= 0) {
            try {
                if (!stopped.await(30, TimeUnit.SECONDS)) {
                    System.out.println("FAIL: Strategy stop timeout.");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        Thread closer = new Thread(new Runnable() {
            public void run() {
                try {
                    client.disconnect();
                } catch (Throwable error) {
                    System.out.println(
                        "Disconnect error: " + error.getClass().getSimpleName()
                    );
                }
            }
        }, "dukascopy-control-disconnect");
        closer.setDaemon(true);
        closer.start();
        try {
            disconnected.await(15, TimeUnit.SECONDS);
            closer.join(5000);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        List<String> blockers = new ArrayList<String>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive()
                    && !thread.isDaemon()
                    && thread != Thread.currentThread()) {
                blockers.add(thread.getName());
            }
        }
        if (!blockers.isEmpty()) {
            System.out.println(
                "JForex left non-daemon threads after disconnect: " + blockers
            );
            System.exit(exitCode.get());
        }
    }

    private static final class ControlStrategy implements IStrategy {
        private final HttpObservation observation;
        private final Path experiment;
        private final Path report;
        private final CountDownLatch finished;
        private final AtomicInteger exitCode;

        private ControlStrategy(
                HttpObservation observation,
                Path experiment,
                Path report,
                CountDownLatch finished,
                AtomicInteger exitCode) {
            this.observation = observation;
            this.experiment = experiment;
            this.report = report;
            this.finished = finished;
            this.exitCode = exitCode;
        }

        public void onStart(com.dukascopy.api.IContext context) {
            try {
                final IHistory history = context.getHistory();
                final boolean[] sawTarget = new boolean[] {false};
                final List<Integer> codes = new ArrayList<Integer>();
                final List<Integer> bars = new ArrayList<Integer>();
                List<String> rows = capture(
                    observation,
                    experiment,
                    new IsolationCheck() {
                        public void beforeRequest(String date) throws IOException {
                            revalidate(experiment);
                        }
                    },
                    new UtcClock() {
                        public long now() {
                            return System.currentTimeMillis();
                        }
                    },
                    new DatedRequest() {
                        public CandleSpan request(String date) throws JFException {
                            List<IBar> loaded = history.getBars(
                                Instrument.EURUSD,
                                Period.ONE_MIN,
                                OfferSide.BID,
                                Filter.NO_FILTER,
                                parseUtc(FinalTwoDatesProbe.startText(date)),
                                parseUtc(FinalTwoDatesProbe.endText(date))
                            );
                            return CandleSpan.fromBars(loaded);
                        }
                    }
                );
                writeReport(report, rows);
                for (int i = 0; i < DATES.length; i++) {
                    codes.add(Integer.valueOf(-1));
                    bars.add(Integer.valueOf(0));
                }
                for (int i = 0; i < rows.size(); i++) {
                    String row = rows.get(i);
                    for (int dateIndex = 0; dateIndex < DATES.length; dateIndex++) {
                        if (row.startsWith("\"" + DATES[dateIndex] + "\"")) {
                            codes.set(dateIndex, Integer.valueOf(codeFromRow(row)));
                            bars.set(dateIndex, Integer.valueOf(barsFromRow(row)));
                            if (dateIndex == 1) {
                                sawTarget[0] = true;
                            }
                        }
                    }
                }
                String letter = sawTarget[0]
                    ? classify(
                        codes.get(0).intValue(),
                        bars.get(0).intValue(),
                        codes.get(1).intValue(),
                        bars.get(1).intValue()
                    )
                    : "D";
                System.out.println("classification=" + letter);
                System.out.println("Probe report written.");
                exitCode.set(0);
            } catch (Exception error) {
                System.out.println(
                    "FAIL: Probe error: " + error.getClass().getSimpleName()
                );
                exitCode.set(1);
            } finally {
                finished.countDown();
            }
        }

        public void onTick(Instrument instrument, ITick tick) {}

        public void onBar(
                Instrument instrument,
                Period period,
                IBar askBar,
                IBar bidBar) {}

        public void onMessage(IMessage message) {}

        public void onAccount(com.dukascopy.api.IAccount account) {}

        public void onStop() {}
    }

    private static int codeFromRow(String row) {
        String[] fields = splitCsv(row);
        if (fields.length < 8 || fields[7].length() == 0) {
            return -1;
        }
        return Integer.parseInt(fields[7]);
    }

    private static int barsFromRow(String row) {
        String[] fields = splitCsv(row);
        if (fields.length < 13 || fields[12].length() == 0) {
            return 0;
        }
        return Integer.parseInt(fields[12]);
    }

    private static String[] splitCsv(String row) {
        List<String> fields = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < row.length(); i++) {
            char ch = row.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[fields.size()]);
    }
}
