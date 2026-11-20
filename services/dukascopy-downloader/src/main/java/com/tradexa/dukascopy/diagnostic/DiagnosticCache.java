package com.tradexa.dukascopy.diagnostic;

import com.dukascopy.dds2.greed.util.FilePathManager;
import com.dukascopy.dds2.greed.util.IFilePathManager;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Points the JForex cache at a directory under the diagnostic root and
 * checks the resolved path before any history request.
 */
final class DiagnosticCache {
    private DiagnosticCache() {}

    static String isolate(Path diagnosticRoot, Path cacheDirectory)
            throws IOException {
        Path root = diagnosticRoot.toAbsolutePath().normalize();
        Path cache = cacheDirectory.toAbsolutePath().normalize();
        if (!cache.startsWith(root) || cache.equals(root)) {
            throw new IOException("Diagnostic cache is outside its root");
        }
        if (cache.toString().contains("EURUSD_M1_3y_production")) {
            throw new IOException("Diagnostic cache overlaps production");
        }
        clear(cache, root);
        Files.createDirectories(cache);
        Files.createDirectories(cache.resolve("jftemp"));

        IFilePathManager paths = FilePathManager.getInstance();
        paths.setCacheFolderPath(cache.toString());
        paths.setCacheTempFolderPath(cache.resolve("jftemp").toString());
        paths.setFoldersAccessible(true);

        String resolved = paths.getCacheDirectory();
        if (resolved == null) {
            return null;
        }
        Path resolvedPath = PathsWithoutTrailingSeparator(resolved);
        if (!resolvedPath.startsWith(cache)) {
            return null;
        }
        return resolved;
    }

    private static Path PathsWithoutTrailingSeparator(String value) {
        String trimmed = value;
        while (trimmed.endsWith("/") || trimmed.endsWith("\\")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return Paths.get(trimmed).toAbsolutePath().normalize();
    }

    private static void clear(Path cache, Path root) throws IOException {
        if (!Files.exists(cache)) {
            return;
        }
        deleteRecursively(cache, root);
    }

    private static void deleteRecursively(Path path, Path root) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(root) || absolute.equals(root)) {
            throw new IOException("Refusing to delete outside the diagnostic cache");
        }
        if (Files.isDirectory(absolute)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(absolute)) {
                for (Path child : children) {
                    deleteRecursively(child, root);
                }
            }
        }
        Files.deleteIfExists(absolute);
    }
}
