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
 * One session and two history requests: 2023-11-28, then 2023-11-19.
 * Writes only a new EURUSD_M1_diagnostic_transport directory.
 * An empty bar list is not a market closure, and HTTP 200 with an empty
 * body is not a successful download.
 */
public class TransportDateProbe {
    static final String[] DATES = {
        "2023-11-28",
        "2023-11-19"
    };
    static final byte[] VERSION_BYTES = new byte[] {0x35};
    static final String DIRECTORY_PREFIX = "EURUSD_M1_diagnostic_transport_";
    static final String ISOLATION_FAILED = "Cache isolation failed";
    private static final String JNLP_URL =
        "http://platform.dukascopy.com/demo_3/jforex_3.jnlp";
    private static final String PRODUCTION_CACHE =
        "C:\\Users\\Admin\\AppData\\Local\\JForex\\.cache";

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

    static final class DateOutcome {
        final int bars;
        final boolean completed;
        final boolean positiveBody;
        final boolean cleanFailure;

        DateOutcome(int bars, boolean completed, boolean positiveBody) {
            this.bars = bars;
            this.completed = completed;
            this.positiveBody = positiveBody;
            this.cleanFailure = bars <= 0 && !positiveBody;
        }

        boolean full() {
            return completed && bars == 1440 && positiveBody;
        }
    }

    static final class CaptureResult {
        final List<String> rows = new ArrayList<String>();
        DateOutcome control;
        DateOutcome comparison;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            System.out.println("FAIL: Usage: TransportDateProbe");
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
        final Path report = experiment.resolve("transport-report.csv");

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
            strategyId[0] = client.startStrategy(new TransportStrategy(
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
        if (forbidden(root, root) != null) {
            return forbidden(root, root);
        }
        Path cache = canonical(strip(resolvedCache));
        Path temp = canonical(strip(resolvedTemp));
        if (!same(cache, root)) {
            return forbidden(cache, root) != null
                ? forbidden(cache, root)
                : "outside-diagnostic-root";
        }
        if (!same(temp, root.resolve("jftemp"))) {
            return forbidden(temp, root) != null
                ? forbidden(temp, root)
                : "outside-diagnostic-root";
        }
        return null;
    }

    static DateOutcome outcome(
            boolean completed,
            CandleSpan span,
            List<HttpAttempt> attempts) {
        int bars = span == null ? -1 : span.count;
        boolean positive = false;
        if (attempts != null) {
            for (int i = 0; i < attempts.size(); i++) {
                if (attempts.get(i).bytes > 0L) {
                    positive = true;
                }
            }
        }
        return new DateOutcome(bars, completed, positive);
    }

    static String classify(DateOutcome control, DateOutcome comparison) {
        if (control == null || comparison == null) {
            return "D";
        }
        if (control.full() && comparison.full()) {
            return "A";
        }
        if (control.full() && comparison.cleanFailure) {
            return "B";
        }
        if (control.cleanFailure && comparison.cleanFailure) {
            return "C";
        }
        return "D";
    }

    static CaptureResult capture(
            HttpObservation observation,
            Path experiment,
            IsolationCheck check,
            UtcClock clock,
            DatedRequest request) throws Exception {

        CaptureResult result = new CaptureResult();
        for (int i = 0; i < DATES.length; i++) {
            String date = DATES[i];
            assertPermittedDate(date);
            observation.drain();
            try {
                check.beforeRequest(date);
            } catch (IOException ex) {
                result.rows.add(resultRow(
                    date,
                    clock.now(),
                    clock.now(),
                    false,
                    "",
                    "",
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
            DateOutcome dateOutcome = outcome(completed, span, attempts);
            if (i == 0) {
                result.control = dateOutcome;
            } else {
                result.comparison = dateOutcome;
            }
            if (attempts.isEmpty()) {
                result.rows.add(resultRow(
                    date, started, ended, false, "", "", "", "",
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
                    result.rows.add(resultRow(
                        date,
                        started,
                        ended,
                        true,
                        Integer.toString(attemptIndex + 1),
                        code,
                        attempt.contentLength,
                        attempt.retryAfter,
                        exception,
                        message,
                        Long.toString(attempt.bytes),
                        completed,
                        span,
                        experiment
                    ));
                }
            }
            System.out.println(
                "date=" + date
                + " code=" + (primaryCode(attempts) < 0
                    ? ""
                    : Integer.toString(primaryCode(attempts)))
                + " bytes=" + maxBytes(attempts)
                + " content_length=" + joinHeader(attempts, true)
                + " retry_after=" + joinHeader(attempts, false)
                + " bars=" + span.count
                + " first=" + formatMinute(span.firstMillis)
                + " last=" + formatMinute(span.lastMillis)
                + " completed=" + completed
            );
        }
        return result;
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
                + "http_opened,attempt,response_code,content_length,retry_after,"
                + "exception_class,exception_message,bytes,getbars_completed,bar_count,"
                + "first_candle_utc,last_candle_utc,cache_relative,cache_exists,"
                + "cache_bytes,cache_sha256\n"
            );
            for (int i = 0; i < rows.size(); i++) {
                writer.write(rows.get(i));
                writer.write('\n');
            }
        } finally {
            writer.close();
        }
    }

    private static void assertPermittedDate(String date) {
        if (!"2023-11-28".equals(date) && !"2023-11-19".equals(date)) {
            throw new IllegalStateException("Unexpected diagnostic date");
        }
    }

    private static long maxBytes(List<HttpAttempt> attempts) {
        long max = 0L;
        if (attempts == null) {
            return max;
        }
        for (int i = 0; i < attempts.size(); i++) {
            if (attempts.get(i).bytes > max) {
                max = attempts.get(i).bytes;
            }
        }
        return max;
    }

    private static String joinHeader(List<HttpAttempt> attempts, boolean contentLength) {
        if (attempts == null || attempts.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < attempts.size(); i++) {
            if (i > 0) {
                builder.append('|');
            }
            String value = contentLength
                ? attempts.get(i).contentLength
                : attempts.get(i).retryAfter;
            builder.append(value == null ? "" : value);
        }
        return builder.toString();
    }

    private static String resultRow(
            String date,
            long started,
            long ended,
            boolean httpOpened,
            String attempt,
            String code,
            String contentLength,
            String retryAfter,
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
        return csv(date) + ","
            + csv(FinalTwoDatesProbe.startText(date)) + ","
            + csv(FinalTwoDatesProbe.endText(date)) + ","
            + csv(formatUtc(started)) + ","
            + csv(formatUtc(ended)) + ","
            + csv(Boolean.toString(httpOpened)) + ","
            + csv(attempt) + ","
            + csv(code) + ","
            + csv(contentLength) + ","
            + csv(retryAfter) + ","
            + csv(exceptionClass) + ","
            + csv(exceptionMessage) + ","
            + csv(bytes) + ","
            + csv(Boolean.toString(completed)) + ","
            + csv(Integer.toString(span.count)) + ","
            + csv(formatMinute(span.firstMillis)) + ","
            + csv(formatMinute(span.lastMillis)) + ","
            + csv(chunkRelativeText(date)) + ","
            + csv(Boolean.toString(cacheExists)) + ","
            + csv(cacheExists ? Long.toString(cacheBytes) : "") + ","
            + csv(cacheHash);
    }

    static String chunkRelativeText(String date) {
        return FinalTwoDatesProbe.chunkRelative(date).toString().replace('\\', '/');
    }

    private static String forbidden(Path path, Path allowedRoot) {
        String text = path.toAbsolutePath().normalize().toString().toLowerCase();
        if (text.contains(PRODUCTION_CACHE.toLowerCase())
                || text.contains("\\jforex\\.cache")
                || text.contains("/jforex/.cache")) {
            return "forbidden-production-cache";
        }
        String allowedName = allowedRoot.getFileName() == null
            ? ""
            : allowedRoot.getFileName().toString().toLowerCase();
        for (Path part : path.toAbsolutePath().normalize()) {
            String name = part.toString().toLowerCase();
            if (name.startsWith("eurusd_m1_diagnostic") && !name.equals(allowedName)) {
                return "forbidden-existing-diagnostic";
            }
            if (name.startsWith("eurusd_m1_cache_quarantine")) {
                return "forbidden-quarantine";
            }
            if ("eurusd_m1_3y_production".equals(name)) {
                return "forbidden-production-dataset";
            }
            if ("cache-getbars".equals(name) || "cache-readbars".equals(name)) {
                return "forbidden-existing-diagnostic";
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
        }, "dukascopy-transport-disconnect");
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

    private static final class TransportStrategy implements IStrategy {
        private final HttpObservation observation;
        private final Path experiment;
        private final Path report;
        private final CountDownLatch finished;
        private final AtomicInteger exitCode;

        private TransportStrategy(
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
                CaptureResult captured = capture(
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
                writeReport(report, captured.rows);
                System.out.println(
                    "classification=" + classify(captured.control, captured.comparison)
                );
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
}
