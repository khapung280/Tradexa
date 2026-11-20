package com.tradexa.dukascopy.diagnostic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Assert;
import org.junit.Test;

public class ZeroByteVersion5ProbeTest {
    @Test
    public void versionFileIsTheSingleByteFive() throws Exception {
        Path parent = Paths.get("target", "zero-byte-version5-path-test");
        Files.createDirectories(parent);
        Path file = parent.resolve("version-unit.txt");
        Files.write(file, ZeroByteVersion5Probe.VERSION_BYTES);
        try {
            Assert.assertEquals(1, ZeroByteVersion5Probe.VERSION_BYTES.length);
            Assert.assertEquals(0x35, ZeroByteVersion5Probe.VERSION_BYTES[0]);
            Assert.assertTrue(ZeroByteVersion5Probe.isExactVersion(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void emptyChunkHasTheEmptySha256() throws Exception {
        Path parent = Paths.get("target", "zero-byte-version5-path-test");
        Files.createDirectories(parent);
        Path empty = Files.createTempFile(parent, "empty", ".bi5");
        try {
            Assert.assertEquals(
                ZeroByteVersion5Probe.EMPTY_SHA256,
                ZeroByteVersion5Probe.sha256(empty)
            );
            Assert.assertTrue(ZeroByteVersion5Probe.isZeroChunk(empty));
        } finally {
            Files.deleteIfExists(empty);
        }
    }

    @Test
    public void mayTwentyFirstUsesTheZeroBasedCachePath() {
        Assert.assertEquals(
            Paths.get("EURUSD", "2024", "04", "21", "BID_candles_min_1.bi5"),
            ZeroByteVersion5Probe.chunkRelative("2024-05-21")
        );
        Assert.assertEquals(86340000L, ZeroByteVersion5Probe.requestSpanMillis());
        Assert.assertEquals("2024-05-21 00:00:00", ZeroByteVersion5Probe.START_TEXT);
        Assert.assertEquals("2024-05-21 23:59:00", ZeroByteVersion5Probe.END_TEXT);
    }

    @Test
    public void cachePathMustStayInsideTheNewExperiment() throws Exception {
        Path parent = Paths.get("target", "zero-byte-version5-path-test");
        Files.createDirectories(parent);
        Path root = Files.createTempDirectory(
            parent,
            "EURUSD_M1_diagnostic_zero_byte_version5_test"
        );
        Path earlier = root.resolve("EURUSD_M1_diagnostic_zero_cache_experiment");
        Files.createDirectory(earlier);
        try {
            Assert.assertTrue(ZeroByteVersion5Probe.acceptsCache(root, root.toString()));
            Assert.assertFalse(ZeroByteVersion5Probe.acceptsCache(root, earlier.toString()));
            Assert.assertFalse(ZeroByteVersion5Probe.acceptsCache(
                root,
                "C:\\Users\\Admin\\AppData\\Local\\JForex\\.cache"
            ));
            Assert.assertFalse(ZeroByteVersion5Probe.acceptsCache(root, null));
        } finally {
            Files.deleteIfExists(earlier);
            Files.deleteIfExists(root);
        }
    }

    @Test
    public void conclusionsDistinguishReuseFromReplacement() {
        Assert.assertEquals(
            "zero-byte file was reused with no observed HTTP request; empty list is not proof of a closed market",
            ZeroByteVersion5Probe.conclude("onConnect", true, 0, true, 0L, true, 0, true)
        );
        Assert.assertEquals(
            "an HTTP response replaced the zero-byte file and candles were returned",
            ZeroByteVersion5Probe.conclude("onConnect", true, 1, true, 34560L, false, 1440, true)
        );
        Assert.assertEquals(
            "onConnect did not complete; getBars was not called",
            ZeroByteVersion5Probe.conclude("timeout", false, 0, false, -1L, false, -1, false)
        );
    }
}
