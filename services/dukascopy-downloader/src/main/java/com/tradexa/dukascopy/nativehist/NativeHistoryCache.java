package com.tradexa.dukascopy.nativehist;

import com.dukascopy.dds2.greed.util.FilePathManager;
import com.dukascopy.dds2.greed.util.IFilePathManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Isolated JForex cache for the native six-timeframe download.
 * It refuses the production M1 cache, the production M1 dataset,
 * and both existing quarantine directories.
 */
public final class NativeHistoryCache {
    public static final byte[] VERSION_BYTES = new byte[] {0x35};
    public static final String DIRECTORY_PREFIX = "EURUSD_native_history_cache_";
    public static final String ISOLATION_FAILED = "Cache isolation failed";
    static final String PRODUCTION_CACHE =
        "C:\\Users\\Admin\\AppData\\Local\\JForex\\.cache";
    static final String QUARANTINE_ONE =
        "EURUSD_M1_cache_quarantine_20261118T122750Z";
    static final String QUARANTINE_TWO =
        "EURUSD_M1_cache_quarantine_20261118T173558Z";

    private NativeHistoryCache() {
    }

    public static Path create(Path outputDirectory, String stamp) throws IOException {
        if (outputDirectory == null || stamp == null || !stamp.matches("\\d{8}T\\d{6}Z")) {
            throw new IOException(ISOLATION_FAILED);
        }
        Path cacheRoot = outputDirectory.resolve(DIRECTORY_PREFIX + stamp)
            .toAbsolutePath()
            .normalize();
        if (isolationProblem(cacheRoot, cacheRoot.toString(), cacheRoot.resolve("jftemp").toString()) != null
                || Files.exists(cacheRoot)) {
            throw new IOException(ISOLATION_FAILED);
        }
        Files.createDirectories(cacheRoot.resolve("jftemp"));
        Path version = cacheRoot.resolve("version.txt");
        Files.write(version, VERSION_BYTES, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        byte[] stored = Files.readAllBytes(version);
        if (stored.length != 1 || stored[0] != VERSION_BYTES[0]) {
            throw new IOException(ISOLATION_FAILED);
        }
        return cacheRoot;
    }

    public static void configure(Path cacheRoot) throws IOException {
        IFilePathManager paths = FilePathManager.getInstance();
        paths.setCacheFolderPath(cacheRoot.toAbsolutePath().normalize().toString());
        paths.setCacheTempFolderPath(cacheRoot.resolve("jftemp").toAbsolutePath().normalize().toString());
        paths.setFoldersAccessible(true);
        revalidate(cacheRoot);
    }

    public static void revalidate(Path cacheRoot) throws IOException {
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

    public static String isolationProblem(
            Path cacheRoot,
            String resolvedCache,
            String resolvedTemp) {
        if (cacheRoot == null || resolvedCache == null || resolvedTemp == null) {
            return "missing-path";
        }
        Path root = canonical(cacheRoot);
        String name = root.getFileName() == null ? "" : root.getFileName().toString();
        if (!name.matches(DIRECTORY_PREFIX + "\\d{8}T\\d{6}Z")) {
            return "bad-cache-directory";
        }
        if (forbidden(root) != null) {
            return forbidden(root);
        }
        Path cache = canonical(strip(resolvedCache));
        Path temp = canonical(strip(resolvedTemp));
        if (!same(cache, root)) {
            return forbidden(cache) != null ? forbidden(cache) : "outside-cache-root";
        }
        if (!same(temp, root.resolve("jftemp"))) {
            return forbidden(temp) != null ? forbidden(temp) : "outside-cache-root";
        }
        return null;
    }

    public static int clearZeroByteChunks(Path cacheRoot) throws IOException {
        if (cacheRoot == null || cacheRoot.getFileName() == null
                || !cacheRoot.getFileName().toString().startsWith(DIRECTORY_PREFIX)) {
            throw new IOException(ISOLATION_FAILED);
        }
        return deleteZeroByteBi5(cacheRoot);
    }

    private static int deleteZeroByteBi5(Path directory) throws IOException {
        int removed = 0;
        try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                if (Files.isDirectory(child)) {
                    removed += deleteZeroByteBi5(child);
                } else if (child.getFileName().toString().endsWith(".bi5")
                        && Files.size(child) == 0L) {
                    Files.delete(child);
                    removed++;
                }
            }
        }
        return removed;
    }

    private static String forbidden(Path path) {
        String text = path.toAbsolutePath().normalize().toString().toLowerCase();
        if (text.contains(PRODUCTION_CACHE.toLowerCase())
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
