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
 * One-date experiment. A zero-byte chunk is placed in a cache whose
 * version.txt is already the single byte 5. The probe does not read or
 * write the production cache, ledger, scheduler, or staging files.
 */
public class ZeroByteVersion5Probe {
    static final String DATE = "2024-05-21";
    static final String START_TEXT = "2024-05-21 00:00:00";
    static final String END_TEXT = "2024-05-21 23:59:00";
    static final String EMPTY_SHA256 =
        "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855";
    static final byte[] VERSION_BYTES = new byte[] {0x35};
    static final Path EXPERIMENT_ROOT = Paths.get(
        "output",
        "EURUSD_M1_diagnostic_zero_byte_version5_test"
    );
    static final String LISTENER_STATUS =
        "not_applicable; getBars has no LoadingProgressListener";
    static final String LIMITATION =
        "One date only. The HTTP observer stores status, exception class, and byte count, not URLs. "
        + "An empty list is not classified as a closed market.";
    private static final String JNLP_URL =
        "http://platform.dukascopy.com/demo_3/jforex_3.jnlp";

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            System.out.println("FAIL: Usage: ZeroByteVersion5Probe");
            System.exit(2);
            return;
        }
        String startedAt = utcNow();
        Path experiment = EXPERIMENT_ROOT.toAbsolutePath().normalize();
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

        Path versionFile = experiment.resolve("version.txt");
        Path chunk = experiment.resolve(chunkRelative(DATE));
        Files.createDirectories(chunk.getParent());
        Files.createDirectories(experiment.resolve("jftemp"));
        Files.write(versionFile, VERSION_BYTES);
        Files.write(chunk, new byte[0]);
        if (!isExactVersion(versionFile) || !isZeroChunk(chunk)) {
            System.out.println("FAIL: Prepared cache failed verification.");
            System.exit(2);
            return;
        }
        String initialHash = sha256(chunk);
        System.out.println(
            "Prepared version=5 emptyBytes=0 emptySha256=" + initialHash
            + " chunk=" + chunkRelative(DATE)
        );

        String resolved = configureCache(experiment);
        if (!acceptsCache(experiment, resolved)
                || !isExactVersion(versionFile)
                || !isZeroChunk(chunk)) {
            System.out.println("FAIL: Cache directory is outside the experiment root.");
            writeReport(
                experiment.resolve("zero-byte-version5-report.csv"),
                startedAt,
                initialHash,
                "not_attempted",
                Collections.<HttpAttempt>emptyList(),
                "",
                -1,
                false,
                false,
                -1L,
                "",
                "cache path was not inside the experiment directory; client was not constructed"
            );
            System.exit(2);
            return;
        }
        System.out.println("Experiment cache verified.");

        HttpObservation observation = new HttpObservation();
        ObservingURLStreamHandlerFactory.install(observation);

        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final CountDownLatch stopped = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        final AtomicInteger exitCode = new AtomicInteger(1);
        final long[] strategyId = new long[] {-1L};
        final IClient client = ClientFactory.getDefaultInstance();
        final Path report = experiment.resolve("zero-byte-version5-report.csv");

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
                boolean stillZero = isZeroChunk(chunk);
                writeReport(
                    report,
                    startedAt,
                    initialHash,
                    "timeout",
                    Collections.<HttpAttempt>emptyList(),
                    "",
                    -1,
                    false,
                    stillZero,
                    sizeOrMissing(chunk),
                    hashOrBlank(chunk),
                    conclude("timeout", false, 0, stillZero, sizeOrMissing(chunk), stillZero, -1, false)
                );
                exitCode.set(1);
                return;
            }
            awaitDeleteThread();
            boolean versionStill5 = isExactVersion(versionFile);
            boolean chunkStillZero = isZeroChunk(chunk);
            String resolvedAfter = FilePathManager.getInstance().getCacheDirectory();
            if (!versionStill5 || !chunkStillZero || !acceptsCache(experiment, resolvedAfter)) {
                System.out.println(
                    "FAIL: Cache precondition changed before getBars."
                    + " versionStill5=" + versionStill5
                    + " chunkStillZero=" + chunkStillZero
                );
                writeReport(
                    report,
                    startedAt,
                    initialHash,
                    "onConnect",
                    Collections.<HttpAttempt>emptyList(),
                    "",
                    -1,
                    false,
                    chunkStillZero,
                    sizeOrMissing(chunk),
                    hashOrBlank(chunk),
                    conclude("onConnect", false, 0, chunkStillZero, sizeOrMissing(chunk), false, -1, false)
                );
                exitCode.set(1);
                return;
            }
            client.setSubscribedInstruments(Collections.singleton(Instrument.EURUSD));
            strategyId[0] = client.startStrategy(new OneDateStrategy(
                observation,
                chunk,
                report,
                startedAt,
                initialHash,
                finished,
                exitCode
            ));
            if (!finished.await(5, TimeUnit.MINUTES)) {
                System.out.println("FAIL: Probe timed out.");
                exitCode.set(1);
            }
        } finally {
            shutdown(client, strategyId[0], stopped, disconnected, exitCode);
        }
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

    static long requestSpanMillis() {
        return parseUtc(END_TEXT) - parseUtc(START_TEXT);
    }

    static boolean isIsolatedExperimentPath(Path path) {
        String text = path.toAbsolutePath().normalize().toString().toLowerCase();
        return text.contains("eurusd_m1_diagnostic_zero_byte_version5_test")
            && !text.contains("appdata")
            && !text.contains("eurusd_m1_3y_production")
            && !text.contains("eurusd_m1_diagnostic_zero_cache_experiment")
            && !text.contains("eurusd_m1_diagnostic_cache_version_test")
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

    static boolean isZeroChunk(Path file) throws IOException {
        return Files.isRegularFile(file)
            && Files.size(file) == 0L
            && EMPTY_SHA256.equalsIgnoreCase(sha256(file));
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

    static String conclude(
            String connectionStatus,
            boolean requestSent,
            int attempts,
            boolean filePresentBefore,
            long finalBytes,
            boolean hashUnchanged,
            int bars,
            boolean callCompleted) {
        if (!"onConnect".equals(connectionStatus)) {
            return "onConnect did not complete; getBars was not called";
        }
        if (!filePresentBefore || !requestSent) {
            return "zero-byte file was absent or the cache moved before getBars; request was not sent";
        }
        if (!callCompleted) {
            return "getBars did not complete";
        }
        if (attempts == 0 && hashUnchanged && finalBytes == 0L && bars == 0) {
            return "zero-byte file was reused with no observed HTTP request; empty list is not proof of a closed market";
        }
        if (attempts == 0 && hashUnchanged && finalBytes == 0L && bars > 0) {
            return "candles were returned with no observed HTTP request while the zero-byte file remained";
        }
        if (attempts > 0 && finalBytes > 0L && bars > 0) {
            return "an HTTP response replaced the zero-byte file and candles were returned";
        }
        if (attempts > 0 && finalBytes == 0L && hashUnchanged && bars == 0) {
            return "HTTP was observed, the zero-byte file remained, and getBars returned no candles";
        }
        return "observed result does not match a single expected pattern";
    }

    private static void awaitDeleteThread() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            boolean running = false;
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (thread.isAlive()
                        && thread.getName().startsWith("DelOldVersionCache")) {
                    running = true;
                    thread.join(1000L);
                }
            }
            if (!running) {
                return;
            }
        }
    }

    private static long sizeOrMissing(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return -1L;
            }
            return Files.size(file);
        } catch (IOException error) {
            return -1L;
        }
    }

    private static String hashOrBlank(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return "";
            }
            return sha256(file);
        } catch (IOException error) {
            return "";
        }
    }

    static void writeReport(
            Path report,
            String startedAt,
            String initialHash,
            String connectionStatus,
            List<HttpAttempt> attempts,
            String callException,
            int bars,
            boolean callCompleted,
            boolean filePresentBefore,
            long finalBytes,
            String finalHash,
            String conclusion) throws IOException {
        boolean hashUnchanged = EMPTY_SHA256.equalsIgnoreCase(finalHash);
        BufferedWriter writer = Files.newBufferedWriter(report, StandardCharsets.UTF_8);
        try {
            writer.write(
                "experiment_timestamp_utc,date,range_start_utc,range_end_utc,"
                + "initial_version,initial_sha256,connection_status,attempt,"
                + "response_code,exception_class,bytes,final_cache_bytes,final_sha256,"
                + "bar_count,call_completed,listener_status,conclusion,limitations"
            );
            writer.newLine();
            if (attempts.isEmpty()) {
                writeRow(
                    writer, startedAt, initialHash, connectionStatus, "", "",
                    callException, "", bars, callCompleted, finalBytes, finalHash,
                    conclusion
                );
            } else {
                for (int i = 0; i < attempts.size(); i++) {
                    HttpAttempt attempt = attempts.get(i);
                    String code = attempt.responseCode < 0
                        ? ""
                        : Integer.toString(attempt.responseCode);
                    String exception = attempt.exceptionClass;
                    if (exception.length() == 0 && i == attempts.size() - 1) {
                        exception = callException;
                    }
                    writeRow(
                        writer,
                        startedAt,
                        initialHash,
                        connectionStatus,
                        Integer.toString(i + 1),
                        code,
                        exception,
                        Long.toString(attempt.bytes),
                        bars,
                        callCompleted,
                        finalBytes,
                        finalHash,
                        conclusion
                    );
                }
            }
        } finally {
            writer.close();
        }
        System.out.println(
            "connection=" + connectionStatus
            + " filePresentBefore=" + filePresentBefore
            + " hashUnchanged=" + hashUnchanged
            + " bars=" + bars
            + " callCompleted=" + callCompleted
        );
        System.out.println("conclusion=" + conclusion);
        System.out.println("Probe report written.");
    }

    private static void writeRow(
            BufferedWriter writer,
            String startedAt,
            String initialHash,
            String connectionStatus,
            String attempt,
            String code,
            String exceptionClass,
            String bytes,
            int bars,
            boolean callCompleted,
            long finalBytes,
            String finalHash,
            String conclusion) throws IOException {
        writer.write(csv(startedAt));
        writer.write(',');
        writer.write(csv(DATE));
        writer.write(',');
        writer.write(csv(START_TEXT));
        writer.write(',');
        writer.write(csv(END_TEXT));
        writer.write(',');
        writer.write(csv("5"));
        writer.write(',');
        writer.write(csv(initialHash));
        writer.write(',');
        writer.write(csv(connectionStatus));
        writer.write(',');
        writer.write(csv(attempt));
        writer.write(',');
        writer.write(csv(code));
        writer.write(',');
        writer.write(csv(exceptionClass));
        writer.write(',');
        writer.write(csv(bytes));
        writer.write(',');
        writer.write(csv(Long.toString(finalBytes)));
        writer.write(',');
        writer.write(csv(finalHash));
        writer.write(',');
        writer.write(csv(Integer.toString(bars)));
        writer.write(',');
        writer.write(csv(Boolean.toString(callCompleted)));
        writer.write(',');
        writer.write(csv(LISTENER_STATUS));
        writer.write(',');
        writer.write(csv(conclusion));
        writer.write(',');
        writer.write(csv(LIMITATION));
        writer.newLine();
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

    private static String utcNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
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
        }, "dukascopy-version5-disconnect");
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
            System.out.println(
                "IClient.disconnect() does not stop the non-daemon ClientConnector thread. Exiting."
            );
            System.exit(exitCode.get());
        }
    }

    private static final class OneDateStrategy implements IStrategy {
        private final HttpObservation observation;
        private final Path chunk;
        private final Path report;
        private final String startedAt;
        private final String initialHash;
        private final CountDownLatch finished;
        private final AtomicInteger exitCode;

        private OneDateStrategy(
                HttpObservation observation,
                Path chunk,
                Path report,
                String startedAt,
                String initialHash,
                CountDownLatch finished,
                AtomicInteger exitCode) {
            this.observation = observation;
            this.chunk = chunk;
            this.report = report;
            this.startedAt = startedAt;
            this.initialHash = initialHash;
            this.finished = finished;
            this.exitCode = exitCode;
        }

        public void onStart(com.dukascopy.api.IContext context) throws JFException {
            try {
                run(context.getHistory());
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

        private void run(IHistory history) throws Exception {
            boolean presentBefore = isZeroChunk(chunk);
            observation.drain();
            observation.setRecording(true);
            int bars = -1;
            String callException = "";
            boolean completed = false;
            try {
                List<IBar> loaded = history.getBars(
                    Instrument.EURUSD,
                    Period.ONE_MIN,
                    OfferSide.BID,
                    Filter.NO_FILTER,
                    parseUtc(START_TEXT),
                    parseUtc(END_TEXT)
                );
                completed = true;
                bars = loaded == null ? -1 : loaded.size();
            } catch (Exception error) {
                callException = error.getClass().getSimpleName();
            } finally {
                observation.setRecording(false);
            }
            List<HttpAttempt> attempts = observation.drain();
            long finalBytes = sizeOrMissing(chunk);
            String finalHash = hashOrBlank(chunk);
            boolean hashUnchanged = EMPTY_SHA256.equalsIgnoreCase(finalHash);
            String conclusion = conclude(
                "onConnect",
                true,
                attempts.size(),
                presentBefore,
                finalBytes,
                hashUnchanged,
                bars,
                completed
            );
            writeReport(
                report,
                startedAt,
                initialHash,
                "onConnect",
                attempts,
                callException,
                bars,
                completed,
                presentBefore,
                finalBytes,
                finalHash,
                conclusion
            );
        }

        public void onTick(Instrument instrument, ITick tick) {}

        public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) {}

        public void onMessage(IMessage message) {}

        public void onAccount(com.dukascopy.api.IAccount account) {}

        public void onStop() {}
    }
}
