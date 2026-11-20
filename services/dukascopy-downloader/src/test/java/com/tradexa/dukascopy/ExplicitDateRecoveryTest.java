package com.tradexa.dukascopy;

import com.dukascopy.dds2.greed.util.FilePathManager;
import com.dukascopy.dds2.greed.util.IFilePathManager;
import com.tradexa.dukascopy.DukascopyDownloader.ExplicitRecoveryExercise;
import com.tradexa.dukascopy.DukascopyDownloader.RecoveryGate;
import com.tradexa.dukascopy.DukascopyDownloader.RecoveryHttpProbe;
import com.tradexa.dukascopy.diagnostic.RecoveryTransportLog;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class ExplicitDateRecoveryTest {
    @Test
    public void commandAcceptsOnlyTheApprovedPair() {
        Assert.assertTrue(DukascopyDownloader.acceptsExplicitDateRecovery(new String[] {
            "download-3y-dates",
            "2023-11-18",
            "2026-11-15"
        }));
        Assert.assertFalse(DukascopyDownloader.acceptsExplicitDateRecovery(new String[] {
            "download-3y-dates",
            "2026-11-15",
            "2023-11-18"
        }));
        Assert.assertFalse(DukascopyDownloader.acceptsExplicitDateRecovery(new String[] {
            "download-3y-dates",
            "2023-11-28",
            "2023-11-30"
        }));
        Assert.assertFalse(DukascopyDownloader.acceptsExplicitDateRecovery(new String[] {
            "download-3y-dates",
            "2023-11-18"
        }));
        Assert.assertFalse(DukascopyDownloader.acceptsExplicitDateRecovery(new String[] {
            "download-3y-dates",
            "2023-11-18",
            "2026-11-15",
            "2023-11-19"
        }));
        Assert.assertFalse(DukascopyDownloader.acceptsExplicitDateRecovery(new String[] {
            "download-3y",
            "200"
        }));
        Assert.assertFalse(DukascopyDownloader.acceptsExplicitDateRecovery(null));
        Assert.assertEquals(3, DukascopyDownloader.productionAttemptLimit());
    }

    @Test
    public void preflightRejectsResolvedRowsMissingFilesAndWindowMismatch() throws Exception {
        Path parent = Paths.get("target", "explicit-date-recovery-test");
        Files.createDirectories(parent);
        Path saved = fixture(parent, "saved", "DATA_SAVED", "UNKNOWN");
        assertRejected(saved, "Explicit recovery requires UNKNOWN: 2023-11-18");
        Path confirmed = fixture(parent, "confirmed", "UNKNOWN", "CONFIRMED_EMPTY");
        assertRejected(confirmed, "Explicit recovery requires UNKNOWN: 2026-11-15");
        Path noLedger = Files.createTempDirectory(parent, "no-ledger");
        Files.createDirectories(noLedger.resolve("EURUSD_M1_3y_staging"));
        Files.write(
            noLedger.resolve("EURUSD_M1_3y.scheduler.properties"),
            scheduler(false).getBytes(StandardCharsets.UTF_8)
        );
        assertRejected(noLedger, "Ledger was not found");
        Path noScheduler = fixture(parent, "no-scheduler", "UNKNOWN", "UNKNOWN");
        Files.delete(noScheduler.resolve("EURUSD_M1_3y.scheduler.properties"));
        assertRejected(noScheduler, "Production scheduler was not found");
        Path noStaging = fixture(parent, "no-staging", "UNKNOWN", "UNKNOWN");
        deleteRecursively(noStaging.resolve("EURUSD_M1_3y_staging"));
        assertRejected(noStaging, "Production staging directory is missing");
        Path mismatch = fixture(parent, "mismatch", "UNKNOWN", "UNKNOWN");
        String ledger = new String(
            Files.readAllBytes(mismatch.resolve("EURUSD_M1_3y.ledger.properties")),
            StandardCharsets.UTF_8
        );
        Files.write(
            mismatch.resolve("EURUSD_M1_3y.ledger.properties"),
            ledger.replace(
                "windowEnd=" + DukascopyDownloader.FIXED_PRODUCTION_WINDOW_END,
                "windowEnd=" + (DukascopyDownloader.FIXED_PRODUCTION_WINDOW_END + 60000L)
            ).getBytes(StandardCharsets.UTF_8)
        );
        assertRejected(mismatch, "Production window does not match");
    }

    @Test
    public void existingAndOrphanCsvStopWithoutDeletion() throws Exception {
        Path parent = Paths.get("target", "explicit-date-recovery-test");
        Files.createDirectories(parent);
        Path first = fixture(parent, "orphan-first", "UNKNOWN", "UNKNOWN");
        Path csv = first.resolve("EURUSD_M1_3y_staging").resolve("2023-11-18.csv");
        Files.write(csv, new byte[] {1, 2, 3});
        byte[] before = Files.readAllBytes(first.resolve("EURUSD_M1_3y.ledger.properties"));
        assertRejected(first, "Orphan staging file: 2023-11-18");
        Assert.assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(csv));
        Assert.assertArrayEquals(
            before,
            Files.readAllBytes(first.resolve("EURUSD_M1_3y.ledger.properties"))
        );

        Path second = fixture(parent, "orphan-second", "UNKNOWN", "UNKNOWN");
        Path secondCsv = second.resolve("EURUSD_M1_3y_staging").resolve("2026-11-15.csv");
        Files.write(secondCsv, new byte[] {4});
        assertRejected(second, "Orphan staging file: 2026-11-15");
        Assert.assertArrayEquals(new byte[] {4}, Files.readAllBytes(secondCsv));
    }

    @Test
    public void interruptedStagingTempStopsAndPreservesTheLedger() throws Exception {
        Path parent = Paths.get("target", "explicit-date-recovery-test");
        Files.createDirectories(parent);
        Path directory = fixture(parent, "tmp", "UNKNOWN", "UNKNOWN");
        Path temp = directory.resolve("EURUSD_M1_3y_staging").resolve("2023-11-18.csv.tmp");
        Files.write(temp, new byte[] {9});
        byte[] before = Files.readAllBytes(directory.resolve("EURUSD_M1_3y.ledger.properties"));
        try {
            DukascopyDownloader.exerciseExplicitRecovery(directory, "data", "data");
            Assert.fail("Expected the temporary staging file to stop recovery");
        } catch (Exception ex) {
            Assert.assertEquals("Interrupted staging update", ex.getMessage());
        }
        Assert.assertArrayEquals(new byte[] {9}, Files.readAllBytes(temp));
        Assert.assertArrayEquals(
            before,
            Files.readAllBytes(directory.resolve("EURUSD_M1_3y.ledger.properties"))
        );
    }

    @Test
    public void emptyPartialAndInvalidResponsesDoNotSave() throws Exception {
        assertRejectedOutcome("empty", "Empty response", 0);
        assertRejectedOutcome("partial", "Partial response", 10);
        assertRejectedOutcome("duplicate", "Duplicate timestamp", 1440);
        assertRejectedOutcome("missing", "Missing M1 minute", 1440);
        assertRejectedOutcome("ohlc", "Invalid OHLC", 1440);
        assertRejectedOutcome("volume", "Invalid volume", 1440);
        assertRejectedOutcome("failed", "Request failed", 0);
        assertRejectedOutcome("confirmed", "Empty response", 0);
    }

    @Test
    public void firstDateFailureDoesNotRequestTheSecondDate() throws Exception {
        Path directory = fixture(
            Paths.get("target", "explicit-date-recovery-test"),
            "stop",
            "UNKNOWN",
            "UNKNOWN"
        );
        ExplicitRecoveryExercise result = DukascopyDownloader.exerciseExplicitRecovery(
            directory,
            "empty",
            "data"
        );
        Assert.assertEquals(1, result.requests);
        Assert.assertEquals("UNKNOWN", result.firstStatus);
        Assert.assertEquals(12, result.firstAttempts);
        Assert.assertEquals(0L, result.firstRows);
        Assert.assertEquals("UNKNOWN", result.secondStatus);
        Assert.assertEquals(12, result.secondAttempts);
        Assert.assertFalse(result.firstStaging);
        Assert.assertFalse(result.secondStaging);
        Assert.assertTrue(result.ledgerUnchanged);
        Assert.assertTrue(result.schedulerUnchanged);
        Assert.assertTrue(result.report.contains("date=2023-11-18"));
        Assert.assertFalse(result.report.contains("2026-11-15"));
    }

    @Test
    public void failedDownloadPreservesTheLedgerAndWritesADiagnosticReport() throws Exception {
        Path directory = fixture(
            Paths.get("target", "explicit-date-recovery-test"),
            "report",
            "UNKNOWN",
            "UNKNOWN"
        );
        byte[] before = Files.readAllBytes(directory.resolve("EURUSD_M1_3y.ledger.properties"));
        ExplicitRecoveryExercise result = DukascopyDownloader.exerciseExplicitRecovery(
            directory,
            "failed",
            "data"
        );
        Assert.assertArrayEquals(
            before,
            Files.readAllBytes(directory.resolve("EURUSD_M1_3y.ledger.properties"))
        );
        Assert.assertTrue(result.ledgerUnchanged);
        Assert.assertTrue(result.schedulerUnchanged);
        Assert.assertEquals("UNKNOWN", result.otherStatus);
        Assert.assertEquals(12, result.otherAttempts);
        Assert.assertFalse(result.otherStaging);
        Assert.assertTrue(result.report.contains("TRADEXA_RECOVERY_ATTEMPT_V1"));
        Assert.assertTrue(result.report.contains("reason=Request failed"));
        Assert.assertTrue(result.report.contains("ledger_unchanged=true"));
        Assert.assertFalse(result.report.contains("simulated"));
        Assert.assertFalse(result.report.toLowerCase().contains("password"));
        Assert.assertFalse(result.report.toLowerCase().contains("http"));
    }

    @Test
    public void validDaysSaveInOrderAndLeaveTheSchedulerUnchanged() throws Exception {
        Path directory = fixture(
            Paths.get("target", "explicit-date-recovery-test"),
            "saved-both",
            "UNKNOWN",
            "UNKNOWN"
        );
        ExplicitRecoveryExercise result = DukascopyDownloader.exerciseExplicitRecovery(
            directory,
            "data",
            "data"
        );
        Assert.assertEquals(2, result.requests);
        Assert.assertEquals("DATA_SAVED", result.firstStatus);
        Assert.assertEquals(13, result.firstAttempts);
        Assert.assertEquals(1440L, result.firstRows);
        Assert.assertEquals("DATA_SAVED", result.secondStatus);
        Assert.assertEquals(13, result.secondAttempts);
        Assert.assertEquals(1440L, result.secondRows);
        Assert.assertEquals("UNKNOWN", result.otherStatus);
        Assert.assertEquals(12, result.otherAttempts);
        Assert.assertTrue(result.firstStaging);
        Assert.assertTrue(result.secondStaging);
        Assert.assertFalse(result.otherStaging);
        Assert.assertTrue(result.schedulerUnchanged);
        Assert.assertEquals("", result.report);
        String firstCsv = new String(
            Files.readAllBytes(directory.resolve("EURUSD_M1_3y_staging").resolve("2023-11-18.csv")),
            StandardCharsets.UTF_8
        );
        Assert.assertTrue(firstCsv.startsWith("time_utc,open,high,low,close,volume\n"));
        Assert.assertTrue(firstCsv.contains("2023-11-18 00:00:00"));
        Assert.assertTrue(firstCsv.contains("2023-11-18 23:59:00"));
        Assert.assertEquals(1441, count(firstCsv, '\n'));
    }

    @Test
    public void secondDateIsRequestedOnlyAfterTheFirstDateIsSaved() throws Exception {
        Path directory = fixture(
            Paths.get("target", "explicit-date-recovery-test"),
            "second-empty",
            "UNKNOWN",
            "UNKNOWN"
        );
        ExplicitRecoveryExercise result = DukascopyDownloader.exerciseExplicitRecovery(
            directory,
            "data",
            "empty"
        );
        Assert.assertEquals(2, result.requests);
        Assert.assertEquals("DATA_SAVED", result.firstStatus);
        Assert.assertEquals(1440L, result.firstRows);
        Assert.assertEquals("UNKNOWN", result.secondStatus);
        Assert.assertEquals(12, result.secondAttempts);
        Assert.assertEquals(0L, result.secondRows);
        Assert.assertFalse(result.secondStaging);
        Assert.assertTrue(result.schedulerUnchanged);
        Assert.assertTrue(result.report.contains("date=2026-11-15"));
        Assert.assertTrue(result.report.contains("reason=Empty response"));
        Assert.assertTrue(result.report.contains("ledger_unchanged=true"));
    }

    @Test
    public void cachePathsMustStayInsideTheRecoveryDirectory() throws Exception {
        Path parent = Files.createTempDirectory("recovery-cache-check");
        try {
            Path cache = ExplicitRecoveryCache.create(parent, "20261119T041500Z");
            Assert.assertNull(ExplicitRecoveryCache.isolationProblem(
                cache,
                cache.toString() + "\\",
                cache.resolve("jftemp").toString()
            ));
            byte[] version = Files.readAllBytes(cache.resolve("version.txt"));
            Assert.assertEquals(1, version.length);
            Assert.assertEquals((byte) 0x35, version[0]);
            Assert.assertEquals(
                "forbidden-production-cache",
                ExplicitRecoveryCache.isolationProblem(
                    cache,
                    ExplicitRecoveryCache.PRODUCTION_CACHE,
                    cache.resolve("jftemp").toString()
                )
            );
            Assert.assertEquals(
                "forbidden-production-dataset",
                ExplicitRecoveryCache.isolationProblem(
                    cache,
                    parent.resolve("EURUSD_M1_3y_production").toString(),
                    cache.resolve("jftemp").toString()
                )
            );
            Assert.assertEquals(
                "forbidden-quarantine",
                ExplicitRecoveryCache.isolationProblem(
                    cache,
                    parent.resolve(ExplicitRecoveryCache.QUARANTINE_ONE).toString(),
                    cache.resolve("jftemp").toString()
                )
            );
            Assert.assertEquals(
                "forbidden-quarantine",
                ExplicitRecoveryCache.isolationProblem(
                    cache,
                    parent.resolve(ExplicitRecoveryCache.QUARANTINE_TWO).toString(),
                    cache.resolve("jftemp").toString()
                )
            );
        } finally {
            deleteRecursively(parent);
        }
    }

    @Test
    public void cacheIsRevalidatedBeforeTheRequest() throws Exception {
        Path directory = fixture(
            Paths.get("target", "explicit-date-recovery-test"),
            "revalidate",
            "UNKNOWN",
            "UNKNOWN"
        );
        ExplicitRecoveryExercise blocked = DukascopyDownloader.exerciseExplicitRecovery(
            directory,
            "data",
            "data",
            new RecoveryGate() {
                public void beforeRequest(String date) throws java.io.IOException {
                    throw new java.io.IOException("Cache isolation failed");
                }
            }
        );
        Assert.assertEquals(0, blocked.requests);
        Assert.assertTrue(blocked.ledgerUnchanged);
        Assert.assertTrue(blocked.schedulerUnchanged);
        Assert.assertFalse(blocked.firstStaging);
        Assert.assertTrue(blocked.report.contains("reason=Cache isolation failed"));
        Assert.assertFalse(blocked.report.contains("2026-11-15"));
    }

    @Test
    public void filePathManagerCacheIsIsolatedAndRestored() throws Exception {
        Path productionVersion = Paths.get(
            "C:\\Users\\Admin\\AppData\\Local\\JForex\\.cache\\version.txt"
        );
        byte[] productionBefore = Files.readAllBytes(productionVersion);
        IFilePathManager paths = FilePathManager.getInstance();
        String previousCache = paths.getCacheDirectory();
        String previousTemp = paths.getCacheTempDirectory();
        boolean previousAccess = paths.isFoldersAccessible();
        Path parent = Files.createTempDirectory("recovery-file-path-manager");
        try {
            Path cache = ExplicitRecoveryCache.create(parent, "20261119T041600Z");
            ExplicitRecoveryCache.configure(cache);
            String resolved = paths.getCacheDirectory();
            Assert.assertNull(ExplicitRecoveryCache.isolationProblem(
                cache,
                resolved,
                paths.getCacheTempDirectory()
            ));
            paths.setCacheFolderPath(ExplicitRecoveryCache.PRODUCTION_CACHE);
            try {
                ExplicitRecoveryCache.revalidate(cache);
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
        Assert.assertArrayEquals(productionBefore, Files.readAllBytes(productionVersion));
    }

    @Test
    public void creatingARecoveryCacheDoesNotDeleteANeighbor() throws Exception {
        Path parent = Files.createTempDirectory("recovery-neighbor");
        Path neighbor = parent.resolve(ExplicitRecoveryCache.QUARANTINE_ONE);
        Files.createDirectories(neighbor);
        Files.write(neighbor.resolve("keep.txt"), new byte[] {7});
        try {
            ExplicitRecoveryCache.create(neighbor, "20261119T041700Z");
            Assert.fail("Expected a quarantine path to be rejected");
        } catch (java.io.IOException ex) {
            Assert.assertEquals("Cache isolation failed", ex.getMessage());
        } finally {
            Assert.assertArrayEquals(
                new byte[] {7},
                Files.readAllBytes(neighbor.resolve("keep.txt"))
            );
            deleteRecursively(parent);
        }
    }

    @Test
    public void transportEvidenceDoesNotSaveAnEmptyOrPartialDay() throws Exception {
        Path directory = fixture(
            Paths.get("target", "explicit-date-recovery-test"),
            "transport-empty",
            "UNKNOWN",
            "UNKNOWN"
        );
        final RecoveryTransportLog log = RecoveryTransportLog.openForTests();
        ExplicitRecoveryExercise empty = DukascopyDownloader.exerciseExplicitRecovery(
            directory,
            "empty",
            "data",
            allow(),
            log,
            new RecoveryHttpProbe() {
                public void duringRequest(String date) throws IOException {
                    log.observe(http(200, new byte[0], "0", "", null));
                }
            }
        );
        Assert.assertEquals(1, empty.requests);
        Assert.assertEquals("UNKNOWN", empty.firstStatus);
        Assert.assertEquals(0L, empty.firstRows);
        Assert.assertFalse(empty.firstStaging);
        Assert.assertFalse(empty.secondStaging);
        Assert.assertTrue(empty.ledgerUnchanged);
        Assert.assertTrue(empty.schedulerUnchanged);
        Assert.assertTrue(empty.report.contains("reason=Empty response"));
        Assert.assertTrue(empty.report.contains("TRADEXA_RECOVERY_ATTEMPT_V1"));
        Assert.assertFalse(empty.report.contains("2026-11-15"));
        Assert.assertTrue(empty.transportReport.contains("\"2023-11-18\""));
        Assert.assertTrue(empty.transportReport.contains("\"200\""));
        Assert.assertTrue(empty.transportReport.contains("\"0\""));
        Assert.assertFalse(empty.transportReport.contains("2026-11-15"));
        Assert.assertFalse(log.isRecording());

        Path partialDirectory = fixture(
            Paths.get("target", "explicit-date-recovery-test"),
            "transport-partial",
            "UNKNOWN",
            "UNKNOWN"
        );
        final RecoveryTransportLog partialLog = RecoveryTransportLog.openForTests();
        ExplicitRecoveryExercise partial = DukascopyDownloader.exerciseExplicitRecovery(
            partialDirectory,
            "partial",
            "data",
            allow(),
            partialLog,
            new RecoveryHttpProbe() {
                public void duringRequest(String date) throws IOException {
                    partialLog.observe(http(200, new byte[] {1, 2, 3}, "3", "", null));
                }
            }
        );
        Assert.assertEquals(1, partial.requests);
        Assert.assertFalse(partial.firstStaging);
        Assert.assertTrue(partial.report.contains("reason=Partial response"));
        Assert.assertTrue(partial.transportReport.contains("\"200\""));
        Assert.assertTrue(partial.transportReport.contains("\"3\""));
        Assert.assertFalse(partial.transportReport.contains("2026-11-15"));
    }

    @Test
    public void positiveHttpBytesStillRequireFourteenFortyCandles() throws Exception {
        Path directory = fixture(
            Paths.get("target", "explicit-date-recovery-test"),
            "transport-saved",
            "UNKNOWN",
            "UNKNOWN"
        );
        final RecoveryTransportLog log = RecoveryTransportLog.openForTests();
        ExplicitRecoveryExercise saved = DukascopyDownloader.exerciseExplicitRecovery(
            directory,
            "data",
            "empty",
            allow(),
            log,
            new RecoveryHttpProbe() {
                public void duringRequest(String date) throws IOException {
                    if ("2023-11-18".equals(date)) {
                        log.observe(http(200, new byte[8], "8", "", null));
                    } else {
                        log.observe(http(
                            503,
                            new byte[0],
                            "0",
                            "45",
                            new IOException("503 https://secret.example/chunk")
                        ));
                    }
                }
            }
        );
        Assert.assertEquals(2, saved.requests);
        Assert.assertEquals("DATA_SAVED", saved.firstStatus);
        Assert.assertEquals(1440L, saved.firstRows);
        Assert.assertTrue(saved.firstStaging);
        Assert.assertEquals("UNKNOWN", saved.secondStatus);
        Assert.assertFalse(saved.secondStaging);
        Assert.assertTrue(saved.schedulerUnchanged);
        Assert.assertTrue(saved.report.contains("date=2026-11-15"));
        Assert.assertTrue(saved.report.contains("reason=Empty response"));
        Assert.assertFalse(saved.report.toLowerCase().contains("http"));
        Assert.assertFalse(saved.report.contains("secret.example"));
        Assert.assertTrue(saved.transportReport.contains("\"2023-11-18\""));
        Assert.assertTrue(saved.transportReport.contains("\"2026-11-15\""));
        Assert.assertTrue(saved.transportReport.contains("\"http-503\""));
        Assert.assertTrue(saved.transportReport.contains("\"45\""));
        Assert.assertFalse(saved.transportReport.contains("secret.example"));
        Assert.assertFalse(saved.transportReport.contains("https://"));
        Assert.assertFalse(log.isRecording());
    }

    @Test
    public void firstDateTransportFailureDoesNotRequestTheSecondDate() throws Exception {
        Path directory = fixture(
            Paths.get("target", "explicit-date-recovery-test"),
            "transport-stop",
            "UNKNOWN",
            "UNKNOWN"
        );
        final RecoveryTransportLog log = RecoveryTransportLog.openForTests();
        ExplicitRecoveryExercise stopped = DukascopyDownloader.exerciseExplicitRecovery(
            directory,
            "failed",
            "data",
            allow(),
            log,
            new RecoveryHttpProbe() {
                public void duringRequest(String date) throws IOException {
                    if ("2023-11-18".equals(date)) {
                        log.observe(thrown(new ConnectException(
                            "Connection timed out: getsockopt https://secret.example/a"
                        )));
                    } else {
                        log.observe(thrown(new SocketException("Connection reset")));
                    }
                }
            }
        );
        Assert.assertEquals(1, stopped.requests);
        Assert.assertEquals("UNKNOWN", stopped.firstStatus);
        Assert.assertEquals("UNKNOWN", stopped.secondStatus);
        Assert.assertFalse(stopped.firstStaging);
        Assert.assertFalse(stopped.secondStaging);
        Assert.assertTrue(stopped.ledgerUnchanged);
        Assert.assertTrue(stopped.report.contains("reason=Request failed"));
        Assert.assertFalse(stopped.report.contains("2026-11-15"));
        Assert.assertTrue(stopped.transportReport.contains("\"connect-timeout\""));
        Assert.assertFalse(stopped.transportReport.contains("\"connection-reset\""));
        Assert.assertFalse(stopped.transportReport.contains("2026-11-15"));
        Assert.assertFalse(stopped.transportReport.contains("secret.example"));
        Assert.assertFalse(log.isRecording());
    }

    @Test
    public void isolationFailureStillStopsBeforeEitherRequest() throws Exception {
        Path directory = fixture(
            Paths.get("target", "explicit-date-recovery-test"),
            "transport-isolation",
            "UNKNOWN",
            "UNKNOWN"
        );
        final RecoveryTransportLog log = RecoveryTransportLog.openForTests();
        ExplicitRecoveryExercise blocked = DukascopyDownloader.exerciseExplicitRecovery(
            directory,
            "data",
            "data",
            new RecoveryGate() {
                public void beforeRequest(String date) throws IOException {
                    throw new IOException("Cache isolation failed");
                }
            },
            log,
            new RecoveryHttpProbe() {
                public void duringRequest(String date) throws IOException {
                    log.observe(http(200, new byte[] {1}, "1", "", null));
                }
            }
        );
        Assert.assertEquals(0, blocked.requests);
        Assert.assertTrue(blocked.ledgerUnchanged);
        Assert.assertTrue(blocked.schedulerUnchanged);
        Assert.assertFalse(blocked.firstStaging);
        Assert.assertTrue(blocked.report.contains("reason=Cache isolation failed"));
        Assert.assertFalse(blocked.report.contains("2026-11-15"));
        Assert.assertFalse(blocked.transportReport.contains("\"200\""));
        Assert.assertFalse(blocked.transportReport.contains("2026-11-15"));
        Assert.assertFalse(log.isRecording());
    }

    @Test
    public void observerInstallationFailureDoesNotPermitAConnection() {
        Assert.assertFalse(DukascopyDownloader.recoveryMayConnect(null, true));
        Assert.assertTrue(DukascopyDownloader.recoveryMayConnect(
            RecoveryTransportLog.openForTests(),
            true
        ));
        Assert.assertTrue(DukascopyDownloader.recoveryMayConnect(null, false));
        Assert.assertTrue(RecoveryTransportLog.isFactoryAlreadyDefined(
            new Error("factory already defined")
        ));
    }

    @Test
    public void normalRetryAttemptCountRemainsThree() throws Exception {
        Path parent = Paths.get("target", "explicit-date-recovery-test");
        Files.createDirectories(parent);
        Path defaults = Files.createTempDirectory(parent, "defaults");
        Assert.assertEquals(3, DukascopyDownloader.defaultEmptyAttemptCount(defaults));
    }

    private static void assertRejectedOutcome(
            String outcome,
            String reason,
            int candles) throws Exception {
        Path directory = fixture(
            Paths.get("target", "explicit-date-recovery-test"),
            outcome,
            "UNKNOWN",
            "UNKNOWN"
        );
        ExplicitRecoveryExercise result = DukascopyDownloader.exerciseExplicitRecovery(
            directory,
            outcome,
            "data"
        );
        Assert.assertEquals(1, result.requests);
        Assert.assertEquals("UNKNOWN", result.firstStatus);
        Assert.assertEquals(12, result.firstAttempts);
        Assert.assertEquals(0L, result.firstRows);
        Assert.assertFalse(result.firstStaging);
        Assert.assertFalse(result.secondStaging);
        Assert.assertTrue(result.ledgerUnchanged);
        Assert.assertTrue(result.schedulerUnchanged);
        Assert.assertTrue(result.report.contains("reason=" + reason));
        Assert.assertTrue(result.report.contains("candles=" + candles));
        Assert.assertTrue(result.report.contains("ledger_unchanged=true"));
    }

    private static void assertRejected(Path directory, String message) {
        try {
            DukascopyDownloader.prepareExplicitDateRecovery(directory);
            Assert.fail("Expected rejection");
        } catch (Exception ex) {
            Assert.assertEquals(message, ex.getMessage());
        }
    }

    private static Path fixture(
            Path parent,
            String name,
            String firstStatus,
            String secondStatus) throws Exception {
        Files.createDirectories(parent);
        Path directory = Files.createTempDirectory(parent, name);
        StringBuilder ledger = new StringBuilder();
        ledger.append("windowStart=")
            .append(DukascopyDownloader.FIXED_PRODUCTION_WINDOW_START)
            .append('\n');
        ledger.append("windowEnd=")
            .append(DukascopyDownloader.FIXED_PRODUCTION_WINDOW_END)
            .append('\n');
        ledger.append("schemaVersion=2\n");
        ledger.append(day("2023-11-18", firstStatus));
        ledger.append(day("2023-11-19", "UNKNOWN"));
        ledger.append(day("2026-11-15", secondStatus));
        Files.write(
            directory.resolve("EURUSD_M1_3y.ledger.properties"),
            ledger.toString().getBytes(StandardCharsets.UTF_8)
        );
        Files.write(
            directory.resolve("EURUSD_M1_3y.scheduler.properties"),
            scheduler(false).getBytes(StandardCharsets.UTF_8)
        );
        Files.createDirectories(directory.resolve("EURUSD_M1_3y_staging"));
        return directory;
    }

    private static String scheduler(boolean mismatch) {
        long windowEnd = DukascopyDownloader.FIXED_PRODUCTION_WINDOW_END
            + (mismatch ? 60000L : 0L);
        return "schemaVersion=1\n"
            + "windowStart=" + DukascopyDownloader.FIXED_PRODUCTION_WINDOW_START + "\n"
            + "windowEnd=" + windowEnd + "\n"
            + "nextDay=1759536000000\n";
    }

    private static String day(String date, String status) {
        long first = "2026-11-15".equals(date) ? 1794700800000L : 1700265600000L;
        if ("DATA_SAVED".equals(status)) {
            return "day." + date + ".status=DATA_SAVED\n"
                + "day." + date + ".attempts=12\n"
                + "day." + date + ".lastAttempt=1000\n"
                + "day." + date + ".rowCount=1\n"
                + "day." + date + ".firstCandle=" + first + "\n"
                + "day." + date + ".lastCandle=" + first + "\n"
                + "day." + date + ".detail=saved\n"
                + "day." + date + ".sha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n"
                + "day." + date + ".confirmAt=-1\n"
                + "day." + date + ".confirmReason=\n"
                + "day." + date + ".confirmMethod=\n";
        }
        if ("CONFIRMED_EMPTY".equals(status)) {
            return "day." + date + ".status=CONFIRMED_EMPTY\n"
                + "day." + date + ".attempts=12\n"
                + "day." + date + ".lastAttempt=1000\n"
                + "day." + date + ".rowCount=0\n"
                + "day." + date + ".firstCandle=-1\n"
                + "day." + date + ".lastCandle=-1\n"
                + "day." + date + ".detail=empty\n"
                + "day." + date + ".sha256=\n"
                + "day." + date + ".confirmAt=1000\n"
                + "day." + date + ".confirmReason=Verified market closure using an authoritative trading calendar\n"
                + "day." + date + ".confirmMethod=explicit-cli\n";
        }
        return "day." + date + ".status=UNKNOWN\n"
            + "day." + date + ".attempts=12\n"
            + "day." + date + ".lastAttempt=1000\n"
            + "day." + date + ".rowCount=0\n"
            + "day." + date + ".firstCandle=-1\n"
            + "day." + date + ".lastCandle=-1\n"
            + "day." + date + ".detail=unknown\n"
            + "day." + date + ".sha256=\n"
            + "day." + date + ".confirmAt=-1\n"
            + "day." + date + ".confirmReason=\n"
            + "day." + date + ".confirmMethod=\n";
    }

    private static int count(String text, char value) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == value) {
                count++;
            }
        }
        return count;
    }

    private static RecoveryGate allow() {
        return new RecoveryGate() {
            public void beforeRequest(String date) {
            }
        };
    }

    private static HttpURLConnection http(
            int code,
            byte[] body,
            String contentLength,
            String retryAfter,
            IOException streamFailure) throws IOException {
        ScriptedConnection connection = new ScriptedConnection();
        connection.code = code;
        connection.body = body;
        connection.contentLength = contentLength;
        connection.retryAfter = retryAfter;
        connection.streamFailure = streamFailure;
        return connection;
    }

    private static HttpURLConnection thrown(IOException failure) throws IOException {
        ScriptedConnection connection = new ScriptedConnection();
        connection.responseFailure = failure;
        return connection;
    }

    private static final class ScriptedConnection extends HttpURLConnection {
        private int code = 200;
        private byte[] body = new byte[0];
        private String contentLength = "";
        private String retryAfter = "";
        private IOException responseFailure;
        private IOException streamFailure;

        private ScriptedConnection() throws IOException {
            super(new URL("http://127.0.0.1/unused"));
        }

        public void connect() {
        }

        public void disconnect() {
        }

        public boolean usingProxy() {
            return false;
        }

        public int getResponseCode() throws IOException {
            if (responseFailure != null) {
                throw responseFailure;
            }
            return code;
        }

        public String getHeaderField(String name) {
            if ("Content-Length".equals(name)) {
                return contentLength;
            }
            if ("Retry-After".equals(name)) {
                return retryAfter;
            }
            if ("Authorization".equals(name) || "Cookie".equals(name) || "Set-Cookie".equals(name)) {
                return "secret-token";
            }
            return null;
        }

        public Map<String, List<String>> getHeaderFields() {
            throw new AssertionError("getHeaderFields must not be called");
        }

        public InputStream getInputStream() throws IOException {
            if (streamFailure != null) {
                throw streamFailure;
            }
            return new ByteArrayInputStream(body);
        }

        public OutputStream getOutputStream() {
            return new OutputStream() {
                public void write(int value) {
                }
            };
        }
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
