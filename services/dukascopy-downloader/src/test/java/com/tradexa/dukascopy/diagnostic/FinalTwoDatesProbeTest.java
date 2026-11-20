package com.tradexa.dukascopy.diagnostic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class FinalTwoDatesProbeTest {
    @Test
    public void probeQueriesOnlyTheTwoDatesInOrder() {
        Assert.assertArrayEquals(
            new String[] {"2023-11-18", "2026-11-15"},
            FinalTwoDatesProbe.DATES
        );
        Assert.assertEquals(
            Paths.get("EURUSD", "2023", "10", "18", "BID_candles_min_1.bi5"),
            FinalTwoDatesProbe.chunkRelative("2023-11-18")
        );
        Assert.assertEquals(
            Paths.get("EURUSD", "2026", "10", "15", "BID_candles_min_1.bi5"),
            FinalTwoDatesProbe.chunkRelative("2026-11-15")
        );
        for (int i = 0; i < FinalTwoDatesProbe.DATES.length; i++) {
            String date = FinalTwoDatesProbe.DATES[i];
            Assert.assertEquals(date + " 00:00:00", FinalTwoDatesProbe.startText(date));
            Assert.assertEquals(date + " 23:59:00", FinalTwoDatesProbe.endText(date));
            Assert.assertEquals(86340000L, FinalTwoDatesProbe.requestSpanMillis(date));
        }
        Assert.assertEquals(1, FinalTwoDatesProbe.VERSION_BYTES.length);
        Assert.assertEquals(0x35, FinalTwoDatesProbe.VERSION_BYTES[0]);
    }

    @Test
    public void eachDateIsRequestedOnceAndObservationsStaySeparate() throws Exception {
        Path parent = Paths.get("target", "final2-probe-test");
        Files.createDirectories(parent);
        Path experiment = Files.createTempDirectory(
            parent,
            "EURUSD_M1_diagnostic_final2_"
        );
        try {
            HttpObservation observation = new HttpObservation();
            observation.setRecording(true);
            HttpAttempt earlier = observation.begin();
            earlier.responseCode = 404;
            earlier.bytes = 9;
            observation.setRecording(false);
            final List<String> calls = new ArrayList<String>();
            List<String> rows = FinalTwoDatesProbe.captureDates(
                observation,
                experiment,
                new FinalTwoDatesProbe.DatedRequest() {
                    public int request(String date) {
                        calls.add(date);
                        HttpAttempt attempt = observation.begin();
                        if ("2023-11-18".equals(date)) {
                            attempt.responseCode = 200;
                            attempt.bytes = 11;
                            return 0;
                        }
                        attempt.responseCode = 503;
                        attempt.exceptionClass = "IOException";
                        attempt.exceptionMessage = AttemptRecorder.redact(
                            "Server returned HTTP response code: 503 for URL: https://example.invalid/chunk"
                        );
                        return 0;
                    }
                }
            );
            Assert.assertEquals(2, calls.size());
            Assert.assertEquals("2023-11-18", calls.get(0));
            Assert.assertEquals("2026-11-15", calls.get(1));
            Assert.assertEquals(2, rows.size());
            Assert.assertTrue(rows.get(0).contains("\"2023-11-18\""));
            Assert.assertTrue(rows.get(0).contains("\"2023-11-18 00:00:00\""));
            Assert.assertTrue(rows.get(0).contains("\"2023-11-18 23:59:00\""));
            Assert.assertTrue(rows.get(0).contains("\"200\""));
            Assert.assertTrue(rows.get(0).contains("\"11\""));
            Assert.assertFalse(rows.get(0).contains("404"));
            Assert.assertFalse(rows.get(0).contains("503"));
            Assert.assertTrue(rows.get(1).contains("\"2026-11-15\""));
            Assert.assertTrue(rows.get(1).contains("\"503\""));
            Assert.assertTrue(rows.get(1).contains("[url]"));
            Assert.assertFalse(rows.get(1).contains("https://"));
            Assert.assertFalse(rows.get(1).contains("example.invalid"));
            Assert.assertEquals(0, observation.drain().size());
        } finally {
            Files.deleteIfExists(experiment);
        }
    }

    @Test
    public void directoriesStayUniqueAndAwayFromProduction() throws Exception {
        Path first = FinalTwoDatesProbe.experimentDirectory("20261118T180000Z");
        Path second = FinalTwoDatesProbe.experimentDirectory("20261118T180001Z");
        Assert.assertFalse(first.equals(second));
        Assert.assertTrue(first.toString().contains(
            "EURUSD_M1_diagnostic_final2_20261118T180000Z"
        ));
        Assert.assertTrue(FinalTwoDatesProbe.isIsolatedExperimentPath(first));
        Assert.assertFalse(FinalTwoDatesProbe.isIsolatedExperimentPath(
            Paths.get("output", "EURUSD_M1_3y_production")
        ));
        Assert.assertFalse(FinalTwoDatesProbe.isIsolatedExperimentPath(
            Paths.get("output", "EURUSD_M1_cache_quarantine_20261118T122750Z")
        ));
        Assert.assertFalse(FinalTwoDatesProbe.isIsolatedExperimentPath(
            Paths.get("output", "EURUSD_M1_cache_quarantine_20261118T173558Z")
        ));
        Assert.assertFalse(FinalTwoDatesProbe.isIsolatedExperimentPath(
            Paths.get("output", "EURUSD_M1_diagnostic_final4_20261118T124646Z")
        ));
        Assert.assertFalse(FinalTwoDatesProbe.isIsolatedExperimentPath(
            Paths.get("C:\\Users\\Admin\\AppData\\Local\\JForex\\.cache")
        ));
        Path parent = Paths.get("target", "final2-probe-test");
        Files.createDirectories(parent);
        Path root = Files.createTempDirectory(parent, "EURUSD_M1_diagnostic_final2_");
        try {
            Assert.assertTrue(FinalTwoDatesProbe.acceptsCache(root, root.toString()));
        } finally {
            Files.deleteIfExists(root);
        }
        Assert.assertEquals(
            "Read timed out",
            AttemptRecorder.redact("Read timed out")
        );
        Assert.assertEquals(
            "Connection reset",
            AttemptRecorder.redact("Connection reset")
        );
    }
}
