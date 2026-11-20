package com.tradexa.dukascopy;

import com.dukascopy.dds2.greed.util.FilePathManager;
import com.dukascopy.dds2.greed.util.IFilePathManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Fresh JForex cache for one explicit recovery run.
 * FilePathManager methods used here are setCacheFolderPath,
 * setCacheTempFolderPath, setFoldersAccessible, getCacheDirectory,
 * and getCacheTempDirectory.
 */
final class ExplicitRecoveryCache {
    static final byte[] VERSION_BYTES = new byte[] {0x35};
    static final String DIRECTORY_PREFIX = "EURUSD_M1_diagnostic_recovery_";
    static final String ISOLATION_FAILED = "Cache isolation failed";
    static final String PRODUCTION_CACHE =
        "C:\\Users\\Admin\\AppData\\Local\\JForex\\.cache";
    static final String QUARANTINE_ONE =
        "EURUSD_M1_cache_quarantine_20261118T122750Z";
    static final String QUARANTINE_TWO =
        "EURUSD_M1_cache_quarantine_20261118T173558Z";

    private ExplicitRecoveryCache() {
    }

    static Path create(Path outputDirectory, String stamp) throws IOException {
        if (outputDirectory == null || stamp == null || !stamp.matches("\\d{8}T\\d{6}Z")) {
            throw new IOException(ISOLATION_FAILED);
        }
        Path cacheRoot = outputDirectory
            .resolve(DIRECTORY_PREFIX + stamp)
            .toAbsolutePath()
            .normalize();
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
            return "bad-recovery-directory";
        }
        if (forbidden(root)) {
            return forbiddenCode(root);
        }
        Path cache = canonical(strip(resolvedCache));
        Path temp = canonical(strip(resolvedTemp));
        if (!same(cache, root)) {
            return forbidden(cache) ? forbiddenCode(cache) : "outside-recovery-root";
        }
        if (!same(temp, root.resolve("jftemp"))) {
            return forbidden(temp) ? forbiddenCode(temp) : "outside-recovery-root";
        }
        return null;
    }

    private static boolean forbidden(Path path) {
        return forbiddenCode(path) != null;
    }

    private static String forbiddenCode(Path path) {
        String text = path.toAbsolutePath().normalize().toString().toLowerCase();
        String productionCache = PRODUCTION_CACHE.toLowerCase();
        if (text.contains(productionCache.toLowerCase())
                || text.contains("\\jforex\\.cache")
                || text.contains("/jforex/.cache")) {
            return "forbidden-production-cache";
        }
        if (text.contains("eurusd_m1_3y_production")) {
            return "forbidden-production-dataset";
        }
        if (text.contains(QUARANTINE_ONE.toLowerCase())
                || text.contains(QUARANTINE_TWO.toLowerCase())) {
            return "forbidden-quarantine";
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
}
