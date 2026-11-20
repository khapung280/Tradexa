package com.tradexa.dukascopy.nativehist;

import com.dukascopy.dds2.greed.util.FilePathManager;
import com.dukascopy.dds2.greed.util.IFilePathManager;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Assert;
import org.junit.Test;

public class NativeHistoryCacheTest {
    @Test
    public void cacheRejectsProductionAndQuarantinePaths() throws Exception {
        Path parent = Files.createTempDirectory("native-cache");
        try {
            Path cache = NativeHistoryCache.create(parent, "20261119T060000Z");
            Assert.assertNull(NativeHistoryCache.isolationProblem(
                cache, cache.toString(), cache.resolve("jftemp").toString()
            ));
            Path poison = cache.resolve("EURUSD").resolve("empty.bi5");
            Files.createDirectories(poison.getParent());
            Files.write(poison, new byte[0]);
            Path kept = cache.resolve("EURUSD").resolve("kept.bi5");
            Files.write(kept, new byte[] {1});
            Assert.assertEquals(1, NativeHistoryCache.clearZeroByteChunks(cache));
            Assert.assertFalse(Files.exists(poison));
            Assert.assertEquals(1, Files.size(kept));
            Assert.assertEquals(1, Files.size(cache.resolve("version.txt")));
            Assert.assertNotNull(NativeHistoryCache.isolationProblem(
                cache,
                NativeHistoryCache.PRODUCTION_CACHE,
                cache.resolve("jftemp").toString()
            ));
            Path quarantine = parent.resolve(NativeHistoryCache.QUARANTINE_ONE);
            Assert.assertNotNull(NativeHistoryCache.isolationProblem(
                quarantine,
                quarantine.toString(),
                quarantine.resolve("jftemp").toString()
            ));
        } finally {
            delete(parent);
        }
    }

    @Test
    public void configuredCacheIsRestored() throws Exception {
        IFilePathManager paths = FilePathManager.getInstance();
        String previousCache = paths.getCacheDirectory();
        String previousTemp = paths.getCacheTempDirectory();
        boolean previousAccess = paths.isFoldersAccessible();
        Path parent = Files.createTempDirectory("native-cache-config");
        try {
            Path cache = NativeHistoryCache.create(parent, "20261119T060100Z");
            NativeHistoryCache.configure(cache);
            Assert.assertNull(NativeHistoryCache.isolationProblem(
                cache,
                paths.getCacheDirectory(),
                paths.getCacheTempDirectory()
            ));
        } finally {
            paths.setFoldersAccessible(previousAccess);
            paths.setCacheFolderPath(previousCache);
            paths.setCacheTempFolderPath(previousTemp);
            delete(parent);
        }
    }

    private static void delete(Path path) throws Exception {
        if (path == null || !Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) {
                    delete(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
