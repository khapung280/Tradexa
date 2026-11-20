package com.tradexa.dukascopy.diagnostic;

import com.dukascopy.charts.data.datacache.DataCacheException;
import com.dukascopy.charts.data.datacache.LocalCacheManager;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Offline cache-version experiment. It does not connect to Dukascopy and
 * does not read or write the production cache, ledger, scheduler, or staging.
 */
public class CacheVersionInitializationExperiment {
    static final Path EXPERIMENT_ROOT = Paths.get(
        "output",
        "EURUSD_M1_diagnostic_cache_version_test"
    );
    static final byte[] DUMMY_BYTES = "CACHE-VERSION-TEST".getBytes(StandardCharsets.US_ASCII);
    private static final Path EMPTY_CHUNK = Paths.get(
        "EURUSD", "2024", "04", "21", "BID_candles_min_1.bi5"
    );
    private static final Path DUMMY_CHUNK = Paths.get(
        "EURUSD", "2024", "04", "22", "BID_candles_min_1.bi5"
    );

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            System.out.println("FAIL: Usage: CacheVersionInitializationExperiment");
            System.exit(2);
            return;
        }
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

        Path emptyFile = experiment.resolve(EMPTY_CHUNK);
        Path dummyFile = experiment.resolve(DUMMY_CHUNK);
        Files.createDirectories(emptyFile.getParent());
        Files.createDirectories(dummyFile.getParent());
        Files.write(emptyFile, new byte[0]);
        Files.write(dummyFile, DUMMY_BYTES);
        if (Files.exists(experiment.resolve("version.txt"))
                || Files.size(emptyFile) != 0L
                || Files.size(dummyFile) != DUMMY_BYTES.length) {
            System.out.println("FAIL: Test cache was not prepared correctly.");
            System.exit(2);
            return;
        }
        String emptyHash = sha256(emptyFile);
        String dummyHash = sha256(dummyFile);
        System.out.println(
            "Prepared emptyBytes=0 emptySha256=" + emptyHash
            + " dummyBytes=" + DUMMY_BYTES.length
            + " dummySha256=" + dummyHash
        );

        String resolved = configureCache(experiment);
        if (!acceptsCache(experiment, resolved)) {
            System.out.println("FAIL: Cache directory is outside the experiment root.");
            System.exit(2);
            return;
        }
        System.out.println("Experiment cache verified.");

        try {
            new LocalCacheManager("", false);
        } catch (DataCacheException error) {
            System.out.println("FAIL: Cache initialization: " + error.getClass().getSimpleName());
            System.exit(1);
            return;
        } catch (RuntimeException error) {
            System.out.println("FAIL: Cache initialization: " + error.getClass().getSimpleName());
            System.exit(1);
            return;
        }

        int joined = awaitDeleteThread();
        boolean eurusdExists = Files.isDirectory(experiment.resolve("EURUSD"));
        boolean emptyExists = Files.exists(emptyFile);
        boolean dummyExists = Files.exists(dummyFile);
        Path versionFile = experiment.resolve("version.txt");
        String versionText = Files.isRegularFile(versionFile)
            ? new String(Files.readAllBytes(versionFile), StandardCharsets.US_ASCII)
            : "";
        List<String> names = listRelative(experiment);
        writeReport(
            experiment.resolve("cache-version-report.csv"),
            emptyHash,
            dummyHash,
            joined,
            eurusdExists,
            emptyExists,
            dummyExists,
            versionText,
            names
        );
        System.out.println(
            "joinedDeleteThreads=" + joined
            + " eurusdExists=" + eurusdExists
            + " emptyExists=" + emptyExists
            + " dummyExists=" + dummyExists
            + " versionText=" + versionText
            + " files=" + names
        );
        System.out.println("Probe report written.");
        System.exit(0);
    }

    static boolean isIsolatedExperimentPath(Path path) {
        String text = path.toAbsolutePath().normalize().toString().toLowerCase();
        return text.contains("eurusd_m1_diagnostic_cache_version_test")
            && !text.contains("eurusd_m1_3y_production")
            && !text.contains("eurusd_m1_diagnostic_zero_cache_experiment")
            && !text.contains("appdata")
            && !text.contains(".cache");
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
        try {
            Path root = experimentRoot.toAbsolutePath().normalize().toRealPath();
            Path resolvedPath = stripTrailingSeparator(resolved)
                .toAbsolutePath()
                .normalize()
                .toRealPath();
            if (!resolvedPath.startsWith(root)) {
                return false;
            }
            return isIsolatedExperimentPath(resolvedPath);
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

    private static int awaitDeleteThread() throws InterruptedException {
        int joined = 0;
        long deadline = System.currentTimeMillis() + 30000L;
        while (System.currentTimeMillis() < deadline) {
            boolean running = false;
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (thread.isAlive()
                        && thread.getName().startsWith("DelOldVersionCache")) {
                    running = true;
                    thread.join(1000L);
                    joined++;
                }
            }
            if (!running) {
                return joined;
            }
        }
        return joined;
    }

    private static List<String> listRelative(Path root) throws IOException {
        List<String> names = new ArrayList<String>();
        collect(root, root, names);
        return names;
    }

    private static void collect(Path root, Path current, List<String> names) throws IOException {
        DirectoryStream<Path> children = Files.newDirectoryStream(current);
        try {
            for (Path child : children) {
                names.add(root.relativize(child).toString());
                if (Files.isDirectory(child)) {
                    collect(root, child, names);
                }
            }
        } finally {
            children.close();
        }
    }

    private static void writeReport(
            Path report,
            String emptyHash,
            String dummyHash,
            int joined,
            boolean eurusdExists,
            boolean emptyExists,
            boolean dummyExists,
            String versionText,
            List<String> names) throws IOException {
        BufferedWriter writer = Files.newBufferedWriter(report, StandardCharsets.UTF_8);
        try {
            writer.write(
                "empty_sha256,dummy_sha256,joined_delete_threads,eurusd_exists,"
                + "empty_exists,dummy_exists,version_text,files"
            );
            writer.newLine();
            writer.write(emptyHash);
            writer.write(',');
            writer.write(dummyHash);
            writer.write(',');
            writer.write(Integer.toString(joined));
            writer.write(',');
            writer.write(Boolean.toString(eurusdExists));
            writer.write(',');
            writer.write(Boolean.toString(emptyExists));
            writer.write(',');
            writer.write(Boolean.toString(dummyExists));
            writer.write(',');
            writer.write(versionText);
            writer.write(',');
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    writer.write('|');
                }
                writer.write(names.get(i).replace(',', ';'));
            }
            writer.newLine();
        } finally {
            writer.close();
        }
    }

    private static Path stripTrailingSeparator(String value) {
        String trimmed = value;
        while (trimmed.endsWith("/") || trimmed.endsWith("\\")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return Paths.get(trimmed);
    }
}
