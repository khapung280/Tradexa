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
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One-date experiment. It copies a zero-byte history chunk into a new cache
 * and does not delete that cache. It does not read or write the production
 * ledger, scheduler, or staging files.
 */
public class ZeroByteCacheReproductionProbe {
    static final String EXPECTED_SHA256 =
        "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855";
    static final Path SOURCE_CHUNK = Paths.get(
        "C:\\Users\\Admin\\AppData\\Local\\JForex\\.cache\\EURUSD\\2024\\04\\21\\BID_candles_min_1.bi5"
    );
    static final Path EXPERIMENT_ROOT = Paths.get(
        "output",
        "EURUSD_M1_diagnostic_zero_cache_experiment"
    );
    private static final String JNLP_URL =
        "http://platform.dukascopy.com/demo_3/jforex_3.jnlp";
    private static final String START_TEXT = "2024-05-21 00:00:00";
    private static final String END_TEXT = "2024-05-21 23:59:00";

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            System.out.println("FAIL: Usage: ZeroByteCacheReproductionProbe");
            System.exit(2);
            return;
        }
        Path experiment = EXPERIMENT_ROOT.toAbsolutePath().normalize();
        if (Files.exists(experiment)) {
            System.out.println("FAIL: Experiment directory already exists.");
            System.exit(2);
            return;
        }
        if (!Files.isRegularFile(SOURCE_CHUNK)) {
            System.out.println("FAIL: Source cache file is missing.");
            System.exit(2);
            return;
        }
        Path productionCache = cacheRootOf(SOURCE_CHUNK);
        if (productionCache == null || experiment.startsWith(productionCache)) {
            System.out.println("FAIL: Experiment directory is inside the production cache.");
            System.exit(2);
            return;
        }
        if (!isExpectedZeroFile(SOURCE_CHUNK)) {
            System.out.println("FAIL: Source cache file is not the expected zero-byte file.");
            System.exit(2);
            return;
        }

        Path destination = experiment.resolve(Paths.get(
            "EURUSD", "2024", "04", "21", "BID_candles_min_1.bi5"
        ));
        Files.createDirectories(destination.getParent());
        Files.createDirectories(experiment.resolve("jftemp"));
        Files.copy(SOURCE_CHUNK, destination);
        if (!isExpectedZeroFile(SOURCE_CHUNK) || !isExpectedZeroFile(destination)) {
            System.out.println("FAIL: Copied cache file failed verification.");
            System.exit(2);
            return;
        }
        System.out.println("Zero-byte cache copy verified.");

        String resolved = configureCache(experiment);
        if (!acceptsCache(experiment, resolved, productionCache)) {
            System.out.println("FAIL: Cache directory is outside the experiment root.");
            System.exit(2);
            return;
        }
        System.out.println("Experiment cache verified.");

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
        final Path report = experiment.resolve("zerobyte-cache-report.csv");

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
            client.setSubscribedInstruments(Collections.singleton(Instrument.EURUSD));
            strategyId[0] = client.startStrategy(new OneDateStrategy(
                observation,
                destination,
                report,
                finished,
                exitCode
            ));
            if (!finished.await(30, TimeUnit.MINUTES)) {
                System.out.println("FAIL: Probe timed out.");
                exitCode.set(1);
            }
        } finally {
            shutdown(client, strategyId[0], stopped, disconnected, exitCode);
        }
    }

    static Path cacheRootOf(Path chunkFile) {
        Path directory = chunkFile;
        for (int i = 0; i < 5; i++) {
            directory = directory.getParent();
            if (directory == null) {
                return null;
            }
        }
        return directory;
    }

    static boolean isExpectedZeroFile(Path file) throws IOException {
        return Files.isRegularFile(file)
            && Files.size(file) == 0L
            && EXPECTED_SHA256.equalsIgnoreCase(sha256(file));
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

    static String configureCache(Path experiment) {
        IFilePathManager paths = FilePathManager.getInstance();
        paths.setCacheFolderPath(experiment.toString());
        paths.setCacheTempFolderPath(experiment.resolve("jftemp").toString());
        paths.setFoldersAccessible(true);
        return paths.getCacheDirectory();
    }

    static boolean acceptsCache(
            Path experimentRoot,
            String resolved,
            Path productionCacheRoot) {
        if (resolved == null || resolved.trim().isEmpty() || productionCacheRoot == null) {
            return false;
        }
        try {
            Path root = experimentRoot.toAbsolutePath().normalize().toRealPath();
            Path resolvedPath = stripTrailingSeparator(resolved)
                .toAbsolutePath()
                .normalize()
                .toRealPath();
            Path production = productionCacheRoot.toAbsolutePath().normalize().toRealPath();
            if (!resolvedPath.startsWith(root) || root.startsWith(production)) {
                return false;
            }
            if (resolvedPath.startsWith(production)) {
                return false;
            }
            return !containsBlockedName(resolvedPath);
        } catch (IOException error) {
            return false;
        }
    }

    private static boolean containsBlockedName(Path path) {
        for (int i = 0; i < path.getNameCount(); i++) {
            String name = path.getName(i).toString();
            if ("EURUSD_M1_3y_production".equals(name)
                    || "cache-getbars".equals(name)
                    || "cache-readbars".equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static Path stripTrailingSeparator(String value) {
        String trimmed = value;
        while (trimmed.endsWith("/") || trimmed.endsWith("\\")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return Paths.get(trimmed);
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
        }, "dukascopy-zerocache-disconnect");
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
        private final CountDownLatch finished;
        private final AtomicInteger exitCode;

        private OneDateStrategy(
                HttpObservation observation,
                Path chunk,
                Path report,
                CountDownLatch finished,
                AtomicInteger exitCode) {
            this.observation = observation;
            this.chunk = chunk;
            this.report = report;
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
            long cacheBytes = Files.size(chunk);
            String cacheHash = sha256(chunk);
            long sourceBytes = Files.size(SOURCE_CHUNK);
            String sourceHash = sha256(SOURCE_CHUNK);
            writeReport(
                attempts,
                callException,
                bars,
                completed,
                cacheBytes,
                cacheHash,
                sourceBytes,
                sourceHash
            );
            System.out.println(
                "date=2024-05-21"
                + " attempts=" + attempts.size()
                + " bars=" + bars
                + " completed=" + completed
                + " exception=" + callException
                + " cacheBytes=" + cacheBytes
                + " cacheSha256=" + cacheHash
                + " sourceBytes=" + sourceBytes
                + " sourceSha256=" + sourceHash
            );
            System.out.println("Probe report written.");
        }

        private void writeReport(
                List<HttpAttempt> attempts,
                String callException,
                int bars,
                boolean completed,
                long cacheBytes,
                String cacheHash,
                long sourceBytes,
                String sourceHash) throws IOException {
            BufferedWriter writer = Files.newBufferedWriter(
                report,
                StandardCharsets.UTF_8
            );
            try {
                writer.write(
                    "date,range_start_utc,range_end_utc,attempt,response_code,"
                    + "exception_class,bytes,bar_count,completed,cache_bytes,"
                    + "cache_sha256,source_bytes,source_sha256"
                );
                writer.newLine();
                if (attempts.isEmpty()) {
                    writeRow(
                        writer, "", "", callException, "", bars, completed,
                        cacheBytes, cacheHash, sourceBytes, sourceHash
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
                            Integer.toString(i + 1),
                            code,
                            exception,
                            Long.toString(attempt.bytes),
                            bars,
                            completed,
                            cacheBytes,
                            cacheHash,
                            sourceBytes,
                            sourceHash
                        );
                    }
                }
            } finally {
                writer.close();
            }
        }

        private static void writeRow(
                BufferedWriter writer,
                String attempt,
                String code,
                String exceptionClass,
                String bytes,
                int bars,
                boolean completed,
                long cacheBytes,
                String cacheHash,
                long sourceBytes,
                String sourceHash) throws IOException {
            writer.write("2024-05-21,");
            writer.write(START_TEXT);
            writer.write(',');
            writer.write(END_TEXT);
            writer.write(',');
            writer.write(attempt);
            writer.write(',');
            writer.write(code);
            writer.write(',');
            writer.write(exceptionClass);
            writer.write(',');
            writer.write(bytes);
            writer.write(',');
            writer.write(Integer.toString(bars));
            writer.write(',');
            writer.write(Boolean.toString(completed));
            writer.write(',');
            writer.write(Long.toString(cacheBytes));
            writer.write(',');
            writer.write(cacheHash);
            writer.write(',');
            writer.write(Long.toString(sourceBytes));
            writer.write(',');
            writer.write(sourceHash);
            writer.newLine();
        }

        public void onTick(Instrument instrument, ITick tick) {}

        public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) {}

        public void onMessage(IMessage message) {}

        public void onAccount(com.dukascopy.api.IAccount account) {}

        public void onStop() {}
    }
}
