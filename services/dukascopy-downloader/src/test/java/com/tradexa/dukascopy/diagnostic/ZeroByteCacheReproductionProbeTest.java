package com.tradexa.dukascopy.diagnostic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Assert;
import org.junit.Test;

public class ZeroByteCacheReproductionProbeTest {
    @Test
    public void emptyFileMatchesExpectedHash() throws Exception {
        Path empty = Files.createTempFile("zerocache-empty", ".bi5");
        try {
            Assert.assertEquals(
                ZeroByteCacheReproductionProbe.EXPECTED_SHA256,
                ZeroByteCacheReproductionProbe.sha256(empty)
            );
            Assert.assertTrue(
                ZeroByteCacheReproductionProbe.isExpectedZeroFile(empty)
            );
        } finally {
            Files.deleteIfExists(empty);
        }
    }

    @Test
    public void cacheRootIsTheDukascopyCacheDirectory() {
        Path chunk = Paths.get(
            "C:\\Users\\Admin\\AppData\\Local\\JForex\\.cache\\EURUSD\\2024\\04\\21\\BID_candles_min_1.bi5"
        );
        Path root = ZeroByteCacheReproductionProbe.cacheRootOf(chunk);
        Assert.assertNotNull(root);
        Assert.assertEquals(".cache", root.getFileName().toString());
    }

    @Test
    public void resolvedCacheMustStayInsideTheExperimentDirectory() throws Exception {
        Path experiment = Files.createTempDirectory("zerocache-experiment");
        Path production = Files.createTempDirectory("zerocache-production");
        Path other = Files.createTempDirectory("zerocache-other");
        try {
            Assert.assertTrue(ZeroByteCacheReproductionProbe.acceptsCache(
                experiment,
                experiment.toString(),
                production
            ));
            Assert.assertFalse(ZeroByteCacheReproductionProbe.acceptsCache(
                experiment,
                production.toString(),
                production
            ));
            Assert.assertFalse(ZeroByteCacheReproductionProbe.acceptsCache(
                experiment,
                other.toString(),
                production
            ));
            Assert.assertFalse(ZeroByteCacheReproductionProbe.acceptsCache(
                experiment,
                null,
                production
            ));
        } finally {
            Files.deleteIfExists(experiment);
            Files.deleteIfExists(production);
            Files.deleteIfExists(other);
        }
    }
}
