package com.tradexa.dukascopy.diagnostic;

import com.dukascopy.dds2.greed.util.FilePathManager;
import com.dukascopy.dds2.greed.util.IFilePathManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class TransportDateProbeTest {
    @Test
    public void probeRequestsOnlyTheControlThenTheComparison() {
        Assert.assertArrayEquals(
            new String[] {"2023-11-28", "2023-11-19"},
            TransportDateProbe.DATES
        );
        Assert.assertEquals(
            "EURUSD/2023/10/28/BID_candles_min_1.bi5",
            TransportDateProbe.chunkRelativeText("2023-11-28")
        );
        Assert.assertEquals(
            "EURUSD/2023/10/19/BID_candles_min_1.bi5",
            TransportDateProbe.chunkRelativeText("2023-11-19")
        );
        Assert.assertEquals(1, TransportDateProbe.VERSION_BYTES.length);
        Assert.assertEquals(0x35, TransportDateProbe.VERSION_BYTES[0]);
        String joined = TransportDateProbe.DATES[0] + " " + TransportDateProbe.DATES[1];
        Assert.assertFalse(joined.contains("2023-11-18"));
        Assert.assertFalse(joined.contains("2026-11-15"));
    }

    @Test
    public void eachDateIsRequestedOnceAndHeadersAreRecorded() throws Exception {
        Path experiment = newExperiment();
        try {
            final List<String> calls = new ArrayList<String>();
            HttpObservation observation = new HttpObservation();
            TransportDateProbe.CaptureResult captured = TransportDateProbe.capture(
                observation,
                experiment,
                allow(),
                fixedClock(),
                new TransportDateProbe.DatedRequest() {
                    public TransportDateProbe.CandleSpan request(String date) {
                        calls.add(date);
                        HttpAttempt attempt = observation.begin();
                        if ("2023-11-28".equals(date)) {
                            attempt.responseCode = 200;
                            attempt.bytes = 12049L;
                            attempt.contentLength = "12049";
                            attempt.retryAfter = "";
                            return new TransportDateProbe.CandleSpan(
                                1440,
                                1701129600000L,
                                1701215940000L
                            );
                        }
                        attempt.responseCode = 200;
                        attempt.bytes = 0L;
                        attempt.contentLength = "0";
                        attempt.retryAfter = "30";
                        return new TransportDateProbe.CandleSpan(0, -1L, -1L);
                    }
                }
            );
            Assert.assertEquals(2, calls.size());
            Assert.assertEquals("2023-11-28", calls.get(0));
            Assert.assertEquals("2023-11-19", calls.get(1));
            Assert.assertEquals(1, count(calls, "2023-11-28"));
            Assert.assertEquals(1, count(calls, "2023-11-19"));
            Assert.assertEquals(2, captured.rows.size());
            Assert.assertTrue(captured.rows.get(0).contains("\"12049\""));
            Assert.assertTrue(captured.rows.get(0).contains("\"1440\""));
            Assert.assertTrue(captured.rows.get(1).contains("\"200\""));
            Assert.assertTrue(captured.rows.get(1).contains("\"0\""));
            Assert.assertTrue(captured.rows.get(1).contains("\"30\""));
            Assert.assertFalse(captured.rows.get(1).contains("2023-11-18"));
            Assert.assertFalse(captured.rows.get(1).contains("2026-11-15"));
            Assert.assertEquals(
                "B",
                TransportDateProbe.classify(captured.control, captured.comparison)
            );
            Path report = experiment.resolve("transport-report.csv");
            TransportDateProbe.writeReport(report, captured.rows);
            String header = Files.readAllLines(report, StandardCharsets.UTF_8).get(0);
            Assert.assertTrue(header.contains("content_length"));
            Assert.assertTrue(header.contains("retry_after"));
            Assert.assertFalse(header.contains("authorization"));
            Assert.assertFalse(header.contains("cookie"));
        } finally {
            deleteRecursively(experiment);
        }
    }

    @Test
    public void emptyBodyIsNotFullDataAndOutcomesMatchTheReportLetters() {
        HttpAttempt empty = new HttpAttempt();
        empty.responseCode = 200;
        empty.bytes = 0L;
        empty.contentLength = "0";
        TransportDateProbe.DateOutcome emptyBody = TransportDateProbe.outcome(
            true,
            new TransportDateProbe.CandleSpan(0, -1L, -1L),
            Collections.singletonList(empty)
        );
        Assert.assertFalse(emptyBody.full());
        Assert.assertTrue(emptyBody.cleanFailure);

        HttpAttempt full = new HttpAttempt();
        full.responseCode = 200;
        full.bytes = 2745L;
        full.contentLength = "2745";
        TransportDateProbe.DateOutcome fullDay = TransportDateProbe.outcome(
            true,
            new TransportDateProbe.CandleSpan(1440, 1700352000000L, 1700438340000L),
            Collections.singletonList(full)
        );
        TransportDateProbe.DateOutcome control = TransportDateProbe.outcome(
            true,
            new TransportDateProbe.CandleSpan(1440, 1701129600000L, 1701215940000L),
            Collections.singletonList(full)
        );
        Assert.assertEquals("A", TransportDateProbe.classify(control, fullDay));
        Assert.assertEquals("B", TransportDateProbe.classify(control, emptyBody));
        Assert.assertEquals("C", TransportDateProbe.classify(emptyBody, emptyBody));
        Assert.assertEquals("D", TransportDateProbe.classify(emptyBody, fullDay));

        HttpAttempt partial = new HttpAttempt();
        partial.responseCode = 200;
        partial.bytes = 100L;
        partial.contentLength = "100";
        TransportDateProbe.DateOutcome partialDay = TransportDateProbe.outcome(
            true,
            new TransportDateProbe.CandleSpan(10, 1700352000000L, 1700352540000L),
            Collections.singletonList(partial)
        );
        Assert.assertEquals("D", TransportDateProbe.classify(control, partialDay));
        Assert.assertEquals("D", TransportDateProbe.classify(control, null));
    }

    @Test
    public void aFailedFirstRequestStillRequestsTheComparisonOnce() throws Exception {
        Path experiment = newExperiment();
        try {
            final List<String> calls = new ArrayList<String>();
            final HttpObservation observation = new HttpObservation();
            TransportDateProbe.CaptureResult captured = TransportDateProbe.capture(
                observation,
                experiment,
                allow(),
                fixedClock(),
                new TransportDateProbe.DatedRequest() {
                    public TransportDateProbe.CandleSpan request(String date) throws Exception {
                        calls.add(date);
                        if ("2023-11-28".equals(date)) {
                            throw new java.net.ConnectException(
                                "Connection timed out: getsockopt https://secret.example/chunk"
                            );
                        }
                        HttpAttempt attempt = observation.begin();
                        attempt.responseCode = 200;
                        attempt.bytes = 2745L;
                        attempt.contentLength = "2745";
                        return new TransportDateProbe.CandleSpan(
                            1440,
                            1700352000000L,
                            1700438340000L
                        );
                    }
                }
            );
            Assert.assertEquals(2, calls.size());
            Assert.assertEquals(1, count(calls, "2023-11-28"));
            Assert.assertEquals(1, count(calls, "2023-11-19"));
            Assert.assertTrue(captured.rows.get(0).contains("[url]"));
            Assert.assertFalse(captured.rows.get(0).contains("https://"));
            Assert.assertFalse(captured.rows.get(0).contains("secret.example"));
            Assert.assertEquals(
                "D",
                TransportDateProbe.classify(captured.control, captured.comparison)
            );
        } finally {
            deleteRecursively(experiment);
        }
    }

    @Test
    public void isolationFailureStopsBeforeTheNextRequest() throws Exception {
        Path experiment = newExperiment();
        try {
            final List<String> calls = new ArrayList<String>();
            TransportDateProbe.CaptureResult captured = TransportDateProbe.capture(
                new HttpObservation(),
                experiment,
                new TransportDateProbe.IsolationCheck() {
                    public void beforeRequest(String date) throws java.io.IOException {
                        if ("2023-11-19".equals(date)) {
                            throw new java.io.IOException("Cache isolation failed");
                        }
                    }
                },
                fixedClock(),
                new TransportDateProbe.DatedRequest() {
                    public TransportDateProbe.CandleSpan request(String date) {
                        calls.add(date);
                        return new TransportDateProbe.CandleSpan(
                            1440,
                            1701129600000L,
                            1701215940000L
                        );
                    }
                }
            );
            Assert.assertEquals(1, calls.size());
            Assert.assertEquals("2023-11-28", calls.get(0));
            Assert.assertNull(captured.comparison);
            Assert.assertEquals("D", TransportDateProbe.classify(
                captured.control,
                captured.comparison
            ));
            Assert.assertTrue(captured.rows.get(1).contains("Cache isolation failed"));
            Assert.assertFalse(captured.rows.get(1).contains("2023-11-18"));
        } finally {
            deleteRecursively(experiment);
        }
    }

    @Test
    public void cachePathsRejectProductionPreviousDiagnosticsAndQuarantine() throws Exception {
        Path parent = Files.createTempDirectory("transport-isolation");
        try {
            Path cache = TransportDateProbe.create(parent, "20261119T050000Z");
            byte[] version = Files.readAllBytes(cache.resolve("version.txt"));
            Assert.assertEquals(1, version.length);
            Assert.assertEquals((byte) 0x35, version[0]);
            Assert.assertNull(TransportDateProbe.isolationProblem(
                cache,
                cache.toString() + "\\",
                cache.resolve("jftemp").toString()
            ));
            Assert.assertEquals(
                "forbidden-production-cache",
                TransportDateProbe.isolationProblem(
                    cache,
                    "C:\\Users\\Admin\\AppData\\Local\\JForex\\.cache",
                    cache.resolve("jftemp").toString()
                )
            );
            Assert.assertEquals(
                "forbidden-production-dataset",
                TransportDateProbe.isolationProblem(
                    cache,
                    parent.resolve("EURUSD_M1_3y_production").toString(),
                    cache.resolve("jftemp").toString()
                )
            );
            Assert.assertEquals(
                "forbidden-quarantine",
                TransportDateProbe.isolationProblem(
                    cache,
                    parent.resolve("EURUSD_M1_cache_quarantine_20261118T173558Z").toString(),
                    cache.resolve("jftemp").toString()
                )
            );
            Assert.assertEquals(
                "forbidden-existing-diagnostic",
                TransportDateProbe.isolationProblem(
                    cache,
                    parent.resolve("EURUSD_M1_diagnostic").toString(),
                    cache.resolve("jftemp").toString()
                )
            );
            Assert.assertEquals(
                "forbidden-existing-diagnostic",
                TransportDateProbe.isolationProblem(
                    cache,
                    parent.resolve("EURUSD_M1_diagnostic_control_20261119T044607Z").toString(),
                    cache.resolve("jftemp").toString()
                )
            );
            Assert.assertEquals(
                "forbidden-existing-diagnostic",
                TransportDateProbe.isolationProblem(
                    cache,
                    parent.resolve("EURUSD_M1_diagnostic_recovery_20261119T043338Z").toString(),
                    cache.resolve("jftemp").toString()
                )
            );
            try {
                TransportDateProbe.create(parent, "20261119T050000Z");
                Assert.fail("existing diagnostic directory must be refused");
            } catch (java.io.IOException expected) {
                Assert.assertEquals("Cache isolation failed", expected.getMessage());
            }
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
        Path parent = Files.createTempDirectory("transport-file-path");
        try {
            Path cache = TransportDateProbe.create(parent, "20261119T050100Z");
            TransportDateProbe.configure(cache);
            Assert.assertNull(TransportDateProbe.isolationProblem(
                cache,
                paths.getCacheDirectory(),
                paths.getCacheTempDirectory()
            ));
            paths.setCacheFolderPath("C:\\Users\\Admin\\AppData\\Local\\JForex\\.cache");
            try {
                TransportDateProbe.revalidate(cache);
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

    private static TransportDateProbe.IsolationCheck allow() {
        return new TransportDateProbe.IsolationCheck() {
            public void beforeRequest(String date) {
            }
        };
    }

    private static TransportDateProbe.UtcClock fixedClock() {
        return new TransportDateProbe.UtcClock() {
            public long now() {
                return 1795063200000L;
            }
        };
    }

    private static Path newExperiment() throws Exception {
        Path parent = Paths.get("target", "transport-date-probe-test");
        Files.createDirectories(parent);
        return Files.createTempDirectory(parent, "transport-capture-");
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
