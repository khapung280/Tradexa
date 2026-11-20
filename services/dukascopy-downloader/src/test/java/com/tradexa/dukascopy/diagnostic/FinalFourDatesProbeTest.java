package com.tradexa.dukascopy.diagnostic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Assert;
import org.junit.Test;

public class FinalFourDatesProbeTest {
    @Test
    public void probeQueriesOnlyTheFourRemainingDates() {
        Assert.assertArrayEquals(
            new String[] {
                "2026-11-15",
                "2023-11-18",
                "2023-11-28",
                "2023-11-30"
            },
            FinalFourDatesProbe.DATES
        );
        Assert.assertEquals(
            Paths.get("EURUSD", "2026", "10", "15", "BID_candles_min_1.bi5"),
            FinalFourDatesProbe.chunkRelative("2026-11-15")
        );
        Assert.assertEquals(
            Paths.get("EURUSD", "2023", "10", "18", "BID_candles_min_1.bi5"),
            FinalFourDatesProbe.chunkRelative("2023-11-18")
        );
        Assert.assertEquals(
            Paths.get("EURUSD", "2023", "10", "28", "BID_candles_min_1.bi5"),
            FinalFourDatesProbe.chunkRelative("2023-11-28")
        );
        Assert.assertEquals(
            Paths.get("EURUSD", "2023", "10", "30", "BID_candles_min_1.bi5"),
            FinalFourDatesProbe.chunkRelative("2023-11-30")
        );
        for (int i = 0; i < FinalFourDatesProbe.DATES.length; i++) {
            String date = FinalFourDatesProbe.DATES[i];
            Assert.assertEquals(date + " 00:00:00", FinalFourDatesProbe.startText(date));
            Assert.assertEquals(date + " 23:59:00", FinalFourDatesProbe.endText(date));
            Assert.assertEquals(86340000L, FinalFourDatesProbe.requestSpanMillis(date));
        }
    }

    @Test
    public void versionByteIsFiveAndPathsStayOutsideProduction() throws Exception {
        Assert.assertEquals(1, FinalFourDatesProbe.VERSION_BYTES.length);
        Assert.assertEquals(0x35, FinalFourDatesProbe.VERSION_BYTES[0]);
        Path parent = Paths.get("target", "final4-path-test");
        Files.createDirectories(parent);
        Path root = Files.createTempDirectory(parent, "EURUSD_M1_diagnostic_final4_");
        try {
            Assert.assertTrue(FinalFourDatesProbe.acceptsCache(root, root.toString()));
            Assert.assertFalse(FinalFourDatesProbe.acceptsCache(
                root,
                "C:\\Users\\Admin\\AppData\\Local\\JForex\\.cache"
            ));
            Assert.assertFalse(FinalFourDatesProbe.isIsolatedExperimentPath(
                root.resolve("EURUSD_M1_cache_quarantine_20261118T122750Z")
            ));
            Assert.assertFalse(FinalFourDatesProbe.isIsolatedExperimentPath(
                root.resolve("EURUSD_M1_3y_production")
            ));
        } finally {
            Files.deleteIfExists(root);
        }
    }

    @Test
    public void exceptionTextDropsUrls() {
        Assert.assertEquals(
            "Server returned HTTP response code: 503 for URL: [url]",
            AttemptRecorder.redact(
                "Server returned HTTP response code: 503 for URL: https://example.invalid/chunk"
            )
        );
        Assert.assertEquals("Read timed out", AttemptRecorder.redact("Read timed out"));
        Assert.assertEquals("", AttemptRecorder.redact(null));
    }
}
