package com.tradexa.dukascopy.diagnostic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Assert;
import org.junit.Test;

public class CacheVersionInitializationExperimentTest {
    @Test
    public void dummyPayloadIsNotEmpty() {
        Assert.assertTrue(CacheVersionInitializationExperiment.DUMMY_BYTES.length > 0);
        Assert.assertEquals(
            "CACHE-VERSION-TEST",
            new String(
                CacheVersionInitializationExperiment.DUMMY_BYTES,
                StandardCharsets.US_ASCII
            )
        );
    }

    @Test
    public void isolatedPathRejectsProductionAndTheEarlierExperiment() throws Exception {
        Path parent = Paths.get("target", "cache-version-path-test");
        Files.createDirectories(parent);
        Path root = Files.createTempDirectory(
            parent,
            "eurusd_m1_diagnostic_cache_version_test"
        );
        Path production = root.resolve("EURUSD_M1_3y_production");
        Path earlier = root.resolve("EURUSD_M1_diagnostic_zero_cache_experiment");
        Files.createDirectory(production);
        Files.createDirectory(earlier);
        try {
            Assert.assertTrue(
                CacheVersionInitializationExperiment.isIsolatedExperimentPath(root)
            );
            Assert.assertFalse(
                CacheVersionInitializationExperiment.isIsolatedExperimentPath(production)
            );
            Assert.assertFalse(
                CacheVersionInitializationExperiment.isIsolatedExperimentPath(earlier)
            );
            Assert.assertTrue(CacheVersionInitializationExperiment.acceptsCache(
                root,
                root.toString()
            ));
            Assert.assertFalse(CacheVersionInitializationExperiment.acceptsCache(
                root,
                production.toString()
            ));
        } finally {
            Files.deleteIfExists(production);
            Files.deleteIfExists(earlier);
            Files.deleteIfExists(root);
        }
    }
}
