package com.tradexa.dukascopy.nativehist;

import com.dukascopy.api.Filter;
import com.dukascopy.charts.data.datacache.CandleData;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IContext;
import com.dukascopy.api.IHistory;
import com.dukascopy.api.IMessage;
import com.dukascopy.api.IStrategy;
import com.dukascopy.api.ITick;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.JFException;
import com.dukascopy.api.OfferSide;
import com.dukascopy.api.system.ClientFactory;
import com.dukascopy.api.system.IClient;
import com.dukascopy.api.system.ISystemListener;
import com.tradexa.dukascopy.diagnostic.NativeTransportLog;
import com.tradexa.dukascopy.nativehist.NativeDownloadEngine.Report;
import com.tradexa.dukascopy.nativehist.NativeDownloadEngine.Request;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One resumable session that downloads native EURUSD BID M5, M15, M30,
 * H1, H4, and D1 candles. It does not write the verified M1 dataset.
 * D1 volume is copied from the server bar and is not replaced by an
 * M1 or H1 sum.
 */
public final class NativeHistoryDownloader {
    private static final String JNLP_URL =
        "http://platform.dukascopy.com/demo_3/jforex_3.jnlp";
    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private NativeHistoryDownloader() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "retry-m5-2024-01-14".equals(args[0])) {
            retrySundayM5();
            return;
        }
        if (args.length != 1 || !"download-native-3y".equals(args[0])) {
            System.out.println("FAIL: Usage: download-native-3y");
            System.exit(2);
            return;
        }
        Path dataset = Paths.get("output", "EURUSD_native_timeframes_3y")
            .toAbsolutePath()
            .normalize();
        Path m1 = Paths.get("output", "EURUSD_M1_3y_production", "EURUSD_M1_3y_staging")
            .toAbsolutePath()
            .normalize();
        if (!Files.isDirectory(m1)) {
            System.out.println("FAIL: Verified M1 staging is missing");
            System.exit(2);
            return;
        }
        Files.createDirectories(dataset);
        Path cache;
        try {
            cache = openCache(dataset);
            NativeHistoryCache.configure(cache);
        } catch (IOException ex) {
            System.out.println("FAIL: " + NativeHistoryCache.ISOLATION_FAILED);
            System.exit(2);
            return;
        }
        String username = System.getenv("JFOREX_USERNAME");
        String password = System.getenv("JFOREX_PASSWORD");
        if (username == null || username.trim().isEmpty()
                || password == null || password.isEmpty()) {
            System.out.println("FAIL: Credentials not found.");
            System.exit(2);
            return;
        }
        final NativeTransportLog transport;
        try {
            transport = NativeTransportLog.install();
        } catch (IOException ex) {
            System.out.println("FAIL: " + ex.getMessage());
            System.exit(2);
            return;
        }
        final Path cacheRoot = cache;
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final CountDownLatch stopped = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        final AtomicInteger exitCode = new AtomicInteger(1);
        final long[] strategyId = new long[] {-1L};
        final IClient client = ClientFactory.getDefaultInstance();
        client.setSystemListener(new ISystemListener() {
            public void onStart(long processId) {
            }

            public void onStop(long processId) {
                stopped.countDown();
            }

            public void onConnect() {
                System.out.println("Connected to Dukascopy!");
                connected.countDown();
            }

            public void onDisconnect() {
                System.out.println("Disconnected.");
                disconnected.countDown();
            }
        });
        try {
            System.out.println("Connecting to Dukascopy Demo...");
            client.connect(JNLP_URL, username, password);
            if (!connected.await(90, TimeUnit.SECONDS) || !client.isConnected()) {
                System.out.println("FAIL: Connection timeout.");
                exitCode.set(1);
                return;
            }
            try {
                NativeHistoryCache.revalidate(cacheRoot);
            } catch (IOException ex) {
                System.out.println("FAIL: " + NativeHistoryCache.ISOLATION_FAILED);
                exitCode.set(1);
                return;
            }
            client.setSubscribedInstruments(Collections.singleton(Instrument.EURUSD));
            strategyId[0] = client.startStrategy(new IStrategy() {
                public void onStart(IContext context) {
                    try {
                        Request request = new Request();
                        request.datasetRoot = dataset;
                        request.history = new JForexHistory(context.getHistory());
                        request.minutes = new M1StagingSource(m1);
                        request.guard = new NativeDownloadEngine.Guard() {
                            public void beforeRequest() throws IOException {
                                NativeHistoryCache.revalidate(cacheRoot);
                            }
                        };
                        request.pause = new NativeDownloadEngine.Pause() {
                            public void pause(long millis) throws InterruptedException {
                                Thread.sleep(millis);
                            }
                        };
                        request.cleanup = new NativeDownloadEngine.RetryCleanup() {
                            public void beforeRetry() throws IOException {
                                NativeHistoryCache.clearZeroByteChunks(cacheRoot);
                            }
                        };
                        request.transport = transport;
                        request.windowStart = NativeWindow.START_MILLIS;
                        request.windowEnd = NativeWindow.END_MILLIS;
                        request.referenceDays = NativeReferencePlan.productionDays();
                        request.m1IntegrityPassed = NativeReadiness.latestM1AuditPasses(
                            dataset.getParent()
                        );
                        Report report = NativeDownloadEngine.run(request);
                        exitCode.set(report.ready ? 0 : 2);
                    } catch (Exception ex) {
                        System.out.println("FAIL: " + ex.getClass().getSimpleName());
                        exitCode.set(1);
                    } finally {
                        finished.countDown();
                    }
                }

                public void onTick(Instrument instrument, ITick tick) {
                }

                public void onBar(Instrument instrument, com.dukascopy.api.Period period, IBar askBar, IBar bidBar) {
                }

                public void onMessage(IMessage message) {
                }

                public void onAccount(com.dukascopy.api.IAccount account) {
                }

                public void onStop() {
                }
            });
            if (!finished.await(12, TimeUnit.HOURS)) {
                System.out.println("FAIL: Download timed out.");
                exitCode.set(1);
            }
        } finally {
            try {
                transport.cleanup();
                transport.writeIfAbsent(dataset.resolve(NativeTransportLog.REPORT_NAME));
            } catch (IOException ex) {
                System.out.println("FAIL: HTTP transport report failed.");
            }
            shutdown(client, strategyId[0], stopped, disconnected, exitCode);
        }
    }

    private static void retrySundayM5() throws Exception {
        final String date = "2024-01-14";
        Path cache = Paths.get("output", "EURUSD_native_history_cache_20261119T061034Z")
            .toAbsolutePath()
            .normalize();
        Path m1File = cache.resolve("EURUSD").resolve("2024").resolve("00").resolve("14")
            .resolve("BID_candles_min_1.bi5");
        Path staging = Paths.get("output", "EURUSD_M1_3y_production", "EURUSD_M1_3y_staging")
            .toAbsolutePath()
            .normalize();
        Path reportDir = Paths.get(
            "output",
            "EURUSD_native_m5_retry_" + STAMP.format(Instant.now())
        ).toAbsolutePath().normalize();
        if (!Files.isRegularFile(m1File) || !Files.isDirectory(staging)) {
            System.out.println("FAIL: Sunday M1 cache or verified staging is missing");
            System.exit(2);
            return;
        }
        byte[] before = Files.readAllBytes(m1File);
        final String beforeHash = NativeCsv.sha256(before);
        final int beforeBars = decodedBars(before, NativeGrid.dateStart(date));
        Files.createDirectories(reportDir);
        try {
            NativeHistoryCache.configure(cache);
        } catch (IOException ex) {
            System.out.println("FAIL: " + NativeHistoryCache.ISOLATION_FAILED);
            System.exit(2);
            return;
        }
        String username = System.getenv("JFOREX_USERNAME");
        String password = System.getenv("JFOREX_PASSWORD");
        if (username == null || username.trim().isEmpty()
                || password == null || password.isEmpty()) {
            System.out.println("FAIL: Credentials not found.");
            System.exit(2);
            return;
        }
        final NativeTransportLog transport;
        try {
            transport = NativeTransportLog.install();
        } catch (IOException ex) {
            System.out.println("FAIL: " + ex.getMessage());
            System.exit(2);
            return;
        }
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final CountDownLatch stopped = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        final AtomicInteger exitCode = new AtomicInteger(2);
        final long[] strategyId = new long[] {-1L};
        final StringBuilder summary = new StringBuilder();
        final IClient client = ClientFactory.getDefaultInstance();
        client.setSystemListener(new ISystemListener() {
            public void onStart(long processId) {
            }

            public void onStop(long processId) {
                stopped.countDown();
            }

            public void onConnect() {
                System.out.println("Connected to Dukascopy!");
                connected.countDown();
            }

            public void onDisconnect() {
                System.out.println("Disconnected.");
                disconnected.countDown();
            }
        });
        try {
            System.out.println("Connecting to Dukascopy Demo...");
            client.connect(JNLP_URL, username, password);
            if (!connected.await(90, TimeUnit.SECONDS) || !client.isConnected()) {
                System.out.println("FAIL: Connection timeout.");
                exitCode.set(1);
                return;
            }
            try {
                NativeHistoryCache.revalidate(cache);
            } catch (IOException ex) {
                System.out.println("FAIL: " + NativeHistoryCache.ISOLATION_FAILED);
                exitCode.set(1);
                return;
            }
            client.setSubscribedInstruments(Collections.singleton(Instrument.EURUSD));
            strategyId[0] = client.startStrategy(new IStrategy() {
                public void onStart(IContext context) {
                    try {
                        JForexHistory history = new JForexHistory(context.getHistory());
                        long day = NativeGrid.dateStart(date);
                        long from = history.barStart(NativeTimeframe.M5, day);
                        long to = history.barStart(NativeTimeframe.M5, day + 86400000L - 60000L);
                        final NativeBarClock clock = new NativeBarClock() {
                            public long barStart(NativeTimeframe timeframe, long timeMillis) {
                                try {
                                    return history.barStart(timeframe, timeMillis);
                                } catch (IOException ex) {
                                    throw new IllegalStateException("Native bar clock failed");
                                }
                            }

                            public long nextBarStart(NativeTimeframe timeframe, long barTimeMillis) {
                                try {
                                    return history.nextBarStart(timeframe, barTimeMillis);
                                } catch (IOException ex) {
                                    throw new IllegalStateException("Native bar clock failed");
                                }
                            }
                        };
                        List<Long> expected = NativeGrid.barsOnDate(
                            clock,
                            NativeTimeframe.M5,
                            date,
                            day,
                            day + 86400000L - 60000L
                        );
                        List<NativeCandle> bars = new ArrayList<NativeCandle>();
                        String failure = "Request failed";
                        int usedAttempt = 0;
                        for (int attempt = 1; attempt <= 3; attempt++) {
                            usedAttempt = attempt;
                            if (attempt > 1) {
                                Thread.sleep(attempt == 2 ? 2000L : 5000L);
                            }
                            NativeHistoryCache.revalidate(cache);
                            transport.begin(NativeTimeframe.M5.name(), date);
                            try {
                                bars = history.request(NativeTimeframe.M5, from, to);
                                failure = null;
                            } catch (IOException ex) {
                                bars = new ArrayList<NativeCandle>();
                                failure = "Request failed";
                            } finally {
                                transport.end(NativeTimeframe.M5.name(), date);
                            }
                            if (bars.size() == expected.size() && sameTimes(bars, expected)) {
                                failure = null;
                                break;
                            }
                            failure = bars.isEmpty()
                                ? "Empty response is not a confirmed market closure"
                                : "Partial native response";
                        }
                        byte[] after = Files.readAllBytes(m1File);
                        String afterHash = NativeCsv.sha256(after);
                        int afterBars = decodedBars(after, day);
                        boolean intact = beforeHash.equals(afterHash)
                            && before.length == after.length
                            && beforeBars == 1440
                            && afterBars == 1440;
                        summary.append("date=").append(date).append('\n');
                        summary.append("timeframe=M5\n");
                        summary.append("attempts=").append(usedAttempt).append('\n');
                        summary.append("returned=").append(bars.size()).append('\n');
                        summary.append("expected=").append(expected.size()).append('\n');
                        summary.append("request_from=").append(NativeGrid.formatStamp(from)).append('\n');
                        summary.append("request_to=").append(NativeGrid.formatStamp(to)).append('\n');
                        summary.append("failure=").append(failure == null ? "" : failure).append('\n');
                        summary.append("m1_bi5_bytes=").append(before.length).append('\n');
                        summary.append("m1_bi5_sha256_before=").append(beforeHash).append('\n');
                        summary.append("m1_bi5_sha256_after=").append(afterHash).append('\n');
                        summary.append("m1_decoded_before=").append(beforeBars).append('\n');
                        summary.append("m1_decoded_after=").append(afterBars).append('\n');
                        summary.append("m1_cache_intact=").append(intact).append('\n');
                        summary.append("transport=\n").append(transport.reportText());
                        if (!intact) {
                            summary.append("result=FAIL\n");
                            System.out.println("M5 retry cache integrity failed");
                            exitCode.set(2);
                            return;
                        }
                        if (failure != null) {
                            summary.append("result=FAIL\n");
                            System.out.println("M5 retry failed: " + failure);
                            exitCode.set(2);
                            return;
                        }
                        List<NativeCandle> minutes = NativeCsv.parse(
                            Files.readAllBytes(staging.resolve(date + ".csv"))
                        );
                        List<com.tradexa.dukascopy.nativehist.NativeReferenceComparator.Finding> findings =
                            NativeReferenceComparator.compare(
                                NativeTimeframe.M5,
                                "SUNDAY_OPEN",
                                date,
                                clock,
                                NativeWindow.START_MILLIS,
                                NativeWindow.END_MILLIS,
                                minutes,
                                bars
                            );
                        int exact = 0;
                        int other = 0;
                        for (int i = 0; i < findings.size(); i++) {
                            if (NativeReferenceComparator.EXACT.equals(findings.get(i).classification)) {
                                exact++;
                            } else {
                                other++;
                            }
                        }
                        summary.append("exact=").append(exact).append('\n');
                        summary.append("other=").append(other).append('\n');
                        summary.append("result=").append(other == 0 ? "PASS" : "FAIL").append('\n');
                        NativeCsv.atomicWrite(reportDir.resolve("m5-2024-01-14.csv"), NativeCsv.bytes(bars));
                        System.out.println(
                            "M5 retry date=" + date + " returned=" + bars.size()
                            + " exact=" + exact + " other=" + other
                        );
                        exitCode.set(other == 0 ? 0 : 2);
                    } catch (Exception ex) {
                        summary.append("result=FAIL\n");
                        System.out.println("FAIL: " + ex.getClass().getSimpleName());
                        exitCode.set(1);
                    } finally {
                        try {
                            M1StagingSource.writeText(reportDir.resolve("retry-summary.txt"), summary.toString());
                        } catch (IOException ex) {
                            System.out.println("FAIL: Retry report failed.");
                        }
                        finished.countDown();
                    }
                }

                public void onTick(Instrument instrument, ITick tick) {
                }

                public void onBar(Instrument instrument, com.dukascopy.api.Period period, IBar askBar, IBar bidBar) {
                }

                public void onMessage(IMessage message) {
                }

                public void onAccount(com.dukascopy.api.IAccount account) {
                }

                public void onStop() {
                }
            });
            if (!finished.await(10, TimeUnit.MINUTES)) {
                System.out.println("FAIL: M5 retry timed out.");
                exitCode.set(1);
            }
        } finally {
            try {
                transport.cleanup();
                transport.write(reportDir.resolve(NativeTransportLog.REPORT_NAME));
            } catch (IOException ex) {
                System.out.println("FAIL: HTTP transport report failed.");
            }
            shutdown(client, strategyId[0], stopped, disconnected, exitCode);
        }
    }

    private static boolean sameTimes(List<NativeCandle> bars, List<Long> expected) {
        if (bars.size() != expected.size()) {
            return false;
        }
        for (int i = 0; i < bars.size(); i++) {
            if (bars.get(i).timeMillis != expected.get(i).longValue()) {
                return false;
            }
        }
        return true;
    }

    private static int decodedBars(byte[] data, long chunk) {
        int count = 0;
        for (int offset = 0; offset + 24 <= data.length; offset += 24) {
            CandleData candle = new CandleData();
            candle.fromBytes(5, chunk, 0.0001, data, offset);
            if (candle.getTime() != Long.MIN_VALUE) {
                count++;
            }
        }
        return count;
    }

    private static Path openCache(Path dataset) throws IOException {
        Path session = dataset.resolve("session.properties");
        if (Files.isRegularFile(session)) {
            String text = new String(Files.readAllBytes(session), StandardCharsets.UTF_8);
            String cacheText = value(text, "cacheDirectory");
            if (!text.contains("windowStart=" + NativeWindow.START_MILLIS)
                    || !text.contains("windowEnd=" + NativeWindow.END_MILLIS)
                    || cacheText == null) {
                throw new IOException(NativeHistoryCache.ISOLATION_FAILED);
            }
            return Paths.get(cacheText);
        }
        String stamp = STAMP.format(Instant.now());
        Path cache = NativeHistoryCache.create(Paths.get("output"), stamp);
        String body = "schemaVersion=1\n"
            + "windowStart=" + NativeWindow.START_MILLIS + "\n"
            + "windowEnd=" + NativeWindow.END_MILLIS + "\n"
            + "batchSize=100\n"
            + "cacheDirectory=" + cache.toAbsolutePath().normalize() + "\n";
        Files.write(session, body.getBytes(StandardCharsets.UTF_8));
        return cache;
    }

    private static String value(String text, String key) {
        String prefix = key + "=";
        int start = text.indexOf(prefix);
        if (start < 0) {
            return null;
        }
        int end = text.indexOf('\n', start);
        if (end < 0) {
            end = text.length();
        }
        return text.substring(start + prefix.length(), end).trim();
    }

    private static void shutdown(
            IClient client,
            long strategyId,
            CountDownLatch stopped,
            CountDownLatch disconnected,
            AtomicInteger exitCode) {
        try {
            if (strategyId >= 0L) {
                client.stopStrategy(strategyId);
                stopped.await(15, TimeUnit.SECONDS);
            }
        } catch (Exception ex) {
            System.out.println("Strategy stop error: " + ex.getClass().getSimpleName());
        }
        Thread closer = new Thread(new Runnable() {
            public void run() {
                try {
                    client.disconnect();
                } catch (Throwable error) {
                    System.out.println("Disconnect error: " + error.getClass().getSimpleName());
                }
            }
        }, "native-history-disconnect");
        closer.setDaemon(true);
        closer.start();
        try {
            disconnected.await(15, TimeUnit.SECONDS);
            closer.join(5000L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        List<String> blockers = new ArrayList<String>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && !thread.isDaemon() && thread != Thread.currentThread()) {
                blockers.add(thread.getName());
            }
        }
        if (!blockers.isEmpty()) {
            System.out.println("JForex left non-daemon threads after disconnect: " + blockers);
            System.exit(exitCode.get());
        }
    }

    private static final class JForexHistory implements NativeDownloadEngine.History {
        private final IHistory history;

        private JForexHistory(IHistory history) {
            this.history = history;
        }

        public long barStart(NativeTimeframe timeframe, long timeMillis) throws IOException {
            try {
                return history.getBarStart(timeframe.period(), timeMillis);
            } catch (JFException ex) {
                throw new IOException("Request failed");
            }
        }

        public long nextBarStart(NativeTimeframe timeframe, long barTimeMillis) throws IOException {
            try {
                return history.getNextBarStart(timeframe.period(), barTimeMillis);
            } catch (JFException ex) {
                throw new IOException("Request failed");
            }
        }

        public List<NativeCandle> request(
                NativeTimeframe timeframe,
                long fromInclusive,
                long toInclusive) throws IOException {
            List<IBar> bars;
            try {
                bars = history.getBars(
                    Instrument.EURUSD,
                    timeframe.period(),
                    OfferSide.BID,
                    Filter.NO_FILTER,
                    fromInclusive,
                    toInclusive
                );
            } catch (JFException ex) {
                throw new IOException("Request failed");
            }
            if (bars == null) {
                throw new IOException("Request failed");
            }
            List<NativeCandle> candles = new ArrayList<NativeCandle>();
            for (int i = 0; i < bars.size(); i++) {
                IBar bar = bars.get(i);
                if (bar == null) {
                    throw new IOException("Request failed");
                }
                // Source volume, including D1. It is not replaced by an M1 or H1 sum.
                candles.add(new NativeCandle(
                    bar.getTime(),
                    bar.getOpen(),
                    bar.getHigh(),
                    bar.getLow(),
                    bar.getClose(),
                    bar.getVolume()
                ));
            }
            return candles;
        }
    }
}
