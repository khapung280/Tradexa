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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 * Two-date diagnostic. It uses a new cache with version.txt already set to
 * 5 and does not create chunk files beforehand. It does not read or write
 * the production cache, ledger, scheduler, staging, or an existing quarantine.
 */
public class FinalTwoDatesProbe {
    static final String[] DATES = {
        "2023-11-18",
        "2026-11-15"
    };
    static final byte[] VERSION_BYTES = new byte[] {0x35};
    private static final String JNLP_URL =
        "http://platform.dukascopy.com/demo_3/jforex_3.jnlp";

    interface DatedRequest {
        int request(String date) throws Exception;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            System.out.println("FAIL: Usage: FinalTwoDatesProbe");
            System.exit(2);
            return;
        }
        String stamp = utcStamp();
        Path experiment = experimentDirectory(stamp).toAbsolutePath().normalize();
        if (Files.exists(experiment)) {
            System.out.println("FAIL: Experiment directory already exists.");
            System.exit(2);
            return;
        }
        if (!isIsolatedExperimentPath(experiment)) {
            System.out.println("FAIL: Experiment directory is not isolated.");
            System.exit(2);
            return;
        }
        String username = System.getenv("JFOREX_USERNAME");
        String password = System.getenv("JFOREX_PASSWORD");
        if (username == null || username.trim().isEmpty()
                || password == null || password.isEmpty()) {
            System.out.println("FAIL: Credentials not found.");
            System.exit(2);
            return;
        }

        Files.createDirectories(experiment.resolve("jftemp"));
        Path versionFile = experiment.resolve("version.txt");
        Files.write(versionFile, VERSION_BYTES);
        if (!isExactVersion(versionFile) || hasChunkFile(experiment)) {
            System.out.println("FAIL: Prepared cache failed verification.");
            System.exit(2);
            return;
        }
        String resolved = configureCache(experiment);
        if (!acceptsCache(experiment, resolved) || !isExactVersion(versionFile)) {
            System.out.println("FAIL: Cache directory is outside the experiment root.");
            System.exit(2);
            return;
        }
        System.out.println("Experiment cache verified.");
        System.out.println("Diagnostic directory: " + experiment);

        HttpObservation observation = new HttpObservation();
        ObservingURLStreamHandlerFactory.install(observation);
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final CountDownLatch stopped = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        final AtomicInteger exitCode = new AtomicInteger(1);
        final long[] strategyId = new long[] {-1L};
        final IClient client = ClientFactory.getDefaultInstance();
        final Path report = experiment.resolve("final2-report.csv");

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
            boolean connectedInTime = connected.await(90, TimeUnit.SECONDS);
            if (!connectedInTime || !client.isConnected()) {
                System.out.println("FAIL: Connection timeout.");
                exitCode.set(1);
                return;
            }
            String resolvedAfter = FilePathManager.getInstance().getCacheDirectory();
            if (!isExactVersion(versionFile) || !acceptsCache(experiment, resolvedAfter)) {
                System.out.println("FAIL: Cache path changed before getBars.");
                exitCode.set(1);
                return;
            }
            client.setSubscribedInstruments(Collections.singleton(Instrument.EURUSD));
            strategyId[0] = client.startStrategy(new TwoDateStrategy(
                observation,
                experiment,
                report,
                finished,
                exitCode
            ));
            if (!finished.await(10, TimeUnit.MINUTES)) {
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
        return Paths.get("output", "EURUSD_M1_diagnostic_final2_" + stamp);
    }

    static Path chunkRelative(String date) {
        if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new IllegalArgumentException("Bad date");
        }
        int year = Integer.parseInt(date.substring(0, 4));
        int month = Integer.parseInt(date.substring(5, 7));
        int day = Integer.parseInt(date.substring(8, 10));
        return Paths.get(
            "EURUSD",
            Integer.toString(year),
            twoDigits(month - 1),
            twoDigits(day),
            "BID_candles_min_1.bi5"
        );
    }

    static String startText(String date) {
        return date + " 00:00:00";
    }

    static String endText(String date) {
        return date + " 23:59:00";
    }

    static long requestSpanMillis(String date) {
        return parseUtc(endText(date)) - parseUtc(startText(date));
    }

    static boolean isIsolatedExperimentPath(Path path) {
        String text = path.toAbsolutePath().normalize().toString().toLowerCase();
        return text.contains("eurusd_m1_diagnostic_final2_")
            && !text.contains("appdata")
            && !text.contains("eurusd_m1_3y_production")
            && !text.contains("eurusd_m1_cache_quarantine")
            && !text.contains("eurusd_m1_diagnostic_final4_")
            && !text.contains("eurusd_m1_diagnostic_zero_cache_experiment")
            && !text.contains("eurusd_m1_diagnostic_cache_version_test")
            && !text.contains("eurusd_m1_diagnostic_zero_byte_version5_test")
            && !text.contains("cache-getbars")
            && !text.contains("cache-readbars")
            && !text.contains(".cache");
    }

    static boolean isExactVersion(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        byte[] data = Files.readAllBytes(file);
        return data.length == 1 && data[0] == VERSION_BYTES[0];
    }

    static String configureCache(Path experiment) {
        IFilePathManager paths = FilePathManager.getInstance();
        paths.setCacheFolderPath(experiment.toString());
        paths.setCacheTempFolderPath(experiment.resolve("jftemp").toString());
        paths.setFoldersAccessible(true);
        return paths.getCacheDirectory();
    }

    static boolean acceptsCache(Path experimentRoot, String resolved) {
        if (resolved == null || resolved.trim().isEmpty()) {
            return false;
        }
        if (!isIsolatedExperimentPath(Paths.get(resolved))
                || !isIsolatedExperimentPath(experimentRoot)) {
            return false;
        }
        try {
            Path root = experimentRoot.toAbsolutePath().normalize().toRealPath();
            Path resolvedPath = stripTrailingSeparator(resolved)
                .toAbsolutePath()
                .normalize()
                .toRealPath();
            return resolvedPath.startsWith(root);
        } catch (IOException error) {
            return false;
        }
    }

    static List<String> captureDates(
            HttpObservation observation,
            Path experiment,
            DatedRequest request) throws Exception {

        List<String> rows = new ArrayList<String>();
        for (int i = 0; i < DATES.length; i++) {
            rows.addAll(queryOne(observation, experiment, request, DATES[i]));
        }
        return rows;
    }

    private static List<String> queryOne(
            HttpObservation observation,
            Path experiment,
            DatedRequest request,
            String date) throws Exception {

        observation.drain();
        observation.setRecording(true);
        int bars = -1;
        String callException = "";
        String callMessage = "";
        boolean completed = false;
        try {
            bars = request.request(date);
            completed = true;
        } catch (Exception error) {
            callException = error.getClass().getSimpleName();
            callMessage = AttemptRecorder.redact(error.getMessage());
        } finally {
            observation.setRecording(false);
        }
        List<HttpAttempt> attempts = observation.drain();
        Path chunk = experiment.resolve(chunkRelative(date));
        boolean cacheExists = Files.isRegularFile(chunk);
        long cacheBytes = cacheExists ? Files.size(chunk) : -1L;
        String cacheHash = cacheExists ? sha256(chunk) : "";
        List<String> rows = new ArrayList<String>();
        if (attempts.isEmpty()) {
            rows.add(row(
                date, false, "", "", callException, callMessage, "",
                completed, bars, cacheExists, cacheBytes, cacheHash
            ));
        } else {
            for (int i = 0; i < attempts.size(); i++) {
                HttpAttempt attempt = attempts.get(i);
                String exception = attempt.exceptionClass;
                String message = attempt.exceptionMessage;
                if (exception.length() == 0 && i == attempts.size() - 1) {
                    exception = callException;
                    message = callMessage;
                }
                String code = attempt.responseCode < 0
                    ? ""
                    : Integer.toString(attempt.responseCode);
                rows.add(row(
                    date,
                    true,
                    Integer.toString(i + 1),
                    code,
                    exception,
                    message,
                    Long.toString(attempt.bytes),
                    completed,
                    bars,
                    cacheExists,
                    cacheBytes,
                    cacheHash
                ));
            }
        }
        System.out.println(
            "date=" + date
            + " http=" + attempts.size()
            + " bars=" + bars
            + " completed=" + completed
            + " cacheBytes=" + (cacheExists ? Long.toString(cacheBytes) : "absent")
        );
        return rows;
    }

    private static boolean hasChunkFile(Path root) throws IOException {
        return containsBi5(root);
    }

    private static boolean containsBi5(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            return path.getFileName() != null
                && path.getFileName().toString().endsWith(".bi5");
        }
        DirectoryStream<Path> children = Files.newDirectoryStream(path);
        try {
            for (Path child : children) {
                if (containsBi5(child)) {
                    return true;
                }
            }
        } finally {
            children.close();
        }
        return false;
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
                hex.append(String.format("%02X", Integer.valueOf(hash[i] & 0xFF)));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 unavailable");
        }
    }

    private static void writeReport(Path report, List<String> rows) throws IOException {
        BufferedWriter writer = Files.newBufferedWriter(report, StandardCharsets.UTF_8);
        try {
            writer.write(
                "date,range_start_utc,range_end_utc,http_opened,attempt,"
                + "response_code,exception_class,exception_message,bytes,"
                + "getbars_completed,bar_count,cache_exists,cache_bytes,cache_sha256"
            );
            writer.newLine();
            for (int i = 0; i < rows.size(); i++) {
                writer.write(rows.get(i));
                writer.newLine();
            }
        } finally {
            writer.close();
        }
    }

    private static String row(
            String date,
            boolean httpOpened,
            String attempt,
            String code,
            String exceptionClass,
            String exceptionMessage,
            String bytes,
            boolean completed,
            int bars,
            boolean cacheExists,
            long cacheBytes,
            String cacheHash) {
        return csv(date) + ","
            + csv(startText(date)) + ","
            + csv(endText(date)) + ","
            + csv(Boolean.toString(httpOpened)) + ","
            + csv(attempt) + ","
            + csv(code) + ","
            + csv(exceptionClass) + ","
            + csv(exceptionMessage) + ","
            + csv(bytes) + ","
            + csv(Boolean.toString(completed)) + ","
            + csv(Integer.toString(bars)) + ","
            + csv(Boolean.toString(cacheExists)) + ","
            + csv(cacheExists ? Long.toString(cacheBytes) : "") + ","
            + csv(cacheHash);
    }

    private static String csv(String value) {
        String text = value == null ? "" : value;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static String twoDigits(int value) {
        if (value < 0 || value > 99) {
            throw new IllegalArgumentException("Bad date part");
        }
        return value < 10 ? "0" + value : Integer.toString(value);
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

    private static Path stripTrailingSeparator(String value) {
        String trimmed = value;
        while (trimmed.endsWith("/") || trimmed.endsWith("\\")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return Paths.get(trimmed);
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
            System.out.println(
                "Strategy stop error: " + error.getClass().getSimpleName()
            );
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
        }, "dukascopy-final2-disconnect");
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

    private static final class TwoDateStrategy implements IStrategy {
        private final HttpObservation observation;
        private final Path experiment;
        private final Path report;
        private final CountDownLatch finished;
        private final AtomicInteger exitCode;

        private TwoDateStrategy(
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

        public void onStart(com.dukascopy.api.IContext context) throws JFException {
            try {
                final IHistory history = context.getHistory();
                observation.drain();
                List<String> rows = captureDates(
                    observation,
                    experiment,
                    new DatedRequest() {
                        public int request(String date) throws Exception {
                            List<IBar> loaded = history.getBars(
                                Instrument.EURUSD,
                                Period.ONE_MIN,
                                OfferSide.BID,
                                Filter.NO_FILTER,
                                parseUtc(startText(date)),
                                parseUtc(endText(date))
                            );
                            return loaded == null ? -1 : loaded.size();
                        }
                    }
                );
                writeReport(report, rows);
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

        public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) {}

        public void onMessage(IMessage message) {}

        public void onAccount(com.dukascopy.api.IAccount account) {}

        public void onStop() {}
    }
}
