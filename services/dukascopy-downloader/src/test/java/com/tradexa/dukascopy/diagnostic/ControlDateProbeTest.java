package com.tradexa.dukascopy.diagnostic;

import com.dukascopy.dds2.greed.util.FilePathManager;
import com.dukascopy.dds2.greed.util.IFilePathManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class ControlDateProbeTest {
    @Test
    public void probeRequestsOnlyTheControlThenTheTarget() {
        Assert.assertArrayEquals(
            new String[] {"2023-11-19", "2023-11-18"},
            ControlDateProbe.DATES
        );
        Assert.assertEquals(
            "EURUSD/2023/10/19/BID_candles_min_1.bi5",
            ControlDateProbe.chunkRelativeText("2023-11-19")
        );
        Assert.assertEquals(
            "EURUSD/2023/10/18/BID_candles_min_1.bi5",
            ControlDateProbe.chunkRelativeText("2023-11-18")
        );
        Assert.assertEquals(1, ControlDateProbe.VERSION_BYTES.length);
        Assert.assertEquals(0x35, ControlDateProbe.VERSION_BYTES[0]);
    }

    @Test
    public void eachDateIsRequestedOnceAndFailuresAreNotRetried() throws Exception {
        Path experiment = newExperiment();
        try {
            final List<String> calls = new ArrayList<String>();
            HttpObservation observation = new HttpObservation();
            List<String> rows = ControlDateProbe.capture(
                observation,
                experiment,
                allow(),
                fixedClock(),
                new ControlDateProbe.DatedRequest() {
                    public ControlDateProbe.CandleSpan request(String date) {
                        calls.add(date);
                        HttpAttempt attempt = observation.begin();
                        if ("2023-11-19".equals(date)) {
                            attempt.responseCode = 200;
                            attempt.bytes = 12000;
                            return new ControlDateProbe.CandleSpan(
                                1440,
                                1700352000000L,
                                1700438340000L
                            );
                        }
                        attempt.responseCode = 503;
                        attempt.bytes = 0;
                        attempt.exceptionClass = "IOException";
                        attempt.exceptionMessage = AttemptRecorder.redact(
                            "Server returned HTTP response code: 503 for URL: https://example.invalid/chunk"
                        );
                        return new ControlDateProbe.CandleSpan(0, -1L, -1L);
                    }
                }
            );
            Assert.assertEquals(2, calls.size());
            Assert.assertEquals("2023-11-19", calls.get(0));
            Assert.assertEquals("2023-11-18", calls.get(1));
            Assert.assertEquals(2, rows.size());
            Assert.assertTrue(rows.get(0).contains("\"2023-11-19\""));
            Assert.assertTrue(rows.get(0).contains("\"200\""));
            Assert.assertTrue(rows.get(0).contains("\"1440\""));
            Assert.assertTrue(rows.get(0).contains("\"2023-11-19 00:00:00\""));
            Assert.assertTrue(rows.get(0).contains("\"2023-11-19 23:59:00\""));
            Assert.assertTrue(rows.get(1).contains("\"503\""));
            Assert.assertTrue(rows.get(1).contains("[url]"));
            Assert.assertFalse(rows.get(1).contains("https://"));
            Assert.assertFalse(rows.get(1).contains("example.invalid"));
            Assert.assertEquals("A", ControlDateProbe.classify(200, 1440, 503, 0));
        } finally {
            deleteRecursively(experiment);
        }
    }

    @Test
    public void anEmptyFirstResponseStillRequestsTheTargetOnce() throws Exception {
        Path experiment = newExperiment();
        try {
            final List<String> calls = new ArrayList<String>();
            List<String> rows = ControlDateProbe.capture(
                new HttpObservation(),
                experiment,
                allow(),
                fixedClock(),
                new ControlDateProbe.DatedRequest() {
                    public ControlDateProbe.CandleSpan request(String date) throws Exception {
                        calls.add(date);
                        if ("2023-11-19".equals(date)) {
                            throw new java.io.IOException(
                                "Server returned HTTP response code: 503 for URL: https://example.invalid/x"
                            );
                        }
                        return new ControlDateProbe.CandleSpan(1440, 1700265600000L, 1700351940000L);
                    }
                }
            );
            Assert.assertEquals(2, calls.size());
            Assert.assertEquals(1, count(calls, "2023-11-19"));
            Assert.assertEquals(1, count(calls, "2023-11-18"));
            Assert.assertTrue(rows.get(0).contains("[url]"));
            Assert.assertFalse(rows.get(0).contains("https://"));
            Assert.assertEquals("C", ControlDateProbe.classify(-1, 0, 200, 1440));
            Assert.assertEquals("B", ControlDateProbe.classify(503, 0, -1, 0));
            Assert.assertEquals("D", ControlDateProbe.classify(200, 1440, 200, 10));
        } finally {
            deleteRecursively(experiment);
        }
    }

    @Test
    public void isolationFailureStopsBeforeTheNextRequest() throws Exception {
        Path experiment = newExperiment();
        try {
            final List<String> calls = new ArrayList<String>();
            List<String> rows = ControlDateProbe.capture(
                new HttpObservation(),
                experiment,
                new ControlDateProbe.IsolationCheck() {
                    public void beforeRequest(String date) throws java.io.IOException {
                        if ("2023-11-18".equals(date)) {
                            throw new java.io.IOException("Cache isolation failed");
                        }
                    }
                },
                fixedClock(),
                new ControlDateProbe.DatedRequest() {
                    public ControlDateProbe.CandleSpan request(String date) {
                        calls.add(date);
                        return new ControlDateProbe.CandleSpan(1440, 1700352000000L, 1700438340000L);
                    }
                }
            );
            Assert.assertEquals(1, calls.size());
            Assert.assertEquals("2023-11-19", calls.get(0));
            Assert.assertEquals(2, rows.size());
            Assert.assertTrue(rows.get(1).contains("Cache isolation failed"));
            Assert.assertFalse(rows.get(1).contains("2026-11-15"));
        } finally {
            deleteRecursively(experiment);
        }
    }

    @Test
    public void cachePathsRejectProductionRecoveryAndTheOldDiagnostic() throws Exception {
        Path parent = Files.createTempDirectory("control-isolation");
        try {
            Path cache = ControlDateProbe.create(parent, "20261119T044000Z");
            byte[] version = Files.readAllBytes(cache.resolve("version.txt"));
            Assert.assertEquals(1, version.length);
            Assert.assertEquals((byte) 0x35, version[0]);
            Assert.assertNull(ControlDateProbe.isolationProblem(
                cache,
                cache.toString() + "\\",
                cache.resolve("jftemp").toString()
            ));
            Assert.assertEquals(
                "forbidden-production-cache",
                ControlDateProbe.isolationProblem(
                    cache,
                    "C:\\Users\\Admin\\AppData\\Local\\JForex\\.cache",
                    cache.resolve("jftemp").toString()
                )
            );
            Assert.assertEquals(
                "forbidden-production-dataset",
                ControlDateProbe.isolationProblem(
                    cache,
                    parent.resolve("EURUSD_M1_3y_production").toString(),
                    cache.resolve("jftemp").toString()
                )
            );
            Assert.assertEquals(
                "forbidden-quarantine",
                ControlDateProbe.isolationProblem(
                    cache,
                    parent.resolve("EURUSD_M1_cache_quarantine_20261118T122750Z").toString(),
                    cache.resolve("jftemp").toString()
                )
            );
            Assert.assertEquals(
                "forbidden-recovery-cache",
                ControlDateProbe.isolationProblem(
                    cache,
                    parent.resolve("EURUSD_M1_diagnostic_recovery_20261119T043338Z").toString(),
                    cache.resolve("jftemp").toString()
                )
            );
            Assert.assertEquals(
                "forbidden-existing-diagnostic",
                ControlDateProbe.isolationProblem(
                    cache,
                    parent.resolve("EURUSD_M1_diagnostic").toString(),
                    cache.resolve("jftemp").toString()
                )
            );
        } finally {
            deleteRecursively(parent);
        }
    }

    @Test
    public void filePathManagerCacheIsRevalidatedAndRestored() throws Exception {
        Path productionVersion = Paths.get(
            "C:\\Users\\Admin\\AppData\\Local\\JForex\\.cache\\version.txt"
        );
        byte[] before = Files.readAllBytes(productionVersion);
        IFilePathManager paths = FilePathManager.getInstance();
        String previousCache = paths.getCacheDirectory();
        String previousTemp = paths.getCacheTempDirectory();
        boolean previousAccess = paths.isFoldersAccessible();
        Path parent = Files.createTempDirectory("control-file-path");
        try {
            Path cache = ControlDateProbe.create(parent, "20261119T044100Z");
            ControlDateProbe.configure(cache);
            Assert.assertNull(ControlDateProbe.isolationProblem(
                cache,
                paths.getCacheDirectory(),
                paths.getCacheTempDirectory()
            ));
            paths.setCacheFolderPath("C:\\Users\\Admin\\AppData\\Local\\JForex\\.cache");
            try {
                ControlDateProbe.revalidate(cache);
                Assert.fail("Expected cache isolation to fail");
            } catch (java.io.IOException ex) {
                Assert.assertEquals("Cache isolation failed", ex.getMessage());
            }
        } finally {
            paths.setFoldersAccessible(previousAccess);
            paths.setCacheFolderPath(previousCache);
            paths.setCacheTempFolderPath(previousTemp);
            deleteRecursively(parent);
        }
        Assert.assertArrayEquals(before, Files.readAllBytes(productionVersion));
    }

    private static ControlDateProbe.IsolationCheck allow() {
        return new ControlDateProbe.IsolationCheck() {
            public void beforeRequest(String date) {
            }
        };
    }

    private static ControlDateProbe.UtcClock fixedClock() {
        return new ControlDateProbe.UtcClock() {
            public long now() {
                return 1795063200000L;
            }
        };
    }

    private static Path newExperiment() throws Exception {
        Path parent = Paths.get("target", "control-date-probe-test");
        Files.createDirectories(parent);
        return Files.createTempDirectory(parent, "EURUSD_M1_diagnostic_control_");
    }

    private static int count(List<String> values, String expected) {
        int count = 0;
        for (int i = 0; i < values.size(); i++) {
            if (expected.equals(values.get(i))) {
                count++;
            }
        }
        return count;
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (path == null || !Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
