package com.tradexa.dukascopy;

import com.dukascopy.api.*;
import com.dukascopy.api.system.ClientFactory;
import com.dukascopy.api.system.IClient;
import com.dukascopy.api.system.ISystemListener;
import com.tradexa.dukascopy.diagnostic.RecoveryTransportLog;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class DukascopyDownloader {

    private static final String JNLP_URL =
        "http://platform.dukascopy.com/demo_3/jforex_3.jnlp";

    private static final Instrument INSTRUMENT = Instrument.EURUSD;
    private static final Period PERIOD = Period.ONE_MIN;
    private static final String CSV_HEADER =
        "time_utc,open,high,low,close,volume";

    private static final SimpleDateFormat UTC_FORMAT =
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'");
    private static final SimpleDateFormat CSV_UTC_FORMAT =
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    static {
        UTC_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
        CSV_UTC_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) throws Exception {

        if (args.length > 0 && "plan-test".equals(args[0])) {
            if (!runPlannerSelfTest()) {
                throw new IllegalStateException("Planner self-test failed.");
            }
            return;
        }

        if (args.length > 0 && "storage-test".equals(args[0])) {
            if (!runStorageSelfTest()) {
                throw new IllegalStateException("Storage self-test failed.");
            }
            return;
        }

        if (args.length > 0 && "failure-test".equals(args[0])) {
            if (!runFailureClassificationTest()) {
                throw new IllegalStateException(
                    "Failure-classification test failed."
                );
            }
            return;
        }

        if (args.length > 0 && "ledger-test".equals(args[0])) {
            if (!runLedgerSelfTest()) {
                throw new IllegalStateException("Ledger self-test failed.");
            }
            return;
        }

        if (args.length > 0 && "staging-test".equals(args[0])) {
            if (!runStagingSelfTest()) {
                throw new IllegalStateException("Staging self-test failed.");
            }
            return;
        }

        if (args.length > 0 && "retry-test".equals(args[0])) {
            if (!runRetrySelfTest()) {
                throw new IllegalStateException("Retry self-test failed.");
            }
            return;
        }

        if (args.length > 0 && "merge-test".equals(args[0])) {
            if (!runMergeSelfTest()) {
                throw new IllegalStateException("Merge self-test failed.");
            }
            return;
        }

        if (args.length > 0 && "confirmation-test".equals(args[0])) {
            if (!runConfirmationSelfTest()) {
                throw new IllegalStateException("Confirmation self-test failed.");
            }
            return;
        }

        if (args.length > 0 && "confirm-empty".equals(args[0])) {
            if (args.length > 1 && "--production".equals(args[1])) {
                runProductionConfirmCommand(args);
            } else {
                runConfirmEmptyCommand(args);
            }
            return;
        }

        if (args.length > 0 && "merge-3y".equals(args[0])) {
            if (args.length != 1) {
                System.out.println("FAIL: Usage: merge-3y");
                return;
            }
            runMergeProduction();
            return;
        }

        if (args.length > 0 && "production-test".equals(args[0])) {
            if (!runProductionSelfTest()) {
                throw new IllegalStateException("Production self-test failed.");
            }
            return;
        }

        if (args.length > 0 && "plan".equals(args[0])) {
            if (args.length < 3) {
                System.out.println(
                    "FAIL: Usage: plan yyyy-MM-dd HH:mm:ss"
                );
                return;
            }
            StringBuilder timestamp = new StringBuilder(args[1]);
            for (int i = 2; i < args.length; i++) {
                timestamp.append(' ').append(args[i]);
            }
            printThreeYearPlan(parseUtcMinute(timestamp.toString()));
            return;
        }

        final Long downloadDayStart;
        if (args.length > 0 && "download-day".equals(args[0])) {
            if (args.length != 2) {
                System.out.println("FAIL: Usage: download-day yyyy-MM-dd");
                return;
            }
            try {
                downloadDayStart = Long.valueOf(parseUtcDate(args[1]));
            } catch (ParseException ex) {
                System.out.println("FAIL: Invalid UTC date: " + args[1]);
                return;
            }
        } else {
            downloadDayStart = null;
        }

        final RangeRequest downloadRange;
        if (args.length > 0 && "download-range".equals(args[0])) {
            if (args.length < 3 || args.length > 4) {
                System.out.println(
                    "FAIL: Usage: download-range yyyy-MM-dd yyyy-MM-dd [max-days]"
                );
                return;
            }
            try {
                long rangeStart = parseUtcDate(args[1]);
                long rangeEndDay = parseUtcDate(args[2]);
                if (rangeStart > rangeEndDay) {
                    System.out.println(
                        "FAIL: Range start is after the range end."
                    );
                    return;
                }
                int maxDays = 0;
                if (args.length == 4) {
                    if (!args[3].matches("\\d+")) {
                        System.out.println(
                            "FAIL: max-days must be a positive integer."
                        );
                        return;
                    }
                    maxDays = Integer.parseInt(args[3]);
                    if (maxDays <= 0) {
                        System.out.println(
                            "FAIL: max-days must be a positive integer."
                        );
                        return;
                    }
                }
                downloadRange = new RangeRequest(
                    rangeStart,
                    endOfUtcMinuteDay(rangeEndDay),
                    maxDays
                );
            } catch (ParseException ex) {
                System.out.println(
                    "FAIL: Invalid UTC date in download-range."
                );
                return;
            }
        } else {
            downloadRange = null;
        }

        final Integer productionMaxDays;
        if (args.length > 0 && "download-3y".equals(args[0])) {
            if (args.length > 2) {
                System.out.println(
                    "FAIL: Usage: download-3y [MAX_DAYS_PER_RUN]"
                );
                return;
            }
            int maxDays = PRODUCTION_DEFAULT_MAX_DAYS;
            if (args.length == 2) {
                if (!args[1].matches("\\d+")) {
                    System.out.println(
                        "FAIL: max-days must be a positive integer."
                    );
                    return;
                }
                maxDays = Integer.parseInt(args[1]);
                if (maxDays <= 0) {
                    System.out.println(
                        "FAIL: max-days must be a positive integer."
                    );
                    return;
                }
            }
            productionMaxDays = Integer.valueOf(maxDays);
        } else {
            productionMaxDays = null;
        }

        final boolean explicitDateRecovery;
        if (args.length > 0 && EXPLICIT_RECOVERY_COMMAND.equals(args[0])) {
            if (!acceptsExplicitDateRecovery(args)) {
                System.out.println(
                    "FAIL: Usage: download-3y-dates 2023-11-18 2026-11-15"
                );
                return;
            }
            try {
                prepareExplicitDateRecovery(PRODUCTION_DIRECTORY);
                explicitRecoveryCache = ExplicitRecoveryCache.create(
                    Paths.get("output"),
                    recoveryStamp()
                );
                ExplicitRecoveryCache.configure(explicitRecoveryCache);
                explicitRecoveryTransport = RecoveryTransportLog.installForRecovery();
                if (!recoveryMayConnect(explicitRecoveryTransport, true)) {
                    System.out.println("FAIL: " + RecoveryTransportLog.INSTALL_FAILED);
                    return;
                }
            } catch (IOException ex) {
                System.out.println("FAIL: " + ex.getMessage());
                return;
            }
            explicitDateRecovery = true;
        } else {
            explicitDateRecovery = false;
        }

        String username = System.getenv("JFOREX_USERNAME");
        String password = System.getenv("JFOREX_PASSWORD");

        if (username == null || username.trim().isEmpty()
                || password == null || password.isEmpty()) {
            System.out.println("FAIL: Credentials not found.");
            return;
        }

        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final CountDownLatch stopped = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        final AtomicInteger exitCode = new AtomicInteger(1);
        final AtomicBoolean historyRequestActive = new AtomicBoolean(false);
        final long[] strategyId = new long[] {-1L};

        final IClient client = ClientFactory.getDefaultInstance();

        client.setSystemListener(new ISystemListener() {

            public void onConnect() {
                System.out.println("Connected to Dukascopy!");
                connected.countDown();
            }

            public void onDisconnect() {
                System.out.println("Disconnected.");
                disconnected.countDown();
            }

            public void onStart(long id) {}

            public void onStop(long id) {
                stopped.countDown();
            }
        });

        try {
            System.out.println("Connecting to Dukascopy Demo...");

            client.connect(JNLP_URL, username, password);

            if (!connected.await(90, TimeUnit.SECONDS)
                    || !client.isConnected()) {
                System.out.println("FAIL: Connection timeout.");
                return;
            }

            client.setSubscribedInstruments(
                Collections.singleton(INSTRUMENT)
            );

            strategyId[0] = client.startStrategy(new IStrategy() {

                public void onStart(IContext context)
                        throws JFException {

                    try {
                        IHistory history = context.getHistory();

                        if (explicitDateRecovery) {
                            runLiveExplicitDateRecovery(
                                history,
                                exitCode,
                                historyRequestActive
                            );
                            return;
                        }

                        if (productionMaxDays != null) {
                            runProductionDownload(
                                history,
                                client,
                                productionMaxDays.intValue(),
                                exitCode,
                                historyRequestActive
                            );
                            return;
                        }

                        if (downloadRange != null) {
                            runRangeDownload(
                                history,
                                downloadRange,
                                exitCode
                            );
                            return;
                        }

                        if (downloadDayStart != null) {
                            runOneDayDownload(
                                history,
                                downloadDayStart.longValue(),
                                exitCode
                            );
                            return;
                        }

                        System.out.println(
                            "Checking latest available EUR/USD tick"
                        );

                        ITick lastTick = history.getLastTick(INSTRUMENT);

                        if (lastTick == null) {
                            System.out.println("FAIL: No latest tick received.");
                            return;
                        }

                        System.out.println(
                            "Latest tick time: "
                            + UTC_FORMAT.format(new Date(lastTick.getTime()))
                        );

                        long currentBarStart = history.getBarStart(
                            PERIOD,
                            lastTick.getTime()
                        );

                        long to = history.getPreviousBarStart(
                            PERIOD,
                            currentBarStart
                        );

                        System.out.println(
                            "Latest completed candle: "
                            + UTC_FORMAT.format(new Date(to))
                        );

                        List<IBar> bars = history.getBars(
                            INSTRUMENT,
                            PERIOD,
                            OfferSide.BID,
                            Filter.NO_FILTER,
                            60,
                            to,
                            0
                        );

                        if (bars == null) {
                            System.out.println("FAIL: API returned null.");
                            return;
                        }

                        System.out.println(
                            "Returned candles: " + bars.size()
                        );

                        int displayCount = Math.min(10, bars.size());

                        System.out.println("--------------------------------");
                            System.out.println(
                            "FIRST " + displayCount + " CANDLES"
                            );
                        System.out.println("--------------------------------");

                        for (int i = 0; i < displayCount; i++) {

                            IBar bar = bars.get(i);

                            System.out.println(
                                UTC_FORMAT.format(new Date(bar.getTime()))
                                + " | Open: " + bar.getOpen()
                                + " | High: " + bar.getHigh()
                                + " | Low: " + bar.getLow()
                                + " | Close: " + bar.getClose()
                                + " | Volume: " + bar.getVolume()
                            );
                        }

                        System.out.println("--------------------------------");

                        Path csv = Paths.get("output", "EURUSD_M1_test.csv")
                            .toAbsolutePath();
                        int written = writeCandles(csv, bars);
                        int dataRows = countDataRows(csv);

                        System.out.println("CSV file: " + csv);
                        System.out.println("CSV data rows: " + dataRows);

                        if (bars.size() == 60 && written == 60 && dataRows == 60) {
                        System.out.println(
                            "PASS: Exactly 60 M1 candles received!"
                        );
                            exitCode.set(0);
                        } else {
                            System.out.println(
                                "FAIL: Expected 60 candles, received "
                                + bars.size()
                                + "; CSV data rows "
                                + dataRows
                            );
                        }

                        System.out.println("--------------------------------");

                    } catch (Exception e) {
                        System.out.println(
                            "FAIL: Historical data request error."
                        );
                        System.out.println("Reason: " + e.getMessage());
                        e.printStackTrace();

                    } finally {
                        finished.countDown();
                    }
                }

                public void onTick(Instrument instrument, ITick tick) {}

                public void onBar(
                    Instrument instrument,
                    Period period,
                    IBar askBar,
                    IBar bidBar
                ) {}

                public void onMessage(IMessage message) {}

                public void onAccount(IAccount account) {}

                public void onStop() {}
            });

            long waitMillis = 120000L;
            if (explicitDateRecovery) {
                waitMillis = productionBatchWaitMillis(2);
            } else if (productionMaxDays != null) {
                waitMillis = productionBatchWaitMillis(
                    productionMaxDays.intValue()
                );
            } else if (downloadRange != null) {
                waitMillis = 360000L;
            } else if (downloadDayStart != null) {
                waitMillis = 180000L;
            }
            if (!finished.await(waitMillis, TimeUnit.MILLISECONDS)) {
                if (historyRequestActive.get()) {
                    System.out.println(
                        "FAIL: A history request is still running. "
                        + "JForex provides no safe cancellation for a blocked "
                        + "getBars call, so this process will exit without "
                        + "marking that day complete."
                    );
                } else {
                    System.out.println("FAIL: Data request timeout.");
                }
            }

        } finally {
            if (explicitRecoveryTransport != null) {
                try {
                    explicitRecoveryTransport.cleanup();
                    if (explicitRecoveryCache != null) {
                        explicitRecoveryTransport.writeIfAbsent(explicitRecoveryCache);
                    }
                } catch (Exception ex) {
                    System.out.println("FAIL: HTTP transport report failed.");
                }
            }
            shutdown(
                client,
                strategyId[0],
                stopped,
                disconnected,
                exitCode
            );
        }
    }

    private static int writeCandles(Path csv, List<IBar> bars)
            throws IOException {

        List<IBar> ordered = new ArrayList<IBar>(bars);
        Collections.sort(ordered, new Comparator<IBar>() {
            public int compare(IBar left, IBar right) {
                return Long.compare(left.getTime(), right.getTime());
            }
        });

        Path parent = csv.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        int rows = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(
                csv,
                StandardCharsets.UTF_8)) {

            writer.write(CSV_HEADER);
            writer.newLine();

            for (IBar bar : ordered) {
                writer.write(CSV_UTC_FORMAT.format(new Date(bar.getTime())));
                writer.write(',');
                writer.write(Double.toString(bar.getOpen()));
                writer.write(',');
                writer.write(Double.toString(bar.getHigh()));
                writer.write(',');
                writer.write(Double.toString(bar.getLow()));
                writer.write(',');
                writer.write(Double.toString(bar.getClose()));
                writer.write(',');
                writer.write(Double.toString(bar.getVolume()));
                writer.newLine();
                rows++;
            }
        }

        return rows;
    }

    private static int countDataRows(Path csv) throws IOException {
        int rows = 0;
        String previousTime = null;

        try (BufferedReader reader = Files.newBufferedReader(
                csv,
                StandardCharsets.UTF_8)) {

            String header = reader.readLine();
            if (!CSV_HEADER.equals(header)) {
                throw new IOException("Unexpected CSV header: " + header);
            }

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }

                int comma = line.indexOf(',');
                String time = comma < 0 ? line : line.substring(0, comma);
                if (previousTime != null && previousTime.compareTo(time) > 0) {
                    throw new IOException(
                        "CSV rows are not chronological at " + time
                    );
                }

                previousTime = time;
                rows++;
            }
        }

        return rows;
    }

    /**
     * IClient.disconnect() stops strategies and asks the transport to quit, but
     * ClientConnector is a non-daemon thread and session termination waits for
     * it for up to the transport timeout (default one hour). There is no public
     * API that interrupts those threads, so System.exit is used only when they
     * are still alive after a bounded disconnect attempt.
     */
    private static void shutdown(
            IClient client,
            long strategyId,
            CountDownLatch stopped,
            CountDownLatch disconnected,
            AtomicInteger exitCode) {

        try {
            if (strategyId >= 0
                    && client.getStartedStrategies().containsKey(strategyId)) {
                client.stopStrategy(strategyId);
            }
        } catch (Exception error) {
            System.out.println("Strategy stop error: " + error.getMessage());
        }

        if (strategyId >= 0) {
            try {
                if (!stopped.await(30, TimeUnit.SECONDS)) {
                    System.out.println("FAIL: Strategy stop timeout.");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }

        Thread closer = new Thread(new Runnable() {
            public void run() {
                try {
            client.disconnect();
                } catch (Throwable error) {
                    System.out.println(
                        "Disconnect error: " + error.getMessage()
                    );
                }
            }
        }, "dukascopy-disconnect");
        closer.setDaemon(true);
        closer.start();

        try {
            disconnected.await(15, TimeUnit.SECONDS);
            closer.join(5000);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }

        List<String> blockers = new ArrayList<String>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive()
                    && !thread.isDaemon()
                    && thread != Thread.currentThread()) {
                blockers.add(thread.getName());
            }
        }

        if (blockers.isEmpty()) {
            return;
        }

        System.out.println(
            "JForex left non-daemon threads after disconnect: " + blockers
        );
        System.out.println(
            "IClient.disconnect() does not stop the non-daemon ClientConnector thread. Exiting."
        );
        System.exit(exitCode.get());
    }

    private static void runOneDayDownload(
            IHistory history,
            long dayStart,
            AtomicInteger exitCode) {

        try {
            System.out.println("One-day download mode");
            System.out.println(
                "Requested UTC date: "
                + formatUtcMinute(dayStart).substring(0, 10)
            );
            System.out.println("Checking latest available EUR/USD tick");

            ITick lastTick = history.getLastTick(INSTRUMENT);
            if (lastTick == null) {
                System.out.println("FAIL: No latest tick received.");
                return;
            }

            System.out.println(
                "Latest tick time: "
                + UTC_FORMAT.format(new Date(lastTick.getTime()))
            );

            long currentBarStart = history.getBarStart(
                PERIOD,
                lastTick.getTime()
            );
            long latestCompleted = history.getPreviousBarStart(
                PERIOD,
                currentBarStart
            );
            System.out.println(
                "Latest completed candle: "
                + UTC_FORMAT.format(new Date(latestCompleted))
            );

            long tickDay = startOfUtcDay(lastTick.getTime());
            long fullDayEnd = endOfUtcMinuteDay(dayStart);
            if (dayStart > tickDay) {
                System.out.println(
                    "FAIL: Requested date is after the latest tick. "
                    + "No candles were requested."
                );
                return;
            }
            if (dayStart == tickDay && latestCompleted < dayStart) {
                System.out.println(
                    "Requested interval: "
                    + UTC_FORMAT.format(new Date(dayStart))
                    + " -> "
                    + UTC_FORMAT.format(new Date(fullDayEnd))
                );
                System.out.println(
                    "No completed M1 candle exists in the requested interval. "
                    + "No candles were fabricated."
                );
                return;
            }

            long dayEnd = fullDayEnd;
            if (dayEnd > latestCompleted) {
                dayEnd = latestCompleted;
                System.out.println(
                    "Interval capped at the latest completed M1 candle."
                );
            }

            List<UtcInterval> intervals = planDailyIntervals(dayStart, dayEnd);
            if (intervals.size() != 1
                    || intervals.get(0).startMillis != dayStart
                    || intervals.get(0).endMillis != dayEnd) {
                System.out.println(
                    "FAIL: The requested interval is not one UTC day."
                );
                return;
            }

            System.out.println(
                "Requested interval: "
                + UTC_FORMAT.format(new Date(dayStart))
                + " -> "
                + UTC_FORMAT.format(new Date(dayEnd))
            );

            DayChunkResult loaded = loadDayWithRetry(
                new HistoryDaySource(history),
                dayStart,
                dayEnd,
                LIVE_RETRY_DELAY,
                utcDate(dayStart)
            );
            if (!loaded.complete()) {
                System.out.println(
                    "FAIL: " + utcDate(dayStart) + " is "
                    + loaded.statusName() + ". " + loaded.detail
                );
                System.out.println(
                    "Checkpoint was not advanced. "
                    + "An empty list is not treated as a closed market."
                );
                return;
            }

            List<CandleRecord> records = loaded.candles;
            System.out.println("Returned candles: " + records.size());
            if (records.isEmpty()) {
                System.out.println(
                    "Confirmed empty market day. No candles were fabricated."
                );
                System.out.println("First returned candle: none");
                System.out.println("Last returned candle: none");
            } else {
                CandleRecord first = records.get(0);
                CandleRecord last = records.get(records.size() - 1);
                System.out.println(
                    "First returned candle: "
                    + UTC_FORMAT.format(new Date(first.timeMillis))
                    + " | Open: " + first.open
                    + " | High: " + first.high
                    + " | Low: " + first.low
                    + " | Close: " + first.close
                    + " | Volume: " + first.volume
                );
                System.out.println(
                    "Last returned candle: "
                    + UTC_FORMAT.format(new Date(last.timeMillis))
                    + " | Open: " + last.open
                    + " | High: " + last.high
                    + " | Low: " + last.low
                    + " | Close: " + last.close
                    + " | Volume: " + last.volume
                );
                reportCandleGaps(records, dayStart, dayEnd);
            }

            Path directory = Paths.get("output", "phase3_live_test")
                .toAbsolutePath();
            CandleArchive archive = CandleArchive.open(
                directory,
                dayStart,
                dayEnd
            );
            int appended = archive.appendChunk(dayStart, dayEnd, records);
            Path csv = directory.resolve("EURUSD_M1.csv");
            Path checkpointPath = directory.resolve("EURUSD_M1.checkpoint");
            StoreCheckpoint checkpoint = readCheckpoint(checkpointPath);
            verifyConsistent(csv, checkpoint, dayStart, dayEnd);
            CsvTail tail = readCsvTail(csv);

            System.out.println("CSV file: " + csv);
            System.out.println("Rows appended: " + appended);
            System.out.println("CSV data rows: " + tail.rowCount);
            System.out.println("Checkpoint file: " + checkpointPath);
            System.out.println(
                "Checkpoint windowStart: "
                + UTC_FORMAT.format(new Date(checkpoint.windowStart))
            );
            System.out.println(
                "Checkpoint windowEnd: "
                + UTC_FORMAT.format(new Date(checkpoint.windowEnd))
            );
            System.out.println(
                "Checkpoint completedThrough: "
                + UTC_FORMAT.format(new Date(checkpoint.completedThrough))
            );
            System.out.println(
                "Checkpoint lastCandleTime: "
                + (checkpoint.lastCandleTime < 0
                    ? "none"
                    : UTC_FORMAT.format(new Date(checkpoint.lastCandleTime)))
            );
            System.out.println(
                "Checkpoint rowCount: " + checkpoint.rowCount
            );

            if (checkpoint.completedThrough != dayEnd
                    || tail.rowCount != checkpoint.rowCount
                    || tail.lastCandleTime != checkpoint.lastCandleTime) {
                System.out.println("FAIL: CSV and checkpoint do not match.");
                return;
            }
            if (records.isEmpty()) {
                if (checkpoint.rowCount != 0) {
                    System.out.println(
                        "FAIL: Empty market day left existing CSV rows."
                    );
                    return;
                }
                System.out.println(
                    "PASS: Empty market day recorded without fabricated candles."
                );
                exitCode.set(0);
                return;
            }
            if (checkpoint.lastCandleTime != records.get(records.size() - 1).timeMillis
                    || checkpoint.rowCount != records.size()
                    || appended > records.size()) {
                System.out.println(
                    "FAIL: Checkpoint does not cover the returned candles."
                );
                return;
            }

            System.out.println("PASS: One-day download saved.");
            exitCode.set(0);

        } catch (Exception error) {
            System.out.println("FAIL: One-day download error.");
            System.out.println("Reason: " + error.getMessage());
            error.printStackTrace();
        }
    }

    private static final class RangeRequest {
        private final long windowStart;
        private final long windowEnd;
        private final int maxDays;

        private RangeRequest(long windowStart, long windowEnd, int maxDays) {
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.maxDays = maxDays;
        }
    }

    private static void runRangeDownload(
            IHistory history,
            RangeRequest request,
            AtomicInteger exitCode) {

        try {
            System.out.println("Download-range mode");
            System.out.println(
                "Range: "
                + utcDate(request.windowStart)
                + " -> "
                + utcDate(request.windowEnd)
            );
            System.out.println(
                "Max days this run: "
                + (request.maxDays == 0
                    ? "none"
                    : Integer.toString(request.maxDays))
            );
            if (!verifyRangeResumeInvariants()) {
                System.out.println("FAIL: Resume invariant check failed.");
                return;
            }

            System.out.println("Checking latest available EUR/USD tick");
            ITick lastTick = history.getLastTick(INSTRUMENT);
            if (lastTick == null) {
                System.out.println("FAIL: No latest tick received.");
                return;
            }
            System.out.println(
                "Latest tick time: "
                + UTC_FORMAT.format(new Date(lastTick.getTime()))
            );
            long currentBarStart = history.getBarStart(
                PERIOD,
                lastTick.getTime()
            );
            long latestCompleted = history.getPreviousBarStart(
                PERIOD,
                currentBarStart
            );
            System.out.println(
                "Latest completed candle: "
                + UTC_FORMAT.format(new Date(latestCompleted))
            );

            long tickDay = startOfUtcDay(lastTick.getTime());
            if (request.windowStart > tickDay) {
                System.out.println(
                    "FAIL: Requested range starts after the latest tick. "
                    + "No candles were requested."
                );
                return;
            }

            List<UtcInterval> intervals = planDailyIntervals(
                request.windowStart,
                request.windowEnd
            );
            for (int i = 0; i < intervals.size(); i++) {
                UtcInterval interval = intervals.get(i);
                if (!isUtcTime(interval.startMillis, 0, 0)
                        || !isUtcTime(interval.endMillis, 23, 59)) {
                    System.out.println(
                        "FAIL: Planned chunk is not a full UTC day."
                    );
                    return;
                }
            }
            System.out.println("Planned UTC days: " + intervals.size());

            Path directory = Paths.get("output", "phase4_resume_test")
                .toAbsolutePath();
            CandleArchive archive = CandleArchive.open(
                directory,
                request.windowStart,
                request.windowEnd
            );
            Path csv = archive.csvPath;
            System.out.println("CSV file: " + csv);
            printCheckpoint("Checkpoint before requests", archive);

            if (archive.completedThrough >= 0
                    && !simulateRepeatedBoundary(archive)) {
                return;
            }

            int completedThisRun = 0;
            int rowsThisRun = 0;
            List<String> skippedDates = new ArrayList<String>();
            List<String> requestedDates = new ArrayList<String>();
            List<String> emptyDates = new ArrayList<String>();
            List<String> dayRows = new ArrayList<String>();

            for (int i = 0; i < intervals.size(); i++) {
                UtcInterval interval = intervals.get(i);
                String date = utcDate(interval.startMillis);
                if (archive.completedThrough >= interval.endMillis) {
                    System.out.println("Skipping completed UTC day: " + date);
                    skippedDates.add(date);
                    continue;
                }
                if (request.maxDays > 0
                        && completedThisRun >= request.maxDays) {
                    System.out.println(
                        "Stopping after " + request.maxDays
                        + " completed days. Next unfinished day: " + date
                    );
                    break;
                }
                if (interval.startMillis > latestCompleted) {
                    System.out.println(
                        "FAIL: " + date
                        + " is after the latest completed candle. "
                        + "It was not requested."
                    );
                    return;
                }

                long chunkEnd = interval.endMillis;
                if (chunkEnd > latestCompleted) {
                    chunkEnd = latestCompleted;
                    System.out.println(
                        "Day " + date
                        + " is capped at the latest completed candle."
                    );
                }
                if (interval.startMillis > chunkEnd) {
                    System.out.println(
                        "No completed M1 candle exists on " + date
                        + ". No candles were fabricated."
                    );
                    return;
                }

                long rowsBefore = archive.rowCount;
                long throughBefore = archive.completedThrough;
                long bytesBefore = Files.size(csv);
                System.out.println("Requesting UTC day: " + date);
                System.out.println(
                    "Date-range request: "
                    + UTC_FORMAT.format(new Date(interval.startMillis))
                    + " -> "
                    + UTC_FORMAT.format(new Date(chunkEnd))
                );
                requestedDates.add(date);

                DayChunkResult loaded = loadDayWithRetry(
                    new HistoryDaySource(history),
                    interval.startMillis,
                    chunkEnd,
                    LIVE_RETRY_DELAY,
                    date
                );
                if (!loaded.complete()) {
                    System.out.println(
                        "FAIL: " + date + " is " + loaded.statusName()
                        + ". " + loaded.detail
                    );
                    System.out.println(
                        "This is not a completed empty market day. "
                        + "Checkpoint was not advanced."
                    );
                    reportPreservedRows(
                        csv,
                        archive,
                        rowsBefore,
                        throughBefore,
                        bytesBefore
                    );
                    return;
                }

                List<CandleRecord> records = loaded.candles;
                System.out.println(
                    "Returned candles for " + date + ": " + records.size()
                );
                if (records.isEmpty()) {
                    System.out.println(
                        "Confirmed empty market day: " + date
                        + ". No candles were fabricated."
                    );
                } else {
                    CandleRecord first = records.get(0);
                    CandleRecord last = records.get(records.size() - 1);
                    System.out.println(
                        "First returned candle: "
                        + UTC_FORMAT.format(new Date(first.timeMillis))
                    );
                    System.out.println(
                        "Last returned candle: "
                        + UTC_FORMAT.format(new Date(last.timeMillis))
                    );
                    reportCandleGaps(
                        records,
                        interval.startMillis,
                        chunkEnd
                    );
                }

                int appended = archive.appendChunk(
                    interval.startMillis,
                    chunkEnd,
                    records
                );
                verifyConsistent(
                    csv,
                    readCheckpoint(archive.checkpointPath),
                    request.windowStart,
                    request.windowEnd
                );
                if (archive.completedThrough != chunkEnd
                        || archive.rowCount != rowsBefore + appended) {
                    System.out.println(
                        "FAIL: Checkpoint did not advance with the CSV."
                    );
                    return;
                }
                if (records.isEmpty() && appended != 0) {
                    System.out.println(
                        "FAIL: Empty market day wrote candles."
                    );
                    return;
                }
                if (!records.isEmpty() && appended > records.size()) {
                    System.out.println(
                        "FAIL: More rows were written than candles returned."
                    );
                    return;
                }

                rowsThisRun += appended;
                completedThisRun++;
                dayRows.add(date + "=" + appended);
                if (records.isEmpty()) {
                    emptyDates.add(date);
                    System.out.println(
                        "Empty day recorded: " + date
                        + ". Rows appended: 0. Checkpoint advanced to "
                        + UTC_FORMAT.format(new Date(chunkEnd))
                    );
                } else {
                    System.out.println(
                        "Rows appended for " + date + ": " + appended
                    );
                }
                printCheckpoint("Checkpoint after " + date, archive);

                boolean moreDaysRemain = false;
                for (int next = i + 1; next < intervals.size(); next++) {
                    if (archive.completedThrough
                            < intervals.get(next).endMillis) {
                        moreDaysRemain = true;
                        break;
                    }
                }
                if (moreDaysRemain
                        && (request.maxDays == 0
                            || completedThisRun < request.maxDays)
                        && !simulateRepeatedBoundary(archive)) {
                    return;
                }
            }

            System.out.println("--------------------------------");
            System.out.println("RANGE RUN SUMMARY");
            System.out.println(
                "Dates skipped: " + joinDates(skippedDates)
            );
            System.out.println(
                "Dates requested: " + joinDates(requestedDates)
            );
            System.out.println(
                "Empty days: " + joinDates(emptyDates)
            );
            System.out.println(
                "Rows appended by day: " + joinDates(dayRows)
            );
            System.out.println("Rows appended this run: " + rowsThisRun);
            System.out.println("Completed days this run: " + completedThisRun);
            CsvTail tail = readCsvTail(csv);
            System.out.println("CSV data rows: " + tail.rowCount);
            if (tail.rowCount > 0) {
                System.out.println(
                    "CSV last timestamp: "
                    + UTC_FORMAT.format(new Date(tail.lastCandleTime))
                );
            }
            printCheckpoint("Checkpoint at exit", archive);
            if (tail.rowCount != archive.rowCount
                    || tail.lastCandleTime != archive.lastCandleTime) {
                System.out.println("FAIL: CSV and checkpoint do not match.");
                return;
            }
            System.out.println("PASS: Download-range run finished.");
            exitCode.set(0);

        } catch (Exception error) {
            System.out.println("FAIL: Download-range error.");
            System.out.println("Reason: " + error.getMessage());
            error.printStackTrace();
        }
    }

    private static void reportDayFailure(
            String date,
            Exception error,
            Path csv,
            CandleArchive archive,
            long rowsBefore,
            long throughBefore,
            long bytesBefore) throws IOException {

        System.out.println(
            "FAIL: Date-range history request failed for " + date + "."
        );
        System.out.println("Reason: " + error.getMessage());
        System.out.println(
            "This is an API error, not an empty market day."
        );
        System.out.println("No other history overload was used.");
        error.printStackTrace();
        reportPreservedRows(
            csv,
            archive,
            rowsBefore,
            throughBefore,
            bytesBefore
        );
    }

    private static void reportPreservedRows(
            Path csv,
            CandleArchive archive,
            long rowsBefore,
            long throughBefore,
            long bytesBefore) throws IOException {

        boolean intact = Files.size(csv) == bytesBefore
            && archive.rowCount == rowsBefore
            && archive.completedThrough == throughBefore;
        System.out.println(
            intact
                ? "Earlier downloaded rows remain intact."
                : "FAIL: Earlier downloaded rows changed after the failed day."
        );
    }

    private static boolean simulateRepeatedBoundary(CandleArchive archive)
            throws IOException {

        if (archive.lastCandleTime < 0 || archive.completedThrough < 0) {
            System.out.println(
                "Boundary simulation skipped: no completed candle yet."
            );
            return true;
        }
        CandleRecord boundary = readLastCandle(archive.csvPath);
        if (boundary == null
                || boundary.timeMillis != archive.lastCandleTime) {
            System.out.println(
                "FAIL: Last CSV candle does not match the checkpoint."
            );
            return false;
        }
        long chunkStart = startOfUtcDay(boundary.timeMillis);
        long chunkEnd = endOfUtcMinuteDay(boundary.timeMillis);
        if (chunkEnd > archive.completedThrough) {
            chunkEnd = archive.completedThrough;
        }
        long rowsBefore = archive.rowCount;
        long throughBefore = archive.completedThrough;
        long bytesBefore = Files.size(archive.csvPath);
        int added = archive.appendChunk(
            chunkStart,
            chunkEnd,
            Collections.singletonList(boundary)
        );
        boolean unchanged = added == 0
            && archive.rowCount == rowsBefore
            && archive.completedThrough == throughBefore
            && archive.lastCandleTime == boundary.timeMillis
            && Files.size(archive.csvPath) == bytesBefore;
        System.out.println(
            "Repeated boundary candle: "
            + UTC_FORMAT.format(new Date(boundary.timeMillis))
        );
        System.out.println("Boundary rows before: " + rowsBefore);
        System.out.println("Boundary rows appended: " + added);
        System.out.println("Boundary rows after: " + archive.rowCount);
        System.out.println(
            unchanged
                ? "PASS: Repeated boundary candle left the row count unchanged."
                : "FAIL: Repeated boundary candle changed the archive."
        );
        return unchanged;
    }

    private static CandleRecord readLastCandle(Path csv) throws IOException {
        CandleRecord last = null;
        try (BufferedReader reader = Files.newBufferedReader(
                csv,
                StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (!CSV_HEADER.equals(header)) {
                throw new IOException("Unexpected CSV header");
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] fields = line.split(",", -1);
                if (fields.length != 6) {
                    throw new IOException("Malformed CSV row");
                }
                long time;
                try {
                    time = parseUtcMinute(fields[0]);
                } catch (ParseException ex) {
                    throw new IOException("Invalid CSV timestamp");
                }
                last = new CandleRecord(
                    time,
                    Double.parseDouble(fields[1]),
                    Double.parseDouble(fields[2]),
                    Double.parseDouble(fields[3]),
                    Double.parseDouble(fields[4]),
                    Double.parseDouble(fields[5])
                );
            }
        } catch (NumberFormatException ex) {
            throw new IOException("Malformed CSV row");
        }
        return last;
    }

    private static boolean verifyRangeResumeInvariants() throws Exception {
        System.out.println(
            "Local resume checks use a temporary directory and do not request Dukascopy data."
        );
        Path root = Files.createTempDirectory("dukascopy-range-resume");
        try {
            long dayStart = parseUtcDate("2024-01-01");
            long dayEnd = endOfUtcMinuteDay(dayStart);
            long nextStart = dayEnd + 60000L;
            long nextEnd = endOfUtcMinuteDay(nextStart);
            CandleArchive archive = CandleArchive.open(
                root,
                dayStart,
                nextEnd
            );
            List<CandleRecord> firstDay = Arrays.asList(
                validCandle("2024-01-01 00:00:00", 1.10, 1.20, 1.00, 1.11, 10),
                validCandle("2024-01-01 00:01:00", 1.11, 1.21, 1.01, 1.12, 11)
            );
            int added = archive.appendChunk(dayStart, dayEnd, firstDay);
            byte[] csvBefore = Files.readAllBytes(archive.csvPath);
            byte[] checkpointBefore = Files.readAllBytes(archive.checkpointPath);
            boolean rejected = false;
            try {
                archive.appendChunk(
                    nextStart,
                    nextEnd,
                    Collections.singletonList(new CandleRecord(
                        nextStart,
                        1.10,
                        1.20,
                        1.00,
                        1.11,
                        -1
                    ))
                );
            } catch (IOException ex) {
                rejected = ex.getMessage() != null
                    && ex.getMessage().indexOf("Invalid volume") >= 0;
            }
            byte[] csvAfter = Files.readAllBytes(archive.csvPath);
            byte[] checkpointAfter = Files.readAllBytes(
                archive.checkpointPath
            );
            CandleArchive reopened = CandleArchive.open(
                root,
                dayStart,
                nextEnd
            );
            boolean preserved = rejected
                && Arrays.equals(csvBefore, csvAfter)
                && Arrays.equals(checkpointBefore, checkpointAfter)
                && reopened.rowCount == 2
                && reopened.completedThrough == dayEnd
                && added == 2;
            System.out.println(
                preserved
                    ? "PASS: A failed later day left the earlier rows unchanged."
                    : "FAIL: A failed later day changed the earlier rows."
            );

            int duplicateAdd = reopened.appendChunk(
                dayStart,
                dayEnd,
                Collections.singletonList(firstDay.get(1))
            );
            boolean boundaryUnchanged = duplicateAdd == 0
                && reopened.rowCount == 2
                && reopened.completedThrough == dayEnd;
            System.out.println(
                boundaryUnchanged
                    ? "PASS: Repeated boundary candle appended 0 rows."
                    : "FAIL: Repeated boundary candle changed the row count."
            );

            int overlapAdd = reopened.appendChunk(
                nextStart,
                nextEnd,
                Arrays.asList(
                    firstDay.get(1),
                    validCandle(
                        "2024-01-02 00:00:00",
                        1.12,
                        1.22,
                        1.02,
                        1.13,
                        12
                    )
                )
            );
            String csvText = new String(
                Files.readAllBytes(reopened.csvPath),
                StandardCharsets.UTF_8
            );
            boolean overlapSkipped = overlapAdd == 1
                && reopened.rowCount == 3
                && countOccurrences(csvText, "2024-01-01 00:01:00,") == 1
                && reopened.completedThrough == nextEnd;
            System.out.println(
                overlapSkipped
                    ? "PASS: Overlapping timestamp was skipped without a duplicate."
                    : "FAIL: Overlapping timestamp was written again."
            );
            return preserved && boundaryUnchanged && overlapSkipped;
        } finally {
            deleteRecursively(root);
        }
    }

    private static void printCheckpoint(String label, CandleArchive archive) {
        System.out.println(label + ":");
        System.out.println(
            "  completedThrough: "
            + (archive.completedThrough < 0
                ? "none"
                : UTC_FORMAT.format(new Date(archive.completedThrough)))
        );
        System.out.println(
            "  lastCandleTime: "
            + (archive.lastCandleTime < 0
                ? "none"
                : UTC_FORMAT.format(new Date(archive.lastCandleTime)))
        );
        System.out.println("  rowCount: " + archive.rowCount);
    }

    private static String utcDate(long millis) {
        return formatUtcMinute(millis).substring(0, 10);
    }

    private static String joinDates(List<String> dates) {
        if (dates.isEmpty()) {
            return "none";
        }
        return String.join(", ", dates);
    }

    private static final int DAY_DATA = 1;
    private static final int DAY_CONFIRMED_EMPTY = 2;
    private static final int DAY_UNKNOWN = 3;
    private static final int DAY_FAILED = 4;
    private static final int DAY_ATTEMPTS = 3;
    private static final long[] DAY_RETRY_DELAYS_MS = {2000L, 5000L};

    private interface DayChunkSource {
        DayChunkResult request(long chunkStart, long chunkEnd);
    }

    private interface RetryDelay {
        void delay(long millis) throws InterruptedException;
    }

    private static final RetryDelay LIVE_RETRY_DELAY = new RetryDelay() {
        public void delay(long millis) throws InterruptedException {
            Thread.sleep(millis);
        }
    };

    private static final class DayChunkResult {
        private final int status;
        private final List<CandleRecord> candles;
        private final String detail;
        private final boolean retryable;

        private DayChunkResult(
                int status,
                List<CandleRecord> candles,
                String detail,
                boolean retryable) {
            this.status = status;
            this.candles = candles == null
                ? new ArrayList<CandleRecord>()
                : candles;
            this.detail = detail == null ? "" : detail;
            this.retryable = retryable;
        }

        private boolean complete() {
            return status == DAY_DATA || status == DAY_CONFIRMED_EMPTY;
        }

        private String statusName() {
            if (status == DAY_DATA) {
                return "DATA";
            }
            if (status == DAY_CONFIRMED_EMPTY) {
                return "CONFIRMED_EMPTY";
            }
            if (status == DAY_UNKNOWN) {
                return "UNKNOWN";
            }
            return "FAILED";
        }
    }

    private static DayChunkResult classifyDayResponse(
            List<CandleRecord> candles,
            boolean confirmedEmpty,
            boolean transportFailure,
            boolean timedOut,
            boolean requestException,
            String failureDetail) {

        if (timedOut) {
            return new DayChunkResult(DAY_FAILED, null, "timeout", true);
        }
        if (requestException || transportFailure) {
            String detail = failureDetail == null || failureDetail.length() == 0
                ? "transport failure"
                : failureDetail;
            return new DayChunkResult(DAY_FAILED, null, detail, true);
        }
        if (candles == null) {
            return new DayChunkResult(
                DAY_UNKNOWN,
                null,
                "The history request returned null.",
                true
            );
        }
        if (!candles.isEmpty()) {
            return new DayChunkResult(DAY_DATA, candles, "", false);
        }
        if (confirmedEmpty) {
            return new DayChunkResult(
                DAY_CONFIRMED_EMPTY,
                candles,
                "Confirmed empty market response.",
                false
            );
        }
        return new DayChunkResult(
            DAY_UNKNOWN,
            candles,
            "Empty list is not proof that the market was closed. "
                + "JForex getBars can return an empty list after a swallowed HTTP failure.",
            true
        );
    }

    private static final class HistoryDaySource implements DayChunkSource {
        private final IHistory history;

        private HistoryDaySource(IHistory history) {
            this.history = history;
        }

        public DayChunkResult request(long chunkStart, long chunkEnd) {
            List<IBar> bars;
            try {
                bars = history.getBars(
                    INSTRUMENT,
                    PERIOD,
                    OfferSide.BID,
                    Filter.NO_FILTER,
                    chunkStart,
                    chunkEnd
                );
            } catch (Exception error) {
                return new DayChunkResult(
                    DAY_FAILED,
                    null,
                    error.getClass().getSimpleName() + ": " + error.getMessage(),
                    true
                );
            }
            if (bars == null) {
                return classifyDayResponse(null, false, false, false, false, null);
            }
            List<CandleRecord> records = new ArrayList<CandleRecord>();
            for (int i = 0; i < bars.size(); i++) {
                IBar bar = bars.get(i);
                if (bar == null) {
                    return new DayChunkResult(
                        DAY_FAILED,
                        null,
                        "Returned bar " + i + " is null",
                        false
                    );
                }
                records.add(new CandleRecord(
                    bar.getTime(),
                    bar.getOpen(),
                    bar.getHigh(),
                    bar.getLow(),
                    bar.getClose(),
                    bar.getVolume()
                ));
            }
            String invalid = validateRecords(records, chunkStart, chunkEnd);
            if (invalid != null) {
                return new DayChunkResult(DAY_FAILED, null, invalid, false);
            }
            return classifyDayResponse(records, false, false, false, false, null);
        }
    }

    private static final class ScriptedDaySource implements DayChunkSource {
        private final DayChunkResult[] steps;
        private int index;
        private int calls;

        private ScriptedDaySource(DayChunkResult[] steps) {
            this.steps = steps;
        }

        public DayChunkResult request(long chunkStart, long chunkEnd) {
            calls++;
            DayChunkResult result = steps[Math.min(index, steps.length - 1)];
            if (index < steps.length - 1) {
                index++;
            }
            return result;
        }
    }

    private static final class RecordingDelay implements RetryDelay {
        private final List<Long> delays = new ArrayList<Long>();

        public void delay(long millis) {
            delays.add(Long.valueOf(millis));
        }
    }

    private static DayChunkResult loadDayWithRetry(
            DayChunkSource source,
            long chunkStart,
            long chunkEnd,
            RetryDelay delay,
            String dateLabel) throws InterruptedException {

        DayChunkResult last = null;
        for (int attempt = 1; attempt <= DAY_ATTEMPTS; attempt++) {
            last = source.request(chunkStart, chunkEnd);
            if (last.complete() || !last.retryable || attempt == DAY_ATTEMPTS) {
                if (!last.complete()) {
                    System.out.println(
                        dateLabel + " attempt " + attempt + "/" + DAY_ATTEMPTS
                        + " " + last.statusName() + ": " + last.detail
                    );
                }
                return last;
            }
            long wait = DAY_RETRY_DELAYS_MS[attempt - 1];
            System.out.println(
                dateLabel + " attempt " + attempt + "/" + DAY_ATTEMPTS
                + " " + last.statusName()
                + ". Retrying in " + wait + " ms."
            );
            delay.delay(wait);
        }
        return last;
    }

    private static boolean commitLoadedDay(
            CandleArchive archive,
            long chunkStart,
            long chunkEnd,
            DayChunkResult result) throws IOException {

        if (!result.complete()) {
            return false;
        }
        archive.appendChunk(chunkStart, chunkEnd, result.candles);
        return true;
    }

    private static String validateRecords(
            List<CandleRecord> records,
            long chunkStart,
            long chunkEnd) {

        long previous = Long.MIN_VALUE;
        for (int i = 0; i < records.size(); i++) {
            CandleRecord candle = records.get(i);
            if (candle.timeMillis < chunkStart || candle.timeMillis > chunkEnd) {
                return "Returned candle is outside the requested interval: "
                    + formatUtcMinute(candle.timeMillis);
            }
            if (previous != Long.MIN_VALUE && candle.timeMillis <= previous) {
                return "Returned candles are not strictly increasing at "
                    + formatUtcMinute(candle.timeMillis);
            }
            previous = candle.timeMillis;
            try {
                validateCandle(candle);
            } catch (IOException error) {
                return error.getMessage();
            }
        }
        return null;
    }

    private static void reportCandleGaps(
            List<CandleRecord> candles,
            long dayStart,
            long dayEnd) {

        int gaps = 0;
        String firstGap = null;
        long previous = dayStart - 60000L;
        for (int i = 0; i < candles.size(); i++) {
            long time = candles.get(i).timeMillis;
            if (time - previous > 60000L) {
                gaps++;
                if (firstGap == null) {
                    firstGap = formatUtcMinute(previous + 60000L)
                        + " -> "
                        + formatUtcMinute(time);
                }
            }
            previous = time;
        }
        if (!candles.isEmpty() && dayEnd - previous > 0) {
            gaps++;
            if (firstGap == null) {
                firstGap = formatUtcMinute(previous + 60000L)
                    + " -> "
                    + formatUtcMinute(dayEnd);
            }
        }
        if (gaps == 0) {
            System.out.println("Minute gaps: 0");
        } else {
            System.out.println(
                "Minute gaps: " + gaps + "; first gap " + firstGap
            );
        }
    }

    private static String nextUnfinishedDate(
            long completedThrough,
            List<UtcInterval> intervals) {

        for (int i = 0; i < intervals.size(); i++) {
            if (completedThrough < intervals.get(i).endMillis) {
                return utcDate(intervals.get(i).startMillis);
            }
        }
        return "none";
    }

    private static boolean runFailureClassificationTest() throws Exception {
        System.out.println(
            "Failure-classification tests use simulated responses and do not connect to Dukascopy."
        );
        Path root = Files.createTempDirectory("dukascopy-failure-test");
        boolean passed = true;
        try {
            passed &= testSuccessfulDay(root.resolve("data"));
            passed &= testConfirmedEmptyDay(root.resolve("empty"));
            passed &= testTransportFailureDoesNotAdvance(root.resolve("http503"));
            passed &= testExceptionDoesNotAdvance(root.resolve("exception"));
            passed &= testTimeoutDoesNotAdvance(root.resolve("timeout"));
            passed &= testAmbiguousEmptyDoesNotAdvance(root.resolve("unknown"));
            passed &= testRetryThenSuccess(root.resolve("retry"));
            passed &= testRestartKeepsNextDay(root.resolve("restart"));
        } finally {
            deleteRecursively(root);
        }
        System.out.println("--------------------------------");
        System.out.println(passed
            ? "FAILURE CLASSIFICATION TESTS PASS"
            : "FAILURE CLASSIFICATION TESTS FAIL");
        return passed;
    }

    private static boolean testSuccessfulDay(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Successful non-empty response");
        long start = parseUtcDate("2024-05-01");
        long end = endOfUtcMinuteDay(start);
        CandleRecord candle = validCandle(
            "2024-05-01 00:05:00", 1.10, 1.20, 1.00, 1.11, 10
        );
        DayChunkResult result = classifyDayResponse(
            Collections.singletonList(candle),
            false, false, false, false, null
        );
        CandleArchive archive = CandleArchive.open(directory, start, end);
        RecordingDelay delay = new RecordingDelay();
        ScriptedDaySource source = new ScriptedDaySource(
            new DayChunkResult[] {result}
        );
        DayChunkResult loaded = loadDayWithRetry(
            source, start, end, delay, "2024-05-01"
        );
        boolean committed = commitLoadedDay(archive, start, end, loaded);
        boolean passed = loaded.status == DAY_DATA
            && committed
            && source.calls == 1
            && delay.delays.isEmpty()
            && archive.rowCount == 1
            && archive.completedThrough == end
            && archive.lastCandleTime == candle.timeMillis;
        System.out.println(passed
            ? "PASS"
            : "FAIL: successful response did not advance the checkpoint");
        return passed;
    }

    private static boolean testConfirmedEmptyDay(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Confirmed empty response");
        long start = parseUtcDate("2024-05-02");
        long end = endOfUtcMinuteDay(start);
        DayChunkResult result = classifyDayResponse(
            new ArrayList<CandleRecord>(),
            true, false, false, false, null
        );
        CandleArchive archive = CandleArchive.open(directory, start, end);
        ScriptedDaySource source = new ScriptedDaySource(
            new DayChunkResult[] {result}
        );
        DayChunkResult loaded = loadDayWithRetry(
            source, start, end, new RecordingDelay(), "2024-05-02"
        );
        long bytesBefore = Files.size(archive.csvPath);
        boolean committed = commitLoadedDay(archive, start, end, loaded);
        String csv = new String(
            Files.readAllBytes(archive.csvPath),
            StandardCharsets.UTF_8
        );
        boolean passed = loaded.status == DAY_CONFIRMED_EMPTY
            && committed
            && source.calls == 1
            && archive.rowCount == 0
            && archive.lastCandleTime < 0
            && archive.completedThrough == end
            && csv.indexOf("2024-05-02") < 0
            && Files.size(archive.csvPath) == bytesBefore;
        System.out.println(passed
            ? "PASS"
            : "FAIL: confirmed empty day changed the CSV or did not advance");
        return passed;
    }

    private static boolean testTransportFailureDoesNotAdvance(Path directory)
            throws Exception {
        System.out.println("================================");
        System.out.println("Empty result with HTTP 503 / IOException evidence");
        long start = parseUtcDate("2024-05-03");
        long end = endOfUtcMinuteDay(start);
        List<CandleRecord> empty = new ArrayList<CandleRecord>();
        DayChunkResult ambiguous = classifyDayResponse(
            empty, false, true, false, false, "HTTP 503 IOException"
        );
        DayChunkResult withBars = classifyDayResponse(
            Collections.singletonList(validCandle(
                "2024-05-03 00:05:00", 1.10, 1.20, 1.00, 1.11, 10
            )),
            true, true, false, false, "HTTP 503 IOException"
        );
        CandleArchive archive = CandleArchive.open(directory, start, end);
        CandleRecord kept = validCandle(
            "2024-05-03 00:01:00", 1.10, 1.20, 1.00, 1.11, 9
        );
        archive.appendChunk(
            start,
            end,
            Collections.singletonList(kept)
        );
        long rows = archive.rowCount;
        long through = archive.completedThrough;
        long bytes = Files.size(archive.csvPath);
        RecordingDelay delay = new RecordingDelay();
        ScriptedDaySource source = new ScriptedDaySource(
            new DayChunkResult[] {ambiguous}
        );
        DayChunkResult loaded = loadDayWithRetry(
            source, start, end, delay, "2024-05-03"
        );
        boolean committed = commitLoadedDay(archive, start, end, loaded);
        boolean passed = ambiguous.status == DAY_FAILED
            && ambiguous.retryable
            && withBars.status == DAY_FAILED
            && !ambiguous.complete()
            && loaded.status == DAY_FAILED
            && !committed
            && source.calls == DAY_ATTEMPTS
            && delay.delays.size() == 2
            && delay.delays.get(0).longValue() == 2000L
            && delay.delays.get(1).longValue() == 5000L
            && archive.rowCount == rows
            && archive.completedThrough == through
            && Files.size(archive.csvPath) == bytes;
        System.out.println(passed
            ? "PASS"
            : "FAIL: HTTP 503 evidence was treated as a completed day");
        return passed;
    }

    private static boolean testExceptionDoesNotAdvance(Path directory)
            throws Exception {
        System.out.println("================================");
        System.out.println("Exception");
        return failedRequestLeavesCheckpoint(
            directory,
            "2024-05-04",
            classifyDayResponse(
                null, false, false, false, true, "IOException: connection reset"
            ),
            "exception"
        );
    }

    private static boolean testTimeoutDoesNotAdvance(Path directory)
            throws Exception {
        System.out.println("================================");
        System.out.println("Timeout");
        return failedRequestLeavesCheckpoint(
            directory,
            "2024-05-05",
            classifyDayResponse(null, false, false, true, false, null),
            "timeout"
        );
    }

    private static boolean testAmbiguousEmptyDoesNotAdvance(Path directory)
            throws Exception {
        System.out.println("================================");
        System.out.println("Empty list without confirmation");
        DayChunkResult unknown = classifyDayResponse(
            new ArrayList<CandleRecord>(),
            false, false, false, false, null
        );
        boolean classified = unknown.status == DAY_UNKNOWN
            && unknown.retryable
            && !unknown.complete();
        boolean stored = failedRequestLeavesCheckpoint(
            directory,
            "2024-05-06",
            unknown,
            "ambiguous empty list"
        );
        if (!classified) {
            System.out.println(
                "FAIL: an empty list was classified as a confirmed empty day"
            );
        }
        return classified && stored;
    }

    private static boolean failedRequestLeavesCheckpoint(
            Path directory,
            String date,
            DayChunkResult failure,
            String label) throws Exception {

        long start = parseUtcDate(date);
        long end = endOfUtcMinuteDay(start);
        CandleArchive archive = CandleArchive.open(directory, start, end);
        CandleRecord kept = validCandle(
            date + " 00:02:00", 1.10, 1.20, 1.00, 1.11, 8
        );
        archive.appendChunk(start, end, Collections.singletonList(kept));
        long rows = archive.rowCount;
        long through = archive.completedThrough;
        long bytes = Files.size(archive.csvPath);
        byte[] checkpoint = Files.readAllBytes(archive.checkpointPath);
        RecordingDelay delay = new RecordingDelay();
        ScriptedDaySource source = new ScriptedDaySource(
            new DayChunkResult[] {failure}
        );
        DayChunkResult loaded = loadDayWithRetry(
            source, start, end, delay, date
        );
        boolean committed = commitLoadedDay(archive, start, end, loaded);
        boolean passed = (failure.status == DAY_FAILED
                || failure.status == DAY_UNKNOWN)
            && loaded.status == failure.status
            && !committed
            && source.calls == DAY_ATTEMPTS
            && delay.delays.size() == 2
            && archive.rowCount == rows
            && archive.completedThrough == through
            && Arrays.equals(checkpoint, Files.readAllBytes(archive.checkpointPath))
            && Files.size(archive.csvPath) == bytes;
        System.out.println(passed
            ? "PASS"
            : "FAIL: " + label + " changed the checkpoint");
        return passed;
    }

    private static boolean testRetryThenSuccess(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Successful retry advances checkpoint");
        long start = parseUtcDate("2024-05-07");
        long end = endOfUtcMinuteDay(start);
        CandleRecord candle = validCandle(
            "2024-05-07 00:09:00", 1.12, 1.22, 1.02, 1.13, 12
        );
        DayChunkResult unknown = classifyDayResponse(
            new ArrayList<CandleRecord>(),
            false, false, false, false, null
        );
        DayChunkResult data = classifyDayResponse(
            Collections.singletonList(candle),
            false, false, false, false, null
        );
        CandleArchive archive = CandleArchive.open(directory, start, end);
        RecordingDelay delay = new RecordingDelay();
        ScriptedDaySource source = new ScriptedDaySource(
            new DayChunkResult[] {unknown, data}
        );
        DayChunkResult loaded = loadDayWithRetry(
            source, start, end, delay, "2024-05-07"
        );
        boolean committed = commitLoadedDay(archive, start, end, loaded);
        boolean passed = loaded.status == DAY_DATA
            && committed
            && source.calls == 2
            && delay.delays.size() == 1
            && delay.delays.get(0).longValue() == 2000L
            && archive.rowCount == 1
            && archive.completedThrough == end
            && archive.lastCandleTime == candle.timeMillis;
        System.out.println(passed
            ? "PASS"
            : "FAIL: a later success did not advance the checkpoint");
        return passed;
    }

    private static boolean testRestartKeepsNextDay(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Restart preserves the next unfinished date");
        long firstStart = parseUtcDate("2024-05-08");
        long firstEnd = endOfUtcMinuteDay(firstStart);
        long secondStart = firstEnd + 60000L;
        long secondEnd = endOfUtcMinuteDay(secondStart);
        List<UtcInterval> intervals = planDailyIntervals(firstStart, secondEnd);
        CandleArchive archive = CandleArchive.open(
            directory, firstStart, secondEnd
        );
        CandleRecord firstCandle = validCandle(
            "2024-05-08 00:04:00", 1.10, 1.20, 1.00, 1.11, 7
        );
        DayChunkResult firstDay = classifyDayResponse(
            Collections.singletonList(firstCandle),
            false, false, false, false, null
        );
        commitLoadedDay(archive, firstStart, firstEnd, firstDay);
        DayChunkResult failure = classifyDayResponse(
            new ArrayList<CandleRecord>(),
            false, true, false, false, "HTTP 503 IOException"
        );
        DayChunkResult exhausted = loadDayWithRetry(
            new ScriptedDaySource(new DayChunkResult[] {failure}),
            secondStart,
            secondEnd,
            new RecordingDelay(),
            "2024-05-09"
        );
        boolean held = !exhausted.complete()
            && archive.completedThrough == firstEnd
            && archive.rowCount == 1;
        CandleArchive reopened = CandleArchive.open(
            directory, firstStart, secondEnd
        );
        String next = nextUnfinishedDate(
            reopened.completedThrough,
            intervals
        );
        CandleRecord secondCandle = validCandle(
            "2024-05-09 00:06:00", 1.13, 1.23, 1.03, 1.14, 6
        );
        DayChunkResult secondDay = classifyDayResponse(
            Collections.singletonList(secondCandle),
            false, false, false, false, null
        );
        commitLoadedDay(reopened, secondStart, secondEnd, secondDay);
        String csv = new String(
            Files.readAllBytes(reopened.csvPath),
            StandardCharsets.UTF_8
        );
        boolean passed = held
            && "2024-05-09".equals(next)
            && reopened.completedThrough == secondEnd
            && reopened.rowCount == 2
            && countOccurrences(csv, "2024-05-08 00:04:00,") == 1
            && countOccurrences(csv, "2024-05-09 00:06:00,") == 1
            && intervals.size() == 2;
        System.out.println(passed
            ? "PASS"
            : "FAIL: restart did not continue from the unfinished day");
        return passed;
    }

    private static String validateReturnedBars(
            List<IBar> bars,
            long dayStart,
            long dayEnd) {

        long previous = Long.MIN_VALUE;
        for (int i = 0; i < bars.size(); i++) {
            IBar bar = bars.get(i);
            if (bar == null) {
                return "Returned bar " + i + " is null";
            }
            long time = bar.getTime();
            if (time < dayStart || time > dayEnd) {
                return "Returned candle is outside the requested interval: "
                    + formatUtcMinute(time);
            }
            if (previous != Long.MIN_VALUE && time <= previous) {
                return "Returned candles are not strictly increasing at "
                    + formatUtcMinute(time);
            }
            previous = time;
            try {
                validateCandle(new CandleRecord(
                    time,
                    bar.getOpen(),
                    bar.getHigh(),
                    bar.getLow(),
                    bar.getClose(),
                    bar.getVolume()
                ));
            } catch (IOException error) {
                return error.getMessage();
            }
        }
        return null;
    }

    private static void reportMinuteGaps(
            List<IBar> bars,
            long dayStart,
            long dayEnd) {

        int gaps = 0;
        String firstGap = null;
        long previous = dayStart - 60000L;
        for (int i = 0; i < bars.size(); i++) {
            long time = bars.get(i).getTime();
            if (time - previous > 60000L) {
                gaps++;
                if (firstGap == null) {
                    firstGap = formatUtcMinute(previous + 60000L)
                        + " -> "
                        + formatUtcMinute(time);
                }
            }
            previous = time;
        }
        if (!bars.isEmpty() && dayEnd - previous > 0) {
            gaps++;
            if (firstGap == null) {
                firstGap = formatUtcMinute(previous + 60000L)
                    + " -> "
                    + formatUtcMinute(dayEnd);
            }
        }
        if (gaps == 0) {
            System.out.println("Minute gaps: 0");
        } else {
            System.out.println(
                "Minute gaps: " + gaps + "; first gap " + firstGap
            );
        }
    }

    private static long parseUtcDate(String text) throws ParseException {
        if (text == null || !text.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new ParseException("Invalid UTC date", 0);
        }
        SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd");
        parser.setLenient(false);
        parser.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date parsed = parser.parse(text);
        long dayStart = startOfUtcDay(parsed.getTime());
        if (!formatUtcMinute(dayStart).startsWith(text)) {
            throw new ParseException("Invalid UTC date", 0);
        }
        return dayStart;
    }

    private static final class UtcInterval {
        private final long startMillis;
        private final long endMillis;

        private UtcInterval(long startMillis, long endMillis) {
            this.startMillis = startMillis;
            this.endMillis = endMillis;
        }
    }

    private static long threeYearStartMillis(long endMillis) {
        requireMinuteBoundary(endMillis, "End");
        Calendar utc = utcCalendar(endMillis);
        utc.add(Calendar.YEAR, -3);
        long startMillis = utc.getTimeInMillis();
        requireMinuteBoundary(startMillis, "Three-year start");
        if (startMillis > endMillis) {
            throw new IllegalArgumentException(
                "Three-year start is after the end"
            );
        }
        return startMillis;
    }

    private static List<UtcInterval> planDailyIntervals(
            long windowStart,
            long windowEnd) {

        requireMinuteBoundary(windowStart, "Window start");
        requireMinuteBoundary(windowEnd, "Window end");
        if (windowStart > windowEnd) {
            throw new IllegalArgumentException(
                "Window start is after the window end"
            );
        }

        List<UtcInterval> intervals = new ArrayList<UtcInterval>();
        long cursor = windowStart;
        while (cursor <= windowEnd) {
            long dayEnd = endOfUtcMinuteDay(cursor);
            if (dayEnd > windowEnd) {
                dayEnd = windowEnd;
            }
            intervals.add(new UtcInterval(cursor, dayEnd));
            if (dayEnd == windowEnd) {
                break;
            }
            cursor = dayEnd + 60000L;
        }
        return intervals;
    }

    private static void printThreeYearPlan(long endMillis) {
        long startMillis = threeYearStartMillis(endMillis);
        printWindow("Three-year start", startMillis, endMillis);
    }

    private static void printWindow(
            String startLabel,
            long startMillis,
            long endMillis) {

        List<UtcInterval> intervals = planDailyIntervals(startMillis, endMillis);
        System.out.println(
            startLabel + ": " + UTC_FORMAT.format(new Date(startMillis))
        );
        System.out.println(
            "Final end: " + UTC_FORMAT.format(new Date(endMillis))
        );
        System.out.println("Total calendar days: " + intervals.size());
        System.out.println("First three intervals:");
        printIntervals(
            intervals,
            0,
            Math.min(3, intervals.size())
        );
        System.out.println("Last three intervals:");
        printIntervals(
            intervals,
            Math.max(0, intervals.size() - 3),
            intervals.size()
        );
    }

    private static void printIntervals(
            List<UtcInterval> intervals,
            int from,
            int to) {

        for (int i = from; i < to; i++) {
            UtcInterval interval = intervals.get(i);
            System.out.println(
                "  " + UTC_FORMAT.format(new Date(interval.startMillis))
                + " -> "
                + UTC_FORMAT.format(new Date(interval.endMillis))
            );
        }
    }

    private static boolean runPlannerSelfTest() throws ParseException {
        boolean passed = true;
        passed &= testThreeYearWindow(
            "Normal three-year date range",
            "2026-11-16 04:05:00",
            "2023-11-16 04:05:00 UTC"
        );
        passed &= testThreeYearWindow(
            "29 February year subtraction",
            "2024-02-29 12:34:00",
            "2021-02-28 12:34:00 UTC"
        );
        passed &= testThreeYearWindow(
            "End timestamp exactly at midnight",
            "2026-11-16 00:00:00",
            "2023-11-16 00:00:00 UTC"
        );
        passed &= testThreeYearWindow(
            "Window starting partway through a UTC day",
            "2025-07-04 15:30:00",
            "2022-07-04 15:30:00 UTC"
        );
        passed &= testExplicitWindow(
            "One-day window",
            "2026-04-02 11:20:00",
            "2026-04-02 19:05:00"
        );
        System.out.println("--------------------------------");
        System.out.println(passed
            ? "PLANNER TESTS PASS"
            : "PLANNER TESTS FAIL");
        return passed;
    }

    private static boolean testThreeYearWindow(
            String name,
            String endText,
            String expectedStart) throws ParseException {

        System.out.println("================================");
        System.out.println(name);
        long endMillis = parseUtcMinute(endText);
        long startMillis = threeYearStartMillis(endMillis);
        printWindow("Three-year start", startMillis, endMillis);
        List<String> failures = validateIntervals(
            startMillis,
            endMillis,
            planDailyIntervals(startMillis, endMillis)
        );
        if (!expectedStart.equals(UTC_FORMAT.format(new Date(startMillis)))) {
            failures.add(
                "Expected start " + expectedStart
                + " but got " + UTC_FORMAT.format(new Date(startMillis))
            );
        }
        return reportCase(failures);
    }

    private static boolean testExplicitWindow(
            String name,
            String startText,
            String endText) throws ParseException {

        System.out.println("================================");
        System.out.println(name);
        long startMillis = parseUtcMinute(startText);
        long endMillis = parseUtcMinute(endText);
        printWindow("Window start", startMillis, endMillis);
        List<UtcInterval> intervals = planDailyIntervals(startMillis, endMillis);
        List<String> failures = validateIntervals(
            startMillis,
            endMillis,
            intervals
        );
        if (intervals.size() != 1) {
            failures.add(
                "Expected 1 calendar day, got " + intervals.size()
            );
        }
        return reportCase(failures);
    }

    private static boolean reportCase(List<String> failures) {
        if (failures.isEmpty()) {
            System.out.println("PASS");
            return true;
        }
        for (int i = 0; i < failures.size(); i++) {
            System.out.println("FAIL: " + failures.get(i));
        }
        return false;
    }

    private static List<String> validateIntervals(
            long windowStart,
            long windowEnd,
            List<UtcInterval> intervals) {

        List<String> failures = new ArrayList<String>();
        if (intervals.isEmpty()) {
            failures.add("No intervals produced");
            return failures;
        }
        if (intervals.get(0).startMillis != windowStart) {
            failures.add("First interval does not start at the window start");
        }
        UtcInterval last = intervals.get(intervals.size() - 1);
        if (last.endMillis != windowEnd) {
            failures.add("Final interval does not end at the window end");
        }
        int calendarDays = inclusiveUtcDays(windowStart, windowEnd);
        if (intervals.size() != calendarDays) {
            failures.add(
                "Total calendar days " + intervals.size()
                + " does not match " + calendarDays
            );
        }

        for (int i = 0; i < intervals.size(); i++) {
            UtcInterval interval = intervals.get(i);
            if (interval.startMillis % 60000L != 0
                    || interval.endMillis % 60000L != 0) {
                failures.add("Interval " + i + " is not an M1 boundary");
            }
            if (interval.startMillis > interval.endMillis) {
                failures.add("Interval " + i + " starts after it ends");
            }
            if (!sameUtcDate(interval.startMillis, interval.endMillis)) {
                failures.add("Interval " + i + " crosses a UTC date");
            }
            if (i > 0) {
                UtcInterval previous = intervals.get(i - 1);
                if (interval.startMillis <= previous.endMillis) {
                    failures.add("Interval " + i + " overlaps the previous day");
                }
                if (interval.startMillis != previous.endMillis + 60000L) {
                    failures.add(
                        "Interval " + (i - 1) + " and " + i
                        + " are not exactly one minute apart"
                    );
                }
                if (!isUtcTime(interval.startMillis, 0, 0)) {
                    failures.add(
                        "Interval " + i + " does not start at 00:00 UTC"
                    );
                }
            }
            if (i < intervals.size() - 1
                    && !isUtcTime(interval.endMillis, 23, 59)) {
                failures.add("Interval " + i + " does not end at 23:59 UTC");
            }
        }
        return failures;
    }

    private static int inclusiveUtcDays(long startMillis, long endMillis) {
        Calendar cursor = utcCalendar(startOfUtcDay(startMillis));
        long limit = startOfUtcDay(endMillis);
        int days = 0;
        while (cursor.getTimeInMillis() <= limit) {
            days++;
            cursor.add(Calendar.DATE, 1);
        }
        return days;
    }

    private static boolean sameUtcDate(long left, long right) {
        Calendar a = utcCalendar(left);
        Calendar b = utcCalendar(right);
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
            && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static boolean isUtcTime(long millis, int hour, int minute) {
        Calendar utc = utcCalendar(millis);
        return utc.get(Calendar.HOUR_OF_DAY) == hour
            && utc.get(Calendar.MINUTE) == minute
            && utc.get(Calendar.SECOND) == 0
            && utc.get(Calendar.MILLISECOND) == 0;
    }

    private static long endOfUtcMinuteDay(long millis) {
        Calendar utc = utcCalendar(millis);
        utc.set(Calendar.HOUR_OF_DAY, 23);
        utc.set(Calendar.MINUTE, 59);
        utc.set(Calendar.SECOND, 0);
        utc.set(Calendar.MILLISECOND, 0);
        return utc.getTimeInMillis();
    }

    private static long startOfUtcDay(long millis) {
        Calendar utc = utcCalendar(millis);
        utc.set(Calendar.HOUR_OF_DAY, 0);
        utc.set(Calendar.MINUTE, 0);
        utc.set(Calendar.SECOND, 0);
        utc.set(Calendar.MILLISECOND, 0);
        return utc.getTimeInMillis();
    }

    private static Calendar utcCalendar(long millis) {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.setTimeInMillis(millis);
        return utc;
    }

    private static long parseUtcMinute(String text) throws ParseException {
        SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        parser.setLenient(false);
        parser.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date parsed = parser.parse(text);
        requireMinuteBoundary(parsed.getTime(), text);
        return parsed.getTime();
    }

    private static void requireMinuteBoundary(long millis, String label) {
        if (millis % 60000L != 0) {
            throw new IllegalArgumentException(
                label + " is not an M1 candle boundary"
            );
        }
    }

    private static final class CandleRecord {
        private final long timeMillis;
        private final double open;
        private final double high;
        private final double low;
        private final double close;
        private final double volume;

        private CandleRecord(
                long timeMillis,
                double open,
                double high,
                double low,
                double close,
                double volume) {
            this.timeMillis = timeMillis;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
        }
    }

    private static final class StoreCheckpoint {
        private final long windowStart;
        private final long windowEnd;
        private final long completedThrough;
        private final long lastCandleTime;
        private final long rowCount;

        private StoreCheckpoint(
                long windowStart,
                long windowEnd,
                long completedThrough,
                long lastCandleTime,
                long rowCount) {
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.completedThrough = completedThrough;
            this.lastCandleTime = lastCandleTime;
            this.rowCount = rowCount;
        }
    }

    private static final class CsvTail {
        private final long rowCount;
        private final long lastCandleTime;

        private CsvTail(long rowCount, long lastCandleTime) {
            this.rowCount = rowCount;
            this.lastCandleTime = lastCandleTime;
        }
    }

    private static final class CandleArchive {
        private static final String CSV_NAME = "EURUSD_M1.csv";
        private static final String CHECKPOINT_NAME = "EURUSD_M1.checkpoint";

        private final Path csvPath;
        private final Path checkpointPath;
        private final long windowStart;
        private final long windowEnd;
        private long completedThrough;
        private long lastCandleTime;
        private long rowCount;

        private CandleArchive(
                Path csvPath,
                Path checkpointPath,
                StoreCheckpoint checkpoint) {
            this.csvPath = csvPath;
            this.checkpointPath = checkpointPath;
            this.windowStart = checkpoint.windowStart;
            this.windowEnd = checkpoint.windowEnd;
            this.completedThrough = checkpoint.completedThrough;
            this.lastCandleTime = checkpoint.lastCandleTime;
            this.rowCount = checkpoint.rowCount;
        }

        private static CandleArchive open(
                Path directory,
                long windowStart,
                long windowEnd) throws IOException {

            requireMinuteBoundary(windowStart, "Window start");
            requireMinuteBoundary(windowEnd, "Window end");
            if (windowStart > windowEnd) {
                throw new IOException("Window start is after the window end");
            }
            Files.createDirectories(directory);
            Path csvPath = directory.resolve(CSV_NAME);
            Path checkpointPath = directory.resolve(CHECKPOINT_NAME);
            boolean csvHasBytes = Files.exists(csvPath) && Files.size(csvPath) > 0;
            boolean checkpointExists = Files.exists(checkpointPath);

            if (!csvHasBytes && !checkpointExists) {
                writeHeaderOnly(csvPath);
                StoreCheckpoint created = new StoreCheckpoint(
                    windowStart,
                    windowEnd,
                    -1L,
                    -1L,
                    0L
                );
                atomicWrite(checkpointPath, checkpointBytes(created));
                return new CandleArchive(csvPath, checkpointPath, created);
            }
            if (!csvHasBytes || !checkpointExists) {
                throw new IOException("CSV and checkpoint do not match");
            }

            StoreCheckpoint checkpoint = readCheckpoint(checkpointPath);
            verifyConsistent(csvPath, checkpoint, windowStart, windowEnd);
            return new CandleArchive(csvPath, checkpointPath, checkpoint);
        }

        private int appendChunk(
                long chunkStart,
                long chunkEnd,
                List<CandleRecord> candles) throws IOException {

            requireMinuteBoundary(chunkStart, "Chunk start");
            requireMinuteBoundary(chunkEnd, "Chunk end");
            if (chunkStart > chunkEnd) {
                throw new IOException("Chunk start is after the chunk end");
            }
            if (candles == null) {
                throw new IOException("Candle list is missing");
            }

            long previous = Long.MIN_VALUE;
            for (int i = 0; i < candles.size(); i++) {
                CandleRecord candle = candles.get(i);
                validateCandle(candle);
                if (candle.timeMillis <= previous) {
                    throw new IOException(
                        "Candle timestamps are not strictly increasing at "
                        + formatUtcMinute(candle.timeMillis)
                    );
                }
                previous = candle.timeMillis;
            }

            if (completedThrough >= 0 && chunkEnd <= completedThrough) {
                for (int i = 0; i < candles.size(); i++) {
                    CandleRecord candle = candles.get(i);
                    if (lastCandleTime < 0
                            || candle.timeMillis > lastCandleTime) {
                        throw new IOException(
                            "Completed chunk contains an unsaved candle at "
                            + formatUtcMinute(candle.timeMillis)
                        );
                    }
                }
                return 0;
            }

            List<CandleRecord> fresh = new ArrayList<CandleRecord>();
            for (int i = 0; i < candles.size(); i++) {
                CandleRecord candle = candles.get(i);
                if (lastCandleTime >= 0
                        && candle.timeMillis <= lastCandleTime) {
                    continue;
                }
                if (candle.timeMillis < chunkStart
                        || candle.timeMillis > chunkEnd) {
                    throw new IOException(
                        "Candle is outside the chunk at "
                        + formatUtcMinute(candle.timeMillis)
                    );
                }
                fresh.add(candle);
            }

            if (!fresh.isEmpty()) {
                StringBuilder lines = new StringBuilder();
                for (int i = 0; i < fresh.size(); i++) {
                    lines.append(toCsvLine(fresh.get(i)));
                }
                appendCsv(csvPath, lines.toString());
            }

            long newLast = lastCandleTime;
            long newCount = rowCount;
            if (!fresh.isEmpty()) {
                newLast = fresh.get(fresh.size() - 1).timeMillis;
                newCount = rowCount + fresh.size();
            }
            commit(new StoreCheckpoint(
                windowStart,
                windowEnd,
                chunkEnd,
                newLast,
                newCount
            ));
            return fresh.size();
        }

        private void commit(StoreCheckpoint checkpoint) throws IOException {
            atomicWrite(checkpointPath, checkpointBytes(checkpoint));
            this.completedThrough = checkpoint.completedThrough;
            this.lastCandleTime = checkpoint.lastCandleTime;
            this.rowCount = checkpoint.rowCount;
        }
    }

    private static void validateCandle(CandleRecord candle) throws IOException {
        if (candle.timeMillis % 60000L != 0) {
            throw new IOException(
                "Timestamp is not an M1 boundary: " + candle.timeMillis
            );
        }
        if (!isFinite(candle.open)
                || !isFinite(candle.high)
                || !isFinite(candle.low)
                || !isFinite(candle.close)
                || candle.high < candle.open
                || candle.high < candle.close
                || candle.low > candle.open
                || candle.low > candle.close
                || candle.high < candle.low) {
            throw new IOException(
                "Invalid OHLC at " + formatUtcMinute(candle.timeMillis)
            );
        }
        if (!isFinite(candle.volume) || candle.volume < 0) {
            throw new IOException(
                "Invalid volume at " + formatUtcMinute(candle.timeMillis)
            );
        }
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static String toCsvLine(CandleRecord candle) {
        return formatUtcMinute(candle.timeMillis)
            + ',' + Double.toString(candle.open)
            + ',' + Double.toString(candle.high)
            + ',' + Double.toString(candle.low)
            + ',' + Double.toString(candle.close)
            + ',' + Double.toString(candle.volume)
            + '\n';
    }

    private static String formatUtcMinute(long millis) {
        Calendar utc = utcCalendar(millis);
        return String.format(
            "%04d-%02d-%02d %02d:%02d:%02d",
            Integer.valueOf(utc.get(Calendar.YEAR)),
            Integer.valueOf(utc.get(Calendar.MONTH) + 1),
            Integer.valueOf(utc.get(Calendar.DAY_OF_MONTH)),
            Integer.valueOf(utc.get(Calendar.HOUR_OF_DAY)),
            Integer.valueOf(utc.get(Calendar.MINUTE)),
            Integer.valueOf(utc.get(Calendar.SECOND))
        );
    }

    private static void writeHeaderOnly(Path csvPath) throws IOException {
        if (Files.exists(csvPath) && Files.size(csvPath) > 0) {
            return;
        }
        StandardOpenOption createOption = Files.exists(csvPath)
            ? StandardOpenOption.APPEND
            : StandardOpenOption.CREATE_NEW;
        try (BufferedWriter writer = Files.newBufferedWriter(
                csvPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.WRITE,
                createOption)) {
            writer.write(CSV_HEADER);
            writer.write('\n');
        }
    }

    private static void appendCsv(Path csvPath, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(
                csvPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            channel.write(ByteBuffer.wrap(bytes));
            channel.force(true);
        }
    }

    private static void atomicWrite(Path target, byte[] content)
            throws IOException {
        Path temporary = target.resolveSibling(
            target.getFileName().toString() + ".tmp"
        );
        try (FileChannel channel = FileChannel.open(
                temporary,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(content));
            channel.force(true);
        }
        try {
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (FileAlreadyExistsException ex) {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING
                );
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] checkpointBytes(StoreCheckpoint checkpoint) {
        String text = "windowStart=" + checkpoint.windowStart + "\n"
            + "windowEnd=" + checkpoint.windowEnd + "\n"
            + "completedThrough=" + checkpoint.completedThrough + "\n"
            + "lastCandleTime=" + checkpoint.lastCandleTime + "\n"
            + "rowCount=" + checkpoint.rowCount + "\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static StoreCheckpoint readCheckpoint(Path path) throws IOException {
        long windowStart = Long.MIN_VALUE;
        long windowEnd = Long.MIN_VALUE;
        long completedThrough = Long.MIN_VALUE;
        long lastCandleTime = Long.MIN_VALUE;
        long rowCount = Long.MIN_VALUE;
        try (BufferedReader reader = Files.newBufferedReader(
                path,
                StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                int separator = line.indexOf('=');
                if (separator <= 0) {
                    throw new IOException("Malformed checkpoint");
                }
                String key = line.substring(0, separator);
                long value = Long.parseLong(line.substring(separator + 1));
                if ("windowStart".equals(key)) {
                    windowStart = value;
                } else if ("windowEnd".equals(key)) {
                    windowEnd = value;
                } else if ("completedThrough".equals(key)) {
                    completedThrough = value;
                } else if ("lastCandleTime".equals(key)) {
                    lastCandleTime = value;
                } else if ("rowCount".equals(key)) {
                    rowCount = value;
                }
            }
        } catch (NumberFormatException ex) {
            throw new IOException("Malformed checkpoint");
        }
        if (windowStart == Long.MIN_VALUE
                || windowEnd == Long.MIN_VALUE
                || completedThrough == Long.MIN_VALUE
                || lastCandleTime == Long.MIN_VALUE
                || rowCount < 0) {
            throw new IOException("Malformed checkpoint");
        }
        return new StoreCheckpoint(
            windowStart,
            windowEnd,
            completedThrough,
            lastCandleTime,
            rowCount
        );
    }

    private static void verifyConsistent(
            Path csvPath,
            StoreCheckpoint checkpoint,
            long windowStart,
            long windowEnd) throws IOException {

        if (checkpoint.windowStart != windowStart
                || checkpoint.windowEnd != windowEnd) {
            throw new IOException("CSV and checkpoint do not match");
        }
        CsvTail tail = readCsvTail(csvPath);
        if (tail.rowCount != checkpoint.rowCount
                || tail.lastCandleTime != checkpoint.lastCandleTime) {
            throw new IOException("CSV and checkpoint do not match");
        }
        if (checkpoint.rowCount == 0 && checkpoint.lastCandleTime != -1L) {
            throw new IOException("CSV and checkpoint do not match");
        }
        if (checkpoint.rowCount > 0 && checkpoint.lastCandleTime < 0) {
            throw new IOException("CSV and checkpoint do not match");
        }
    }

    private static CsvTail readCsvTail(Path csvPath) throws IOException {
        long rows = 0;
        long lastTime = -1L;
        try (BufferedReader reader = Files.newBufferedReader(
                csvPath,
                StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (!CSV_HEADER.equals(header)) {
                throw new IOException("Unexpected CSV header");
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] fields = line.split(",", -1);
                if (fields.length != 6) {
                    throw new IOException("Malformed CSV row");
                }
                long time;
                try {
                    time = parseUtcMinute(fields[0]);
                } catch (ParseException ex) {
                    throw new IOException("Invalid CSV timestamp");
                }
                if (lastTime >= 0 && time <= lastTime) {
                    throw new IOException(
                        "CSV timestamps are not strictly increasing"
                    );
                }
                lastTime = time;
                rows++;
            }
        }
        return new CsvTail(rows, lastTime);
    }

    private static boolean runStorageSelfTest() throws Exception {
        Path root = Files.createTempDirectory("dukascopy-storage-test");
        boolean passed = true;
        try {
            passed &= testCsvInitialization(root.resolve("init"));
            passed &= testAppendOnly(root.resolve("append"));
            passed &= testAtomicCheckpoint(root.resolve("atomic"));
            passed &= testConsistency(root.resolve("consistency"));
            passed &= testResumeAndDuplicates(root.resolve("resume"));
            passed &= testEmptyChunk(root.resolve("empty"));
            passed &= testRejection(root.resolve("reject"));
            passed &= testCrashMismatch(root.resolve("crash"));
        } finally {
            deleteRecursively(root);
        }
        System.out.println("--------------------------------");
        System.out.println(passed
            ? "STORAGE TESTS PASS"
            : "STORAGE TESTS FAIL");
        return passed;
    }

    private static boolean testCsvInitialization(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("CSV initialization without truncation");
        long start = parseUtcMinute("2024-01-01 00:00:00");
        long end = parseUtcMinute("2024-01-03 23:59:00");
        CandleArchive archive = CandleArchive.open(directory, start, end);
        byte[] original = Files.readAllBytes(archive.csvPath);
        CandleArchive.open(directory, start, end);
        List<String> failures = new ArrayList<String>();
        if (!Arrays.equals(original, Files.readAllBytes(archive.csvPath))) {
            failures.add("Opening an existing CSV truncated or rewrote it");
        }
        if (!new String(original, StandardCharsets.UTF_8).equals(
                CSV_HEADER + "\n")) {
            failures.add("New CSV did not contain only the header");
        }
        return reportCase(failures);
    }

    private static boolean testAppendOnly(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Append-only chronological writing");
        long start = parseUtcMinute("2024-01-01 00:00:00");
        long end = parseUtcMinute("2024-01-03 23:59:00");
        CandleArchive archive = CandleArchive.open(directory, start, end);
        int firstAdded = archive.appendChunk(
            start,
            parseUtcMinute("2024-01-01 23:59:00"),
            Arrays.asList(
                validCandle("2024-01-01 00:00:00", 1.10, 1.12, 1.09, 1.11, 10),
                validCandle("2024-01-01 00:01:00", 1.11, 1.13, 1.10, 1.12, 11)
            )
        );
        byte[] afterFirst = Files.readAllBytes(archive.csvPath);
        int secondAdded = archive.appendChunk(
            parseUtcMinute("2024-01-02 00:00:00"),
            parseUtcMinute("2024-01-02 23:59:00"),
            Arrays.asList(
                validCandle("2024-01-02 00:00:00", 1.12, 1.14, 1.11, 1.13, 12)
            )
        );
        String text = new String(
            Files.readAllBytes(archive.csvPath),
            StandardCharsets.UTF_8
        );
        List<String> failures = new ArrayList<String>();
        if (firstAdded != 2 || secondAdded != 1) {
            failures.add("Appended row counts were " + firstAdded + " and " + secondAdded);
        }
        if (!text.startsWith(new String(afterFirst, StandardCharsets.UTF_8))) {
            failures.add("Second append rewrote earlier CSV rows");
        }
        if (!text.contains("2024-01-01 00:00:00,")
                || !text.contains("2024-01-01 00:01:00,")
                || !text.endsWith("2024-01-02 00:00:00,1.12,1.14,1.11,1.13,12.0\n")) {
            failures.add("CSV rows are missing or out of order");
        }
        return reportCase(failures);
    }

    private static boolean testAtomicCheckpoint(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Atomic checkpoint storage");
        long start = parseUtcMinute("2024-01-01 00:00:00");
        long end = parseUtcMinute("2024-01-03 23:59:00");
        CandleArchive archive = CandleArchive.open(directory, start, end);
        archive.appendChunk(
            start,
            parseUtcMinute("2024-01-01 23:59:00"),
            Arrays.asList(
                validCandle("2024-01-01 00:05:00", 1.2, 1.3, 1.1, 1.25, 4)
            )
        );
        StoreCheckpoint checkpoint = readCheckpoint(archive.checkpointPath);
        boolean temporaryLeft = false;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                if (child.getFileName().toString().endsWith(".tmp")) {
                    temporaryLeft = true;
                }
            }
        }
        List<String> failures = new ArrayList<String>();
        if (temporaryLeft) {
            failures.add("Checkpoint temporary file was left behind");
        }
        if (checkpoint.rowCount != 1
                || checkpoint.lastCandleTime != parseUtcMinute("2024-01-01 00:05:00")
                || checkpoint.completedThrough != parseUtcMinute("2024-01-01 23:59:00")) {
            failures.add("Checkpoint does not describe the appended chunk");
        }
        String checkpointText = new String(
            Files.readAllBytes(archive.checkpointPath),
            StandardCharsets.UTF_8
        );
        if (!checkpointText.startsWith("windowStart=")
                || !checkpointText.endsWith("\n")
                || checkpointText.contains("rowCount=\n")) {
            failures.add("Checkpoint text is incomplete");
        }
        return reportCase(failures);
    }

    private static boolean testConsistency(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("CSV/checkpoint consistency verification");
        long start = parseUtcMinute("2024-01-01 00:00:00");
        long end = parseUtcMinute("2024-01-03 23:59:00");
        CandleArchive archive = CandleArchive.open(directory, start, end);
        archive.appendChunk(
            start,
            parseUtcMinute("2024-01-01 23:59:00"),
            Arrays.asList(
                validCandle("2024-01-01 00:00:00", 1.1, 1.2, 1.0, 1.15, 3),
                validCandle("2024-01-01 00:01:00", 1.15, 1.16, 1.14, 1.15, 2)
            )
        );
        CandleArchive reopened = CandleArchive.open(directory, start, end);
        CsvTail tail = readCsvTail(archive.csvPath);
        StoreCheckpoint checkpoint = readCheckpoint(archive.checkpointPath);
        List<String> failures = new ArrayList<String>();
        if (reopened.rowCount != 2 || tail.rowCount != checkpoint.rowCount) {
            failures.add("Reopened archive does not match the CSV tail");
        }
        if (tail.lastCandleTime != checkpoint.lastCandleTime) {
            failures.add("Checkpoint last candle does not match the CSV");
        }
        return reportCase(failures);
    }

    private static boolean testResumeAndDuplicates(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Resume and duplicate prevention");
        long start = parseUtcMinute("2024-01-01 00:00:00");
        long end = parseUtcMinute("2024-01-05 23:59:00");
        CandleArchive archive = CandleArchive.open(directory, start, end);
        archive.appendChunk(
            start,
            parseUtcMinute("2024-01-01 23:59:00"),
            Arrays.asList(
                validCandle("2024-01-01 00:00:00", 1.1, 1.2, 1.0, 1.11, 5),
                validCandle("2024-01-01 00:01:00", 1.11, 1.2, 1.1, 1.12, 6)
            )
        );
        byte[] beforeResume = Files.readAllBytes(archive.csvPath);
        CandleArchive resumed = CandleArchive.open(directory, start, end);
        int added = resumed.appendChunk(
            parseUtcMinute("2024-01-01 00:00:00"),
            parseUtcMinute("2024-01-02 23:59:00"),
            Arrays.asList(
                validCandle("2024-01-01 00:01:00", 1.11, 1.2, 1.1, 1.12, 6),
                validCandle("2024-01-02 00:00:00", 1.12, 1.3, 1.11, 1.2, 7)
            )
        );
        int duplicateReplay = resumed.appendChunk(
            parseUtcMinute("2024-01-01 00:00:00"),
            parseUtcMinute("2024-01-02 23:59:00"),
            Arrays.asList(
                validCandle("2024-01-01 00:00:00", 1.1, 1.2, 1.0, 1.11, 5),
                validCandle("2024-01-01 00:01:00", 1.11, 1.2, 1.1, 1.12, 6),
                validCandle("2024-01-02 00:00:00", 1.12, 1.3, 1.11, 1.2, 7)
            )
        );
        String text = new String(
            Files.readAllBytes(resumed.csvPath),
            StandardCharsets.UTF_8
        );
        List<String> failures = new ArrayList<String>();
        if (added != 1 || duplicateReplay != 0 || resumed.rowCount != 3) {
            failures.add(
                "Resume added " + added
                + ", replay added " + duplicateReplay
                + ", rows " + resumed.rowCount
            );
        }
        if (!text.startsWith(new String(beforeResume, StandardCharsets.UTF_8))) {
            failures.add("Resume rewrote the existing CSV prefix");
        }
        if (countOccurrences(text, "2024-01-01 00:01:00,") != 1) {
            failures.add("Duplicate timestamp was written again");
        }
        return reportCase(failures);
    }

    private static boolean testEmptyChunk(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Empty chunk checkpoint advancement");
        long start = parseUtcMinute("2024-01-01 00:00:00");
        long end = parseUtcMinute("2024-01-05 23:59:00");
        long emptyEnd = parseUtcMinute("2024-01-03 23:59:00");
        CandleArchive archive = CandleArchive.open(directory, start, end);
        archive.appendChunk(
            start,
            parseUtcMinute("2024-01-01 23:59:00"),
            Arrays.asList(
                validCandle("2024-01-01 00:10:00", 1.1, 1.2, 1.0, 1.1, 8)
            )
        );
        byte[] csvBefore = Files.readAllBytes(archive.csvPath);
        long rowsBefore = archive.rowCount;
        int added = archive.appendChunk(
            parseUtcMinute("2024-01-03 00:00:00"),
            emptyEnd,
            new ArrayList<CandleRecord>()
        );
        CandleArchive reopened = CandleArchive.open(directory, start, end);
        List<String> failures = new ArrayList<String>();
        if (added != 0 || reopened.rowCount != rowsBefore) {
            failures.add("Empty chunk changed the CSV row count");
        }
        if (!Arrays.equals(csvBefore, Files.readAllBytes(archive.csvPath))) {
            failures.add("Empty chunk modified the CSV");
        }
        if (reopened.completedThrough != emptyEnd) {
            failures.add("Empty chunk did not advance the checkpoint");
        }
        if (reopened.appendChunk(
                parseUtcMinute("2024-01-03 00:00:00"),
                emptyEnd,
                new ArrayList<CandleRecord>()) != 0) {
            failures.add("Repeating a completed empty chunk wrote data");
        }
        return reportCase(failures);
    }

    private static boolean testRejection(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println(
            "Invalid OHLC, volume, timestamps and backward ordering rejection"
        );
        long start = parseUtcMinute("2024-01-01 00:00:00");
        long end = parseUtcMinute("2024-01-05 23:59:00");
        long chunkEnd = parseUtcMinute("2024-01-01 23:59:00");
        CandleArchive archive = CandleArchive.open(directory, start, end);
        archive.appendChunk(
            start,
            chunkEnd,
            Arrays.asList(
                validCandle("2024-01-01 00:00:00", 1.1, 1.2, 1.0, 1.15, 9)
            )
        );
        byte[] csvBefore = Files.readAllBytes(archive.csvPath);
        byte[] checkpointBefore = Files.readAllBytes(archive.checkpointPath);
        List<String> failures = new ArrayList<String>();
        expectRejection(
            failures,
            archive,
            csvBefore,
            checkpointBefore,
            "high below open",
            start,
            chunkEnd,
            Arrays.asList(
                new CandleRecord(
                    parseUtcMinute("2024-01-01 00:02:00"),
                    1.10, 1.09, 1.08, 1.09, 1
                )
            )
        );
        expectRejection(
            failures,
            archive,
            csvBefore,
            checkpointBefore,
            "low above close",
            start,
            chunkEnd,
            Arrays.asList(
                new CandleRecord(
                    parseUtcMinute("2024-01-01 00:02:00"),
                    1.10, 1.12, 1.11, 1.10, 1
                )
            )
        );
        expectRejection(
            failures,
            archive,
            csvBefore,
            checkpointBefore,
            "high below low",
            start,
            chunkEnd,
            Arrays.asList(
                new CandleRecord(
                    parseUtcMinute("2024-01-01 00:02:00"),
                    1.10, 1.05, 1.20, 1.10, 1
                )
            )
        );
        expectRejection(
            failures,
            archive,
            csvBefore,
            checkpointBefore,
            "negative volume",
            start,
            chunkEnd,
            Arrays.asList(
                validCandle("2024-01-01 00:02:00", 1.1, 1.2, 1.0, 1.15, -1)
            )
        );
        expectRejection(
            failures,
            archive,
            csvBefore,
            checkpointBefore,
            "NaN volume",
            start,
            chunkEnd,
            Arrays.asList(
                validCandle(
                    "2024-01-01 00:02:00",
                    1.1, 1.2, 1.0, 1.15, Double.NaN
                )
            )
        );
        expectRejection(
            failures,
            archive,
            csvBefore,
            checkpointBefore,
            "off-minute timestamp",
            start,
            chunkEnd,
            Arrays.asList(
                new CandleRecord(
                    parseUtcMinute("2024-01-01 00:02:00") + 30000L,
                    1.1, 1.2, 1.0, 1.15, 1
                )
            )
        );
        expectRejection(
            failures,
            archive,
            csvBefore,
            checkpointBefore,
            "backward timestamp",
            start,
            chunkEnd,
            Arrays.asList(
                validCandle("2024-01-01 00:04:00", 1.1, 1.2, 1.0, 1.15, 1),
                validCandle("2024-01-01 00:03:00", 1.1, 1.2, 1.0, 1.15, 1)
            )
        );
        return reportCase(failures);
    }

    private static void expectRejection(
            List<String> failures,
            CandleArchive archive,
            byte[] csvBefore,
            byte[] checkpointBefore,
            String label,
            long chunkStart,
            long chunkEnd,
            List<CandleRecord> candles) throws IOException {

        boolean rejected = false;
        try {
            archive.appendChunk(chunkStart, chunkEnd, candles);
        } catch (IOException ex) {
            rejected = true;
        }
        if (!rejected) {
            failures.add(label + " was accepted");
        }
        if (!Arrays.equals(csvBefore, Files.readAllBytes(archive.csvPath))
                || !Arrays.equals(
                    checkpointBefore,
                    Files.readAllBytes(archive.checkpointPath))) {
            failures.add(label + " modified stored files");
        }
    }

    private static boolean testCrashMismatch(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Crash mismatch detection");
        long start = parseUtcMinute("2024-01-01 00:00:00");
        long end = parseUtcMinute("2024-01-03 23:59:00");
        CandleArchive archive = CandleArchive.open(directory, start, end);
        archive.appendChunk(
            start,
            parseUtcMinute("2024-01-01 23:59:00"),
            Arrays.asList(
                validCandle("2024-01-01 00:00:00", 1.1, 1.2, 1.0, 1.15, 4)
            )
        );
        appendCsv(
            archive.csvPath,
            toCsvLine(validCandle(
                "2024-01-01 00:01:00",
                1.15, 1.16, 1.14, 1.15, 4
            ))
        );
        List<String> failures = new ArrayList<String>();
        if (!openFails(directory, start, end)) {
            failures.add("CSV extended beyond the checkpoint was accepted");
        }
        return reportCase(failures);
    }

    private static boolean openFails(
            Path directory,
            long windowStart,
            long windowEnd) {
        try {
            CandleArchive.open(directory, windowStart, windowEnd);
            return false;
        } catch (IOException ex) {
            return ex.getMessage() != null
                && ex.getMessage().indexOf("do not match") >= 0;
        }
    }

    private static CandleRecord validCandle(
            String time,
            double open,
            double high,
            double low,
            double close,
            double volume) throws ParseException {
        return new CandleRecord(
            parseUtcMinute(time),
            open,
            high,
            low,
            close,
            volume
        );
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int from = 0;
        while (true) {
            int found = text.indexOf(token, from);
            if (found < 0) {
                return count;
            }
            count++;
            from = found + token.length();
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    private static final String LEDGER_FILE_NAME =
        "EURUSD_M1_3y.ledger.properties";
    private static final String STAGING_DIRECTORY_NAME =
        "EURUSD_M1_3y_staging";
    private static final String FINAL_CSV_NAME = "EURUSD_M1_3y.csv";
    private static final String SCHEDULER_FILE_NAME =
        "EURUSD_M1_3y.scheduler.properties";
    private static final int SCHEDULER_SCHEMA_VERSION = 1;
    private static final int PRODUCTION_DEFAULT_MAX_DAYS = 5;
    private static final long PRODUCTION_ATTEMPT_BUDGET_MS = 120000L;
    private static final Path PRODUCTION_DIRECTORY = Paths.get(
        "output",
        "EURUSD_M1_3y_production"
    );
    static final String EXPLICIT_RECOVERY_COMMAND = "download-3y-dates";
    static final String EXPLICIT_RECOVERY_FIRST = "2023-11-18";
    static final String EXPLICIT_RECOVERY_SECOND = "2026-11-15";
    private static Path explicitRecoveryCache;
    private static RecoveryTransportLog explicitRecoveryTransport;
    static final long FIXED_PRODUCTION_WINDOW_START = 1700116860000L;
    static final long FIXED_PRODUCTION_WINDOW_END = 1794811260000L;
    private static final int LEDGER_SCHEMA_VERSION = 2;
    private static boolean failNextStagingWrite;
    private static final String STATUS_DATA_SAVED = "DATA_SAVED";
    private static final String STATUS_CONFIRMED_EMPTY = "CONFIRMED_EMPTY";
    private static final String STATUS_UNKNOWN = "UNKNOWN";
    private static final String STATUS_FAILED = "FAILED";
    private static final String CONFIRM_METHOD = "explicit-cli";
    private static final String CONFIRM_REASON =
        "Verified market closure using an authoritative trading calendar";
    private static final int MAX_LEDGER_DETAIL = 200;

    private static final class DayRecord {
        private final long dayStart;
        private final String status;
        private final int attempts;
        private final long lastAttemptMillis;
        private final long rowCount;
        private final long firstCandle;
        private final long lastCandle;
        private final String detail;
        private final String sha256;
        private final long confirmAt;
        private final String confirmReason;
        private final String confirmMethod;

        private DayRecord(
                long dayStart,
                String status,
                int attempts,
                long lastAttemptMillis,
                long rowCount,
                long firstCandle,
                long lastCandle,
                String detail,
                String sha256,
                long confirmAt,
                String confirmReason,
                String confirmMethod) {
            this.dayStart = dayStart;
            this.status = status;
            this.attempts = attempts;
            this.lastAttemptMillis = lastAttemptMillis;
            this.rowCount = rowCount;
            this.firstCandle = firstCandle;
            this.lastCandle = lastCandle;
            this.detail = detail;
            this.sha256 = sha256;
            this.confirmAt = confirmAt;
            this.confirmReason = confirmReason;
            this.confirmMethod = confirmMethod;
        }
    }

    private static final class LedgerCompleteness {
        private final boolean complete;
        private final int unresolvedCount;
        private final long firstUnresolvedDay;

        private LedgerCompleteness(
                boolean complete,
                int unresolvedCount,
                long firstUnresolvedDay) {
            this.complete = complete;
            this.unresolvedCount = unresolvedCount;
            this.firstUnresolvedDay = firstUnresolvedDay;
        }
    }

    private static final class DayLedger {
        private final Path path;
        private final long windowStart;
        private final long windowEnd;
        private TreeMap<Long, DayRecord> days;

        private DayLedger(
                Path path,
                long windowStart,
                long windowEnd,
                TreeMap<Long, DayRecord> days) {
            this.path = path;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.days = days;
        }

        private static DayLedger open(
                Path directory,
                long windowStart,
                long windowEnd) throws IOException {

            requireMinuteBoundary(windowStart, "Ledger window start");
            requireMinuteBoundary(windowEnd, "Ledger window end");
            if (windowStart > windowEnd) {
                throw new IOException("Ledger window start is after the end");
            }
            Files.createDirectories(directory);
            Path path = directory.resolve(LEDGER_FILE_NAME);
            if (!Files.exists(path)) {
                DayLedger created = new DayLedger(
                    path,
                    windowStart,
                    windowEnd,
                    new TreeMap<Long, DayRecord>()
                );
                created.persist(created.days);
                return created;
            }
            if (Files.size(path) == 0) {
                throw new IOException("Malformed ledger");
            }
            return load(path, windowStart, windowEnd);
        }

        private static DayLedger openExisting(Path directory) throws IOException {
            Path path = directory.resolve(LEDGER_FILE_NAME);
            if (!Files.isRegularFile(path)) {
                throw new IOException("Ledger was not found");
            }
            if (Files.size(path) == 0) {
                throw new IOException("Malformed ledger");
            }
            long[] window = readLedgerWindow(path);
            return load(path, window[0], window[1]);
        }

        private static long[] readLedgerWindow(Path path) throws IOException {
            long windowStart = Long.MIN_VALUE;
            long windowEnd = Long.MIN_VALUE;
            boolean sawStart = false;
            boolean sawEnd = false;
            try (BufferedReader reader = Files.newBufferedReader(
                    path,
                    StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int separator = line.indexOf('=');
                    if (separator <= 0) {
                        throw new IOException("Malformed ledger");
                    }
                    String key = line.substring(0, separator);
                    String value = line.substring(separator + 1);
                    if ("windowStart".equals(key)) {
                        if (sawStart) {
                            throw new IOException("Malformed ledger");
                        }
                        windowStart = parseCanonicalLong(value);
                        sawStart = true;
                    } else if ("windowEnd".equals(key)) {
                        if (sawEnd) {
                            throw new IOException("Malformed ledger");
                        }
                        windowEnd = parseCanonicalLong(value);
                        sawEnd = true;
                    }
                }
            } catch (NumberFormatException ex) {
                throw new IOException("Malformed ledger");
            }
            if (!sawStart || !sawEnd) {
                throw new IOException("Malformed ledger");
            }
            return new long[] {windowStart, windowEnd};
        }

        private void record(
                long dayStart,
                String status,
                long attemptMillis,
                long rowCount,
                long firstCandle,
                long lastCandle,
                String detail) throws IOException {

            record(
                dayStart,
                status,
                attemptMillis,
                rowCount,
                firstCandle,
                lastCandle,
                detail,
                ""
            );
        }

        private void record(
                long dayStart,
                String status,
                long attemptMillis,
                long rowCount,
                long firstCandle,
                long lastCandle,
                String detail,
                String sha256) throws IOException {

            if (dayStart != startOfUtcDay(dayStart)) {
                throw new IOException("Ledger day is not a UTC date boundary");
            }
            if (!covers(dayStart)) {
                throw new IOException("Day is outside the ledger window");
            }
            DayRecord existing = days.get(Long.valueOf(dayStart));
            int attempts = 1;
            if (existing != null) {
                if (isResolvedStatus(existing.status)) {
                    throw new IOException(
                        "Invalid ledger transition from " + existing.status
                    );
                }
                if (!STATUS_UNKNOWN.equals(existing.status)
                        && !STATUS_FAILED.equals(existing.status)) {
                    throw new IOException(
                        "Invalid ledger transition from " + existing.status
                    );
                }
                if (attemptMillis < existing.lastAttemptMillis) {
                    throw new IOException("Last attempt time moved backwards");
                }
                if (existing.attempts == Integer.MAX_VALUE) {
                    throw new IOException("Attempt count cannot increase");
                }
                attempts = existing.attempts + 1;
            }
            DayRecord created = dayRecord(
                dayStart,
                status,
                attempts,
                attemptMillis,
                rowCount,
                firstCandle,
                lastCandle,
                detail,
                sha256
            );
            TreeMap<Long, DayRecord> updated = new TreeMap<Long, DayRecord>(days);
            updated.put(Long.valueOf(dayStart), created);
            persist(updated);
            this.days = updated;
        }

        private void updateSaved(
                long dayStart,
                long attemptMillis,
                long rowCount,
                long firstCandle,
                long lastCandle,
                String detail,
                String sha256) throws IOException {

            if (dayStart != startOfUtcDay(dayStart)) {
                throw new IOException("Ledger day is not a UTC date boundary");
            }
            if (!covers(dayStart)) {
                throw new IOException("Day is outside the ledger window");
            }
            DayRecord existing = days.get(Long.valueOf(dayStart));
            if (existing == null || !STATUS_DATA_SAVED.equals(existing.status)) {
                throw new IOException("Saved-day replacement requires DATA_SAVED");
            }
            if (attemptMillis < existing.lastAttemptMillis) {
                throw new IOException("Last attempt time moved backwards");
            }
            if (existing.attempts == Integer.MAX_VALUE) {
                throw new IOException("Attempt count cannot increase");
            }
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IOException("Malformed ledger digest");
            }
            DayRecord created = dayRecord(
                dayStart,
                STATUS_DATA_SAVED,
                existing.attempts + 1,
                attemptMillis,
                rowCount,
                firstCandle,
                lastCandle,
                detail,
                sha256
            );
            TreeMap<Long, DayRecord> updated = new TreeMap<Long, DayRecord>(days);
            updated.put(Long.valueOf(dayStart), created);
            persist(updated);
            this.days = updated;
        }

        private void ensureUnknown(long dayStart) throws IOException {
            if (dayStart != startOfUtcDay(dayStart)) {
                throw new IOException("Ledger day is not a UTC date boundary");
            }
            if (!covers(dayStart)) {
                throw new IOException("Day is outside the ledger window");
            }
            if (days.get(Long.valueOf(dayStart)) != null) {
                return;
            }
            DayRecord created = dayRecord(
                dayStart,
                STATUS_UNKNOWN,
                0,
                0L,
                0L,
                -1L,
                -1L,
                "Not requested",
                ""
            );
            TreeMap<Long, DayRecord> updated = new TreeMap<Long, DayRecord>(days);
            updated.put(Long.valueOf(dayStart), created);
            persist(updated);
            this.days = updated;
        }

        private void initializeUnknownDays(List<Long> dayStarts) throws IOException {
            TreeMap<Long, DayRecord> updated = new TreeMap<Long, DayRecord>(days);
            for (int i = 0; i < dayStarts.size(); i++) {
                long dayStart = dayStarts.get(i).longValue();
                if (dayStart != startOfUtcDay(dayStart)) {
                    throw new IOException("Ledger day is not a UTC date boundary");
                }
                if (!covers(dayStart)) {
                    throw new IOException("Day is outside the ledger window");
                }
                if (updated.get(Long.valueOf(dayStart)) != null) {
                    continue;
                }
                updated.put(Long.valueOf(dayStart), dayRecord(
                    dayStart,
                    STATUS_UNKNOWN,
                    0,
                    0L,
                    0L,
                    -1L,
                    -1L,
                    "Not requested",
                    ""
                ));
            }
            persist(updated);
            this.days = updated;
        }

        private void persistAttempt(long dayStart, long attemptMillis) throws IOException {
            if (dayStart != startOfUtcDay(dayStart)) {
                throw new IOException("Ledger day is not a UTC date boundary");
            }
            DayRecord existing = days.get(Long.valueOf(dayStart));
            if (existing == null) {
                throw new IOException("Retry day was not initialized");
            }
            if (isResolvedStatus(existing.status)) {
                throw new IOException(
                    "Invalid ledger transition from " + existing.status
                );
            }
            if (attemptMillis < existing.lastAttemptMillis) {
                throw new IOException("Last attempt time moved backwards");
            }
            if (existing.attempts == Integer.MAX_VALUE) {
                throw new IOException("Attempt count cannot increase");
            }
            DayRecord created = dayRecord(
                dayStart,
                existing.status,
                existing.attempts + 1,
                attemptMillis,
                existing.rowCount,
                existing.firstCandle,
                existing.lastCandle,
                existing.detail,
                existing.sha256
            );
            TreeMap<Long, DayRecord> updated = new TreeMap<Long, DayRecord>(days);
            updated.put(Long.valueOf(dayStart), created);
            persist(updated);
            this.days = updated;
        }

        private void finishAttempt(
                long dayStart,
                String status,
                long rowCount,
                long firstCandle,
                long lastCandle,
                String detail,
                String sha256) throws IOException {

            if (STATUS_CONFIRMED_EMPTY.equals(status)) {
                throw new IOException(
                    "CONFIRMED_EMPTY is not an automatic retry result"
                );
            }
            DayRecord existing = days.get(Long.valueOf(dayStart));
            if (existing == null || isResolvedStatus(existing.status)) {
                throw new IOException("Attempt was not persisted");
            }
            if (existing.attempts < 1) {
                throw new IOException("Attempt was not persisted");
            }
            if (STATUS_DATA_SAVED.equals(status)
                    && (sha256 == null || !sha256.matches("[0-9a-f]{64}"))) {
                throw new IOException("Malformed ledger digest");
            }
            DayRecord created = dayRecord(
                dayStart,
                status,
                existing.attempts,
                existing.lastAttemptMillis,
                rowCount,
                firstCandle,
                lastCandle,
                detail,
                sha256
            );
            TreeMap<Long, DayRecord> updated = new TreeMap<Long, DayRecord>(days);
            updated.put(Long.valueOf(dayStart), created);
            persist(updated);
            this.days = updated;
        }

        private LedgerCompleteness assess() {
            int unresolved = 0;
            long first = -1L;
            List<UtcInterval> intervals = planDailyIntervals(
                windowStart,
                windowEnd
            );
            for (int i = 0; i < intervals.size(); i++) {
                long day = startOfUtcDay(intervals.get(i).startMillis);
                DayRecord record = days.get(Long.valueOf(day));
                boolean resolved = record != null && isResolvedStatus(record.status);
                if (!resolved) {
                    unresolved++;
                    if (first < 0L) {
                        first = day;
                    }
                }
            }
            return new LedgerCompleteness(unresolved == 0, unresolved, first);
        }

        private boolean covers(long dayStart) {
            List<UtcInterval> intervals = planDailyIntervals(
                windowStart,
                windowEnd
            );
            for (int i = 0; i < intervals.size(); i++) {
                if (startOfUtcDay(intervals.get(i).startMillis) == dayStart) {
                    return true;
                }
            }
            return false;
        }

        private boolean confirmEmpty(
                long dayStart,
                long confirmAt,
                String reason) throws IOException {

            DayRecord existing = days.get(Long.valueOf(dayStart));
            if (existing == null) {
                throw new IOException("Missing ledger record");
            }
            if (STATUS_CONFIRMED_EMPTY.equals(existing.status)) {
                return false;
            }
            if (!STATUS_UNKNOWN.equals(existing.status)
                    && !STATUS_FAILED.equals(existing.status)) {
                throw new IOException(
                    "Invalid ledger transition from " + existing.status
                );
            }
            DayRecord created = dayRecord(
                dayStart,
                STATUS_CONFIRMED_EMPTY,
                existing.attempts,
                existing.lastAttemptMillis,
                0L,
                -1L,
                -1L,
                existing.detail,
                "",
                confirmAt,
                reason,
                CONFIRM_METHOD
            );
            TreeMap<Long, DayRecord> updated = new TreeMap<Long, DayRecord>(days);
            updated.put(Long.valueOf(dayStart), created);
            persist(updated);
            days = updated;
            return true;
        }

        private void persist(TreeMap<Long, DayRecord> records) throws IOException {
            atomicWrite(path, ledgerBytes(windowStart, windowEnd, records));
        }
    }

    private static boolean isResolvedStatus(String status) {
        return STATUS_DATA_SAVED.equals(status)
            || STATUS_CONFIRMED_EMPTY.equals(status);
    }

    private static DayRecord dayRecord(
            long dayStart,
            String status,
            int attempts,
            long attemptMillis,
            long rowCount,
            long firstCandle,
            long lastCandle,
            String detail,
            String sha256) throws IOException {

        return dayRecord(
            dayStart,
            status,
            attempts,
            attemptMillis,
            rowCount,
            firstCandle,
            lastCandle,
            detail,
            sha256,
            -1L,
            "",
            ""
        );
    }

    private static DayRecord dayRecord(
            long dayStart,
            String status,
            int attempts,
            long attemptMillis,
            long rowCount,
            long firstCandle,
            long lastCandle,
            String detail,
            String sha256,
            long confirmAt,
            String confirmReason,
            String confirmMethod) throws IOException {

        if (!isLedgerStatus(status)) {
            throw new IOException("Malformed ledger status");
        }
        if (attempts < 0) {
            throw new IOException("Malformed ledger attempt count");
        }
        if (attempts == 0
                && (attemptMillis != 0L
                    || (!STATUS_UNKNOWN.equals(status)
                        && !STATUS_CONFIRMED_EMPTY.equals(status)))) {
            throw new IOException("Malformed ledger attempt count");
        }
        if (attemptMillis < 0L) {
            throw new IOException("Malformed ledger attempt time");
        }
        if (detail == null || detail.length() > MAX_LEDGER_DETAIL) {
            throw new IOException("Malformed ledger detail");
        }
        for (int i = 0; i < detail.length(); i++) {
            if (detail.charAt(i) < 32) {
                throw new IOException("Malformed ledger detail");
            }
        }
        if (sha256 == null
                || (sha256.length() != 0 && !sha256.matches("[0-9a-f]{64}"))) {
            throw new IOException("Malformed ledger digest");
        }
        if (confirmReason == null || confirmMethod == null) {
            throw new IOException("Malformed confirmation");
        }
        for (int i = 0; i < confirmReason.length(); i++) {
            if (confirmReason.charAt(i) < 32) {
                throw new IOException("Malformed confirmation");
            }
        }
        boolean unconfirmed = confirmAt == -1L
            && confirmReason.length() == 0
            && confirmMethod.length() == 0;
        boolean explicit = confirmAt >= 0L
            && confirmReason.length() > 0
            && confirmReason.length() <= MAX_LEDGER_DETAIL
            && CONFIRM_METHOD.equals(confirmMethod);
        if (!unconfirmed && !explicit) {
            throw new IOException("Malformed confirmation");
        }
        if (explicit && !STATUS_CONFIRMED_EMPTY.equals(status)) {
            throw new IOException("Malformed confirmation");
        }
        boolean candlesAbsent = firstCandle < 0L && lastCandle < 0L;
        if (STATUS_DATA_SAVED.equals(status)) {
            if (rowCount < 1L || candlesAbsent) {
                throw new IOException("Malformed saved-day record");
            }
            validateLedgerCandles(dayStart, firstCandle, lastCandle);
        } else if (rowCount != 0L || !candlesAbsent || sha256.length() != 0) {
            throw new IOException("Malformed unresolved-day record");
        } else if (firstCandle != -1L || lastCandle != -1L) {
            throw new IOException("Malformed unresolved-day record");
        }
        return new DayRecord(
            dayStart,
            status,
            attempts,
            attemptMillis,
            rowCount,
            firstCandle,
            lastCandle,
            detail,
            sha256,
            confirmAt,
            confirmReason,
            confirmMethod
        );
    }

    private static void validateLedgerCandles(
            long dayStart,
            long firstCandle,
            long lastCandle) throws IOException {

        requireMinuteBoundary(firstCandle, "First candle");
        requireMinuteBoundary(lastCandle, "Last candle");
        if (!sameUtcDate(dayStart, firstCandle)
                || !sameUtcDate(dayStart, lastCandle)
                || firstCandle > lastCandle) {
            throw new IOException("Malformed saved-day candles");
        }
    }

    private static boolean isLedgerStatus(String status) {
        return STATUS_DATA_SAVED.equals(status)
            || STATUS_CONFIRMED_EMPTY.equals(status)
            || STATUS_UNKNOWN.equals(status)
            || STATUS_FAILED.equals(status);
    }

    private static byte[] ledgerBytes(
            long windowStart,
            long windowEnd,
            TreeMap<Long, DayRecord> records) {

        StringBuilder text = new StringBuilder();
        text.append("windowStart=").append(windowStart).append('\n');
        text.append("windowEnd=").append(windowEnd).append('\n');
        text.append("schemaVersion=").append(LEDGER_SCHEMA_VERSION).append('\n');
        for (Map.Entry<Long, DayRecord> entry : records.entrySet()) {
            DayRecord record = entry.getValue();
            String prefix = "day." + utcDate(record.dayStart) + ".";
            text.append(prefix).append("status=").append(record.status).append('\n');
            text.append(prefix).append("attempts=").append(record.attempts).append('\n');
            text.append(prefix).append("lastAttempt=")
                .append(record.lastAttemptMillis).append('\n');
            text.append(prefix).append("rowCount=").append(record.rowCount).append('\n');
            text.append(prefix).append("firstCandle=")
                .append(record.firstCandle).append('\n');
            text.append(prefix).append("lastCandle=")
                .append(record.lastCandle).append('\n');
            text.append(prefix).append("detail=").append(record.detail).append('\n');
            text.append(prefix).append("sha256=").append(record.sha256).append('\n');
            text.append(prefix).append("confirmAt=")
                .append(record.confirmAt).append('\n');
            text.append(prefix).append("confirmReason=")
                .append(record.confirmReason).append('\n');
            text.append(prefix).append("confirmMethod=")
                .append(record.confirmMethod).append('\n');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static final class MutableDay {
        private String status;
        private boolean sawStatus;
        private int attempts = -1;
        private long lastAttempt = Long.MIN_VALUE;
        private long rowCount = Long.MIN_VALUE;
        private long firstCandle = Long.MIN_VALUE;
        private long lastCandle = Long.MIN_VALUE;
        private String detail;
        private boolean sawDetail;
        private String sha256;
        private boolean sawSha;
        private long confirmAt;
        private boolean sawConfirmAt;
        private String confirmReason;
        private boolean sawConfirmReason;
        private String confirmMethod;
        private boolean sawConfirmMethod;
    }

    private static DayLedger load(
            Path path,
            long expectedStart,
            long expectedEnd) throws IOException {

        long windowStart = Long.MIN_VALUE;
        long windowEnd = Long.MIN_VALUE;
        boolean sawStart = false;
        boolean sawEnd = false;
        boolean sawSchema = false;
        long schemaVersion = Long.MIN_VALUE;
        TreeMap<String, MutableDay> builders = new TreeMap<String, MutableDay>();
        try (BufferedReader reader = Files.newBufferedReader(
                path,
                StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() == 0) {
                    throw new IOException("Malformed ledger");
                }
                int separator = line.indexOf('=');
                if (separator <= 0) {
                    throw new IOException("Malformed ledger");
                }
                String key = line.substring(0, separator);
                String value = line.substring(separator + 1);
                if ("windowStart".equals(key)) {
                    if (sawStart) {
                        throw new IOException("Malformed ledger");
                    }
                    windowStart = parseCanonicalLong(value);
                    sawStart = true;
                } else if ("windowEnd".equals(key)) {
                    if (sawEnd) {
                        throw new IOException("Malformed ledger");
                    }
                    windowEnd = parseCanonicalLong(value);
                    sawEnd = true;
                } else if ("schemaVersion".equals(key)) {
                    if (sawSchema) {
                        throw new IOException("Malformed ledger");
                    }
                    schemaVersion = parseCanonicalLong(value);
                    sawSchema = true;
                } else if (key.startsWith("day.")) {
                    int dot = key.indexOf('.', 4);
                    if (dot <= 4) {
                        throw new IOException("Malformed ledger");
                    }
                    String date = key.substring(4, dot);
                    String field = key.substring(dot + 1);
                    if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) {
                        throw new IOException("Malformed ledger");
                    }
                    MutableDay builder = builders.get(date);
                    if (builder == null) {
                        builder = new MutableDay();
                        builders.put(date, builder);
                    }
                    assignLedgerField(builder, field, value);
                } else {
                    throw new IOException("Malformed ledger");
                }
            }
        } catch (NumberFormatException ex) {
            throw new IOException("Malformed ledger");
        }
        if (!sawSchema || schemaVersion != LEDGER_SCHEMA_VERSION) {
            throw new IOException("Unsupported ledger schema");
        }
        if (!sawStart || !sawEnd || windowStart > windowEnd) {
            throw new IOException("Malformed ledger");
        }
        try {
            requireMinuteBoundary(windowStart, "Ledger window start");
            requireMinuteBoundary(windowEnd, "Ledger window end");
        } catch (IllegalArgumentException ex) {
            throw new IOException("Malformed ledger");
        }
        if (windowStart != expectedStart || windowEnd != expectedEnd) {
            throw new IOException("Ledger window does not match");
        }
        TreeMap<Long, DayRecord> days = new TreeMap<Long, DayRecord>();
        for (Map.Entry<String, MutableDay> entry : builders.entrySet()) {
            long dayStart;
            try {
                dayStart = parseUtcDate(entry.getKey());
            } catch (ParseException ex) {
                throw new IOException("Malformed ledger");
            }
            if (!utcDate(dayStart).equals(entry.getKey())) {
                throw new IOException("Malformed ledger");
            }
            MutableDay builder = entry.getValue();
            if (!builder.sawStatus
                    || builder.attempts < 0
                    || builder.lastAttempt == Long.MIN_VALUE
                    || builder.rowCount == Long.MIN_VALUE
                    || builder.firstCandle == Long.MIN_VALUE
                    || builder.lastCandle == Long.MIN_VALUE
                    || !builder.sawDetail
                    || !builder.sawSha
                    || !builder.sawConfirmAt
                    || !builder.sawConfirmReason
                    || !builder.sawConfirmMethod) {
                throw new IOException("Malformed ledger");
            }
            DayRecord record;
            try {
                record = dayRecord(
                    dayStart,
                    builder.status,
                    builder.attempts,
                    builder.lastAttempt,
                    builder.rowCount,
                    builder.firstCandle,
                    builder.lastCandle,
                    builder.detail,
                    builder.sha256,
                    builder.confirmAt,
                    builder.confirmReason,
                    builder.confirmMethod
                );
            } catch (IllegalArgumentException ex) {
                throw new IOException("Malformed ledger");
            }
            if (!containsPlannedDay(windowStart, windowEnd, dayStart)) {
                throw new IOException("Malformed ledger");
            }
            days.put(Long.valueOf(dayStart), record);
        }
        return new DayLedger(path, windowStart, windowEnd, days);
    }

    private static boolean containsPlannedDay(
            long windowStart,
            long windowEnd,
            long dayStart) {

        List<UtcInterval> intervals = planDailyIntervals(windowStart, windowEnd);
        for (int i = 0; i < intervals.size(); i++) {
            if (startOfUtcDay(intervals.get(i).startMillis) == dayStart) {
                return true;
            }
        }
        return false;
    }

    private static void assignLedgerField(
            MutableDay builder,
            String field,
            String value) throws IOException {

        if ("status".equals(field)) {
            if (builder.sawStatus) {
                throw new IOException("Malformed ledger");
            }
            builder.status = value;
            builder.sawStatus = true;
        } else if ("attempts".equals(field)) {
            if (builder.attempts >= 0) {
                throw new IOException("Malformed ledger");
            }
            long parsed = parseCanonicalLong(value);
            if (parsed < 0L || parsed > Integer.MAX_VALUE) {
                throw new IOException("Malformed ledger");
            }
            builder.attempts = (int) parsed;
        } else if ("lastAttempt".equals(field)) {
            if (builder.lastAttempt != Long.MIN_VALUE) {
                throw new IOException("Malformed ledger");
            }
            builder.lastAttempt = parseCanonicalLong(value);
        } else if ("rowCount".equals(field)) {
            if (builder.rowCount != Long.MIN_VALUE) {
                throw new IOException("Malformed ledger");
            }
            builder.rowCount = parseCanonicalLong(value);
        } else if ("firstCandle".equals(field)) {
            if (builder.firstCandle != Long.MIN_VALUE) {
                throw new IOException("Malformed ledger");
            }
            builder.firstCandle = parseCanonicalLong(value);
        } else if ("lastCandle".equals(field)) {
            if (builder.lastCandle != Long.MIN_VALUE) {
                throw new IOException("Malformed ledger");
            }
            builder.lastCandle = parseCanonicalLong(value);
        } else if ("detail".equals(field)) {
            if (builder.sawDetail) {
                throw new IOException("Malformed ledger");
            }
            builder.detail = value;
            builder.sawDetail = true;
        } else if ("sha256".equals(field)) {
            if (builder.sawSha) {
                throw new IOException("Malformed ledger");
            }
            builder.sha256 = value;
            builder.sawSha = true;
        } else if ("confirmAt".equals(field)) {
            if (builder.sawConfirmAt) {
                throw new IOException("Malformed ledger");
            }
            builder.confirmAt = parseCanonicalLong(value);
            builder.sawConfirmAt = true;
        } else if ("confirmReason".equals(field)) {
            if (builder.sawConfirmReason) {
                throw new IOException("Malformed ledger");
            }
            builder.confirmReason = value;
            builder.sawConfirmReason = true;
        } else if ("confirmMethod".equals(field)) {
            if (builder.sawConfirmMethod) {
                throw new IOException("Malformed ledger");
            }
            builder.confirmMethod = value;
            builder.sawConfirmMethod = true;
        } else {
            throw new IOException("Malformed ledger");
        }
    }

    private static long parseCanonicalLong(String value) throws IOException {
        if (value == null || !value.matches("-?\\d+")) {
            throw new IOException("Malformed ledger");
        }
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new IOException("Malformed ledger");
        }
        if (!Long.toString(parsed).equals(value)) {
            throw new IOException("Malformed ledger");
        }
        return parsed;
    }

    private static boolean runLedgerSelfTest() throws Exception {
        System.out.println(
            "Ledger tests use a temporary directory and do not connect to Dukascopy."
        );
        Path root = Files.createTempDirectory("dukascopy-ledger-test");
        boolean passed = true;
        try {
            passed &= testLedgerInitialization(root.resolve("init"));
            passed &= testLedgerFourStatuses(root.resolve("statuses"));
            passed &= testLedgerAttemptCount(root.resolve("attempts"));
            passed &= testLedgerLastAttempt(root.resolve("last-attempt"));
            passed &= testLedgerValidTransitions(root.resolve("transitions"));
            passed &= testLedgerInvalidTransition(root.resolve("invalid"));
            passed &= testLedgerReload(root.resolve("reload"));
            passed &= testLedgerCorrupt(root.resolve("corrupt"));
            passed &= testLedgerDifferentWindow(root.resolve("window"));
            passed &= testLedgerMissingDay(root.resolve("missing"));
            passed &= testLedgerComplete(root.resolve("complete"));
            passed &= testLedgerUnresolved(root.resolve("unresolved"));
            passed &= testLedgerEarliestUnresolved(root.resolve("earliest"));
            passed &= testLedgerFailedUpdate(root.resolve("preserve"));
        } finally {
            deleteRecursively(root);
        }
        System.out.println("--------------------------------");
        System.out.println(passed
            ? "LEDGER TESTS PASS"
            : "LEDGER TESTS FAIL");
        return passed;
    }

    private static long ledgerWindowEnd(String lastDate) throws ParseException {
        return endOfUtcMinuteDay(parseUtcDate(lastDate));
    }

    private static long ledgerAttempt(String timestamp) throws ParseException {
        SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        parser.setLenient(false);
        parser.setTimeZone(TimeZone.getTimeZone("UTC"));
        return parser.parse(timestamp).getTime();
    }

    private static void saveDataDay(
            DayLedger ledger,
            String date,
            String attempt,
            String detail) throws Exception {

        long day = parseUtcDate(date);
        long first = parseUtcMinute(date + " 00:05:00");
        long last = parseUtcMinute(date + " 00:06:00");
        ledger.record(
            day,
            STATUS_DATA_SAVED,
            ledgerAttempt(attempt),
            2L,
            first,
            last,
            detail
        );
    }

    private static void saveStatus(
            DayLedger ledger,
            String date,
            String status,
            String attempt,
            String detail) throws Exception {

        if (STATUS_DATA_SAVED.equals(status)) {
            saveDataDay(ledger, date, attempt, detail);
            return;
        }
        ledger.record(
            parseUtcDate(date),
            status,
            ledgerAttempt(attempt),
            0L,
            -1L,
            -1L,
            detail
        );
    }

    private static boolean testLedgerInitialization(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("New ledger initialization");
        long start = parseUtcDate("2024-06-01");
        long end = ledgerWindowEnd("2024-06-01");
        DayLedger ledger = DayLedger.open(directory, start, end);
        DayLedger reloaded = DayLedger.open(directory, start, end);
        Path file = directory.resolve(LEDGER_FILE_NAME);
        boolean passed = ledger.days.isEmpty()
            && reloaded.days.isEmpty()
            && ledger.windowStart == start
            && ledger.windowEnd == end
            && Files.isRegularFile(file)
            && !Files.exists(directory.resolve(LEDGER_FILE_NAME + ".tmp"))
            && new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .startsWith("windowStart=" + start + "\n");
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testLedgerFourStatuses(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("All four statuses");
        long start = parseUtcDate("2024-06-01");
        long end = ledgerWindowEnd("2024-06-04");
        DayLedger ledger = DayLedger.open(directory, start, end);
        saveStatus(ledger, "2024-06-01", STATUS_DATA_SAVED, "2024-06-01 12:00:00", "saved");
        saveStatus(ledger, "2024-06-02", STATUS_CONFIRMED_EMPTY, "2024-06-02 12:00:00", "empty");
        saveStatus(ledger, "2024-06-03", STATUS_UNKNOWN, "2024-06-03 12:00:00", "unknown");
        saveStatus(ledger, "2024-06-04", STATUS_FAILED, "2024-06-04 12:00:00", "failed");
        DayLedger reloaded = DayLedger.open(directory, start, end);
        boolean passed = statusOf(reloaded, "2024-06-01").equals(STATUS_DATA_SAVED)
            && statusOf(reloaded, "2024-06-02").equals(STATUS_CONFIRMED_EMPTY)
            && statusOf(reloaded, "2024-06-03").equals(STATUS_UNKNOWN)
            && statusOf(reloaded, "2024-06-04").equals(STATUS_FAILED);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testLedgerAttemptCount(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Attempt counting");
        long start = parseUtcDate("2024-06-10");
        long end = ledgerWindowEnd("2024-06-10");
        DayLedger ledger = DayLedger.open(directory, start, end);
        saveStatus(ledger, "2024-06-10", STATUS_UNKNOWN, "2024-06-10 08:00:00", "first");
        saveStatus(ledger, "2024-06-10", STATUS_FAILED, "2024-06-10 09:00:00", "second");
        saveStatus(ledger, "2024-06-10", STATUS_UNKNOWN, "2024-06-10 10:00:00", "third");
        DayRecord record = dayOf(ledger, "2024-06-10");
        boolean passed = record.attempts == 3
            && record.status.equals(STATUS_UNKNOWN);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testLedgerLastAttempt(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Last-attempt timestamp");
        long start = parseUtcDate("2024-06-11");
        long end = ledgerWindowEnd("2024-06-11");
        DayLedger ledger = DayLedger.open(directory, start, end);
        long first = ledgerAttempt("2024-06-11 08:15:00");
        long second = first + 1500L;
        saveStatus(ledger, "2024-06-11", STATUS_FAILED, "2024-06-11 08:15:00", "first");
        ledger.record(
            start,
            STATUS_UNKNOWN,
            second,
            0L,
            -1L,
            -1L,
            "second"
        );
        DayLedger reloaded = DayLedger.open(directory, start, end);
        DayRecord record = dayOf(reloaded, "2024-06-11");
        boolean passed = record.lastAttemptMillis == second
            && record.lastAttemptMillis != first;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testLedgerValidTransitions(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Valid status transitions");
        long start = parseUtcDate("2024-07-01");
        long end = ledgerWindowEnd("2024-07-08");
        DayLedger ledger = DayLedger.open(directory, start, end);
        String[][] steps = new String[][] {
            {"2024-07-01", STATUS_UNKNOWN, STATUS_DATA_SAVED},
            {"2024-07-02", STATUS_UNKNOWN, STATUS_CONFIRMED_EMPTY},
            {"2024-07-03", STATUS_UNKNOWN, STATUS_UNKNOWN},
            {"2024-07-04", STATUS_UNKNOWN, STATUS_FAILED},
            {"2024-07-05", STATUS_FAILED, STATUS_DATA_SAVED},
            {"2024-07-06", STATUS_FAILED, STATUS_CONFIRMED_EMPTY},
            {"2024-07-07", STATUS_FAILED, STATUS_UNKNOWN},
            {"2024-07-08", STATUS_FAILED, STATUS_FAILED}
        };
        boolean passed = true;
        for (int i = 0; i < steps.length; i++) {
            String date = steps[i][0];
            saveStatus(ledger, date, steps[i][1], date + " 08:00:00", "start");
            if (STATUS_DATA_SAVED.equals(steps[i][2])) {
                saveDataDay(ledger, date, date + " 09:00:00", "next");
            } else {
                saveStatus(ledger, date, steps[i][2], date + " 09:00:00", "next");
            }
            DayRecord record = dayOf(ledger, date);
            if (!steps[i][2].equals(record.status) || record.attempts != 2) {
                passed = false;
            }
        }
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testLedgerInvalidTransition(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Invalid status transition rejection");
        long start = parseUtcDate("2024-07-10");
        long end = ledgerWindowEnd("2024-07-11");
        DayLedger ledger = DayLedger.open(directory, start, end);
        saveDataDay(ledger, "2024-07-10", "2024-07-10 08:00:00", "saved");
        saveStatus(
            ledger,
            "2024-07-11",
            STATUS_CONFIRMED_EMPTY,
            "2024-07-11 08:00:00",
            "empty"
        );
        byte[] before = Files.readAllBytes(ledger.path);
        boolean savedRejected = ledgerRejection(
            ledger,
            "2024-07-10",
            STATUS_FAILED
        );
        boolean emptyRejected = ledgerRejection(
            ledger,
            "2024-07-11",
            STATUS_UNKNOWN
        );
        boolean unchanged = Arrays.equals(before, Files.readAllBytes(ledger.path));
        DayLedger reloaded = DayLedger.open(directory, start, end);
        boolean passed = savedRejected
            && emptyRejected
            && unchanged
            && dayOf(reloaded, "2024-07-10").attempts == 1
            && dayOf(reloaded, "2024-07-11").status.equals(STATUS_CONFIRMED_EMPTY);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testLedgerReload(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Atomic persistence and reload");
        long start = parseUtcDate("2024-08-01");
        long end = ledgerWindowEnd("2024-08-01");
        DayLedger ledger = DayLedger.open(directory, start, end);
        long first = parseUtcMinute("2024-08-01 00:05:00");
        long last = parseUtcMinute("2024-08-01 00:07:00");
        long attempt = ledgerAttempt("2024-08-01 11:11:11");
        ledger.record(start, STATUS_DATA_SAVED, attempt, 3L, first, last, "stored");
        DayLedger reloaded = DayLedger.open(directory, start, end);
        DayRecord record = dayOf(reloaded, "2024-08-01");
        boolean passed = record.attempts == 1
            && record.rowCount == 3L
            && record.firstCandle == first
            && record.lastCandle == last
            && record.lastAttemptMillis == attempt
            && "stored".equals(record.detail)
            && !Files.exists(directory.resolve(LEDGER_FILE_NAME + ".tmp"));
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testLedgerCorrupt(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Corrupt-ledger rejection");
        long start = parseUtcDate("2024-08-02");
        long end = ledgerWindowEnd("2024-08-02");
        DayLedger.open(directory, start, end);
        Path file = directory.resolve(LEDGER_FILE_NAME);
        Files.write(file, "this is not a ledger\n".getBytes(StandardCharsets.UTF_8));
        boolean rejected = ledgerOpenFails(directory, start, end, "Malformed ledger");
        System.out.println(rejected ? "PASS" : "FAIL");
        return rejected;
    }

    private static boolean testLedgerDifferentWindow(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Different-window rejection");
        long start = parseUtcDate("2024-08-03");
        long end = ledgerWindowEnd("2024-08-03");
        DayLedger.open(directory, start, end);
        byte[] before = Files.readAllBytes(directory.resolve(LEDGER_FILE_NAME));
        long otherEnd = ledgerWindowEnd("2024-08-04");
        boolean rejected = ledgerOpenFails(
            directory,
            start,
            otherEnd,
            "Ledger window does not match"
        );
        boolean unchanged = Arrays.equals(
            before,
            Files.readAllBytes(directory.resolve(LEDGER_FILE_NAME))
        );
        boolean passed = rejected && unchanged;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testLedgerMissingDay(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Missing day detection");
        long start = parseUtcDate("2024-08-10");
        long end = ledgerWindowEnd("2024-08-12");
        DayLedger ledger = DayLedger.open(directory, start, end);
        saveDataDay(ledger, "2024-08-10", "2024-08-10 08:00:00", "saved");
        saveDataDay(ledger, "2024-08-12", "2024-08-12 08:00:00", "saved");
        LedgerCompleteness result = ledger.assess();
        boolean passed = !result.complete
            && result.unresolvedCount == 1
            && result.firstUnresolvedDay == parseUtcDate("2024-08-11");
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testLedgerComplete(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Dataset completeness with all days resolved");
        long start = parseUtcDate("2024-08-20");
        long end = ledgerWindowEnd("2024-08-21");
        DayLedger ledger = DayLedger.open(directory, start, end);
        saveDataDay(ledger, "2024-08-20", "2024-08-20 08:00:00", "saved");
        saveStatus(
            ledger,
            "2024-08-21",
            STATUS_CONFIRMED_EMPTY,
            "2024-08-21 08:00:00",
            "empty"
        );
        LedgerCompleteness result = ledger.assess();
        boolean passed = result.complete
            && result.unresolvedCount == 0
            && result.firstUnresolvedDay < 0L;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testLedgerUnresolved(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Incompleteness when any day is UNKNOWN or FAILED");
        boolean unknown = unresolvedStatus(directory.resolve("unknown"), STATUS_UNKNOWN);
        boolean failed = unresolvedStatus(directory.resolve("failed"), STATUS_FAILED);
        boolean passed = unknown && failed;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean unresolvedStatus(Path directory, String status) throws Exception {
        long start = parseUtcDate("2024-09-01");
        long end = ledgerWindowEnd("2024-09-02");
        DayLedger ledger = DayLedger.open(directory, start, end);
        saveDataDay(ledger, "2024-09-01", "2024-09-01 08:00:00", "saved");
        saveStatus(ledger, "2024-09-02", status, "2024-09-02 08:00:00", "open");
        LedgerCompleteness result = ledger.assess();
        return !result.complete
            && result.unresolvedCount == 1
            && result.firstUnresolvedDay == parseUtcDate("2024-09-02");
    }

    private static boolean testLedgerEarliestUnresolved(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Earliest unresolved date reporting");
        long start = parseUtcDate("2024-09-10");
        long end = ledgerWindowEnd("2024-09-13");
        DayLedger ledger = DayLedger.open(directory, start, end);
        saveDataDay(ledger, "2024-09-10", "2024-09-10 08:00:00", "saved");
        saveStatus(ledger, "2024-09-11", STATUS_FAILED, "2024-09-11 08:00:00", "failed");
        saveStatus(ledger, "2024-09-13", STATUS_UNKNOWN, "2024-09-13 08:00:00", "unknown");
        LedgerCompleteness result = ledger.assess();
        boolean passed = !result.complete
            && result.unresolvedCount == 3
            && result.firstUnresolvedDay == parseUtcDate("2024-09-11");
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testLedgerFailedUpdate(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Ledger preservation after a simulated failed update");
        long start = parseUtcDate("2024-09-20");
        long end = ledgerWindowEnd("2024-09-21");
        DayLedger ledger = DayLedger.open(directory, start, end);
        saveDataDay(ledger, "2024-09-20", "2024-09-20 08:00:00", "saved");
        byte[] before = Files.readAllBytes(ledger.path);
        boolean rejected = false;
        try {
            ledger.record(
                parseUtcDate("2024-09-21"),
                STATUS_DATA_SAVED,
                ledgerAttempt("2024-09-21 08:00:00"),
                0L,
                -1L,
                -1L,
                "bad\nrow"
            );
        } catch (IOException ex) {
            rejected = ex.getMessage() != null
                && ex.getMessage().indexOf("Malformed") >= 0;
        }
        boolean unchanged = Arrays.equals(before, Files.readAllBytes(ledger.path));
        DayLedger reloaded = DayLedger.open(directory, start, end);
        boolean passed = rejected
            && unchanged
            && reloaded.days.size() == 1
            && dayOf(reloaded, "2024-09-20").status.equals(STATUS_DATA_SAVED)
            && !Files.exists(directory.resolve(LEDGER_FILE_NAME + ".tmp"));
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean ledgerRejection(
            DayLedger ledger,
            String date,
            String status) throws Exception {

        try {
            saveStatus(ledger, date, status, date + " 18:00:00", "retry");
            return false;
        } catch (IOException ex) {
            return ex.getMessage() != null
                && ex.getMessage().indexOf("Invalid ledger transition") >= 0;
        }
    }

    private static boolean ledgerOpenFails(
            Path directory,
            long start,
            long end,
            String expected) {

        try {
            DayLedger.open(directory, start, end);
            return false;
        } catch (IOException ex) {
            return ex.getMessage() != null && ex.getMessage().indexOf(expected) >= 0;
        }
    }

    private static DayRecord dayOf(DayLedger ledger, String date) throws ParseException {
        return ledger.days.get(Long.valueOf(parseUtcDate(date)));
    }

    private static String statusOf(DayLedger ledger, String date) throws ParseException {
        return dayOf(ledger, date).status;
    }

    private static final class DayStagingStore {
        private final Path outputDirectory;
        private final Path stagingDirectory;
        private final DayLedger ledger;

        private DayStagingStore(
                Path outputDirectory,
                Path stagingDirectory,
                DayLedger ledger) {
            this.outputDirectory = outputDirectory;
            this.stagingDirectory = stagingDirectory;
            this.ledger = ledger;
        }

        private static DayStagingStore open(
                Path outputDirectory,
                long windowStart,
                long windowEnd) throws IOException {

            Files.createDirectories(outputDirectory);
            Path stagingDirectory = outputDirectory.resolve(STAGING_DIRECTORY_NAME);
            Files.createDirectories(stagingDirectory);
            DayLedger ledger = DayLedger.open(outputDirectory, windowStart, windowEnd);
            DayStagingStore store = new DayStagingStore(
                outputDirectory,
                stagingDirectory,
                ledger
            );
            store.verify();
            return store;
        }

        private void saveDay(
                long dayStart,
                List<CandleRecord> candles,
                long attemptMillis,
                String detail) throws IOException {

            List<CandleRecord> validated = validateStagingCandles(dayStart, candles);
            byte[] payload = stagingCsvBytes(validated);
            String hash = sha256Hex(payload);
            if (!ledger.covers(dayStart)) {
                throw new IOException("Day is outside the ledger window");
            }
            Path target = stagingFile(dayStart);
            Path temporary = target.resolveSibling(target.getFileName().toString() + ".tmp");
            DayRecord existing = ledger.days.get(Long.valueOf(dayStart));
            long first = validated.get(0).timeMillis;
            long last = validated.get(validated.size() - 1).timeMillis;
            if (existing != null && STATUS_DATA_SAVED.equals(existing.status)) {
                assertStagingMatches(target, existing);
                if (Arrays.equals(Files.readAllBytes(target), payload)) {
                    return;
                }
                if (attemptMillis < existing.lastAttemptMillis) {
                    throw new IOException("Last attempt time moved backwards");
                }
                if (Files.exists(temporary)) {
                    throw new IOException("Interrupted staging update");
                }
                dayRecord(
                    dayStart,
                    STATUS_DATA_SAVED,
                    existing.attempts + 1,
                    attemptMillis,
                    validated.size(),
                    first,
                    last,
                    detail,
                    hash
                );
                atomicWrite(target, payload);
                ledger.updateSaved(
                    dayStart,
                    attemptMillis,
                    validated.size(),
                    first,
                    last,
                    detail,
                    hash
                );
                return;
            }
            if (existing != null && isResolvedStatus(existing.status)) {
                throw new IOException(
                    "Invalid ledger transition from " + existing.status
                );
            }
            if (Files.exists(temporary)) {
                throw new IOException("Interrupted staging update");
            }
            if (Files.exists(target)) {
                throw new IOException("Orphan staging file");
            }
            int attempts = existing == null ? 1 : existing.attempts + 1;
            dayRecord(
                dayStart,
                STATUS_DATA_SAVED,
                attempts,
                attemptMillis,
                validated.size(),
                first,
                last,
                detail,
                hash
            );
            atomicWrite(target, payload);
            ledger.record(
                dayStart,
                STATUS_DATA_SAVED,
                attemptMillis,
                validated.size(),
                first,
                last,
                detail,
                hash
            );
        }

        private void publishAttempt(
                long dayStart,
                List<CandleRecord> candles,
                String detail) throws IOException {

            List<CandleRecord> validated = validateStagingCandles(dayStart, candles);
            byte[] payload = stagingCsvBytes(validated);
            String hash = sha256Hex(payload);
            if (!ledger.covers(dayStart)) {
                throw new IOException("Day is outside the ledger window");
            }
            DayRecord existing = ledger.days.get(Long.valueOf(dayStart));
            if (existing == null
                    || existing.attempts < 1
                    || isResolvedStatus(existing.status)) {
                throw new IOException("Attempt was not persisted");
            }
            Path target = stagingFile(dayStart);
            Path temporary = target.resolveSibling(
                target.getFileName().toString() + ".tmp"
            );
            if (Files.exists(temporary)) {
                throw new IOException("Interrupted staging update");
            }
            if (Files.exists(target)) {
                throw new IOException("Orphan staging file");
            }
            long first = validated.get(0).timeMillis;
            long last = validated.get(validated.size() - 1).timeMillis;
            dayRecord(
                dayStart,
                STATUS_DATA_SAVED,
                existing.attempts,
                existing.lastAttemptMillis,
                validated.size(),
                first,
                last,
                detail,
                hash
            );
            if (failNextStagingWrite) {
                failNextStagingWrite = false;
                throw new IOException("Staging write failed");
            }
            atomicWrite(target, payload);
            ledger.finishAttempt(
                dayStart,
                STATUS_DATA_SAVED,
                validated.size(),
                first,
                last,
                detail,
                hash
            );
        }

        private void noteDay(
                long dayStart,
                String status,
                long attemptMillis,
                String detail) throws IOException {

            if (STATUS_DATA_SAVED.equals(status)) {
                throw new IOException("DATA_SAVED requires a staging file");
            }
            if (Files.exists(stagingFile(dayStart))) {
                throw new IOException("Orphan staging file");
            }
            ledger.record(
                dayStart,
                status,
                attemptMillis,
                0L,
                -1L,
                -1L,
                detail,
                ""
            );
        }

        private Path stagingFile(long dayStart) {
            return stagingDirectory.resolve(utcDate(dayStart) + ".csv");
        }

        private void verify() throws IOException {
            try (DirectoryStream<Path> children =
                    Files.newDirectoryStream(stagingDirectory)) {
                for (Path child : children) {
                    String name = child.getFileName().toString();
                    if (name.endsWith(".tmp")) {
                        throw new IOException("Interrupted staging update");
                    }
                    if (!Files.isRegularFile(child)
                            || !name.matches("\\d{4}-\\d{2}-\\d{2}\\.csv")) {
                        throw new IOException("Unexpected staging file");
                    }
                    long dayStart;
                    try {
                        dayStart = parseUtcDate(name.substring(0, 10));
                    } catch (ParseException ex) {
                        throw new IOException("Unexpected staging file");
                    }
                    if (!utcDate(dayStart).equals(name.substring(0, 10))) {
                        throw new IOException("Unexpected staging file");
                    }
                    DayRecord record = ledger.days.get(Long.valueOf(dayStart));
                    if (record != null && STATUS_CONFIRMED_EMPTY.equals(record.status)) {
                        throw new IOException("CONFIRMED_EMPTY day has a staging file");
                    }
                    if (record == null || !STATUS_DATA_SAVED.equals(record.status)) {
                        throw new IOException("Orphan staging file");
                    }
                    assertStagingMatches(child, record);
                }
            }
            for (DayRecord record : ledger.days.values()) {
                Path file = stagingFile(record.dayStart);
                if (STATUS_CONFIRMED_EMPTY.equals(record.status)) {
                    if (Files.exists(file)) {
                        throw new IOException("CONFIRMED_EMPTY day has a staging file");
                    }
                } else if (STATUS_DATA_SAVED.equals(record.status)) {
                    if (!record.sha256.matches("[0-9a-f]{64}")) {
                        throw new IOException("Staging file does not match the ledger");
                    }
                    assertStagingMatches(file, record);
                } else if (Files.exists(file)) {
                    throw new IOException("Orphan staging file");
                }
            }
        }

        private void assertStagingMatches(Path file, DayRecord record) throws IOException {
            if (!Files.isRegularFile(file)) {
                throw new IOException("Missing staging file");
            }
            byte[] bytes = Files.readAllBytes(file);
            List<CandleRecord> parsed;
            try {
                parsed = parseStagingCsv(record.dayStart, bytes);
            } catch (IOException ex) {
                String detail = ex.getMessage() == null
                    ? "Invalid staging file"
                    : ex.getMessage();
                if (detail.startsWith("Invalid staging file")) {
                    throw new IOException(detail);
                }
                throw new IOException("Invalid staging file: " + detail);
            } catch (RuntimeException ex) {
                throw new IOException("Invalid staging file");
            }
            if (parsed.isEmpty()
                    || !sha256Hex(bytes).equals(record.sha256)
                    || parsed.size() != record.rowCount
                    || parsed.get(0).timeMillis != record.firstCandle
                    || parsed.get(parsed.size() - 1).timeMillis != record.lastCandle) {
                throw new IOException("Staging file does not match the ledger");
            }
        }
    }

    private static List<CandleRecord> validateStagingCandles(
            long dayStart,
            List<CandleRecord> candles) throws IOException {

        if (dayStart != startOfUtcDay(dayStart)) {
            throw new IOException("Staging day is not a UTC date boundary");
        }
        if (candles == null || candles.isEmpty()) {
            throw new IOException("Staging day has no candles");
        }
        long previous = Long.MIN_VALUE;
        for (int i = 0; i < candles.size(); i++) {
            CandleRecord candle = candles.get(i);
            if (candle == null) {
                throw new IOException("Staging day contains a null candle");
            }
            validateCandle(candle);
            if (!sameUtcDate(dayStart, candle.timeMillis)) {
                throw new IOException("Candle is outside the requested UTC day");
            }
            if (previous == candle.timeMillis) {
                throw new IOException("Duplicate timestamp");
            }
            if (candle.timeMillis < previous) {
                throw new IOException("Backward timestamp");
            }
            previous = candle.timeMillis;
        }
        return candles;
    }

    private static byte[] stagingCsvBytes(List<CandleRecord> candles) {
        StringBuilder text = new StringBuilder();
        text.append(CSV_HEADER).append('\n');
        for (int i = 0; i < candles.size(); i++) {
            text.append(toCsvLine(candles.get(i)));
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static List<CandleRecord> parseStagingCsv(long dayStart, byte[] bytes)
            throws IOException {

        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.indexOf('\r') >= 0) {
            throw new IOException("Invalid staging file");
        }
        String[] lines = text.split("\n", -1);
        if (lines.length < 2 || lines[lines.length - 1].length() != 0) {
            throw new IOException("Invalid staging file");
        }
        if (!CSV_HEADER.equals(lines[0])) {
            throw new IOException("Invalid staging file");
        }
        List<CandleRecord> rows = new ArrayList<CandleRecord>();
        for (int i = 1; i < lines.length - 1; i++) {
            if (lines[i].length() == 0) {
                throw new IOException("Invalid staging file");
            }
            rows.add(parseStagingLine(lines[i]));
        }
        validateStagingCandles(dayStart, rows);
        return rows;
    }

    private static CandleRecord parseStagingLine(String line) throws IOException {
        String[] parts = line.split(",", -1);
        if (parts.length != 6) {
            throw new IOException("Invalid staging file");
        }
        long time;
        try {
            time = parseUtcMinute(parts[0]);
        } catch (ParseException ex) {
            throw new IOException("Invalid staging file");
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid staging file");
        }
        try {
            return new CandleRecord(
                time,
                Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2]),
                Double.parseDouble(parts[3]),
                Double.parseDouble(parts[4]),
                Double.parseDouble(parts[5])
            );
        } catch (NumberFormatException ex) {
            throw new IOException("Invalid staging file");
        }
    }

    private static void publishStagingFile(
            Path stagingDirectory,
            long dayStart,
            List<CandleRecord> candles) throws IOException {

        List<CandleRecord> validated = validateStagingCandles(dayStart, candles);
        Path target = stagingDirectory.resolve(utcDate(dayStart) + ".csv");
        atomicWrite(target, stagingCsvBytes(validated));
    }

    private static String sha256Hex(byte[] content) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (int i = 0; i < digest.length; i++) {
                int value = digest[i] & 0xff;
                hex.append(Character.forDigit(value >>> 4, 16));
                hex.append(Character.forDigit(value & 0x0f, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("SHA-256 is not available");
        }
    }

    private static boolean runStagingSelfTest() throws Exception {
        System.out.println(
            "Staging tests use a temporary directory and do not connect to Dukascopy."
        );
        Path root = Files.createTempDirectory("dukascopy-staging-test");
        boolean passed = true;
        try {
            passed &= testStagingCreate(root.resolve("create"));
            passed &= testStagingHeaderAndCount(root.resolve("header"));
            passed &= testStagingOrder(root.resolve("order"));
            passed &= testStagingHash(root.resolve("hash"));
            passed &= testStagingIdentical(root.resolve("identical"));
            passed &= testStagingReplace(root.resolve("replace"));
            passed &= testStagingRejectOhlc(root.resolve("ohlc"));
            passed &= testStagingRejectVolume(root.resolve("volume"));
            passed &= testStagingRejectTimestamps(root.resolve("timestamps"));
            passed &= testStagingRejectOutsideDay(root.resolve("outside"));
            passed &= testStagingPreserveOnInvalid(root.resolve("preserve"));
            passed &= testStagingMissingFile(root.resolve("missing"));
            passed &= testStagingModifiedFile(root.resolve("modified"));
            passed &= testStagingOrphan(root.resolve("orphan"));
            passed &= testStagingCrashBeforeLedger(root.resolve("crash"));
            passed &= testStagingNoFileForUnresolved(root.resolve("unstaged"));
            passed &= testStagingCorruptDoesNotSave(root.resolve("corrupt"));
        } finally {
            deleteRecursively(root);
        }
        System.out.println("--------------------------------");
        System.out.println(passed
            ? "STAGING TESTS PASS"
            : "STAGING TESTS FAIL");
        return passed;
    }

    private static List<CandleRecord> stagingSample(String date) throws ParseException {
        return Arrays.asList(
            validCandle(date + " 00:00:00", 1.10, 1.20, 1.00, 1.15, 10.0),
            validCandle(date + " 00:01:00", 1.15, 1.25, 1.10, 1.20, 11.0),
            validCandle(date + " 00:03:00", 1.20, 1.30, 1.15, 1.25, 12.0)
        );
    }

    private static DayStagingStore openStaging(Path directory, String startDate, String endDate)
            throws Exception {
        return DayStagingStore.open(
            directory,
            parseUtcDate(startDate),
            ledgerWindowEnd(endDate)
        );
    }

    private static boolean testStagingCreate(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Create one valid daily staging CSV");
        DayStagingStore store = openStaging(directory, "2024-10-01", "2024-10-01");
        long day = parseUtcDate("2024-10-01");
        store.saveDay(
            day,
            stagingSample("2024-10-01"),
            ledgerAttempt("2024-10-01 08:00:00"),
            "saved"
        );
        Path csv = store.stagingFile(day);
        boolean passed = Files.isRegularFile(csv)
            && "2024-10-01.csv".equals(csv.getFileName().toString())
            && !Files.exists(directory.resolve(STAGING_DIRECTORY_NAME)
                .resolve("2024-10-01.csv.tmp"))
            && !Files.exists(directory.resolve("EURUSD_M1.csv"))
            && dayOf(store.ledger, "2024-10-01").status.equals(STATUS_DATA_SAVED);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testStagingHeaderAndCount(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Verify header and row count");
        DayStagingStore store = openStaging(directory, "2024-10-01", "2024-10-01");
        long day = parseUtcDate("2024-10-01");
        store.saveDay(
            day,
            stagingSample("2024-10-01"),
            ledgerAttempt("2024-10-01 08:00:00"),
            "saved"
        );
        String text = new String(Files.readAllBytes(store.stagingFile(day)), StandardCharsets.UTF_8);
        int rows = 0;
        String header = "";
        int newline = text.indexOf('\n');
        if (newline >= 0) {
            header = text.substring(0, newline);
            rows = countOccurrences(text, "\n") - 1;
        }
        boolean passed = CSV_HEADER.equals(header)
            && rows == 3
            && dayOf(store.ledger, "2024-10-01").rowCount == 3L;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testStagingOrder(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Verify chronological order");
        DayStagingStore store = openStaging(directory, "2024-10-01", "2024-10-01");
        long day = parseUtcDate("2024-10-01");
        store.saveDay(
            day,
            stagingSample("2024-10-01"),
            ledgerAttempt("2024-10-01 08:00:00"),
            "saved"
        );
        List<CandleRecord> parsed = parseStagingCsv(
            day,
            Files.readAllBytes(store.stagingFile(day))
        );
        boolean ordered = parsed.size() == 3
            && parsed.get(0).timeMillis < parsed.get(1).timeMillis
            && parsed.get(1).timeMillis < parsed.get(2).timeMillis
            && parsed.get(2).timeMillis - parsed.get(1).timeMillis == 120000L;
        String text = new String(
            Files.readAllBytes(store.stagingFile(day)),
            StandardCharsets.UTF_8
        );
        boolean passed = ordered
            && text.indexOf("2024-10-01 00:02:00") < 0
            && text.indexOf("2024-10-01 00:00:00") >= 0
            && text.indexOf("2024-10-01 00:03:00") >= 0;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testStagingHash(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Verify SHA-256 matches the ledger");
        DayStagingStore store = openStaging(directory, "2024-10-01", "2024-10-01");
        long day = parseUtcDate("2024-10-01");
        store.saveDay(
            day,
            stagingSample("2024-10-01"),
            ledgerAttempt("2024-10-01 08:00:00"),
            "saved"
        );
        DayStagingStore reloaded = openStaging(directory, "2024-10-01", "2024-10-01");
        byte[] bytes = Files.readAllBytes(reloaded.stagingFile(day));
        DayRecord record = dayOf(reloaded.ledger, "2024-10-01");
        boolean passed = record.sha256.matches("[0-9a-f]{64}")
            && record.sha256.equals(sha256Hex(bytes))
            && record.firstCandle == parseUtcMinute("2024-10-01 00:00:00")
            && record.lastCandle == parseUtcMinute("2024-10-01 00:03:00")
            && record.rowCount == 3L;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testStagingIdentical(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Write identical data again; verify no duplicate rows");
        DayStagingStore store = openStaging(directory, "2024-10-01", "2024-10-01");
        long day = parseUtcDate("2024-10-01");
        List<CandleRecord> candles = stagingSample("2024-10-01");
        store.saveDay(day, candles, ledgerAttempt("2024-10-01 08:00:00"), "saved");
        byte[] before = Files.readAllBytes(store.stagingFile(day));
        byte[] ledgerBefore = Files.readAllBytes(store.ledger.path);
        store.saveDay(day, candles, ledgerAttempt("2024-10-01 09:00:00"), "saved");
        String text = new String(before, StandardCharsets.UTF_8);
        DayRecord record = dayOf(store.ledger, "2024-10-01");
        boolean passed = Arrays.equals(before, Files.readAllBytes(store.stagingFile(day)))
            && Arrays.equals(ledgerBefore, Files.readAllBytes(store.ledger.path))
            && countOccurrences(text, "2024-10-01 00:00:00,") == 1
            && countOccurrences(text, "2024-10-01 00:01:00,") == 1
            && countOccurrences(text, "2024-10-01 00:03:00,") == 1
            && record.attempts == 1
            && record.rowCount == 3L;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testStagingReplace(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Replace a day with a different valid payload");
        DayStagingStore store = openStaging(directory, "2024-10-02", "2024-10-02");
        long day = parseUtcDate("2024-10-02");
        store.saveDay(
            day,
            stagingSample("2024-10-02"),
            ledgerAttempt("2024-10-02 08:00:00"),
            "saved"
        );
        String previousHash = dayOf(store.ledger, "2024-10-02").sha256;
        List<CandleRecord> replacement = Arrays.asList(
            validCandle("2024-10-02 00:10:00", 1.30, 1.40, 1.20, 1.35, 20.0),
            validCandle("2024-10-02 00:12:00", 1.35, 1.45, 1.30, 1.40, 21.0)
        );
        store.saveDay(
            day,
            replacement,
            ledgerAttempt("2024-10-02 09:00:00"),
            "replaced"
        );
        DayStagingStore reloaded = openStaging(directory, "2024-10-02", "2024-10-02");
        String text = new String(
            Files.readAllBytes(reloaded.stagingFile(day)),
            StandardCharsets.UTF_8
        );
        DayRecord record = dayOf(reloaded.ledger, "2024-10-02");
        boolean passed = record.status.equals(STATUS_DATA_SAVED)
            && record.attempts == 2
            && record.rowCount == 2L
            && record.firstCandle == parseUtcMinute("2024-10-02 00:10:00")
            && record.lastCandle == parseUtcMinute("2024-10-02 00:12:00")
            && !record.sha256.equals(previousHash)
            && record.sha256.equals(sha256Hex(Files.readAllBytes(reloaded.stagingFile(day))))
            && text.indexOf("2024-10-02 00:10:00") >= 0
            && text.indexOf("2024-10-02 00:00:00") < 0
            && countOccurrences(text, "2024-10-02 00:10:00,") == 1;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testStagingRejectOhlc(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Reject invalid OHLC");
        DayStagingStore store = openStaging(directory, "2024-10-03", "2024-10-03");
        List<CandleRecord> candles = Arrays.asList(
            validCandle("2024-10-03 00:00:00", 1.20, 1.10, 1.30, 1.20, 10.0)
        );
        boolean rejected = stagingSaveFails(store, "2024-10-03", candles, "Invalid OHLC");
        boolean passed = rejected
            && !stagingHasCsv(directory)
            && store.ledger.days.isEmpty();
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testStagingRejectVolume(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Reject negative, NaN or infinite volume");
        DayStagingStore store = openStaging(directory, "2024-10-04", "2024-10-04");
        boolean negative = stagingSaveFails(
            store,
            "2024-10-04",
            Arrays.asList(validCandle("2024-10-04 00:00:00", 1.10, 1.20, 1.00, 1.15, -1.0)),
            "Invalid volume"
        );
        boolean nan = stagingSaveFails(
            store,
            "2024-10-04",
            Arrays.asList(validCandle(
                "2024-10-04 00:00:00", 1.10, 1.20, 1.00, 1.15, Double.NaN
            )),
            "Invalid volume"
        );
        boolean infinite = stagingSaveFails(
            store,
            "2024-10-04",
            Arrays.asList(validCandle(
                "2024-10-04 00:00:00",
                1.10,
                1.20,
                1.00,
                1.15,
                Double.POSITIVE_INFINITY
            )),
            "Invalid volume"
        );
        boolean passed = negative && nan && infinite
            && !stagingHasCsv(directory)
            && store.ledger.days.isEmpty();
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testStagingRejectTimestamps(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Reject duplicate or backward timestamps");
        DayStagingStore store = openStaging(directory, "2024-10-05", "2024-10-05");
        CandleRecord first = validCandle("2024-10-05 00:05:00", 1.10, 1.20, 1.00, 1.15, 10.0);
        CandleRecord same = validCandle("2024-10-05 00:05:00", 1.15, 1.25, 1.10, 1.20, 11.0);
        CandleRecord earlier = validCandle("2024-10-05 00:04:00", 1.15, 1.25, 1.10, 1.20, 11.0);
        boolean duplicate = stagingSaveFails(
            store,
            "2024-10-05",
            Arrays.asList(first, same),
            "Duplicate timestamp"
        );
        boolean backward = stagingSaveFails(
            store,
            "2024-10-05",
            Arrays.asList(first, earlier),
            "Backward timestamp"
        );
        boolean passed = duplicate && backward
            && !stagingHasCsv(directory)
            && store.ledger.days.isEmpty();
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testStagingRejectOutsideDay(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Reject candles outside the requested UTC day");
        DayStagingStore store = openStaging(directory, "2024-10-06", "2024-10-06");
        boolean rejected = stagingSaveFails(
            store,
            "2024-10-06",
            Arrays.asList(validCandle("2024-10-07 00:00:00", 1.10, 1.20, 1.00, 1.15, 10.0)),
            "outside the requested UTC day"
        );
        boolean passed = rejected
            && !stagingHasCsv(directory)
            && store.ledger.days.isEmpty();
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testStagingPreserveOnInvalid(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Preserve the previous staging file when new payload validation fails");
        DayStagingStore store = openStaging(directory, "2024-10-08", "2024-10-08");
        long day = parseUtcDate("2024-10-08");
        store.saveDay(
            day,
            stagingSample("2024-10-08"),
            ledgerAttempt("2024-10-08 08:00:00"),
            "saved"
        );
        byte[] fileBefore = Files.readAllBytes(store.stagingFile(day));
        byte[] ledgerBefore = Files.readAllBytes(store.ledger.path);
        boolean rejected = stagingSaveFails(
            store,
            "2024-10-08",
            Arrays.asList(validCandle("2024-10-08 00:00:00", 1.20, 1.10, 1.00, 1.20, 10.0)),
            "Invalid OHLC"
        );
        boolean passed = rejected
            && Arrays.equals(fileBefore, Files.readAllBytes(store.stagingFile(day)))
            && Arrays.equals(ledgerBefore, Files.readAllBytes(store.ledger.path))
            && dayOf(store.ledger, "2024-10-08").attempts == 1
            && dayOf(store.ledger, "2024-10-08").rowCount == 3L;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testStagingMissingFile(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Detect a missing staging file");
        DayStagingStore store = openStaging(directory, "2024-10-09", "2024-10-09");
        long day = parseUtcDate("2024-10-09");
        store.saveDay(
            day,
            stagingSample("2024-10-09"),
            ledgerAttempt("2024-10-09 08:00:00"),
            "saved"
        );
        byte[] ledgerBefore = Files.readAllBytes(store.ledger.path);
        Files.delete(store.stagingFile(day));
        boolean rejected = stagingOpenFails(
            directory,
            "2024-10-09",
            "2024-10-09",
            "Missing staging file"
        );
        boolean passed = rejected
            && !Files.exists(store.stagingFile(day))
            && Arrays.equals(ledgerBefore, Files.readAllBytes(store.ledger.path));
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testStagingModifiedFile(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Detect a modified staging file");
        DayStagingStore store = openStaging(directory, "2024-10-10", "2024-10-10");
        long day = parseUtcDate("2024-10-10");
        List<CandleRecord> candles = stagingSample("2024-10-10");
        store.saveDay(day, candles, ledgerAttempt("2024-10-10 08:00:00"), "saved");
        String text = new String(Files.readAllBytes(store.stagingFile(day)), StandardCharsets.UTF_8);
        String modified = text.replace(",10.0\n", ",11.0\n");
        Files.write(store.stagingFile(day), modified.getBytes(StandardCharsets.UTF_8));
        byte[] modifiedBytes = Files.readAllBytes(store.stagingFile(day));
        boolean openRejected = stagingOpenFails(
            directory,
            "2024-10-10",
            "2024-10-10",
            "Staging file does not match the ledger"
        );
        boolean rewriteRejected = false;
        try {
            store.saveDay(day, candles, ledgerAttempt("2024-10-10 09:00:00"), "saved");
        } catch (IOException ex) {
            rewriteRejected = ex.getMessage() != null
                && ex.getMessage().indexOf("Staging file does not match the ledger") >= 0;
        }
        boolean passed = openRejected
            && rewriteRejected
            && Arrays.equals(modifiedBytes, Files.readAllBytes(store.stagingFile(day)));
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testStagingOrphan(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Detect an orphan staging file without a DATA_SAVED ledger record");
        openStaging(directory, "2024-10-11", "2024-10-11");
        publishStagingFile(
            directory.resolve(STAGING_DIRECTORY_NAME),
            parseUtcDate("2024-10-11"),
            stagingSample("2024-10-11")
        );
        boolean rejected = stagingOpenFails(
            directory,
            "2024-10-11",
            "2024-10-11",
            "Orphan staging file"
        );
        DayLedger ledger = DayLedger.open(
            directory,
            parseUtcDate("2024-10-11"),
            ledgerWindowEnd("2024-10-11")
        );
        boolean passed = rejected && ledger.days.isEmpty();
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testStagingCrashBeforeLedger(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Detect a simulated crash between staging publication and ledger update");
        DayStagingStore store = openStaging(directory, "2024-10-12", "2024-10-12");
        long day = parseUtcDate("2024-10-12");
        store.noteDay(
            day,
            STATUS_UNKNOWN,
            ledgerAttempt("2024-10-12 08:00:00"),
            "unknown"
        );
        publishStagingFile(
            store.stagingDirectory,
            day,
            stagingSample("2024-10-12")
        );
        byte[] ledgerBefore = Files.readAllBytes(store.ledger.path);
        boolean rejected = stagingOpenFails(
            directory,
            "2024-10-12",
            "2024-10-12",
            "Orphan staging file"
        );
        DayLedger ledger = DayLedger.open(
            directory,
            parseUtcDate("2024-10-12"),
            ledgerWindowEnd("2024-10-12")
        );
        DayRecord record = dayOf(ledger, "2024-10-12");
        boolean passed = rejected
            && Arrays.equals(ledgerBefore, Files.readAllBytes(ledger.path))
            && record.status.equals(STATUS_UNKNOWN)
            && record.attempts == 1;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testStagingNoFileForUnresolved(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Verify CONFIRMED_EMPTY, UNKNOWN and FAILED have no staging files");
        DayStagingStore store = openStaging(directory, "2024-10-13", "2024-10-15");
        store.noteDay(
            parseUtcDate("2024-10-13"),
            STATUS_CONFIRMED_EMPTY,
            ledgerAttempt("2024-10-13 08:00:00"),
            "empty"
        );
        store.noteDay(
            parseUtcDate("2024-10-14"),
            STATUS_UNKNOWN,
            ledgerAttempt("2024-10-14 08:00:00"),
            "unknown"
        );
        store.noteDay(
            parseUtcDate("2024-10-15"),
            STATUS_FAILED,
            ledgerAttempt("2024-10-15 08:00:00"),
            "failed"
        );
        DayStagingStore reloaded = openStaging(directory, "2024-10-13", "2024-10-15");
        boolean passed = !stagingHasCsv(directory)
            && statusOf(reloaded.ledger, "2024-10-13").equals(STATUS_CONFIRMED_EMPTY)
            && statusOf(reloaded.ledger, "2024-10-14").equals(STATUS_UNKNOWN)
            && statusOf(reloaded.ledger, "2024-10-15").equals(STATUS_FAILED)
            && reloaded.ledger.days.size() == 3;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testStagingCorruptDoesNotSave(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Verify interrupted or corrupt data never silently becomes DATA_SAVED");
        boolean garbageStaysFailed = corruptFailedDay(directory.resolve("garbage"));
        boolean invalidSavedFile = corruptSavedFile(directory.resolve("invalid"));
        boolean interrupted = interruptedStaging(directory.resolve("tmp"));
        boolean unexpected = unexpectedStaging(directory.resolve("unexpected"));
        boolean unknownSchema = unknownSchemaRejected(directory.resolve("schema"));
        boolean oldSchema = oldSchemaRejected(directory.resolve("old-schema"));
        boolean passed = garbageStaysFailed
            && invalidSavedFile
            && interrupted
            && unexpected
            && unknownSchema
            && oldSchema;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean corruptFailedDay(Path directory) throws Exception {
        DayStagingStore store = openStaging(directory, "2024-10-16", "2024-10-16");
        long day = parseUtcDate("2024-10-16");
        store.noteDay(day, STATUS_FAILED, ledgerAttempt("2024-10-16 08:00:00"), "failed");
        Files.write(store.stagingFile(day), "corrupt\n".getBytes(StandardCharsets.UTF_8));
        byte[] ledgerBefore = Files.readAllBytes(store.ledger.path);
        boolean rejected = stagingOpenFails(directory, "2024-10-16", "2024-10-16", "Orphan staging file");
        DayLedger ledger = DayLedger.open(
            directory,
            parseUtcDate("2024-10-16"),
            ledgerWindowEnd("2024-10-16")
        );
        return rejected
            && Arrays.equals(ledgerBefore, Files.readAllBytes(ledger.path))
            && statusOf(ledger, "2024-10-16").equals(STATUS_FAILED);
    }

    private static boolean corruptSavedFile(Path directory) throws Exception {
        DayStagingStore store = openStaging(directory, "2024-10-17", "2024-10-17");
        long day = parseUtcDate("2024-10-17");
        store.saveDay(
            day,
            stagingSample("2024-10-17"),
            ledgerAttempt("2024-10-17 08:00:00"),
            "saved"
        );
        Files.write(store.stagingFile(day), "not,a,candle,file\n".getBytes(StandardCharsets.UTF_8));
        byte[] ledgerBefore = Files.readAllBytes(store.ledger.path);
        boolean rejected = stagingOpenFails(directory, "2024-10-17", "2024-10-17", "Invalid staging file");
        return rejected
            && Arrays.equals(ledgerBefore, Files.readAllBytes(store.ledger.path))
            && new String(ledgerBefore, StandardCharsets.UTF_8).indexOf("DATA_SAVED") >= 0;
    }

    private static boolean interruptedStaging(Path directory) throws Exception {
        openStaging(directory, "2024-10-18", "2024-10-18");
        Files.write(
            directory.resolve(STAGING_DIRECTORY_NAME).resolve("partial.csv.tmp"),
            new byte[] {1, 2, 3}
        );
        boolean rejected = stagingOpenFails(
            directory,
            "2024-10-18",
            "2024-10-18",
            "Interrupted staging update"
        );
        DayLedger ledger = DayLedger.open(
            directory,
            parseUtcDate("2024-10-18"),
            ledgerWindowEnd("2024-10-18")
        );
        return rejected && ledger.days.isEmpty() && Files.exists(
            directory.resolve(STAGING_DIRECTORY_NAME).resolve("partial.csv.tmp")
        );
    }

    private static boolean unexpectedStaging(Path directory) throws Exception {
        openStaging(directory, "2024-10-19", "2024-10-19");
        Files.write(
            directory.resolve(STAGING_DIRECTORY_NAME).resolve("notes.txt"),
            "notes".getBytes(StandardCharsets.UTF_8)
        );
        boolean rejected = stagingOpenFails(
            directory,
            "2024-10-19",
            "2024-10-19",
            "Unexpected staging file"
        );
        DayLedger ledger = DayLedger.open(
            directory,
            parseUtcDate("2024-10-19"),
            ledgerWindowEnd("2024-10-19")
        );
        return rejected && ledger.days.isEmpty();
    }

    private static boolean unknownSchemaRejected(Path directory) throws Exception {
        long start = parseUtcDate("2024-10-20");
        long end = ledgerWindowEnd("2024-10-20");
        long candle = parseUtcMinute("2024-10-20 00:00:00");
        String text = "windowStart=" + start + "\n"
            + "windowEnd=" + end + "\n"
            + "schemaVersion=3\n"
            + "day.2024-10-20.status=DATA_SAVED\n"
            + "day.2024-10-20.attempts=1\n"
            + "day.2024-10-20.lastAttempt=" + candle + "\n"
            + "day.2024-10-20.rowCount=1\n"
            + "day.2024-10-20.firstCandle=" + candle + "\n"
            + "day.2024-10-20.lastCandle=" + candle + "\n"
            + "day.2024-10-20.detail=claimed\n"
            + "day.2024-10-20.sha256="
            + "0000000000000000000000000000000000000000000000000000000000000000\n";
        Files.createDirectories(directory);
        Files.write(
            directory.resolve(LEDGER_FILE_NAME),
            text.getBytes(StandardCharsets.UTF_8)
        );
        return stagingOpenFails(directory, "2024-10-20", "2024-10-20", "Unsupported ledger schema")
            && ledgerOpenFails(directory, start, end, "Unsupported ledger schema");
    }

    private static boolean oldSchemaRejected(Path directory) throws Exception {
        DayStagingStore store = openStaging(directory, "2024-10-21", "2024-10-21");
        long day = parseUtcDate("2024-10-21");
        store.saveDay(
            day,
            stagingSample("2024-10-21"),
            ledgerAttempt("2024-10-21 08:00:00"),
            "saved"
        );
        String text = new String(Files.readAllBytes(store.ledger.path), StandardCharsets.UTF_8);
        String removed = text.replace("schemaVersion=2\n", "");
        Files.write(store.ledger.path, removed.getBytes(StandardCharsets.UTF_8));
        return stagingOpenFails(directory, "2024-10-21", "2024-10-21", "Unsupported ledger schema");
    }

    private static boolean stagingSaveFails(
            DayStagingStore store,
            String date,
            List<CandleRecord> candles,
            String expected) throws Exception {

        try {
            store.saveDay(
                parseUtcDate(date),
                candles,
                ledgerAttempt(date + " 08:00:00"),
                "bad"
            );
            return false;
        } catch (IOException ex) {
            return ex.getMessage() != null && ex.getMessage().indexOf(expected) >= 0;
        }
    }

    private static boolean stagingOpenFails(
            Path directory,
            String startDate,
            String endDate,
            String expected) throws Exception {

        try {
            openStaging(directory, startDate, endDate);
            return false;
        } catch (IOException ex) {
            return ex.getMessage() != null && ex.getMessage().indexOf(expected) >= 0;
        }
    }

    private static boolean stagingHasCsv(Path directory) throws IOException {
        Path staging = directory.resolve(STAGING_DIRECTORY_NAME);
        if (!Files.isDirectory(staging)) {
            return false;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(staging, "*.csv")) {
            for (Path child : children) {
                if (child != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private interface AttemptClock {
        long now();
    }

    private static final class SequenceClock implements AttemptClock {
        private final long[] times;
        private int index;

        private SequenceClock(long[] times) {
            this.times = times;
        }

        public long now() {
            if (index >= times.length) {
                throw new IllegalStateException("No attempt timestamp remains");
            }
            long value = times[index];
            index++;
            return value;
        }
    }

    private static List<Long> selectRetryDays(
            DayStagingStore store,
            int maxDays,
            long latestCompleted) throws IOException {

        if (maxDays < 1) {
            throw new IOException("max-days must be a positive integer");
        }
        requireMinuteBoundary(latestCompleted, "Latest completed candle");
        List<UtcInterval> intervals = planDailyIntervals(
            store.ledger.windowStart,
            store.ledger.windowEnd
        );
        List<Long> selected = new ArrayList<Long>();
        for (int i = 0; i < intervals.size() && selected.size() < maxDays; i++) {
            UtcInterval interval = intervals.get(i);
            if (interval.startMillis > latestCompleted) {
                continue;
            }
            long day = startOfUtcDay(interval.startMillis);
            DayRecord record = store.ledger.days.get(Long.valueOf(day));
            if (record == null) {
                store.ledger.ensureUnknown(day);
                selected.add(Long.valueOf(day));
            } else if (STATUS_UNKNOWN.equals(record.status)
                    || STATUS_FAILED.equals(record.status)) {
                selected.add(Long.valueOf(day));
            } else if (!isResolvedStatus(record.status)) {
                throw new IOException("Malformed ledger status");
            }
        }
        return selected;
    }

    private static UtcInterval plannedInterval(DayLedger ledger, long dayStart) {
        List<UtcInterval> intervals = planDailyIntervals(
            ledger.windowStart,
            ledger.windowEnd
        );
        for (int i = 0; i < intervals.size(); i++) {
            UtcInterval interval = intervals.get(i);
            if (startOfUtcDay(interval.startMillis) == dayStart) {
                return interval;
            }
        }
        return null;
    }

    private static DayChunkResult retryOutcome(DayChunkResult result) {
        if (result == null
                || result.status == DAY_CONFIRMED_EMPTY
                || (result.status == DAY_DATA
                    && (result.candles == null || result.candles.isEmpty()))) {
            List<CandleRecord> candles = result == null ? null : result.candles;
            return classifyDayResponse(candles, false, false, false, false, null);
        }
        return result;
    }

    private static List<Long> runRetryDays(
            DayStagingStore store,
            DayChunkSource source,
            RetryDelay delay,
            AttemptClock clock,
            int maxDays,
            long latestCompleted) throws IOException, InterruptedException {

        List<Long> selected = selectRetryDays(store, maxDays, latestCompleted);
        for (int i = 0; i < selected.size(); i++) {
            processSelectedDay(
                store,
                source,
                delay,
                clock,
                selected.get(i).longValue(),
                latestCompleted
            );
        }
        return selected;
    }

    private static final class DayRunResult {
        private final String status;
        private final int attempts;
        private final int candles;
        private final long rows;

        private DayRunResult(String status, int attempts, int candles, long rows) {
            this.status = status;
            this.attempts = attempts;
            this.candles = candles;
            this.rows = rows;
        }
    }

    private static DayRunResult processSelectedDay(
            DayStagingStore store,
            DayChunkSource source,
            RetryDelay delay,
            AttemptClock clock,
            long day,
            long latestCompleted) throws IOException, InterruptedException {

        return processSelectedDay(
            store,
            source,
            delay,
            clock,
            day,
            latestCompleted,
            DAY_ATTEMPTS
        );
    }

    private static DayRunResult processSelectedDay(
            DayStagingStore store,
            DayChunkSource source,
            RetryDelay delay,
            AttemptClock clock,
            long day,
            long latestCompleted,
            int maxAttempts) throws IOException, InterruptedException {

        if (maxAttempts < 1 || maxAttempts > DAY_ATTEMPTS) {
            throw new IOException("Invalid attempt limit");
        }
        UtcInterval interval = plannedInterval(store.ledger, day);
        if (interval == null || interval.startMillis > latestCompleted) {
            DayRecord current = store.ledger.days.get(Long.valueOf(day));
            return new DayRunResult(
                current == null ? STATUS_UNKNOWN : current.status,
                current == null ? 0 : current.attempts,
                0,
                0L
            );
        }
        long requestEnd = interval.endMillis > latestCompleted
            ? latestCompleted
            : interval.endMillis;
        if (requestEnd < interval.startMillis) {
            DayRecord current = store.ledger.days.get(Long.valueOf(day));
            return new DayRunResult(
                current == null ? STATUS_UNKNOWN : current.status,
                current == null ? 0 : current.attempts,
                0,
                0L
            );
        }
        int candles = 0;
        boolean stopDay = false;
        for (int attempt = 1; attempt <= maxAttempts && !stopDay; attempt++) {
            store.ledger.persistAttempt(day, clock.now());
            DayChunkResult result = retryOutcome(
                source.request(interval.startMillis, requestEnd)
            );
            candles = result.candles.size();
            if (result.status == DAY_DATA && !result.candles.isEmpty()) {
                try {
                    store.publishAttempt(day, result.candles, "saved");
                } catch (IOException ex) {
                    System.out.println(
                        utcDate(day) + " staging write failed: " + ex.getMessage()
                    );
                }
                stopDay = true;
                continue;
            }
            String status = result.status == DAY_FAILED
                ? STATUS_FAILED
                : STATUS_UNKNOWN;
            store.ledger.finishAttempt(
                day,
                status,
                0L,
                -1L,
                -1L,
                result.detail,
                ""
            );
            if (!result.retryable || attempt == maxAttempts) {
                System.out.println(
                    utcDate(day) + " attempt " + attempt + "/" + maxAttempts
                    + " " + result.statusName() + ": " + result.detail
                );
                stopDay = true;
            } else {
                long wait = DAY_RETRY_DELAYS_MS[attempt - 1];
                System.out.println(
                    utcDate(day) + " attempt " + attempt + "/" + DAY_ATTEMPTS
                    + " " + result.statusName()
                    + ". Retrying in " + wait + " ms."
                );
                delay.delay(wait);
            }
        }
        DayRecord after = store.ledger.days.get(Long.valueOf(day));
        long rows = after != null && STATUS_DATA_SAVED.equals(after.status)
            ? after.rowCount
            : 0L;
        return new DayRunResult(
            after == null ? STATUS_UNKNOWN : after.status,
            after == null ? 0 : after.attempts,
            candles,
            rows
        );
    }

    private static boolean runRetrySelfTest() throws Exception {
        System.out.println(
            "Retry tests use a temporary directory and simulated responses. They do not connect to Dukascopy."
        );
        Path root = Files.createTempDirectory("dukascopy-retry-test");
        failNextStagingWrite = false;
        boolean passed = true;
        try {
            passed &= testRetryMissingInitialization(root.resolve("missing"));
            passed &= testRetrySkipsSaved(root.resolve("saved"));
            passed &= testRetrySkipsConfirmedEmpty(root.resolve("empty"));
            passed &= testRetrySelectsUnknown(root.resolve("unknown"));
            passed &= testRetrySelectsFailed(root.resolve("failed"));
            passed &= testRetryOldestFirst(root.resolve("order"));
            passed &= testRetryMaxDays(root.resolve("max"));
            passed &= testRetrySuccessfulSave(root.resolve("success"));
            passed &= testRetryEmptyStaysUnknown(root.resolve("ambiguous"));
            passed &= testRetryExceptionStaysFailed(root.resolve("exception"));
            passed &= testRetryAttemptPersists(root.resolve("restart"));
            passed &= testRetryExhaustedStaysIncomplete(root.resolve("exhausted"));
            passed &= testRetrySkipsResolvedOnRerun(root.resolve("rerun"));
            passed &= testRetryCrashStaysRetryable(root.resolve("crash"));
            passed &= testRetryStagingFailureDoesNotSave(root.resolve("staging-fail"));
        } finally {
            failNextStagingWrite = false;
            deleteRecursively(root);
        }
        System.out.println("--------------------------------");
        System.out.println(passed
            ? "RETRY TESTS PASS"
            : "RETRY TESTS FAIL");
        return passed;
    }

    private static SequenceClock retryClock(String... timestamps) throws ParseException {
        long[] times = new long[timestamps.length];
        for (int i = 0; i < timestamps.length; i++) {
            times[i] = ledgerAttempt(timestamps[i]);
        }
        return new SequenceClock(times);
    }

    private static boolean sameDay(List<Long> days, int index, String date) throws ParseException {
        return days.size() > index && days.get(index).longValue() == parseUtcDate(date);
    }

    private static boolean testRetryMissingInitialization(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Missing ledger day initialization");
        DayStagingStore store = openStaging(directory, "2024-12-01", "2024-12-01");
        List<Long> selected = selectRetryDays(
            store,
            5,
            ledgerWindowEnd("2024-12-01")
        );
        DayStagingStore reloaded = openStaging(directory, "2024-12-01", "2024-12-01");
        DayRecord record = dayOf(reloaded.ledger, "2024-12-01");
        boolean passed = selected.size() == 1
            && sameDay(selected, 0, "2024-12-01")
            && record.status.equals(STATUS_UNKNOWN)
            && record.attempts == 0
            && record.lastAttemptMillis == 0L
            && "Not requested".equals(record.detail)
            && !stagingHasCsv(directory);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testRetrySkipsSaved(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("DATA_SAVED day is skipped");
        DayStagingStore store = openStaging(directory, "2024-12-02", "2024-12-02");
        store.saveDay(
            parseUtcDate("2024-12-02"),
            stagingSample("2024-12-02"),
            ledgerAttempt("2024-12-02 08:00:00"),
            "saved"
        );
        final int[] calls = new int[1];
        List<Long> selected = runRetryDays(
            store,
            new DayChunkSource() {
                public DayChunkResult request(long start, long end) {
                    calls[0]++;
                    throw new IllegalStateException("resolved day was requested");
                }
            },
            new RecordingDelay(),
            retryClock("2024-12-02 09:00:00"),
            5,
            ledgerWindowEnd("2024-12-02")
        );
        boolean passed = selected.isEmpty()
            && calls[0] == 0
            && statusOf(store.ledger, "2024-12-02").equals(STATUS_DATA_SAVED);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testRetrySkipsConfirmedEmpty(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("CONFIRMED_EMPTY day is skipped");
        DayStagingStore store = openStaging(directory, "2024-12-03", "2024-12-03");
        store.noteDay(
            parseUtcDate("2024-12-03"),
            STATUS_CONFIRMED_EMPTY,
            ledgerAttempt("2024-12-03 08:00:00"),
            "empty"
        );
        final int[] calls = new int[1];
        List<Long> selected = runRetryDays(
            store,
            new DayChunkSource() {
                public DayChunkResult request(long start, long end) {
                    calls[0]++;
                    throw new IllegalStateException("resolved day was requested");
                }
            },
            new RecordingDelay(),
            retryClock("2024-12-03 09:00:00"),
            5,
            ledgerWindowEnd("2024-12-03")
        );
        boolean passed = selected.isEmpty()
            && calls[0] == 0
            && statusOf(store.ledger, "2024-12-03").equals(STATUS_CONFIRMED_EMPTY)
            && !stagingHasCsv(directory);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testRetrySelectsUnknown(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("UNKNOWN day is selected");
        DayStagingStore store = openStaging(directory, "2024-12-04", "2024-12-04");
        store.noteDay(
            parseUtcDate("2024-12-04"),
            STATUS_UNKNOWN,
            ledgerAttempt("2024-12-04 08:00:00"),
            "unknown"
        );
        List<Long> selected = selectRetryDays(
            store,
            5,
            ledgerWindowEnd("2024-12-04")
        );
        boolean passed = selected.size() == 1
            && sameDay(selected, 0, "2024-12-04")
            && statusOf(store.ledger, "2024-12-04").equals(STATUS_UNKNOWN);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testRetrySelectsFailed(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("FAILED day is selected");
        DayStagingStore store = openStaging(directory, "2024-12-05", "2024-12-05");
        store.noteDay(
            parseUtcDate("2024-12-05"),
            STATUS_FAILED,
            ledgerAttempt("2024-12-05 08:00:00"),
            "failed"
        );
        List<Long> selected = selectRetryDays(
            store,
            5,
            ledgerWindowEnd("2024-12-05")
        );
        boolean passed = selected.size() == 1
            && sameDay(selected, 0, "2024-12-05")
            && statusOf(store.ledger, "2024-12-05").equals(STATUS_FAILED);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testRetryOldestFirst(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Oldest unresolved dates are selected first");
        DayStagingStore store = openStaging(directory, "2024-12-06", "2024-12-08");
        store.noteDay(
            parseUtcDate("2024-12-06"),
            STATUS_FAILED,
            ledgerAttempt("2024-12-06 08:00:00"),
            "failed"
        );
        store.noteDay(
            parseUtcDate("2024-12-07"),
            STATUS_CONFIRMED_EMPTY,
            ledgerAttempt("2024-12-07 08:00:00"),
            "empty"
        );
        final List<String> requested = new ArrayList<String>();
        List<Long> selected = runRetryDays(
            store,
            new DayChunkSource() {
                public DayChunkResult request(long start, long end) {
                    requested.add(utcDate(start));
                    try {
                        return classifyDayResponse(
                            stagingSample(utcDate(start)),
                            false,
                            false,
                            false,
                            false,
                            null
                        );
                    } catch (ParseException ex) {
                        throw new IllegalStateException(ex.getMessage());
                    }
                }
            },
            new RecordingDelay(),
            retryClock("2024-12-06 09:00:00", "2024-12-08 09:00:00"),
            5,
            ledgerWindowEnd("2024-12-08")
        );
        boolean passed = selected.size() == 2
            && sameDay(selected, 0, "2024-12-06")
            && sameDay(selected, 1, "2024-12-08")
            && requested.size() == 2
            && "2024-12-06".equals(requested.get(0))
            && "2024-12-08".equals(requested.get(1))
            && statusOf(store.ledger, "2024-12-07").equals(STATUS_CONFIRMED_EMPTY);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testRetryMaxDays(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Maximum distinct days per run is respected");
        DayStagingStore store = openStaging(directory, "2024-12-09", "2024-12-12");
        store.saveDay(
            parseUtcDate("2024-12-09"),
            stagingSample("2024-12-09"),
            ledgerAttempt("2024-12-09 08:00:00"),
            "saved"
        );
        byte[] savedBefore = Files.readAllBytes(store.stagingFile(parseUtcDate("2024-12-09")));
        store.noteDay(
            parseUtcDate("2024-12-10"),
            STATUS_FAILED,
            ledgerAttempt("2024-12-10 08:00:00"),
            "failed"
        );
        store.noteDay(
            parseUtcDate("2024-12-11"),
            STATUS_UNKNOWN,
            ledgerAttempt("2024-12-11 08:00:00"),
            "unknown"
        );
        final List<String> requested = new ArrayList<String>();
        List<Long> selected = runRetryDays(
            store,
            new DayChunkSource() {
                public DayChunkResult request(long start, long end) {
                    requested.add(utcDate(start));
                    return classifyDayResponse(
                        new ArrayList<CandleRecord>(),
                        false,
                        false,
                        false,
                        false,
                        null
                    );
                }
            },
            new RecordingDelay(),
            retryClock(
                "2024-12-10 09:00:00",
                "2024-12-10 09:00:01",
                "2024-12-10 09:00:02",
                "2024-12-11 09:00:00",
                "2024-12-11 09:00:01",
                "2024-12-11 09:00:02"
            ),
            1,
            ledgerWindowEnd("2024-12-12")
        );
        int firstDayCalls = 0;
        int laterCalls = 0;
        for (int i = 0; i < requested.size(); i++) {
            if ("2024-12-10".equals(requested.get(i))) {
                firstDayCalls++;
            } else {
                laterCalls++;
            }
        }
        boolean passed = selected.size() == 1
            && sameDay(selected, 0, "2024-12-10")
            && firstDayCalls == DAY_ATTEMPTS
            && laterCalls == 0
            && dayOf(store.ledger, "2024-12-12") == null
            && statusOf(store.ledger, "2024-12-11").equals(STATUS_UNKNOWN)
            && dayOf(store.ledger, "2024-12-11").attempts == 1
            && Arrays.equals(
                savedBefore,
                Files.readAllBytes(store.stagingFile(parseUtcDate("2024-12-09")))
            );
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testRetrySuccessfulSave(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Successful retry writes staging and marks DATA_SAVED");
        DayStagingStore store = openStaging(directory, "2024-12-13", "2024-12-13");
        store.noteDay(
            parseUtcDate("2024-12-13"),
            STATUS_UNKNOWN,
            ledgerAttempt("2024-12-13 08:00:00"),
            "unknown"
        );
        final List<CandleRecord> candles = stagingSample("2024-12-13");
        ScriptedDaySource source = new ScriptedDaySource(new DayChunkResult[] {
            classifyDayResponse(candles, false, false, false, false, null)
        });
        runRetryDays(
            store,
            source,
            new RecordingDelay(),
            retryClock("2024-12-13 09:00:00"),
            5,
            ledgerWindowEnd("2024-12-13")
        );
        DayStagingStore reloaded = openStaging(directory, "2024-12-13", "2024-12-13");
        DayRecord record = dayOf(reloaded.ledger, "2024-12-13");
        byte[] bytes = Files.readAllBytes(reloaded.stagingFile(parseUtcDate("2024-12-13")));
        boolean passed = source.calls == 1
            && record.status.equals(STATUS_DATA_SAVED)
            && record.attempts == 2
            && record.rowCount == 3L
            && record.firstCandle == parseUtcMinute("2024-12-13 00:00:00")
            && record.lastCandle == parseUtcMinute("2024-12-13 00:03:00")
            && record.sha256.equals(sha256Hex(bytes))
            && record.lastAttemptMillis == ledgerAttempt("2024-12-13 09:00:00");
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testRetryEmptyStaysUnknown(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Empty response remains UNKNOWN");
        DayStagingStore store = openStaging(directory, "2024-12-14", "2024-12-14");
        ScriptedDaySource source = new ScriptedDaySource(new DayChunkResult[] {
            classifyDayResponse(
                new ArrayList<CandleRecord>(),
                false,
                false,
                false,
                false,
                null
            )
        });
        RecordingDelay delays = new RecordingDelay();
        runRetryDays(
            store,
            source,
            delays,
            retryClock(
                "2024-12-14 09:00:00",
                "2024-12-14 09:00:01",
                "2024-12-14 09:00:02"
            ),
            5,
            ledgerWindowEnd("2024-12-14")
        );
        DayRecord record = dayOf(store.ledger, "2024-12-14");
        LedgerCompleteness completeness = store.ledger.assess();
        boolean passed = source.calls == DAY_ATTEMPTS
            && delays.delays.size() == 2
            && delays.delays.get(0).longValue() == 2000L
            && delays.delays.get(1).longValue() == 5000L
            && record.status.equals(STATUS_UNKNOWN)
            && !record.status.equals(STATUS_CONFIRMED_EMPTY)
            && record.attempts == DAY_ATTEMPTS
            && !completeness.complete
            && !stagingHasCsv(directory);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testRetryExceptionStaysFailed(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Exception remains FAILED");
        DayStagingStore store = openStaging(directory, "2024-12-15", "2024-12-15");
        ScriptedDaySource source = new ScriptedDaySource(new DayChunkResult[] {
            classifyDayResponse(
                null,
                false,
                false,
                false,
                true,
                "IOException: connection reset"
            )
        });
        RecordingDelay delays = new RecordingDelay();
        runRetryDays(
            store,
            source,
            delays,
            retryClock(
                "2024-12-15 09:00:00",
                "2024-12-15 09:00:01",
                "2024-12-15 09:00:02"
            ),
            5,
            ledgerWindowEnd("2024-12-15")
        );
        DayRecord record = dayOf(store.ledger, "2024-12-15");
        boolean passed = source.calls == DAY_ATTEMPTS
            && delays.delays.size() == 2
            && delays.delays.get(0).longValue() == 2000L
            && delays.delays.get(1).longValue() == 5000L
            && record.status.equals(STATUS_FAILED)
            && record.attempts == DAY_ATTEMPTS
            && record.detail.indexOf("IOException") >= 0
            && !store.ledger.assess().complete
            && !stagingHasCsv(directory);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testRetryAttemptPersists(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Attempt count and last-attempt time persist across restart");
        DayStagingStore store = openStaging(directory, "2024-12-16", "2024-12-17");
        long savedDay = parseUtcDate("2024-12-16");
        store.saveDay(
            savedDay,
            stagingSample("2024-12-16"),
            ledgerAttempt("2024-12-16 08:00:00"),
            "saved"
        );
        byte[] savedBefore = Files.readAllBytes(store.stagingFile(savedDay));
        String savedHash = dayOf(store.ledger, "2024-12-16").sha256;
        long third = ledgerAttempt("2024-12-17 09:00:02");
        runRetryDays(
            store,
            new ScriptedDaySource(new DayChunkResult[] {
                classifyDayResponse(
                    new ArrayList<CandleRecord>(),
                    false,
                    false,
                    false,
                    false,
                    null
                )
            }),
            new RecordingDelay(),
            retryClock(
                "2024-12-17 09:00:00",
                "2024-12-17 09:00:01",
                "2024-12-17 09:00:02"
            ),
            5,
            ledgerWindowEnd("2024-12-17")
        );
        DayStagingStore reloaded = openStaging(directory, "2024-12-16", "2024-12-17");
        DayRecord retried = dayOf(reloaded.ledger, "2024-12-17");
        DayRecord preserved = dayOf(reloaded.ledger, "2024-12-16");
        boolean passed = retried.status.equals(STATUS_UNKNOWN)
            && retried.attempts == DAY_ATTEMPTS
            && retried.lastAttemptMillis == third
            && preserved.status.equals(STATUS_DATA_SAVED)
            && preserved.sha256.equals(savedHash)
            && Arrays.equals(savedBefore, Files.readAllBytes(reloaded.stagingFile(savedDay)));
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testRetryExhaustedStaysIncomplete(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Exhausted retries do not advance completeness");
        DayStagingStore store = openStaging(directory, "2024-12-18", "2024-12-19");
        store.saveDay(
            parseUtcDate("2024-12-18"),
            stagingSample("2024-12-18"),
            ledgerAttempt("2024-12-18 08:00:00"),
            "saved"
        );
        runRetryDays(
            store,
            new ScriptedDaySource(new DayChunkResult[] {
                classifyDayResponse(
                    new ArrayList<CandleRecord>(),
                    false,
                    false,
                    false,
                    false,
                    null
                )
            }),
            new RecordingDelay(),
            retryClock(
                "2024-12-19 09:00:00",
                "2024-12-19 09:00:01",
                "2024-12-19 09:00:02"
            ),
            5,
            ledgerWindowEnd("2024-12-19")
        );
        LedgerCompleteness completeness = store.ledger.assess();
        DayRecord unresolved = dayOf(store.ledger, "2024-12-19");
        boolean passed = !completeness.complete
            && completeness.unresolvedCount == 1
            && completeness.firstUnresolvedDay == parseUtcDate("2024-12-19")
            && unresolved.status.equals(STATUS_UNKNOWN)
            && unresolved.attempts == DAY_ATTEMPTS;
        boolean noUnresolvedFile = !Files.exists(store.stagingFile(parseUtcDate("2024-12-19")));
        System.out.println(passed && noUnresolvedFile ? "PASS" : "FAIL");
        return passed && noUnresolvedFile;
    }

    private static boolean testRetrySkipsResolvedOnRerun(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Re-running after success does not redownload resolved days");
        DayStagingStore store = openStaging(directory, "2024-12-20", "2024-12-20");
        final List<CandleRecord> candles = stagingSample("2024-12-20");
        runRetryDays(
            store,
            new ScriptedDaySource(new DayChunkResult[] {
                classifyDayResponse(candles, false, false, false, false, null)
            }),
            new RecordingDelay(),
            retryClock("2024-12-20 09:00:00"),
            5,
            ledgerWindowEnd("2024-12-20")
        );
        byte[] before = Files.readAllBytes(store.stagingFile(parseUtcDate("2024-12-20")));
        int attempts = dayOf(store.ledger, "2024-12-20").attempts;
        final int[] calls = new int[1];
        List<Long> selected = runRetryDays(
            store,
            new DayChunkSource() {
                public DayChunkResult request(long start, long end) {
                    calls[0]++;
                    throw new IllegalStateException("resolved day was requested");
                }
            },
            new RecordingDelay(),
            retryClock("2024-12-20 10:00:00"),
            5,
            ledgerWindowEnd("2024-12-20")
        );
        boolean passed = selected.isEmpty()
            && calls[0] == 0
            && attempts == dayOf(store.ledger, "2024-12-20").attempts
            && statusOf(store.ledger, "2024-12-20").equals(STATUS_DATA_SAVED)
            && Arrays.equals(
                before,
                Files.readAllBytes(store.stagingFile(parseUtcDate("2024-12-20")))
            );
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testRetryCrashStaysRetryable(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("A crash during a request leaves the day retryable");
        DayStagingStore store = openStaging(directory, "2024-12-21", "2024-12-21");
        long attempt = ledgerAttempt("2024-12-21 09:00:00");
        boolean interrupted = false;
        try {
            runRetryDays(
                store,
                new DayChunkSource() {
                    public DayChunkResult request(long start, long end) {
                        throw new RuntimeException("interrupted");
                    }
                },
                new RecordingDelay(),
                retryClock("2024-12-21 09:00:00"),
                5,
                ledgerWindowEnd("2024-12-21")
            );
        } catch (RuntimeException ex) {
            interrupted = "interrupted".equals(ex.getMessage());
        }
        DayStagingStore reloaded = openStaging(directory, "2024-12-21", "2024-12-21");
        DayRecord record = dayOf(reloaded.ledger, "2024-12-21");
        List<Long> again = selectRetryDays(
            reloaded,
            5,
            ledgerWindowEnd("2024-12-21")
        );
        boolean passed = interrupted
            && record.status.equals(STATUS_UNKNOWN)
            && record.attempts == 1
            && record.lastAttemptMillis == attempt
            && !record.status.equals(STATUS_DATA_SAVED)
            && again.size() == 1
            && sameDay(again, 0, "2024-12-21")
            && !reloaded.ledger.assess().complete
            && !stagingHasCsv(directory);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testRetryStagingFailureDoesNotSave(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("A failed staging write does not mark DATA_SAVED");
        DayStagingStore store = openStaging(directory, "2024-12-22", "2024-12-22");
        final List<CandleRecord> candles = stagingSample("2024-12-22");
        failNextStagingWrite = true;
        try {
            runRetryDays(
                store,
                new ScriptedDaySource(new DayChunkResult[] {
                    classifyDayResponse(candles, false, false, false, false, null)
                }),
                new RecordingDelay(),
                retryClock("2024-12-22 09:00:00"),
                5,
                ledgerWindowEnd("2024-12-22")
            );
        } finally {
            failNextStagingWrite = false;
        }
        DayStagingStore reloaded = openStaging(directory, "2024-12-22", "2024-12-22");
        DayRecord record = dayOf(reloaded.ledger, "2024-12-22");
        boolean passed = record.status.equals(STATUS_UNKNOWN)
            && record.attempts == 1
            && record.rowCount == 0L
            && record.sha256.length() == 0
            && !reloaded.ledger.assess().complete
            && !stagingHasCsv(directory);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static final class MergeGap {
        private final long missingStart;
        private final long durationMillis;

        private MergeGap(long missingStart, long durationMillis) {
            this.missingStart = missingStart;
            this.durationMillis = durationMillis;
        }
    }

    private static final class MergeReport {
        private final long windowStart;
        private final long windowEnd;
        private final int plannedDays;
        private final int savedIncluded;
        private final int confirmedEmptySkipped;
        private final int unknownCount;
        private final int failedCount;
        private final long firstUnresolvedDay;
        private final int mergedCandles;
        private final long firstCandle;
        private final long lastCandle;
        private final List<MergeGap> gaps;
        private final Path csvPath;
        private final boolean datasetComplete;

        private MergeReport(
                long windowStart,
                long windowEnd,
                int plannedDays,
                int savedIncluded,
                int confirmedEmptySkipped,
                int unknownCount,
                int failedCount,
                long firstUnresolvedDay,
                int mergedCandles,
                long firstCandle,
                long lastCandle,
                List<MergeGap> gaps,
                Path csvPath,
                boolean datasetComplete) {
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.plannedDays = plannedDays;
            this.savedIncluded = savedIncluded;
            this.confirmedEmptySkipped = confirmedEmptySkipped;
            this.unknownCount = unknownCount;
            this.failedCount = failedCount;
            this.firstUnresolvedDay = firstUnresolvedDay;
            this.mergedCandles = mergedCandles;
            this.firstCandle = firstCandle;
            this.lastCandle = lastCandle;
            this.gaps = gaps;
            this.csvPath = csvPath;
            this.datasetComplete = datasetComplete;
        }
    }

    private static final class MergeCursor {
        private long previous = Long.MIN_VALUE;
        private long firstCandle = -1L;
        private long lastCandle = -1L;
        private int count;
        private final List<MergeGap> gaps = new ArrayList<MergeGap>();
    }

    private static void acceptMergedCandle(
            MergeCursor cursor,
            CandleRecord candle,
            boolean reportGaps) throws IOException {

        validateCandle(candle);
        if (cursor.previous != Long.MIN_VALUE) {
            if (candle.timeMillis == cursor.previous) {
                throw new IOException(
                    "Duplicate timestamp at " + formatUtcMinute(candle.timeMillis)
                );
            }
            if (candle.timeMillis < cursor.previous) {
                throw new IOException(
                    "Backward timestamp at " + formatUtcMinute(candle.timeMillis)
                );
            }
            long step = candle.timeMillis - cursor.previous;
            if (step > 60000L) {
                long missingStart = cursor.previous + 60000L;
                long duration = step - 60000L;
                cursor.gaps.add(new MergeGap(missingStart, duration));
                if (reportGaps) {
                    System.out.println(
                        "Gap: " + formatUtcMinute(missingStart)
                        + " duration " + duration + " ms"
                    );
                }
            }
        }
        if (cursor.count == 0) {
            cursor.firstCandle = candle.timeMillis;
        }
        cursor.lastCandle = candle.timeMillis;
        cursor.previous = candle.timeMillis;
        cursor.count++;
    }

    private static int auditStreamedMerge(Path file) throws IOException {
        int rows = 0;
        long previous = Long.MIN_VALUE;
        boolean sawHeader = false;
        try (BufferedReader reader = Files.newBufferedReader(
                file,
                StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!sawHeader) {
                    if (!CSV_HEADER.equals(line)) {
                        throw new IOException("Merged CSV audit failed");
                    }
                    sawHeader = true;
                    continue;
                }
                if (line.length() == 0) {
                    throw new IOException("Merged CSV audit failed");
                }
                CandleRecord candle = parseStagingLine(line);
                validateCandle(candle);
                if (previous != Long.MIN_VALUE && candle.timeMillis <= previous) {
                    throw new IOException("Merged CSV audit failed");
                }
                previous = candle.timeMillis;
                rows++;
            }
        }
        if (!sawHeader) {
            throw new IOException("Merged CSV audit failed");
        }
        return rows;
    }

    private static MergeReport mergeFinal(DayStagingStore store) throws IOException {
        DayStagingStore fresh = DayStagingStore.open(
            store.outputDirectory,
            store.ledger.windowStart,
            store.ledger.windowEnd
        );
        List<UtcInterval> intervals = planDailyIntervals(
            fresh.ledger.windowStart,
            fresh.ledger.windowEnd
        );
        int savedIncluded = 0;
        int confirmedEmptySkipped = 0;
        int unknownCount = 0;
        int failedCount = 0;
        boolean stopped = false;
        Path target = fresh.outputDirectory.resolve(FINAL_CSV_NAME).toAbsolutePath();
        Path temporary = target.resolveSibling(FINAL_CSV_NAME + ".tmp");
        if (Files.exists(temporary)) {
            throw new IOException("Interrupted merge");
        }
        MergeCursor cursor = new MergeCursor();
        boolean published = false;
        try {
            BufferedWriter writer = Files.newBufferedWriter(
                temporary,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            );
            try {
                writer.write(CSV_HEADER);
                writer.write('\n');
                for (int i = 0; i < intervals.size(); i++) {
                    long day = startOfUtcDay(intervals.get(i).startMillis);
                    DayRecord record = fresh.ledger.days.get(Long.valueOf(day));
                    if (record != null && STATUS_UNKNOWN.equals(record.status)) {
                        unknownCount++;
                    } else if (record != null && STATUS_FAILED.equals(record.status)) {
                        failedCount++;
                    }
                    if (stopped) {
                        continue;
                    }
                    if (record == null
                            || STATUS_UNKNOWN.equals(record.status)
                            || STATUS_FAILED.equals(record.status)) {
                        stopped = true;
                        continue;
                    }
                    if (STATUS_CONFIRMED_EMPTY.equals(record.status)) {
                        if (Files.exists(fresh.stagingFile(day))) {
                            throw new IOException(
                                "CONFIRMED_EMPTY day has a staging file"
                            );
                        }
                        confirmedEmptySkipped++;
                        continue;
                    }
                    if (!STATUS_DATA_SAVED.equals(record.status)) {
                        throw new IOException("Malformed ledger status");
                    }
                    byte[] bytes = Files.readAllBytes(fresh.stagingFile(day));
                    if (!sha256Hex(bytes).equals(record.sha256)) {
                        throw new IOException(
                            "Staging file does not match the ledger"
                        );
                    }
                    List<CandleRecord> rows = parseStagingCsv(day, bytes);
                    if (rows.size() != record.rowCount
                            || rows.get(0).timeMillis != record.firstCandle
                            || rows.get(rows.size() - 1).timeMillis
                                != record.lastCandle) {
                        throw new IOException(
                            "Staging file does not match the ledger"
                        );
                    }
                    for (int row = 0; row < rows.size(); row++) {
                        acceptMergedCandle(cursor, rows.get(row), true);
                        writer.write(toCsvLine(rows.get(row)));
                    }
                    savedIncluded++;
                }
                writer.flush();
            } finally {
                writer.close();
            }
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            int reread = auditStreamedMerge(temporary);
            if (reread != cursor.count) {
                throw new IOException("Merged CSV audit failed");
            }
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING
                );
            }
            published = true;
        } finally {
            if (!published) {
                Files.deleteIfExists(temporary);
            }
        }
        LedgerCompleteness completeness = fresh.ledger.assess();
        MergeReport report = new MergeReport(
            fresh.ledger.windowStart,
            fresh.ledger.windowEnd,
            intervals.size(),
            savedIncluded,
            confirmedEmptySkipped,
            unknownCount,
            failedCount,
            completeness.firstUnresolvedDay,
            cursor.count,
            cursor.firstCandle,
            cursor.lastCandle,
            cursor.gaps,
            target,
            completeness.complete
        );
        printMergeReport(report);
        return report;
    }

    private static void publishMerged(Path target, List<CandleRecord> candles)
            throws IOException {

        auditMergedCandles(candles, false);
        byte[] payload = mergedCsvBytes(candles);
        List<CandleRecord> reread = parseMergedCsv(payload);
        auditMergedCandles(reread, false);
        if (reread.size() != candles.size()) {
            throw new IOException("Merged CSV audit failed");
        }
        for (int i = 0; i < candles.size(); i++) {
            if (reread.get(i).timeMillis != candles.get(i).timeMillis) {
                throw new IOException("Merged CSV audit failed");
            }
        }
        atomicWrite(target, payload);
    }

    private static byte[] mergedCsvBytes(List<CandleRecord> candles) {
        StringBuilder text = new StringBuilder();
        text.append(CSV_HEADER).append('\n');
        for (int i = 0; i < candles.size(); i++) {
            text.append(toCsvLine(candles.get(i)));
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static List<CandleRecord> parseMergedCsv(byte[] bytes) throws IOException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.indexOf('\r') >= 0) {
            throw new IOException("Merged CSV audit failed");
        }
        String[] lines = text.split("\n", -1);
        if (lines.length < 1 || !CSV_HEADER.equals(lines[0]) || lines[lines.length - 1].length() != 0) {
            throw new IOException("Merged CSV audit failed");
        }
        List<CandleRecord> rows = new ArrayList<CandleRecord>();
        for (int i = 1; i < lines.length - 1; i++) {
            if (lines[i].length() == 0) {
                throw new IOException("Merged CSV audit failed");
            }
            rows.add(parseStagingLine(lines[i]));
        }
        return rows;
    }

    private static List<MergeGap> auditMergedCandles(
            List<CandleRecord> candles,
            boolean reportGaps) throws IOException {

        List<MergeGap> gaps = new ArrayList<MergeGap>();
        long previous = Long.MIN_VALUE;
        for (int i = 0; i < candles.size(); i++) {
            CandleRecord candle = candles.get(i);
            validateCandle(candle);
            if (previous != Long.MIN_VALUE) {
                if (candle.timeMillis == previous) {
                    throw new IOException(
                        "Duplicate timestamp at " + formatUtcMinute(candle.timeMillis)
                    );
                }
                if (candle.timeMillis < previous) {
                    throw new IOException(
                        "Backward timestamp at " + formatUtcMinute(candle.timeMillis)
                    );
                }
                long step = candle.timeMillis - previous;
                if (step > 60000L) {
                    long missingStart = previous + 60000L;
                    long duration = step - 60000L;
                    gaps.add(new MergeGap(missingStart, duration));
                    if (reportGaps) {
                        System.out.println(
                            "Gap: " + formatUtcMinute(missingStart)
                            + " duration " + duration + " ms"
                        );
                    }
                }
            }
            previous = candle.timeMillis;
        }
        return gaps;
    }

    private static long gapDuration(List<MergeGap> gaps) {
        long total = 0L;
        for (int i = 0; i < gaps.size(); i++) {
            total += gaps.get(i).durationMillis;
        }
        return total;
    }

    private static void printMergeReport(MergeReport report) {
        System.out.println("Window start: " + formatUtcMinute(report.windowStart));
        System.out.println("Window end: " + formatUtcMinute(report.windowEnd));
        System.out.println("Planned days: " + report.plannedDays);
        System.out.println("DATA_SAVED days included: " + report.savedIncluded);
        System.out.println(
            "CONFIRMED_EMPTY days skipped: " + report.confirmedEmptySkipped
        );
        System.out.println("UNKNOWN days: " + report.unknownCount);
        System.out.println("FAILED days: " + report.failedCount);
        System.out.println("First unresolved: " + (
            report.firstUnresolvedDay < 0L
                ? "none"
                : formatUtcMinute(report.firstUnresolvedDay).substring(0, 10)
        ));
        System.out.println("Merged candles: " + report.mergedCandles);
        System.out.println("First candle: " + (
            report.firstCandle < 0L ? "none" : formatUtcMinute(report.firstCandle)
        ));
        System.out.println("Last candle: " + (
            report.lastCandle < 0L ? "none" : formatUtcMinute(report.lastCandle)
        ));
        System.out.println("Gaps: " + report.gaps.size());
        System.out.println("Gap duration ms: " + gapDuration(report.gaps));
        System.out.println("Final CSV: " + report.csvPath.toAbsolutePath());
        System.out.println("DATASET_COMPLETE: " + report.datasetComplete);
    }

    private static boolean runMergeSelfTest() throws Exception {
        System.out.println(
            "Merge tests use a temporary directory and synthetic fixtures. They do not connect to Dukascopy."
        );
        Path root = Files.createTempDirectory("dukascopy-merge-test");
        boolean passed = true;
        try {
            passed &= testMergeStopsAtUnknown(root.resolve("hole"));
            passed &= testMergeSkipsConfirmedEmpty(root.resolve("empty"));
            passed &= testMergeStopsAtFailed(root.resolve("failed"));
            passed &= testMergeStopsAtMissing(root.resolve("missing"));
            passed &= testMergeMissingStagingFile(root.resolve("missing-file"));
            passed &= testMergeHashMismatch(root.resolve("hash"));
            passed &= testMergeWrongMetadata(root.resolve("metadata"));
            passed &= testMergeCorruptDailyCsv(root.resolve("corrupt"));
            passed &= testMergeOrphanStagingFile(root.resolve("orphan"));
            passed &= testMergeConfirmedEmptyFile(root.resolve("empty-file"));
            passed &= testMergeDuplicateAcrossDays(root.resolve("duplicate"));
            passed &= testMergeBackwardAcrossDays(root.resolve("backward"));
            passed &= testMergeReportsGaps(root.resolve("gaps"));
            passed &= testMergeRejectsBadCandles(root.resolve("bad-candles"));
            passed &= testMergePreservesPreviousCsv(root.resolve("preserve"));
            passed &= testMergeDeterministic(root.resolve("repeat"));
            passed &= testMergeCompleteDataset(root.resolve("complete"));
            passed &= testMergeIncompleteDataset(root.resolve("incomplete"));
        } finally {
            deleteRecursively(root);
        }
        System.out.println("--------------------------------");
        System.out.println(passed ? "MERGE TESTS PASS" : "MERGE TESTS FAIL");
        return passed;
    }

    private static List<CandleRecord> mergePair(String date, String firstMinute, String secondMinute)
            throws ParseException {
        return Arrays.asList(
            validCandle(date + " " + firstMinute, 1.10, 1.20, 1.00, 1.15, 10.0),
            validCandle(date + " " + secondMinute, 1.15, 1.25, 1.10, 1.20, 11.0)
        );
    }

    private static void saveMergeDay(DayStagingStore store, String date, String firstMinute, String secondMinute)
            throws Exception {
        store.saveDay(
            parseUtcDate(date),
            mergePair(date, firstMinute, secondMinute),
            ledgerAttempt(date + " 08:00:00"),
            "saved"
        );
    }

    private static void setLedgerProperty(Path ledger, String key, String value) throws IOException {
        String text = new String(Files.readAllBytes(ledger), StandardCharsets.UTF_8);
        String[] lines = text.split("\n", -1);
        boolean found = false;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith(key + "=")) {
                lines[i] = key + "=" + value;
                found = true;
            }
        }
        if (!found) {
            throw new IOException("Missing ledger key " + key);
        }
        StringBuilder replaced = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                replaced.append('\n');
            }
            replaced.append(lines[i]);
        }
        Files.write(ledger, replaced.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static boolean mergeFails(DayStagingStore store, String expected) {
        try {
            mergeFinal(store);
            return false;
        } catch (IOException ex) {
            return ex.getMessage() != null && ex.getMessage().indexOf(expected) >= 0;
        }
    }

    private static boolean finalCsvUntouched(Path csv, byte[] before) throws IOException {
        return Arrays.equals(before, Files.readAllBytes(csv))
            && !Files.exists(csv.resolveSibling(FINAL_CSV_NAME + ".tmp"));
    }

    private static boolean testMergeStopsAtUnknown(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Day 1 DATA_SAVED, day 2 UNKNOWN, day 3 DATA_SAVED includes only day 1");
        DayStagingStore store = openStaging(directory, "2025-01-01", "2025-01-03");
        saveMergeDay(store, "2025-01-01", "00:00:00", "00:01:00");
        store.noteDay(
            parseUtcDate("2025-01-02"),
            STATUS_UNKNOWN,
            ledgerAttempt("2025-01-02 08:00:00"),
            "unknown"
        );
        saveMergeDay(store, "2025-01-03", "00:10:00", "00:11:00");
        MergeReport report = mergeFinal(store);
        String text = new String(Files.readAllBytes(report.csvPath), StandardCharsets.UTF_8);
        boolean passed = report.savedIncluded == 1
            && report.mergedCandles == 2
            && report.unknownCount == 1
            && !report.datasetComplete
            && report.firstUnresolvedDay == parseUtcDate("2025-01-02")
            && text.indexOf("2025-01-01 00:00:00") >= 0
            && text.indexOf("2025-01-01 00:01:00") >= 0
            && text.indexOf("2025-01-03") < 0
            && countOccurrences(text, CSV_HEADER) == 1
            && !Files.exists(report.csvPath.resolveSibling(FINAL_CSV_NAME + ".tmp"));
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testMergeSkipsConfirmedEmpty(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("CONFIRMED_EMPTY day lets the merge continue");
        DayStagingStore store = openStaging(directory, "2025-01-04", "2025-01-06");
        saveMergeDay(store, "2025-01-04", "00:00:00", "00:01:00");
        store.noteDay(
            parseUtcDate("2025-01-05"),
            STATUS_UNKNOWN,
            ledgerAttempt("2025-01-05 08:00:00"),
            "unknown"
        );
        saveMergeDay(store, "2025-01-06", "00:10:00", "00:11:00");
        mergeFinal(store);
        store.noteDay(
            parseUtcDate("2025-01-05"),
            STATUS_CONFIRMED_EMPTY,
            ledgerAttempt("2025-01-05 09:00:00"),
            "empty"
        );
        MergeReport report = mergeFinal(store);
        String text = new String(Files.readAllBytes(report.csvPath), StandardCharsets.UTF_8);
        int first = text.indexOf("2025-01-04 00:00:00");
        int third = text.indexOf("2025-01-06 00:10:00");
        boolean passed = report.datasetComplete
            && report.savedIncluded == 2
            && report.confirmedEmptySkipped == 1
            && report.mergedCandles == 4
            && report.unknownCount == 0
            && first >= 0
            && third > first
            && text.indexOf("2025-01-05") < 0
            && !stagingHasCsvForDate(directory, "2025-01-05");
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testMergeStopsAtFailed(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("FAILED day stops the merge");
        DayStagingStore store = openStaging(directory, "2025-01-07", "2025-01-09");
        saveMergeDay(store, "2025-01-07", "00:00:00", "00:01:00");
        store.noteDay(
            parseUtcDate("2025-01-08"),
            STATUS_FAILED,
            ledgerAttempt("2025-01-08 08:00:00"),
            "failed"
        );
        saveMergeDay(store, "2025-01-09", "00:10:00", "00:11:00");
        MergeReport report = mergeFinal(store);
        String text = new String(Files.readAllBytes(report.csvPath), StandardCharsets.UTF_8);
        boolean passed = report.savedIncluded == 1
            && report.failedCount == 1
            && report.mergedCandles == 2
            && !report.datasetComplete
            && report.firstUnresolvedDay == parseUtcDate("2025-01-08")
            && text.indexOf("2025-01-09") < 0;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testMergeStopsAtMissing(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Missing ledger record stops the merge");
        DayStagingStore store = openStaging(directory, "2025-01-10", "2025-01-12");
        saveMergeDay(store, "2025-01-10", "00:00:00", "00:01:00");
        saveMergeDay(store, "2025-01-12", "00:10:00", "00:11:00");
        MergeReport report = mergeFinal(store);
        String text = new String(Files.readAllBytes(report.csvPath), StandardCharsets.UTF_8);
        boolean passed = report.savedIncluded == 1
            && report.plannedDays == 3
            && report.unknownCount == 0
            && report.failedCount == 0
            && !report.datasetComplete
            && report.firstUnresolvedDay == parseUtcDate("2025-01-11")
            && text.indexOf("2025-01-12") < 0
            && text.indexOf("2025-01-10 00:01:00") >= 0;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testMergeMissingStagingFile(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Missing DATA_SAVED staging file rejects the merge");
        DayStagingStore store = openStaging(directory, "2025-01-13", "2025-01-13");
        saveMergeDay(store, "2025-01-13", "00:00:00", "00:01:00");
        MergeReport first = mergeFinal(store);
        byte[] before = Files.readAllBytes(first.csvPath);
        Files.delete(store.stagingFile(parseUtcDate("2025-01-13")));
        boolean rejected = mergeFails(store, "Missing staging file");
        boolean passed = rejected && finalCsvUntouched(first.csvPath, before);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testMergeHashMismatch(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("SHA-256 mismatch rejects the merge");
        DayStagingStore store = openStaging(directory, "2025-01-14", "2025-01-14");
        saveMergeDay(store, "2025-01-14", "00:00:00", "00:01:00");
        MergeReport first = mergeFinal(store);
        byte[] before = Files.readAllBytes(first.csvPath);
        Path staging = store.stagingFile(parseUtcDate("2025-01-14"));
        String text = new String(Files.readAllBytes(staging), StandardCharsets.UTF_8);
        Files.write(staging, text.replace(",10.0\n", ",11.0\n").getBytes(StandardCharsets.UTF_8));
        boolean rejected = mergeFails(store, "Staging file does not match the ledger");
        boolean passed = rejected && finalCsvUntouched(first.csvPath, before);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testMergeWrongMetadata(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Wrong row count or candle bounds reject the merge");
        boolean rowCount = wrongMetadata(
            directory.resolve("rows"),
            "2025-01-15",
            "rowCount",
            "99"
        );
        boolean bounds = wrongMetadata(
            directory.resolve("bounds"),
            "2025-01-16",
            "firstCandle",
            Long.toString(parseUtcMinute("2025-01-16 00:01:00"))
        );
        boolean passed = rowCount && bounds;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean wrongMetadata(Path directory, String date, String field, String value)
            throws Exception {
        DayStagingStore store = openStaging(directory, date, date);
        saveMergeDay(store, date, "00:00:00", "00:01:00");
        MergeReport first = mergeFinal(store);
        byte[] before = Files.readAllBytes(first.csvPath);
        setLedgerProperty(store.ledger.path, "day." + date + "." + field, value);
        return mergeFails(store, "Staging file does not match the ledger")
            && finalCsvUntouched(first.csvPath, before);
    }

    private static boolean testMergeCorruptDailyCsv(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Corrupt daily CSV rejects the merge");
        DayStagingStore store = openStaging(directory, "2025-01-17", "2025-01-17");
        saveMergeDay(store, "2025-01-17", "00:00:00", "00:01:00");
        MergeReport first = mergeFinal(store);
        byte[] before = Files.readAllBytes(first.csvPath);
        byte[] corrupt = "broken\n".getBytes(StandardCharsets.UTF_8);
        Path staging = store.stagingFile(parseUtcDate("2025-01-17"));
        Files.write(staging, corrupt);
        setLedgerProperty(
            store.ledger.path,
            "day.2025-01-17.sha256",
            sha256Hex(corrupt)
        );
        boolean rejected = mergeFails(store, "Invalid staging file");
        boolean passed = rejected && finalCsvUntouched(first.csvPath, before);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testMergeOrphanStagingFile(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Orphan staging file rejects the merge");
        DayStagingStore store = openStaging(directory, "2025-01-18", "2025-01-18");
        saveMergeDay(store, "2025-01-18", "00:00:00", "00:01:00");
        MergeReport first = mergeFinal(store);
        byte[] before = Files.readAllBytes(first.csvPath);
        Files.write(
            directory.resolve(STAGING_DIRECTORY_NAME).resolve("2025-01-19.csv"),
            "orphan\n".getBytes(StandardCharsets.UTF_8)
        );
        boolean rejected = mergeFails(store, "Orphan staging file");
        boolean passed = rejected && finalCsvUntouched(first.csvPath, before);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testMergeConfirmedEmptyFile(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("CONFIRMED_EMPTY day with a staging file rejects the merge");
        DayStagingStore store = openStaging(directory, "2025-01-20", "2025-01-21");
        saveMergeDay(store, "2025-01-20", "00:00:00", "00:01:00");
        store.noteDay(
            parseUtcDate("2025-01-21"),
            STATUS_CONFIRMED_EMPTY,
            ledgerAttempt("2025-01-21 08:00:00"),
            "empty"
        );
        MergeReport first = mergeFinal(store);
        byte[] before = Files.readAllBytes(first.csvPath);
        Files.write(
            store.stagingFile(parseUtcDate("2025-01-21")),
            "should-not-exist\n".getBytes(StandardCharsets.UTF_8)
        );
        boolean rejected = mergeFails(store, "CONFIRMED_EMPTY day has a staging file");
        boolean passed = rejected && finalCsvUntouched(first.csvPath, before);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testMergeDuplicateAcrossDays(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Duplicate timestamps across days reject the merge");
        Path csv = directory.resolve(FINAL_CSV_NAME);
        Files.createDirectories(directory);
        Files.write(csv, "previous-final\n".getBytes(StandardCharsets.UTF_8));
        byte[] before = Files.readAllBytes(csv);
        boolean rejected = false;
        try {
            publishMerged(csv, Arrays.asList(
                validCandle("2025-01-22 23:59:00", 1.10, 1.20, 1.00, 1.15, 10.0),
                validCandle("2025-01-22 23:59:00", 1.15, 1.25, 1.10, 1.20, 11.0)
            ));
        } catch (IOException ex) {
            rejected = ex.getMessage() != null
                && ex.getMessage().indexOf("Duplicate timestamp") >= 0;
        }
        boolean passed = rejected && finalCsvUntouched(csv, before);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testMergeBackwardAcrossDays(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Backward timestamps across days reject the merge");
        Path csv = directory.resolve(FINAL_CSV_NAME);
        Files.createDirectories(directory);
        Files.write(csv, "previous-final\n".getBytes(StandardCharsets.UTF_8));
        byte[] before = Files.readAllBytes(csv);
        boolean rejected = false;
        try {
            publishMerged(csv, Arrays.asList(
                validCandle("2025-01-23 23:59:00", 1.10, 1.20, 1.00, 1.15, 10.0),
                validCandle("2025-01-24 00:00:00", 1.15, 1.25, 1.10, 1.20, 11.0),
                validCandle("2025-01-23 23:58:00", 1.16, 1.26, 1.11, 1.21, 12.0)
            ));
        } catch (IOException ex) {
            rejected = ex.getMessage() != null
                && ex.getMessage().indexOf("Backward timestamp") >= 0;
        }
        boolean passed = rejected && finalCsvUntouched(csv, before);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testMergeReportsGaps(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Legitimate gaps are reported and not fabricated");
        DayStagingStore store = openStaging(directory, "2025-01-25", "2025-01-25");
        store.saveDay(
            parseUtcDate("2025-01-25"),
            stagingSample("2025-01-25"),
            ledgerAttempt("2025-01-25 08:00:00"),
            "saved"
        );
        MergeReport report = mergeFinal(store);
        String text = new String(Files.readAllBytes(report.csvPath), StandardCharsets.UTF_8);
        boolean passed = report.datasetComplete
            && report.mergedCandles == 3
            && report.gaps.size() == 1
            && gapDuration(report.gaps) == 60000L
            && text.indexOf("2025-01-25 00:00:00") >= 0
            && text.indexOf("2025-01-25 00:01:00") >= 0
            && text.indexOf("2025-01-25 00:03:00") >= 0
            && text.indexOf("2025-01-25 00:02:00") < 0;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testMergeRejectsBadCandles(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Invalid OHLC or volume rejects the merge");
        boolean ohlc = badCandleRejected(
            directory.resolve("ohlc"),
            "2025-01-26",
            "2025-01-26 00:00:00,1.1,0.5,1.0,1.15,10.0\n",
            "Invalid OHLC"
        );
        boolean volume = badCandleRejected(
            directory.resolve("volume"),
            "2025-01-27",
            "2025-01-27 00:00:00,1.1,1.2,1.0,1.15,-1.0\n",
            "Invalid volume"
        );
        boolean passed = ohlc && volume;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean badCandleRejected(
            Path directory,
            String date,
            String row,
            String expected) throws Exception {
        DayStagingStore store = openStaging(directory, date, date);
        store.saveDay(
            parseUtcDate(date),
            Arrays.asList(validCandle(date + " 00:00:00", 1.10, 1.20, 1.00, 1.15, 10.0)),
            ledgerAttempt(date + " 08:00:00"),
            "saved"
        );
        MergeReport first = mergeFinal(store);
        byte[] before = Files.readAllBytes(first.csvPath);
        byte[] rewritten = (CSV_HEADER + "\n" + row).getBytes(StandardCharsets.UTF_8);
        Files.write(store.stagingFile(parseUtcDate(date)), rewritten);
        setLedgerProperty(store.ledger.path, "day." + date + ".sha256", sha256Hex(rewritten));
        return mergeFails(store, expected) && finalCsvUntouched(first.csvPath, before);
    }

    private static boolean testMergePreservesPreviousCsv(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("A failed merge leaves the previous final CSV unchanged");
        DayStagingStore store = openStaging(directory, "2025-01-28", "2025-01-28");
        saveMergeDay(store, "2025-01-28", "00:00:00", "00:01:00");
        MergeReport first = mergeFinal(store);
        byte[] before = Files.readAllBytes(first.csvPath);
        Files.delete(store.stagingFile(parseUtcDate("2025-01-28")));
        boolean rejected = mergeFails(store, "Missing staging file");
        boolean passed = rejected
            && finalCsvUntouched(first.csvPath, before)
            && new String(before, StandardCharsets.UTF_8).indexOf("2025-01-28 00:00:00") >= 0;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testMergeDeterministic(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Repeated merge produces the same output");
        DayStagingStore store = openStaging(directory, "2025-01-29", "2025-01-30");
        saveMergeDay(store, "2025-01-29", "00:00:00", "00:01:00");
        store.noteDay(
            parseUtcDate("2025-01-30"),
            STATUS_CONFIRMED_EMPTY,
            ledgerAttempt("2025-01-30 08:00:00"),
            "empty"
        );
        MergeReport first = mergeFinal(store);
        byte[] before = Files.readAllBytes(first.csvPath);
        MergeReport second = mergeFinal(store);
        String text = new String(before, StandardCharsets.UTF_8);
        boolean passed = Arrays.equals(before, Files.readAllBytes(second.csvPath))
            && first.mergedCandles == second.mergedCandles
            && first.datasetComplete
            && second.datasetComplete
            && countOccurrences(text, CSV_HEADER) == 1
            && text.startsWith(CSV_HEADER + "\n");
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testMergeCompleteDataset(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("All resolved days make the dataset complete");
        DayStagingStore store = openStaging(directory, "2025-02-01", "2025-02-02");
        saveMergeDay(store, "2025-02-01", "00:00:00", "00:01:00");
        store.noteDay(
            parseUtcDate("2025-02-02"),
            STATUS_CONFIRMED_EMPTY,
            ledgerAttempt("2025-02-02 08:00:00"),
            "empty"
        );
        MergeReport report = mergeFinal(store);
        boolean passed = report.datasetComplete
            && report.plannedDays == 2
            && report.savedIncluded == 1
            && report.confirmedEmptySkipped == 1
            && report.unknownCount == 0
            && report.failedCount == 0
            && report.firstUnresolvedDay < 0L
            && report.mergedCandles == 2
            && report.firstCandle == parseUtcMinute("2025-02-01 00:00:00")
            && report.lastCandle == parseUtcMinute("2025-02-01 00:01:00")
            && report.gaps.isEmpty()
            && report.csvPath.getFileName().toString().equals(FINAL_CSV_NAME)
            && report.csvPath.toAbsolutePath().normalize().equals(
                directory.resolve(FINAL_CSV_NAME).toAbsolutePath().normalize()
            );
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testMergeIncompleteDataset(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Any unresolved day makes the dataset incomplete");
        DayStagingStore store = openStaging(directory, "2025-02-03", "2025-02-04");
        saveMergeDay(store, "2025-02-03", "00:00:00", "00:01:00");
        store.noteDay(
            parseUtcDate("2025-02-04"),
            STATUS_FAILED,
            ledgerAttempt("2025-02-04 08:00:00"),
            "failed"
        );
        MergeReport report = mergeFinal(store);
        boolean passed = !report.datasetComplete
            && report.failedCount == 1
            && report.savedIncluded == 1
            && report.firstUnresolvedDay == parseUtcDate("2025-02-04")
            && report.mergedCandles == 2;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean stagingHasCsvForDate(Path directory, String date) {
        return Files.exists(directory.resolve(STAGING_DIRECTORY_NAME).resolve(date + ".csv"));
    }

    private static final class ConfirmCommand {
        private final String date;
        private final String reason;
        private final String confirmDate;

        private ConfirmCommand(String date, String reason, String confirmDate) {
            this.date = date;
            this.reason = reason;
            this.confirmDate = confirmDate;
        }
    }

    private static ConfirmCommand parseConfirmCommand(String[] args) throws IOException {
        if (args == null
                || args.length != 6
                || !"confirm-empty".equals(args[0])
                || args[1] == null
                || args[1].length() == 0
                || !"--reason".equals(args[2])
                || args[3] == null
                || !"--confirm".equals(args[4])
                || args[5] == null
                || args[5].length() == 0) {
            throw new IOException("Malformed confirm-empty command");
        }
        return new ConfirmCommand(args[1], args[3], args[5]);
    }

    private static void runConfirmEmptyCommand(String[] args) {
        ConfirmCommand command;
        try {
            command = parseConfirmCommand(args);
        } catch (IOException ex) {
            System.out.println("FAIL: " + ex.getMessage());
            return;
        }
        try {
            boolean changed = confirmEmptyDay(
                Paths.get("output"),
                command.date,
                command.reason,
                command.confirmDate,
                System.currentTimeMillis()
            );
            System.out.println(changed
                ? "CONFIRMED_EMPTY " + command.date
                : "ALREADY_CONFIRMED_EMPTY " + command.date);
        } catch (ParseException ex) {
            System.out.println("FAIL: Invalid UTC date: " + command.date);
        } catch (RuntimeException ex) {
            System.out.println("FAIL: " + ex.getMessage());
        } catch (IOException ex) {
            System.out.println("FAIL: " + ex.getMessage());
        }
    }

    private static boolean confirmEmptyDay(
            Path directory,
            String dateText,
            String reason,
            String confirmDate,
            long nowMillis) throws IOException, ParseException {

        if (dateText == null
                || confirmDate == null
                || !dateText.equals(confirmDate)) {
            throw new IOException("Confirmation date does not match");
        }
        String supplied = reason == null ? "" : reason.trim();
        if (supplied.length() == 0) {
            throw new IOException("Reason is required");
        }
        if (supplied.length() > MAX_LEDGER_DETAIL) {
            throw new IOException("Reason is too long");
        }
        for (int i = 0; i < supplied.length(); i++) {
            if (supplied.charAt(i) < 32) {
                throw new IOException("Reason is invalid");
            }
        }
        long dayStart = parseUtcDate(dateText);
        if (nowMillis < 0L) {
            throw new IOException("Malformed confirmation time");
        }
        if (dayStart > startOfUtcDay(nowMillis)) {
            throw new IOException("Future date");
        }
        DayLedger ledger = DayLedger.openExisting(directory);
        if (!ledger.covers(dayStart)) {
            throw new IOException("Date is outside the dataset window");
        }
        DayRecord existing = ledger.days.get(Long.valueOf(dayStart));
        if (existing == null) {
            throw new IOException("Missing ledger record");
        }
        Path stagingFile = directory.resolve(STAGING_DIRECTORY_NAME)
            .resolve(utcDate(dayStart) + ".csv");
        if (!STATUS_DATA_SAVED.equals(existing.status) && Files.exists(stagingFile)) {
            throw new IOException("Existing staging file");
        }
        if (STATUS_CONFIRMED_EMPTY.equals(existing.status)) {
            return false;
        }
        if (!STATUS_UNKNOWN.equals(existing.status)
                && !STATUS_FAILED.equals(existing.status)) {
            throw new IOException(
                "Invalid ledger transition from " + existing.status
            );
        }
        Path stagingDirectory = directory.resolve(STAGING_DIRECTORY_NAME);
        if (Files.isDirectory(stagingDirectory)) {
            new DayStagingStore(directory, stagingDirectory, ledger).verify();
        }
        return ledger.confirmEmpty(dayStart, nowMillis, supplied);
    }

    private static boolean runConfirmationSelfTest() throws Exception {
        System.out.println(
            "Confirmation tests use a temporary directory and do not connect to Dukascopy."
        );
        Path root = Files.createTempDirectory("dukascopy-confirmation-test");
        boolean passed = true;
        try {
            passed &= testConfirmUnknown(root.resolve("unknown"));
            passed &= testConfirmFailed(root.resolve("failed"));
            passed &= testConfirmRejectsSaved(root.resolve("saved"));
            passed &= testConfirmRejectsMissing(root.resolve("missing"));
            passed &= testConfirmRejectsOutsideWindow(root.resolve("outside"));
            passed &= testConfirmRejectsFuture(root.resolve("future"));
            passed &= testConfirmRejectsMismatchedDate(root.resolve("mismatch"));
            passed &= testConfirmRejectsEmptyReason(root.resolve("reason"));
            passed &= testConfirmRejectsStagingFile(root.resolve("staging"));
            passed &= testConfirmRejectsCorruptLedger(root.resolve("corrupt"));
            passed &= testConfirmPersistsAfterRestart(root.resolve("restart"));
            passed &= testConfirmPreservesAttempts(root.resolve("attempts"));
            passed &= testConfirmSkipsRetry(root.resolve("retry"));
            passed &= testConfirmSkipsMerge(root.resolve("merge"));
            passed &= testConfirmCompleteness(root.resolve("complete"));
            passed &= testConfirmRepeatIsIdempotent(root.resolve("repeat"));
            passed &= testConfirmFailureIsUnchanged(root.resolve("unchanged"));
        } finally {
            deleteRecursively(root);
        }
        System.out.println("--------------------------------");
        System.out.println(passed
            ? "CONFIRMATION TESTS PASS"
            : "CONFIRMATION TESTS FAIL");
        return passed;
    }

    private static long confirmationNow() throws ParseException {
        return ledgerAttempt("2026-01-15 12:00:00");
    }

    private static DayLedger reopenLedger(Path directory, String startDate, String endDate)
            throws Exception {
        return DayLedger.open(
            directory,
            parseUtcDate(startDate),
            ledgerWindowEnd(endDate)
        );
    }

    private static String confirmationError(
            Path directory,
            String date,
            String reason,
            String confirmDate,
            long now) {
        try {
            confirmEmptyDay(directory, date, reason, confirmDate, now);
            return "";
        } catch (Exception ex) {
            return ex.getMessage() == null
                ? ex.getClass().getSimpleName()
                : ex.getMessage();
        }
    }

    private static boolean confirmCommandRejected(String[] args) {
        try {
            parseConfirmCommand(args);
            return false;
        } catch (IOException ex) {
            return "Malformed confirm-empty command".equals(ex.getMessage());
        }
    }

    private static int countLedgerField(byte[] bytes, String field) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        String needle = "." + field + "=";
        int count = 0;
        int from = 0;
        while (true) {
            int found = text.indexOf(needle, from);
            if (found < 0) {
                return count;
            }
            count++;
            from = found + needle.length();
        }
    }

    private static void prepareUnresolved(
            Path directory,
            String date,
            String status,
            String detail) throws Exception {
        DayStagingStore store = openStaging(directory, date, date);
        store.noteDay(
            parseUtcDate(date),
            status,
            ledgerAttempt(date + " 08:00:00"),
            detail
        );
    }

    private static boolean testConfirmUnknown(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("UNKNOWN becomes CONFIRMED_EMPTY");
        String date = "2024-06-01";
        prepareUnresolved(directory, date, STATUS_UNKNOWN, "not requested");
        long confirmAt = ledgerAttempt("2024-06-02 10:00:00");
        boolean changed = confirmEmptyDay(
            directory,
            date,
            CONFIRM_REASON,
            date,
            confirmAt
        );
        DayRecord record = dayOf(reopenLedger(directory, date, date), date);
        boolean passed = changed
            && STATUS_CONFIRMED_EMPTY.equals(record.status)
            && record.confirmAt == confirmAt
            && CONFIRM_REASON.equals(record.confirmReason)
            && CONFIRM_METHOD.equals(record.confirmMethod)
            && record.attempts == 1
            && record.rowCount == 0L
            && record.sha256.length() == 0
            && !stagingHasCsvForDate(directory, date);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testConfirmFailed(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("FAILED becomes CONFIRMED_EMPTY");
        String date = "2024-06-02";
        prepareUnresolved(directory, date, STATUS_FAILED, "transport failed");
        long confirmAt = ledgerAttempt("2024-06-03 10:00:00");
        boolean changed = confirmEmptyDay(
            directory,
            date,
            CONFIRM_REASON,
            date,
            confirmAt
        );
        DayRecord record = dayOf(reopenLedger(directory, date, date), date);
        boolean passed = changed
            && STATUS_CONFIRMED_EMPTY.equals(record.status)
            && record.confirmAt == confirmAt
            && CONFIRM_REASON.equals(record.confirmReason)
            && CONFIRM_METHOD.equals(record.confirmMethod)
            && "transport failed".equals(record.detail);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testConfirmRejectsSaved(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("DATA_SAVED confirmation is rejected");
        String date = "2024-06-03";
        DayStagingStore store = openStaging(directory, date, date);
        saveMergeDay(store, date, "00:00:00", "00:01:00");
        byte[] before = Files.readAllBytes(directory.resolve(LEDGER_FILE_NAME));
        String error = confirmationError(
            directory,
            date,
            CONFIRM_REASON,
            date,
            confirmationNow()
        );
        DayRecord record = dayOf(reopenLedger(directory, date, date), date);
        boolean passed = error.indexOf("Invalid ledger transition from DATA_SAVED") >= 0
            && Arrays.equals(before, Files.readAllBytes(directory.resolve(LEDGER_FILE_NAME)))
            && STATUS_DATA_SAVED.equals(record.status)
            && record.confirmAt == -1L
            && stagingHasCsvForDate(directory, date);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testConfirmRejectsMissing(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Missing ledger record is rejected");
        String date = "2024-06-04";
        openStaging(directory, date, date);
        byte[] before = Files.readAllBytes(directory.resolve(LEDGER_FILE_NAME));
        String error = confirmationError(
            directory,
            date,
            CONFIRM_REASON,
            date,
            confirmationNow()
        );
        DayLedger reloaded = reopenLedger(directory, date, date);
        boolean passed = "Missing ledger record".equals(error)
            && Arrays.equals(before, Files.readAllBytes(directory.resolve(LEDGER_FILE_NAME)))
            && reloaded.days.isEmpty();
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testConfirmRejectsOutsideWindow(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Date outside the window is rejected");
        openStaging(directory, "2024-05-01", "2024-05-02");
        byte[] before = Files.readAllBytes(directory.resolve(LEDGER_FILE_NAME));
        String error = confirmationError(
            directory,
            "2024-05-03",
            CONFIRM_REASON,
            "2024-05-03",
            confirmationNow()
        );
        boolean passed = "Date is outside the dataset window".equals(error)
            && Arrays.equals(before, Files.readAllBytes(directory.resolve(LEDGER_FILE_NAME)));
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testConfirmRejectsFuture(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Future date is rejected");
        String date = "2026-11-17";
        prepareUnresolved(directory, date, STATUS_UNKNOWN, "not requested");
        byte[] before = Files.readAllBytes(directory.resolve(LEDGER_FILE_NAME));
        String error = confirmationError(
            directory,
            date,
            CONFIRM_REASON,
            date,
            ledgerAttempt("2026-11-16 12:00:00")
        );
        DayRecord record = dayOf(
            reopenLedger(directory, "2026-11-17", "2026-11-17"),
            date
        );
        boolean passed = "Future date".equals(error)
            && Arrays.equals(before, Files.readAllBytes(directory.resolve(LEDGER_FILE_NAME)))
            && STATUS_UNKNOWN.equals(record.status);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testConfirmRejectsMismatchedDate(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Incorrect confirmation date is rejected");
        String date = "2024-06-05";
        prepareUnresolved(directory, date, STATUS_UNKNOWN, "not requested");
        byte[] before = Files.readAllBytes(directory.resolve(LEDGER_FILE_NAME));
        String error = confirmationError(
            directory,
            date,
            CONFIRM_REASON,
            "2024-06-06",
            confirmationNow()
        );
        boolean invalidDate = false;
        try {
            confirmEmptyDay(
                directory,
                "2024-02-31",
                CONFIRM_REASON,
                "2024-02-31",
                confirmationNow()
            );
        } catch (ParseException ex) {
            invalidDate = true;
        }
        ConfirmCommand parsed = parseConfirmCommand(new String[] {
            "confirm-empty",
            "2026-11-14",
            "--reason",
            CONFIRM_REASON,
            "--confirm",
            "2026-11-14"
        });
        boolean malformed = confirmCommandRejected(new String[] {
                "confirm-empty",
                date
            })
            && confirmCommandRejected(new String[] {
                "confirm-empty",
                date,
                "--confirm",
                date,
                "--reason",
                CONFIRM_REASON
            })
            && confirmCommandRejected(new String[] {
                "confirm-empty",
                date,
                "--reason",
                CONFIRM_REASON,
                "--confirm"
            });
        DayRecord record = dayOf(reopenLedger(directory, date, date), date);
        boolean passed = "Confirmation date does not match".equals(error)
            && invalidDate
            && malformed
            && parsed.date.equals("2026-11-14")
            && parsed.confirmDate.equals("2026-11-14")
            && CONFIRM_REASON.equals(parsed.reason)
            && Arrays.equals(before, Files.readAllBytes(directory.resolve(LEDGER_FILE_NAME)))
            && STATUS_UNKNOWN.equals(record.status);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testConfirmRejectsEmptyReason(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Empty reason is rejected");
        String date = "2024-06-06";
        prepareUnresolved(directory, date, STATUS_UNKNOWN, "not requested");
        byte[] before = Files.readAllBytes(directory.resolve(LEDGER_FILE_NAME));
        String empty = confirmationError(directory, date, "", date, confirmationNow());
        String blank = confirmationError(directory, date, "   ", date, confirmationNow());
        String broken = confirmationError(
            directory,
            date,
            "line one\nline two",
            date,
            confirmationNow()
        );
        DayRecord record = dayOf(reopenLedger(directory, date, date), date);
        boolean passed = "Reason is required".equals(empty)
            && "Reason is required".equals(blank)
            && "Reason is invalid".equals(broken)
            && Arrays.equals(before, Files.readAllBytes(directory.resolve(LEDGER_FILE_NAME)))
            && STATUS_UNKNOWN.equals(record.status);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testConfirmRejectsStagingFile(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Existing staging file is rejected");
        String date = "2024-06-07";
        prepareUnresolved(directory, date, STATUS_UNKNOWN, "not requested");
        Path staging = directory.resolve(STAGING_DIRECTORY_NAME).resolve(date + ".csv");
        byte[] payload = "not-a-valid-day\n".getBytes(StandardCharsets.UTF_8);
        Files.write(staging, payload);
        byte[] before = Files.readAllBytes(directory.resolve(LEDGER_FILE_NAME));
        String error = confirmationError(
            directory,
            date,
            CONFIRM_REASON,
            date,
            confirmationNow()
        );
        DayRecord record = dayOf(reopenLedger(directory, date, date), date);
        boolean passed = "Existing staging file".equals(error)
            && Arrays.equals(before, Files.readAllBytes(directory.resolve(LEDGER_FILE_NAME)))
            && Arrays.equals(payload, Files.readAllBytes(staging))
            && STATUS_UNKNOWN.equals(record.status)
            && record.confirmAt == -1L;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testConfirmRejectsCorruptLedger(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Corrupt ledger is rejected");
        String date = "2024-06-08";
        prepareUnresolved(directory, date, STATUS_UNKNOWN, "not requested");
        Path ledger = directory.resolve(LEDGER_FILE_NAME);
        byte[] corrupt = "this is not a ledger\n".getBytes(StandardCharsets.UTF_8);
        Files.write(ledger, corrupt);
        String error = confirmationError(
            directory,
            date,
            CONFIRM_REASON,
            date,
            confirmationNow()
        );
        boolean passed = error.indexOf("Malformed ledger") >= 0
            && Arrays.equals(corrupt, Files.readAllBytes(ledger));
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testConfirmPersistsAfterRestart(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Confirmation survives a restart");
        String date = "2024-06-09";
        prepareUnresolved(directory, date, STATUS_FAILED, "failed earlier");
        long confirmAt = ledgerAttempt("2024-06-10 15:45:30");
        confirmEmptyDay(directory, date, CONFIRM_REASON, date, confirmAt);
        DayRecord first = dayOf(reopenLedger(directory, date, date), date);
        DayRecord second = dayOf(reopenLedger(directory, date, date), date);
        boolean passed = first.confirmAt == confirmAt
            && second.confirmAt == confirmAt
            && CONFIRM_REASON.equals(first.confirmReason)
            && CONFIRM_REASON.equals(second.confirmReason)
            && CONFIRM_METHOD.equals(first.confirmMethod)
            && CONFIRM_METHOD.equals(second.confirmMethod)
            && STATUS_CONFIRMED_EMPTY.equals(second.status);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testConfirmPreservesAttempts(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Attempt count and last attempt are preserved");
        String date = "2024-06-10";
        DayStagingStore store = openStaging(directory, date, date);
        long firstAttempt = ledgerAttempt(date + " 08:00:00");
        long secondAttempt = ledgerAttempt(date + " 09:30:00");
        long confirmAt = ledgerAttempt("2024-06-11 11:11:11");
        store.noteDay(parseUtcDate(date), STATUS_UNKNOWN, firstAttempt, "first");
        store.noteDay(parseUtcDate(date), STATUS_FAILED, secondAttempt, "second");
        DayRecord before = dayOf(store.ledger, date);
        boolean changed = confirmEmptyDay(
            directory,
            date,
            CONFIRM_REASON,
            date,
            confirmAt
        );
        DayRecord after = dayOf(reopenLedger(directory, date, date), date);
        boolean passed = changed
            && before.attempts == 2
            && before.lastAttemptMillis == secondAttempt
            && after.attempts == before.attempts
            && after.lastAttemptMillis == before.lastAttemptMillis
            && after.confirmAt == confirmAt
            && after.confirmAt != after.lastAttemptMillis
            && "second".equals(after.detail)
            && CONFIRM_REASON.equals(after.confirmReason)
            && STATUS_CONFIRMED_EMPTY.equals(after.status);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testConfirmSkipsRetry(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Confirmed date is skipped by retry selection");
        String date = "2024-06-11";
        prepareUnresolved(directory, date, STATUS_UNKNOWN, "not requested");
        confirmEmptyDay(
            directory,
            date,
            CONFIRM_REASON,
            date,
            confirmationNow()
        );
        DayStagingStore fresh = DayStagingStore.open(
            directory,
            parseUtcDate(date),
            ledgerWindowEnd(date)
        );
        final int[] calls = new int[1];
        List<Long> selected = runRetryDays(
            fresh,
            new DayChunkSource() {
                public DayChunkResult request(long start, long end) {
                    calls[0]++;
                    throw new IllegalStateException("confirmed day was requested");
                }
            },
            new RecordingDelay(),
            retryClock(date + " 09:00:00"),
            5,
            ledgerWindowEnd(date)
        );
        boolean passed = selected.isEmpty()
            && calls[0] == 0
            && STATUS_CONFIRMED_EMPTY.equals(statusOf(fresh.ledger, date))
            && !stagingHasCsvForDate(directory, date);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testConfirmSkipsMerge(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Confirmed date is skipped by final merge");
        DayStagingStore store = openStaging(directory, "2025-04-01", "2025-04-03");
        saveMergeDay(store, "2025-04-01", "00:00:00", "00:01:00");
        store.noteDay(
            parseUtcDate("2025-04-02"),
            STATUS_UNKNOWN,
            ledgerAttempt("2025-04-02 08:00:00"),
            "not requested"
        );
        saveMergeDay(store, "2025-04-03", "00:00:00", "00:01:00");
        confirmEmptyDay(
            directory,
            "2025-04-02",
            CONFIRM_REASON,
            "2025-04-02",
            confirmationNow()
        );
        MergeReport report = mergeFinal(store);
        String csv = new String(
            Files.readAllBytes(report.csvPath),
            StandardCharsets.UTF_8
        );
        boolean passed = report.savedIncluded == 2
            && report.confirmedEmptySkipped == 1
            && report.unknownCount == 0
            && report.failedCount == 0
            && report.datasetComplete
            && report.mergedCandles == 4
            && report.firstCandle == parseUtcMinute("2025-04-01 00:00:00")
            && report.lastCandle == parseUtcMinute("2025-04-03 00:01:00")
            && csv.indexOf("2025-04-01 00:00:00") >= 0
            && csv.indexOf("2025-04-03 00:01:00") >= 0
            && csv.indexOf("2025-04-02") < 0
            && !stagingHasCsvForDate(directory, "2025-04-02")
            && stagingHasCsvForDate(directory, "2025-04-01")
            && stagingHasCsvForDate(directory, "2025-04-03");
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testConfirmCompleteness(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Dataset completeness includes confirmed-empty days");
        DayStagingStore store = openStaging(directory, "2025-03-01", "2025-03-02");
        saveMergeDay(store, "2025-03-01", "00:00:00", "00:01:00");
        store.noteDay(
            parseUtcDate("2025-03-02"),
            STATUS_UNKNOWN,
            ledgerAttempt("2025-03-02 08:00:00"),
            "not requested"
        );
        MergeReport before = mergeFinal(store);
        confirmEmptyDay(
            directory,
            "2025-03-02",
            CONFIRM_REASON,
            "2025-03-02",
            confirmationNow()
        );
        DayLedger reloaded = reopenLedger(directory, "2025-03-01", "2025-03-02");
        MergeReport after = mergeFinal(store);
        boolean passed = !before.datasetComplete
            && before.unknownCount == 1
            && before.savedIncluded == 1
            && reloaded.assess().complete
            && reloaded.assess().unresolvedCount == 0
            && after.datasetComplete
            && after.confirmedEmptySkipped == 1
            && after.unknownCount == 0
            && after.savedIncluded == 1
            && after.failedCount == 0;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testConfirmRepeatIsIdempotent(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Repeated confirmation does not create duplicate history");
        String date = "2024-06-12";
        prepareUnresolved(directory, date, STATUS_FAILED, "failed earlier");
        long confirmAt = ledgerAttempt("2024-06-13 16:00:00");
        boolean first = confirmEmptyDay(
            directory,
            date,
            CONFIRM_REASON,
            date,
            confirmAt
        );
        byte[] afterFirst = Files.readAllBytes(directory.resolve(LEDGER_FILE_NAME));
        boolean second = confirmEmptyDay(
            directory,
            date,
            "A later note that must not replace the original confirmation",
            date,
            ledgerAttempt("2024-06-14 16:00:00")
        );
        byte[] afterSecond = Files.readAllBytes(directory.resolve(LEDGER_FILE_NAME));
        DayRecord record = dayOf(reopenLedger(directory, date, date), date);
        boolean passed = first
            && !second
            && Arrays.equals(afterFirst, afterSecond)
            && record.confirmAt == confirmAt
            && CONFIRM_REASON.equals(record.confirmReason)
            && record.attempts == 1
            && countLedgerField(afterSecond, "status") == 1
            && countLedgerField(afterSecond, "confirmAt") == 1
            && countLedgerField(afterSecond, "confirmReason") == 1
            && countLedgerField(afterSecond, "confirmMethod") == 1;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testConfirmFailureIsUnchanged(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Failed confirmation leaves the ledger unchanged");
        String date = "2024-06-13";
        prepareUnresolved(directory, date, STATUS_FAILED, "transport failed");
        Path staging = directory.resolve(STAGING_DIRECTORY_NAME).resolve(date + ".csv");
        Files.write(staging, "blocked\n".getBytes(StandardCharsets.UTF_8));
        byte[] before = Files.readAllBytes(directory.resolve(LEDGER_FILE_NAME));
        String error = confirmationError(
            directory,
            date,
            CONFIRM_REASON,
            date,
            confirmationNow()
        );
        byte[] after = Files.readAllBytes(directory.resolve(LEDGER_FILE_NAME));
        DayRecord record = dayOf(reopenLedger(directory, date, date), date);
        boolean passed = "Existing staging file".equals(error)
            && Arrays.equals(before, after)
            && STATUS_FAILED.equals(record.status)
            && record.attempts == 1
            && record.lastAttemptMillis == ledgerAttempt(date + " 08:00:00")
            && record.confirmAt == -1L
            && record.confirmReason.length() == 0
            && Files.exists(staging)
            && !Files.exists(directory.resolve(LEDGER_FILE_NAME + ".tmp"));
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private interface CompletedCandleCapture {
        long latestCompleted() throws Exception;
    }

    private interface SessionProbe {
        boolean usable();
    }

    private static final SessionProbe USABLE_SESSION = new SessionProbe() {
        public boolean usable() {
            return true;
        }
    };

    private static final class IncrementClock implements AttemptClock {
        private long next;

        private IncrementClock(long start) {
            this.next = start;
        }

        public long now() {
            long value = next;
            next += 1000L;
            return value;
        }
    }

    private static final class ProductionSchedule {
        private final Path path;
        private final long windowStart;
        private final long windowEnd;
        private long nextDay;

        private ProductionSchedule(
                Path path,
                long windowStart,
                long windowEnd,
                long nextDay) {
            this.path = path;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.nextDay = nextDay;
        }

        private static ProductionSchedule create(
                Path directory,
                long windowStart,
                long windowEnd,
                long nextDay) throws IOException {

            ProductionSchedule schedule = new ProductionSchedule(
                directory.resolve(SCHEDULER_FILE_NAME),
                windowStart,
                windowEnd,
                nextDay
            );
            schedule.persist();
            return schedule;
        }

        private static ProductionSchedule load(
                Path directory,
                long windowStart,
                long windowEnd) throws IOException {

            Path path = directory.resolve(SCHEDULER_FILE_NAME);
            if (!Files.isRegularFile(path) || Files.size(path) == 0) {
                throw new IOException("Production scheduler was not found");
            }
            long fileStart = Long.MIN_VALUE;
            long fileEnd = Long.MIN_VALUE;
            long nextDay = Long.MIN_VALUE;
            long schema = Long.MIN_VALUE;
            boolean sawStart = false;
            boolean sawEnd = false;
            boolean sawNext = false;
            boolean sawSchema = false;
            try (BufferedReader reader = Files.newBufferedReader(
                    path,
                    StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int separator = line.indexOf('=');
                    if (separator <= 0) {
                        throw new IOException("Malformed production scheduler");
                    }
                    String key = line.substring(0, separator);
                    String value = line.substring(separator + 1);
                    if ("schemaVersion".equals(key)) {
                        if (sawSchema) {
                            throw new IOException("Malformed production scheduler");
                        }
                        schema = parseCanonicalLong(value);
                        sawSchema = true;
                    } else if ("windowStart".equals(key)) {
                        if (sawStart) {
                            throw new IOException("Malformed production scheduler");
                        }
                        fileStart = parseCanonicalLong(value);
                        sawStart = true;
                    } else if ("windowEnd".equals(key)) {
                        if (sawEnd) {
                            throw new IOException("Malformed production scheduler");
                        }
                        fileEnd = parseCanonicalLong(value);
                        sawEnd = true;
                    } else if ("nextDay".equals(key)) {
                        if (sawNext) {
                            throw new IOException("Malformed production scheduler");
                        }
                        nextDay = parseCanonicalLong(value);
                        sawNext = true;
                    } else {
                        throw new IOException("Malformed production scheduler");
                    }
                }
            } catch (NumberFormatException ex) {
                throw new IOException("Malformed production scheduler");
            }
            if (!sawSchema || schema != SCHEDULER_SCHEMA_VERSION) {
                throw new IOException("Unsupported production scheduler");
            }
            if (!sawStart || !sawEnd || !sawNext
                    || fileStart != windowStart
                    || fileEnd != windowEnd) {
                throw new IOException("Scheduler window does not match");
            }
            if (plannedDayIndex(windowStart, windowEnd, nextDay) < 0) {
                throw new IOException("Scheduler cursor is outside the production window");
            }
            return new ProductionSchedule(path, windowStart, windowEnd, nextDay);
        }

        private void advancePast(long processedDay) throws IOException {
            List<UtcInterval> intervals = planDailyIntervals(windowStart, windowEnd);
            int index = -1;
            for (int i = 0; i < intervals.size(); i++) {
                if (startOfUtcDay(intervals.get(i).startMillis) == processedDay) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                throw new IOException("Scheduler cursor is outside the production window");
            }
            int next = (index + 1) % intervals.size();
            nextDay = startOfUtcDay(intervals.get(next).startMillis);
            persist();
        }

        private void persist() throws IOException {
            String text = "schemaVersion=" + SCHEDULER_SCHEMA_VERSION + "\n"
                + "windowStart=" + windowStart + "\n"
                + "windowEnd=" + windowEnd + "\n"
                + "nextDay=" + nextDay + "\n";
            atomicWrite(path, text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class ProductionProgress {
        private final int plannedDays;
        private final int savedDays;
        private final int confirmedEmptyDays;
        private final int unknownDays;
        private final int failedDays;
        private final int unresolvedDays;
        private final long stagedRows;
        private final long nextDay;
        private final boolean complete;

        private ProductionProgress(
                int plannedDays,
                int savedDays,
                int confirmedEmptyDays,
                int unknownDays,
                int failedDays,
                int unresolvedDays,
                long stagedRows,
                long nextDay,
                boolean complete) {
            this.plannedDays = plannedDays;
            this.savedDays = savedDays;
            this.confirmedEmptyDays = confirmedEmptyDays;
            this.unknownDays = unknownDays;
            this.failedDays = failedDays;
            this.unresolvedDays = unresolvedDays;
            this.stagedRows = stagedRows;
            this.nextDay = nextDay;
            this.complete = complete;
        }
    }

    private static final class ProductionDataset {
        private final Path directory;
        private final DayLedger ledger;
        private final ProductionSchedule schedule;
        private final DayStagingStore store;

        private ProductionDataset(
                Path directory,
                DayLedger ledger,
                ProductionSchedule schedule,
                DayStagingStore store) {
            this.directory = directory;
            this.ledger = ledger;
            this.schedule = schedule;
            this.store = store;
        }
    }

    private static long productionBatchWaitMillis(int maxDays) {
        long retryDelays = DAY_RETRY_DELAYS_MS[0] + DAY_RETRY_DELAYS_MS[1];
        return (long) maxDays * (
            (long) DAY_ATTEMPTS * PRODUCTION_ATTEMPT_BUDGET_MS + retryDelays
        ) + 60000L;
    }

    private static long captureLatestCompleted(IHistory history) throws Exception {
        System.out.println("Checking latest available EUR/USD tick");
        ITick lastTick = history.getLastTick(INSTRUMENT);
        if (lastTick == null) {
            throw new IOException("No latest tick received.");
        }
        System.out.println(
            "Latest tick time: " + UTC_FORMAT.format(new Date(lastTick.getTime()))
        );
        long currentBarStart = history.getBarStart(PERIOD, lastTick.getTime());
        long latestCompleted = history.getPreviousBarStart(PERIOD, currentBarStart);
        requireMinuteBoundary(latestCompleted, "Latest completed candle");
        System.out.println(
            "Latest completed candle: "
            + UTC_FORMAT.format(new Date(latestCompleted))
        );
        return latestCompleted;
    }

    private static List<Long> plannedDayStarts(long windowStart, long windowEnd) {
        List<UtcInterval> intervals = planDailyIntervals(windowStart, windowEnd);
        List<Long> days = new ArrayList<Long>();
        for (int i = 0; i < intervals.size(); i++) {
            days.add(Long.valueOf(startOfUtcDay(intervals.get(i).startMillis)));
        }
        return days;
    }

    private static int plannedDayIndex(
            long windowStart,
            long windowEnd,
            long dayStart) {

        List<Long> days = plannedDayStarts(windowStart, windowEnd);
        for (int i = 0; i < days.size(); i++) {
            if (days.get(i).longValue() == dayStart) {
                return i;
            }
        }
        return -1;
    }

    private static boolean directoryHasEntries(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            return children.iterator().hasNext();
        }
    }

    private static void assertProductionEntries(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IOException("Production directory was not found");
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                if (name.endsWith(".tmp")) {
                    throw new IOException("Interrupted production update");
                }
                boolean allowed = LEDGER_FILE_NAME.equals(name)
                    || STAGING_DIRECTORY_NAME.equals(name)
                    || SCHEDULER_FILE_NAME.equals(name)
                    || FINAL_CSV_NAME.equals(name);
                if (!allowed) {
                    throw new IOException("Unexpected production file");
                }
                if (STAGING_DIRECTORY_NAME.equals(name)) {
                    if (!Files.isDirectory(child)) {
                        throw new IOException("Inconsistent production directory");
                    }
                } else if (!Files.isRegularFile(child)) {
                    throw new IOException("Inconsistent production directory");
                }
            }
        }
    }

    static boolean acceptsExplicitDateRecovery(String[] args) {
        return args != null
            && args.length == 3
            && EXPLICIT_RECOVERY_COMMAND.equals(args[0])
            && EXPLICIT_RECOVERY_FIRST.equals(args[1])
            && EXPLICIT_RECOVERY_SECOND.equals(args[2]);
    }

    static int productionAttemptLimit() {
        return DAY_ATTEMPTS;
    }

    static void prepareExplicitDateRecovery(Path directory) throws IOException {
        if (directory == null || !Files.isDirectory(directory)) {
            throw new IOException("Production directory was not found");
        }
        if (!Files.isRegularFile(directory.resolve(LEDGER_FILE_NAME))) {
            throw new IOException("Ledger was not found");
        }
        if (!Files.isRegularFile(directory.resolve(SCHEDULER_FILE_NAME))) {
            throw new IOException("Production scheduler was not found");
        }
        if (!Files.isDirectory(directory.resolve(STAGING_DIRECTORY_NAME))) {
            throw new IOException("Production staging directory is missing");
        }
        DayLedger ledger = DayLedger.openExisting(directory);
        if (ledger.windowStart != FIXED_PRODUCTION_WINDOW_START
                || ledger.windowEnd != FIXED_PRODUCTION_WINDOW_END) {
            throw new IOException("Production window does not match");
        }
        ProductionSchedule.load(
            directory,
            ledger.windowStart,
            ledger.windowEnd
        );
        assertExplicitRecoveryDay(
            directory,
            ledger,
            explicitRecoveryDay(EXPLICIT_RECOVERY_FIRST),
            EXPLICIT_RECOVERY_FIRST
        );
        assertExplicitRecoveryDay(
            directory,
            ledger,
            explicitRecoveryDay(EXPLICIT_RECOVERY_SECOND),
            EXPLICIT_RECOVERY_SECOND
        );
    }

    private static void assertExplicitRecoveryDay(
            Path directory,
            DayLedger ledger,
            long day,
            String label) throws IOException {

        if (!ledger.covers(day)) {
            throw new IOException("Date is outside the production window: " + label);
        }
        DayRecord record = ledger.days.get(Long.valueOf(day));
        if (record == null) {
            throw new IOException("Missing ledger record: " + label);
        }
        if (!STATUS_UNKNOWN.equals(record.status)) {
            throw new IOException("Explicit recovery requires UNKNOWN: " + label);
        }
        Path csv = directory.resolve(STAGING_DIRECTORY_NAME).resolve(label + ".csv");
        if (Files.exists(csv)) {
            throw new IOException("Orphan staging file: " + label);
        }
    }

    private static long explicitRecoveryDay(String label) throws IOException {
        try {
            return parseUtcDate(label);
        } catch (ParseException ex) {
            throw new IOException("Invalid explicit recovery date: " + label);
        }
    }

    private static void runLiveExplicitDateRecovery(
            IHistory history,
            AtomicInteger exitCode,
            final AtomicBoolean historyRequestActive) {

        try {
            final IHistory activeHistory = history;
            final Path cache = explicitRecoveryCache;
            runExplicitDateRecovery(
                PRODUCTION_DIRECTORY,
                cache,
                new DayChunkSource() {
                    public DayChunkResult request(long start, long end) {
                        historyRequestActive.set(true);
                        try {
                            return new HistoryDaySource(activeHistory).request(start, end);
                        } finally {
                            historyRequestActive.set(false);
                        }
                    }
                },
                new RecoveryGate() {
                    public void beforeRequest(String date) throws IOException {
                        ExplicitRecoveryCache.revalidate(cache);
                    }
                },
                new AttemptClock() {
                    public long now() {
                        return System.currentTimeMillis();
                    }
                },
                explicitRecoveryTransport
            );
            exitCode.set(0);
        } catch (Exception ex) {
            System.out.println("FAIL: Explicit date recovery error.");
            System.out.println("Reason: " + safeFailureReason(ex));
        }
    }

    interface RecoveryGate {
        void beforeRequest(String date) throws IOException;
    }

    interface RecoveryHttpProbe {
        void duringRequest(String date) throws IOException;
    }

    static boolean recoveryMayConnect(
            RecoveryTransportLog transport,
            boolean explicitRecovery) {
        if (!explicitRecovery) {
            return true;
        }
        return transport != null;
    }

    private static String recoveryStamp() {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(
            "yyyyMMdd'T'HHmmss'Z'"
        );
        format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return format.format(new java.util.Date());
    }

    /**
     * A failed request writes the allowlisted recovery report and, when a
     * transport log is present, a separate sanitized HTTP report.
     * The staging CSV is created before the ledger record. A crash between
     * those two writes leaves an orphan CSV, and the next run stops on it.
     * A temporary staging file left from a crash also stops the next run.
     * Neither file is deleted or adopted. HTTP observation does not decide
     * whether a day is saved.
     */
    private static void runExplicitDateRecovery(
            Path directory,
            Path cacheRoot,
            DayChunkSource source,
            RecoveryGate gate,
            AttemptClock clock,
            RecoveryTransportLog transport) throws IOException, InterruptedException {

        try {
            prepareExplicitDateRecovery(directory);
            Path scheduler = directory.resolve(SCHEDULER_FILE_NAME);
            Path ledgerPath = directory.resolve(LEDGER_FILE_NAME);
            byte[] schedulerBefore = Files.readAllBytes(scheduler);
            ProductionDataset dataset = openProduction(directory);
            String[] dates = new String[] {
                EXPLICIT_RECOVERY_FIRST,
                EXPLICIT_RECOVERY_SECOND
            };
            boolean previousSaved = true;
            for (int i = 0; i < dates.length; i++) {
                if (!previousSaved) {
                    System.out.println(
                        "Explicit recovery stopped before " + dates[i] + "."
                    );
                    break;
                }
                String label = dates[i];
                long day = explicitRecoveryDay(label);
                byte[] ledgerBefore = Files.readAllBytes(ledgerPath);
                DayChunkResult result;
                boolean observing = false;
                try {
                    gate.beforeRequest(label);
                    observing = openRecoveryTransport(transport, label);
                    result = source.request(day, endOfUtcMinuteDay(day));
                } catch (Exception ex) {
                    closeRecoveryTransport(transport, label, observing);
                    recordRecoveryAttempt(
                        cacheRoot,
                        label,
                        safeFailureReason(ex),
                        0,
                        Arrays.equals(ledgerBefore, Files.readAllBytes(ledgerPath))
                    );
                    System.out.println(
                        "Explicit date " + label + " not saved: "
                        + safeFailureReason(ex)
                    );
                    previousSaved = false;
                    continue;
                }
                closeRecoveryTransport(transport, label, observing);
                int candles = result.candles == null ? 0 : result.candles.size();
                String rejection = recoveryRejection(label, result);
                if (rejection != null) {
                    recordRecoveryAttempt(
                        cacheRoot,
                        label,
                        rejection,
                        candles,
                        Arrays.equals(ledgerBefore, Files.readAllBytes(ledgerPath))
                    );
                    System.out.println(
                        "Explicit date " + label + " not saved: " + rejection
                    );
                    previousSaved = false;
                    continue;
                }
                dataset.store.saveDay(day, result.candles, clock.now(), "saved");
                DayRecord saved = dataset.store.ledger.days.get(Long.valueOf(day));
                System.out.println(
                    "Explicit date " + label
                    + " status " + saved.status
                    + " attempt " + saved.attempts
                    + " candles " + saved.rowCount
                );
                previousSaved = STATUS_DATA_SAVED.equals(saved.status);
            }
            byte[] schedulerAfter = Files.readAllBytes(scheduler);
            if (!Arrays.equals(schedulerBefore, schedulerAfter)) {
                throw new IOException("Scheduler was modified");
            }
        } finally {
            if (transport != null) {
                try {
                    transport.cleanup();
                    transport.write(cacheRoot);
                } catch (Exception ex) {
                    System.out.println("FAIL: HTTP transport report failed.");
                }
            }
        }
    }

    private static boolean openRecoveryTransport(
            RecoveryTransportLog transport,
            String label) {
        if (transport == null) {
            return false;
        }
        try {
            transport.beginDate(label);
            return true;
        } catch (RuntimeException ex) {
            transport.cleanup();
            System.out.println("FAIL: HTTP observer failed.");
            return false;
        }
    }

    private static void closeRecoveryTransport(
            RecoveryTransportLog transport,
            String label,
            boolean observing) {
        if (transport == null || !observing) {
            return;
        }
        try {
            transport.endDate(label);
        } catch (RuntimeException ex) {
            transport.cleanup();
            System.out.println("FAIL: HTTP observer failed.");
        }
    }

    private static String recoveryRejection(String label, DayChunkResult result)
            throws IOException {
        if (result == null || result.status != DAY_DATA) {
            return result != null && result.status == DAY_FAILED
                ? "Request failed"
                : "Empty response";
        }
        return fullDayRejection(label, result.candles);
    }

    static String fullDayRejection(String date, List<CandleRecord> candles)
            throws IOException {
        long start = explicitRecoveryDay(date);
        long end = endOfUtcMinuteDay(start);
        if (candles == null || candles.isEmpty()) {
            return "Empty response";
        }
        if (candles.size() != 1440) {
            return "Partial response";
        }
        long previous = Long.MIN_VALUE;
        for (int i = 0; i < candles.size(); i++) {
            CandleRecord candle = candles.get(i);
            if (candle == null) {
                return "Partial response";
            }
            long expected = start + (long) i * 60000L;
            if (previous != Long.MIN_VALUE && candle.timeMillis == previous) {
                return "Duplicate timestamp";
            }
            if (previous != Long.MIN_VALUE && candle.timeMillis < previous) {
                return "Backward timestamp";
            }
            if (candle.timeMillis != expected) {
                return "Missing M1 minute";
            }
            try {
                validateCandle(candle);
            } catch (IOException ex) {
                String message = ex.getMessage() == null ? "" : ex.getMessage();
                if (message.startsWith("Invalid volume")) {
                    return "Invalid volume";
                }
                return "Invalid OHLC";
            }
            previous = candle.timeMillis;
        }
        if (candles.get(0).timeMillis != start
                || candles.get(candles.size() - 1).timeMillis != end) {
            return "Partial response";
        }
        return null;
    }

    private static String safeFailureReason(Exception ex) {
        String message = ex == null ? null : ex.getMessage();
        if ("Cache isolation failed".equals(message)
                || "Empty response".equals(message)
                || "Partial response".equals(message)
                || "Duplicate timestamp".equals(message)
                || "Missing M1 minute".equals(message)
                || "Backward timestamp".equals(message)
                || "Invalid OHLC".equals(message)
                || "Invalid volume".equals(message)
                || "Request failed".equals(message)) {
            return message;
        }
        return "Request failed";
    }

    private static void recordRecoveryAttempt(
            Path cacheRoot,
            String date,
            String reason,
            int candles,
            boolean ledgerUnchanged) throws IOException {
        String block = "TRADEXA_RECOVERY_ATTEMPT_V1\n"
            + "date=" + date + "\n"
            + "reason=" + reason + "\n"
            + "candles=" + candles + "\n"
            + "ledger_unchanged=" + ledgerUnchanged + "\n";
        Path report = cacheRoot.resolve("recovery-attempts.txt");
        byte[] existing = Files.isRegularFile(report)
            ? Files.readAllBytes(report)
            : new byte[0];
        byte[] addition = block.getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[existing.length + addition.length];
        System.arraycopy(existing, 0, combined, 0, existing.length);
        System.arraycopy(addition, 0, combined, existing.length, addition.length);
        Files.write(report, combined);
    }

    static final class ExplicitRecoveryExercise {
        final int requests;
        final int delays;
        final String firstStatus;
        final int firstAttempts;
        final long firstRows;
        final String secondStatus;
        final int secondAttempts;
        final long secondRows;
        final String otherStatus;
        final int otherAttempts;
        final boolean schedulerUnchanged;
        final boolean ledgerUnchanged;
        final boolean firstStaging;
        final boolean secondStaging;
        final boolean otherStaging;
        final String report;
        final String transportReport;

        private ExplicitRecoveryExercise(
                int requests,
                int delays,
                String firstStatus,
                int firstAttempts,
                long firstRows,
                String secondStatus,
                int secondAttempts,
                long secondRows,
                String otherStatus,
                int otherAttempts,
                boolean schedulerUnchanged,
                boolean ledgerUnchanged,
                boolean firstStaging,
                boolean secondStaging,
                boolean otherStaging,
                String report,
                String transportReport) {
            this.requests = requests;
            this.delays = delays;
            this.firstStatus = firstStatus;
            this.firstAttempts = firstAttempts;
            this.firstRows = firstRows;
            this.secondStatus = secondStatus;
            this.secondAttempts = secondAttempts;
            this.secondRows = secondRows;
            this.otherStatus = otherStatus;
            this.otherAttempts = otherAttempts;
            this.schedulerUnchanged = schedulerUnchanged;
            this.ledgerUnchanged = ledgerUnchanged;
            this.firstStaging = firstStaging;
            this.secondStaging = secondStaging;
            this.otherStaging = otherStaging;
            this.report = report;
            this.transportReport = transportReport;
        }
    }

    static ExplicitRecoveryExercise exerciseExplicitRecovery(
            Path directory,
            String firstOutcome,
            String secondOutcome) throws Exception {
        return exerciseExplicitRecovery(
            directory,
            firstOutcome,
            secondOutcome,
            new RecoveryGate() {
                public void beforeRequest(String date) {
                }
            },
            null,
            null
        );
    }

    static ExplicitRecoveryExercise exerciseExplicitRecovery(
            Path directory,
            String firstOutcome,
            String secondOutcome,
            RecoveryGate gate) throws Exception {
        return exerciseExplicitRecovery(
            directory,
            firstOutcome,
            secondOutcome,
            gate,
            null,
            null
        );
    }

    static ExplicitRecoveryExercise exerciseExplicitRecovery(
            Path directory,
            final String firstOutcome,
            final String secondOutcome,
            RecoveryGate gate,
            final RecoveryTransportLog transport,
            final RecoveryHttpProbe probe) throws Exception {

        final int[] requests = new int[] {0};
        byte[] ledgerBefore = Files.readAllBytes(
            directory.resolve(LEDGER_FILE_NAME)
        );
        byte[] schedulerBefore = Files.readAllBytes(
            directory.resolve(SCHEDULER_FILE_NAME)
        );
        Path cacheParent = Files.createTempDirectory(
            directory.getParent(),
            "recovery-cache-parent"
        );
        final Path cache = ExplicitRecoveryCache.create(
            cacheParent,
            "20261119T041000Z"
        );
        runExplicitDateRecovery(
            directory,
            cache,
            new DayChunkSource() {
                private int index;

                public DayChunkResult request(long start, long end) {
                    String labelForProbe = utcDate(start);
                    if (probe != null) {
                        try {
                            probe.duringRequest(labelForProbe);
                        } catch (Exception ex) {
                            System.out.println("FAIL: HTTP observer failed.");
                        }
                    }
                    requests[0]++;
                    String outcome = index == 0 ? firstOutcome : secondOutcome;
                    index++;
                    String label = utcDate(start);
                    if ("failed".equals(outcome)) {
                        return new DayChunkResult(
                            DAY_FAILED,
                            null,
                            "simulated failure",
                            true
                        );
                    }
                    if ("confirmed".equals(outcome) || "empty".equals(outcome)) {
                        return new DayChunkResult(
                            outcome.equals("confirmed")
                                ? DAY_CONFIRMED_EMPTY
                                : DAY_UNKNOWN,
                            new ArrayList<CandleRecord>(),
                            "empty",
                            true
                        );
                    }
                    try {
                        return new DayChunkResult(
                            DAY_DATA,
                            recoveryCandles(label, outcome),
                            "saved",
                            false
                        );
                    } catch (IOException ex) {
                        throw new IllegalStateException("Request failed");
                    }
                }
            },
            gate,
            new AttemptClock() {
                public long now() {
                    return 1795006000000L;
                }
            },
            transport
        );
        DayLedger ledger = DayLedger.openExisting(directory);
        DayRecord first = ledger.days.get(Long.valueOf(
            explicitRecoveryDay(EXPLICIT_RECOVERY_FIRST)
        ));
        DayRecord second = ledger.days.get(Long.valueOf(
            explicitRecoveryDay(EXPLICIT_RECOVERY_SECOND)
        ));
        DayRecord other = ledger.days.get(Long.valueOf(
            explicitRecoveryDay("2023-11-19")
        ));
        Path staging = directory.resolve(STAGING_DIRECTORY_NAME);
        Path report = cache.resolve("recovery-attempts.txt");
        String reportText = Files.isRegularFile(report)
            ? new String(Files.readAllBytes(report), StandardCharsets.UTF_8)
            : "";
        return new ExplicitRecoveryExercise(
            requests[0],
            0,
            first.status,
            first.attempts,
            first.rowCount,
            second.status,
            second.attempts,
            second.rowCount,
            other == null ? "" : other.status,
            other == null ? -1 : other.attempts,
            Arrays.equals(
                schedulerBefore,
                Files.readAllBytes(directory.resolve(SCHEDULER_FILE_NAME))
            ),
            Arrays.equals(ledgerBefore, Files.readAllBytes(
                directory.resolve(LEDGER_FILE_NAME)
            )),
            Files.isRegularFile(staging.resolve(EXPLICIT_RECOVERY_FIRST + ".csv")),
            Files.isRegularFile(staging.resolve(EXPLICIT_RECOVERY_SECOND + ".csv")),
            Files.isRegularFile(staging.resolve("2023-11-19.csv")),
            reportText,
            transportReportText(cache)
        );
    }

    private static String transportReportText(Path cache) throws IOException {
        Path report = cache.resolve(RecoveryTransportLog.REPORT_NAME);
        if (!Files.isRegularFile(report)) {
            return "";
        }
        return new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
    }

    private static List<CandleRecord> recoveryCandles(String date, String defect)
            throws IOException {
        long start = explicitRecoveryDay(date);
        int count = "partial".equals(defect) ? 10 : 1440;
        List<CandleRecord> candles = new ArrayList<CandleRecord>();
        for (int i = 0; i < count; i++) {
            long time = start + (long) i * 60000L;
            double high = 1.20;
            double low = 1.00;
            double volume = 1.0;
            if ("duplicate".equals(defect) && i == 4) {
                time = start + 3L * 60000L;
            }
            if ("missing".equals(defect) && i >= 4) {
                time = start + (long) (i + 1) * 60000L;
            }
            if ("ohlc".equals(defect) && i == 3) {
                high = 0.50;
                low = 1.10;
            }
            if ("volume".equals(defect) && i == 3) {
                volume = -1.0;
            }
            candles.add(new CandleRecord(time, 1.10, high, low, 1.15, volume));
        }
        return candles;
    }

    static int defaultEmptyAttemptCount(Path directory) throws Exception {
        long start = parseUtcMinute("2024-12-01 00:00:00");
        long end = parseUtcMinute("2024-12-01 23:59:00");
        initializeProductionWindow(directory, start, end);
        final int[] requests = new int[] {0};
        ProductionDataset dataset = openProduction(directory);
        processSelectedDay(
            dataset.store,
            new DayChunkSource() {
                public DayChunkResult request(long chunkStart, long chunkEnd) {
                    requests[0]++;
                    return new DayChunkResult(
                        DAY_UNKNOWN,
                        new ArrayList<CandleRecord>(),
                        "empty",
                        true
                    );
                }
            },
            new RecordingDelay(),
            new AttemptClock() {
                public long now() {
                    return 1795006000000L;
                }
            },
            start,
            end
        );
        return requests[0];
    }

    private static void initializeProductionWindow(
            Path directory,
            long windowStart,
            long windowEnd) throws IOException {

        Path ledgerPath = directory.resolve(LEDGER_FILE_NAME);
        if (Files.isRegularFile(ledgerPath)) {
            throw new IOException("Production ledger already exists");
        }
        if (directoryHasEntries(directory)) {
            throw new IOException("Inconsistent production directory");
        }
        List<Long> days = plannedDayStarts(windowStart, windowEnd);
        if (days.isEmpty()) {
            throw new IOException("Production window has no planned days");
        }
        Files.createDirectories(directory);
        DayLedger ledger = DayLedger.open(directory, windowStart, windowEnd);
        ledger.initializeUnknownDays(days);
        Files.createDirectories(directory.resolve(STAGING_DIRECTORY_NAME));
        ProductionSchedule.create(
            directory,
            windowStart,
            windowEnd,
            days.get(0).longValue()
        );
        System.out.println(
            "Production window start: " + formatUtcMinute(windowStart)
        );
        System.out.println(
            "Production window end: " + formatUtcMinute(windowEnd)
        );
        System.out.println("Planned calendar days: " + days.size());
    }

    private static ProductionDataset openProduction(Path directory) throws IOException {
        assertProductionEntries(directory);
        if (!Files.isRegularFile(directory.resolve(LEDGER_FILE_NAME))) {
            throw new IOException("Ledger was not found");
        }
        if (!Files.isDirectory(directory.resolve(STAGING_DIRECTORY_NAME))) {
            throw new IOException("Production staging directory is missing");
        }
        DayLedger ledger = DayLedger.openExisting(directory);
        DayStagingStore store = new DayStagingStore(
            directory,
            directory.resolve(STAGING_DIRECTORY_NAME),
            ledger
        );
        store.verify();
        ProductionSchedule schedule = ProductionSchedule.load(
            directory,
            ledger.windowStart,
            ledger.windowEnd
        );
        return new ProductionDataset(directory, ledger, schedule, store);
    }

    private static ProductionDataset resumeOrInitialize(
            Path directory,
            CompletedCandleCapture capture) throws Exception {

        if (Files.isRegularFile(directory.resolve(LEDGER_FILE_NAME))) {
            return openProduction(directory);
        }
        if (directoryHasEntries(directory)) {
            throw new IOException("Inconsistent production directory");
        }
        long windowEnd = capture.latestCompleted();
        initializeProductionWindow(
            directory,
            threeYearStartMillis(windowEnd),
            windowEnd
        );
        return openProduction(directory);
    }

    private static List<Long> selectFairDays(
            ProductionDataset dataset,
            int maxDays,
            long latestCompleted) throws IOException {

        if (maxDays < 1) {
            throw new IOException("max-days must be a positive integer");
        }
        requireMinuteBoundary(latestCompleted, "Latest completed candle");
        List<UtcInterval> intervals = planDailyIntervals(
            dataset.ledger.windowStart,
            dataset.ledger.windowEnd
        );
        int start = plannedDayIndex(
            dataset.ledger.windowStart,
            dataset.ledger.windowEnd,
            dataset.schedule.nextDay
        );
        if (start < 0) {
            throw new IOException("Scheduler cursor is outside the production window");
        }
        List<Long> selected = new ArrayList<Long>();
        for (int scanned = 0;
                scanned < intervals.size() && selected.size() < maxDays;
                scanned++) {
            UtcInterval interval = intervals.get((start + scanned) % intervals.size());
            if (interval.startMillis > latestCompleted) {
                continue;
            }
            long day = startOfUtcDay(interval.startMillis);
            DayRecord record = dataset.ledger.days.get(Long.valueOf(day));
            if (record == null) {
                dataset.ledger.ensureUnknown(day);
                selected.add(Long.valueOf(day));
            } else if (STATUS_UNKNOWN.equals(record.status)
                    || STATUS_FAILED.equals(record.status)) {
                selected.add(Long.valueOf(day));
            } else if (!isResolvedStatus(record.status)) {
                throw new IOException("Malformed ledger status");
            }
        }
        return selected;
    }

    private static ProductionProgress progressOf(ProductionDataset dataset) {
        int saved = 0;
        int confirmed = 0;
        int unknown = 0;
        int failed = 0;
        long rows = 0L;
        List<Long> planned = plannedDayStarts(
            dataset.ledger.windowStart,
            dataset.ledger.windowEnd
        );
        for (int i = 0; i < planned.size(); i++) {
            DayRecord record = dataset.ledger.days.get(planned.get(i));
            if (record == null) {
                unknown++;
            } else if (STATUS_DATA_SAVED.equals(record.status)) {
                saved++;
                rows += record.rowCount;
            } else if (STATUS_CONFIRMED_EMPTY.equals(record.status)) {
                confirmed++;
            } else if (STATUS_FAILED.equals(record.status)) {
                failed++;
            } else {
                unknown++;
            }
        }
        LedgerCompleteness completeness = dataset.ledger.assess();
        return new ProductionProgress(
            planned.size(),
            saved,
            confirmed,
            unknown,
            failed,
            completeness.unresolvedCount,
            rows,
            dataset.schedule.nextDay,
            completeness.complete
        );
    }

    private static void printProductionProgress(ProductionProgress progress) {
        System.out.println("Planned calendar days: " + progress.plannedDays);
        System.out.println("DATA_SAVED days: " + progress.savedDays);
        System.out.println("CONFIRMED_EMPTY days: " + progress.confirmedEmptyDays);
        System.out.println("UNKNOWN days: " + progress.unknownDays);
        System.out.println("FAILED days: " + progress.failedDays);
        System.out.println("Remaining unresolved days: " + progress.unresolvedDays);
        System.out.println("Staged candle rows: " + progress.stagedRows);
        System.out.println("Next scheduled date: " + utcDate(progress.nextDay));
        System.out.println("DATASET_COMPLETE: " + progress.complete);
    }

    private static ProductionProgress runProductionBatch(
            Path directory,
            DayChunkSource source,
            RetryDelay delay,
            AttemptClock clock,
            SessionProbe session,
            int maxDays) throws IOException, InterruptedException {

        ProductionDataset dataset = openProduction(directory);
        long latestCompleted = dataset.ledger.windowEnd;
        List<Long> selected = selectFairDays(dataset, maxDays, latestCompleted);
        for (int i = 0; i < selected.size(); i++) {
            if (!session.usable()) {
                System.out.println(
                    "FAIL: Dukascopy session is unusable. "
                    + "Remaining selected days were not requested."
                );
                break;
            }
            long day = selected.get(i).longValue();
            long started = System.currentTimeMillis();
            DayRunResult result = processSelectedDay(
                dataset.store,
                source,
                delay,
                clock,
                day,
                latestCompleted
            );
            dataset.schedule.advancePast(day);
            long elapsed = System.currentTimeMillis() - started;
            System.out.println(
                "Day " + utcDate(day)
                + " status " + result.status
                + " attempt " + result.attempts
                + " candles " + result.candles
                + " rows " + result.rows
                + " elapsed " + elapsed + " ms"
            );
            printProductionProgress(progressOf(dataset));
        }
        if (selected.isEmpty()) {
            printProductionProgress(progressOf(dataset));
        }
        return progressOf(dataset);
    }

    private static void runProductionDownload(
            IHistory history,
            final IClient client,
            int maxDays,
            AtomicInteger exitCode,
            final AtomicBoolean historyRequestActive) {

        try {
            final IHistory activeHistory = history;
            resumeOrInitialize(
                PRODUCTION_DIRECTORY,
                new CompletedCandleCapture() {
                    public long latestCompleted() throws Exception {
                        historyRequestActive.set(true);
                        try {
                            return captureLatestCompleted(activeHistory);
                        } finally {
                            historyRequestActive.set(false);
                        }
                    }
                }
            );
            runProductionBatch(
                PRODUCTION_DIRECTORY,
                new DayChunkSource() {
                    public DayChunkResult request(long start, long end) {
                        historyRequestActive.set(true);
                        try {
                            return new HistoryDaySource(activeHistory).request(start, end);
                        } finally {
                            historyRequestActive.set(false);
                        }
                    }
                },
                LIVE_RETRY_DELAY,
                new AttemptClock() {
                    public long now() {
                        return System.currentTimeMillis();
                    }
                },
                new SessionProbe() {
                    public boolean usable() {
                        try {
                            return client.isConnected();
                        } catch (RuntimeException ex) {
                            return false;
                        }
                    }
                },
                maxDays
            );
            exitCode.set(0);
        } catch (Exception ex) {
            System.out.println("FAIL: Production download error.");
            System.out.println("Reason: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private static void runMergeProduction() {
        try {
            ProductionDataset dataset = openProduction(PRODUCTION_DIRECTORY);
            mergeFinal(dataset.store);
        } catch (IOException ex) {
            System.out.println("FAIL: " + ex.getMessage());
        }
    }

    private static ConfirmCommand parseProductionConfirmCommand(String[] args)
            throws IOException {

        if (args == null
                || args.length != 7
                || !"confirm-empty".equals(args[0])
                || !"--production".equals(args[1])
                || args[2] == null
                || args[2].length() == 0
                || !"--reason".equals(args[3])
                || args[4] == null
                || !"--confirm".equals(args[5])
                || args[6] == null
                || args[6].length() == 0) {
            throw new IOException("Malformed confirm-empty command");
        }
        return new ConfirmCommand(args[2], args[4], args[6]);
    }

    private static Path productionConfirmDirectory() {
        return PRODUCTION_DIRECTORY;
    }

    private static void runProductionConfirmCommand(String[] args) {
        ConfirmCommand command;
        try {
            command = parseProductionConfirmCommand(args);
        } catch (IOException ex) {
            System.out.println("FAIL: " + ex.getMessage());
            return;
        }
        try {
            boolean changed = confirmEmptyDay(
                productionConfirmDirectory(),
                command.date,
                command.reason,
                command.confirmDate,
                System.currentTimeMillis()
            );
            System.out.println(changed
                ? "CONFIRMED_EMPTY " + command.date
                : "ALREADY_CONFIRMED_EMPTY " + command.date);
        } catch (ParseException ex) {
            System.out.println("FAIL: Invalid UTC date: " + command.date);
        } catch (RuntimeException ex) {
            System.out.println("FAIL: " + ex.getMessage());
        } catch (IOException ex) {
            System.out.println("FAIL: " + ex.getMessage());
        }
    }

    private static long nextSimulatedAttempt = -1L;

    private static AttemptClock simulatedClock() throws ParseException {
        if (nextSimulatedAttempt < 0L) {
            nextSimulatedAttempt = ledgerAttempt("2026-01-01 00:00:00");
        }
        AttemptClock clock = new IncrementClock(nextSimulatedAttempt);
        nextSimulatedAttempt += 86400000L;
        return clock;
    }

    private static ProductionProgress runSimulatedBatch(
            Path directory,
            DayChunkSource source,
            int maxDays) throws Exception {

        return runProductionBatch(
            directory,
            source,
            new RecordingDelay(),
            simulatedClock(),
            USABLE_SESSION,
            maxDays
        );
    }

    private static DayChunkResult productionBars(long time) {
        return classifyDayResponse(
            Arrays.asList(new CandleRecord(time, 1.10, 1.20, 1.00, 1.15, 10.0)),
            false,
            false,
            false,
            false,
            null
        );
    }

    private static DayChunkResult productionEmpty() {
        return classifyDayResponse(
            new ArrayList<CandleRecord>(),
            false,
            false,
            false,
            false,
            null
        );
    }

    private static DayChunkResult productionFailure() {
        return classifyDayResponse(
            null,
            false,
            true,
            false,
            false,
            "IOException: connection reset"
        );
    }

    private static void initShortWindow(Path directory, String startDate, String endDate)
            throws Exception {

        initializeProductionWindow(
            directory,
            parseUtcDate(startDate),
            ledgerWindowEnd(endDate)
        );
    }

    private static String snapshotDatasets() throws Exception {
        StringBuilder text = new StringBuilder();
        appendSnapshot(text, Paths.get("output", "EURUSD_M1_test.csv"));
        appendSnapshot(text, Paths.get("output", "phase3_live_test"));
        appendSnapshot(text, Paths.get("output", "phase4_resume_test"));
        appendSnapshot(text, PRODUCTION_DIRECTORY);
        return text.toString();
    }

    private static void appendSnapshot(StringBuilder text, Path path) throws Exception {
        text.append(path.toString()).append('\n');
        if (!Files.exists(path)) {
            text.append("ABSENT\n");
            return;
        }
        if (Files.isRegularFile(path)) {
            text.append(sha256Hex(Files.readAllBytes(path))).append('\n');
            return;
        }
        List<Path> files = new ArrayList<Path>();
        collectFiles(path, files);
        Collections.sort(files);
        for (int i = 0; i < files.size(); i++) {
            text.append(files.get(i).toString()).append(' ')
                .append(sha256Hex(Files.readAllBytes(files.get(i))))
                .append('\n');
        }
    }

    private static void collectFiles(Path path, List<Path> files) throws IOException {
        if (Files.isRegularFile(path)) {
            files.add(path);
            return;
        }
        if (!Files.isDirectory(path)) {
            return;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
            for (Path child : children) {
                collectFiles(child, files);
            }
        }
    }

    private static boolean runProductionSelfTest() throws Exception {
        System.out.println(
            "Production tests use a temporary directory and simulated responses. They do not connect to Dukascopy."
        );
        String before = snapshotDatasets();
        Path root = Files.createTempDirectory("dukascopy-production-test");
        nextSimulatedAttempt = -1L;
        failNextStagingWrite = false;
        boolean passed = true;
        try {
            passed &= testProductionFreshWindow(root.resolve("fresh"));
            passed &= testProductionThreeYearStart(root.resolve("start"));
            passed &= testProductionLeapYear(root.resolve("leap"));
            passed &= testProductionPartialDays(root.resolve("partial"));
            passed &= testProductionInitializesDays(root.resolve("init"));
            passed &= testProductionPathsIsolated(root.resolve("paths"));
            passed &= testProductionResumeKeepsWindow(root.resolve("resume"));
            passed &= testProductionSkipsSaved(root.resolve("saved"));
            passed &= testProductionSkipsConfirmed(root.resolve("confirmed"));
            passed &= testProductionRetriesUnknown(root.resolve("unknown"));
            passed &= testProductionRetriesFailed(root.resolve("failed"));
            passed &= testProductionEmptyStaysUnknown(root.resolve("empty"));
            passed &= testProductionExceptionStaysFailed(root.resolve("exception"));
            passed &= testProductionStagesBeforeSaved(root.resolve("stage"));
            passed &= testProductionStagingFailure(root.resolve("stage-fail"));
            passed &= testProductionMaxDays(root.resolve("max"));
            passed &= testProductionCursorAdvances(root.resolve("cursor"));
            passed &= testProductionNoStarvation(root.resolve("fair"));
            passed &= testProductionCursorRestarts(root.resolve("restart-cursor"));
            passed &= testProductionWrapAround(root.resolve("wrap"));
            passed &= testProductionCrashStaysUnresolved(root.resolve("crash"));
            passed &= testProductionNoFutureCandles(root.resolve("future"));
            passed &= testProductionMergePrefix(root.resolve("prefix"));
            passed &= testProductionCompleteness(root.resolve("complete"));
            passed &= testProductionConfirmation(root.resolve("confirm-a"), root.resolve("confirm-b"));
            passed &= testProductionProgressRestarts(root.resolve("progress"));
            passed &= testProductionSessionStop(root.resolve("session"));
            passed &= before.equals(snapshotDatasets());
        } finally {
            failNextStagingWrite = false;
            deleteRecursively(root);
        }
        boolean datasets = before.equals(snapshotDatasets());
        System.out.println("================================");
        System.out.println("Existing test datasets remain unchanged");
        System.out.println(datasets ? "PASS" : "FAIL");
        passed &= datasets;
        System.out.println("--------------------------------");
        System.out.println(passed
            ? "PRODUCTION TESTS PASS"
            : "PRODUCTION TESTS FAIL");
        return passed;
    }

    private static ProductionDataset captureFresh(Path directory, final String timestamp)
            throws Exception {

        final long candle = parseUtcMinute(timestamp);
        final int[] calls = new int[1];
        ProductionDataset dataset = resumeOrInitialize(
            directory,
            new CompletedCandleCapture() {
                public long latestCompleted() {
                    calls[0]++;
                    return candle;
                }
            }
        );
        if (calls[0] != 1) {
            throw new IOException("Latest completed candle was not captured once");
        }
        return dataset;
    }

    private static boolean testProductionFreshWindow(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Fresh window captured from a simulated latest completed candle");
        long candle = parseUtcMinute("2024-06-15 12:34:00");
        ProductionDataset dataset = captureFresh(directory, "2024-06-15 12:34:00");
        boolean passed = dataset.ledger.windowEnd == candle
            && dataset.ledger.windowStart == threeYearStartMillis(candle)
            && dataset.schedule.nextDay == startOfUtcDay(dataset.ledger.windowStart)
            && Files.isRegularFile(directory.resolve(LEDGER_FILE_NAME));
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testProductionThreeYearStart(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Exact three-calendar-year UTC start");
        ProductionDataset dataset = captureFresh(directory, "2024-06-15 12:34:00");
        boolean passed = "2021-06-15 12:34:00".equals(
            formatUtcMinute(dataset.ledger.windowStart)
        ) && dataset.ledger.windowStart == threeYearStartMillis(
            dataset.ledger.windowEnd
        );
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testProductionLeapYear(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Leap-year behavior");
        ProductionDataset dataset = captureFresh(directory, "2024-02-29 12:34:00");
        boolean passed = "2021-02-28 12:34:00".equals(
            formatUtcMinute(dataset.ledger.windowStart)
        ) && "2024-02-29 12:34:00".equals(
            formatUtcMinute(dataset.ledger.windowEnd)
        );
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testProductionPartialDays(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Partial first and last daily chunks");
        ProductionDataset dataset = captureFresh(directory, "2024-06-15 12:34:00");
        List<UtcInterval> intervals = planDailyIntervals(
            dataset.ledger.windowStart,
            dataset.ledger.windowEnd
        );
        UtcInterval first = intervals.get(0);
        UtcInterval last = intervals.get(intervals.size() - 1);
        final List<long[]> requests = new ArrayList<long[]>();
        DayChunkSource source = new DayChunkSource() {
            public DayChunkResult request(long start, long end) {
                requests.add(new long[] {start, end});
                return productionBars(start);
            }
        };
        runSimulatedBatch(directory, source, 1);
        dataset.schedule.nextDay = startOfUtcDay(last.startMillis);
        dataset.schedule.persist();
        runSimulatedBatch(directory, source, 1);
        boolean passed = first.startMillis == dataset.ledger.windowStart
            && first.endMillis == endOfUtcMinuteDay(dataset.ledger.windowStart)
            && last.endMillis == dataset.ledger.windowEnd
            && last.startMillis == startOfUtcDay(dataset.ledger.windowEnd)
            && requests.size() >= 2
            && requests.get(0)[0] == first.startMillis
            && requests.get(0)[1] == first.endMillis
            && requests.get(requests.size() - 1)[0] == last.startMillis
            && requests.get(requests.size() - 1)[1] == last.endMillis;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testProductionInitializesDays(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("All planned day records initialized correctly");
        ProductionDataset dataset = captureFresh(directory, "2024-05-02 00:00:00");
        List<Long> days = plannedDayStarts(
            dataset.ledger.windowStart,
            dataset.ledger.windowEnd
        );
        boolean passed = dataset.ledger.days.size() == days.size();
        for (int i = 0; i < days.size() && passed; i++) {
            DayRecord record = dataset.ledger.days.get(days.get(i));
            passed = record != null
                && STATUS_UNKNOWN.equals(record.status)
                && record.attempts == 0
                && record.lastAttemptMillis == 0L
                && "Not requested".equals(record.detail);
        }
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testProductionPathsIsolated(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Production paths isolated from every test dataset");
        Path phase3 = Paths.get("output", "phase3_live_test").normalize();
        Path phase4 = Paths.get("output", "phase4_resume_test").normalize();
        Path sample = Paths.get("output", "EURUSD_M1_test.csv").normalize();
        Files.createDirectories(directory);
        Files.write(directory.resolve("notes.txt"), "extra".getBytes(StandardCharsets.UTF_8));
        boolean rejected = false;
        try {
            initializeProductionWindow(
                directory,
                parseUtcDate("2024-06-01"),
                ledgerWindowEnd("2024-06-01")
            );
        } catch (IOException ex) {
            rejected = "Inconsistent production directory".equals(ex.getMessage());
        }
        boolean passed = rejected
            && PRODUCTION_DIRECTORY.normalize().equals(
                Paths.get("output", "EURUSD_M1_3y_production").normalize()
            )
            && !PRODUCTION_DIRECTORY.normalize().equals(phase3)
            && !PRODUCTION_DIRECTORY.normalize().equals(phase4)
            && !PRODUCTION_DIRECTORY.normalize().equals(sample)
            && productionConfirmDirectory().normalize().equals(
                PRODUCTION_DIRECTORY.normalize()
            );
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testProductionResumeKeepsWindow(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Resume reuses the original saved window when a later tick is newer");
        final long candle = parseUtcMinute("2024-06-15 12:34:00");
        resumeOrInitialize(directory, new CompletedCandleCapture() {
            public long latestCompleted() {
                return candle;
            }
        });
        byte[] before = Files.readAllBytes(directory.resolve(LEDGER_FILE_NAME));
        final int[] calls = new int[1];
        ProductionDataset resumed = resumeOrInitialize(
            directory,
            new CompletedCandleCapture() {
                public long latestCompleted() {
                    calls[0]++;
                    return candle + 86400000L;
                }
            }
        );
        String text = new String(before, StandardCharsets.UTF_8);
        Files.write(
            directory.resolve(LEDGER_FILE_NAME),
            text.replace("schemaVersion=2\n", "schemaVersion=1\n")
                .getBytes(StandardCharsets.UTF_8)
        );
        boolean schemaRejected = false;
        try {
            openProduction(directory);
        } catch (IOException ex) {
            schemaRejected = "Unsupported ledger schema".equals(ex.getMessage());
        }
        boolean passed = calls[0] == 0
            && resumed.ledger.windowStart == threeYearStartMillis(candle)
            && resumed.ledger.windowEnd == candle
            && schemaRejected
            && new String(
                Files.readAllBytes(directory.resolve(LEDGER_FILE_NAME)),
                StandardCharsets.UTF_8
            ).indexOf("schemaVersion=1\n") >= 0;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean requestedDay(List<long[]> requests, long day) {
        for (int i = 0; i < requests.size(); i++) {
            if (startOfUtcDay(requests.get(i)[0]) == day) {
                return true;
            }
        }
        return false;
    }

    private static boolean testProductionSkipsSaved(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("DATA_SAVED days are skipped");
        initShortWindow(directory, "2024-07-01", "2024-07-02");
        final List<long[]> requests = new ArrayList<long[]>();
        runSimulatedBatch(directory, new DayChunkSource() {
            public DayChunkResult request(long start, long end) {
                requests.add(new long[] {start, end});
                return productionBars(start);
            }
        }, 1);
        requests.clear();
        runSimulatedBatch(directory, new DayChunkSource() {
            public DayChunkResult request(long start, long end) {
                requests.add(new long[] {start, end});
                return productionBars(start);
            }
        }, 2);
        ProductionDataset dataset = openProduction(directory);
        boolean passed = STATUS_DATA_SAVED.equals(
            statusOf(dataset.ledger, "2024-07-01")
        ) && !requestedDay(requests, parseUtcDate("2024-07-01"))
            && requestedDay(requests, parseUtcDate("2024-07-02"));
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testProductionSkipsConfirmed(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("CONFIRMED_EMPTY days are skipped");
        initShortWindow(directory, "2024-07-03", "2024-07-04");
        confirmEmptyDay(
            directory,
            "2024-07-03",
            CONFIRM_REASON,
            "2024-07-03",
            confirmationNow()
        );
        final List<long[]> requests = new ArrayList<long[]>();
        runSimulatedBatch(directory, new DayChunkSource() {
            public DayChunkResult request(long start, long end) {
                requests.add(new long[] {start, end});
                return productionBars(start);
            }
        }, 2);
        boolean passed = !requestedDay(requests, parseUtcDate("2024-07-03"))
            && requestedDay(requests, parseUtcDate("2024-07-04"))
            && STATUS_CONFIRMED_EMPTY.equals(
                statusOf(openProduction(directory).ledger, "2024-07-03")
            );
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testProductionRetriesUnknown(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("UNKNOWN days are retried");
        initShortWindow(directory, "2024-07-05", "2024-07-05");
        runSimulatedBatch(directory, new DayChunkSource() {
            public DayChunkResult request(long start, long end) {
                return productionEmpty();
            }
        }, 1);
        final int[] calls = new int[1];
        runSimulatedBatch(directory, new DayChunkSource() {
            public DayChunkResult request(long start, long end) {
                calls[0]++;
                return productionBars(start);
            }
        }, 1);
        DayRecord record = dayOf(openProduction(directory).ledger, "2024-07-05");
        boolean passed = calls[0] > 0
            && STATUS_DATA_SAVED.equals(record.status);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testProductionRetriesFailed(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("FAILED days are retried");
        initShortWindow(directory, "2024-07-06", "2024-07-06");
        runSimulatedBatch(directory, new DayChunkSource() {
            public DayChunkResult request(long start, long end) {
                return productionFailure();
            }
        }, 1);
        final int[] calls = new int[1];
        runSimulatedBatch(directory, new DayChunkSource() {
            public DayChunkResult request(long start, long end) {
                calls[0]++;
                return productionBars(start);
            }
        }, 1);
        DayRecord record = dayOf(openProduction(directory).ledger, "2024-07-06");
        boolean passed = calls[0] > 0 && STATUS_DATA_SAVED.equals(record.status);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testProductionEmptyStaysUnknown(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Empty live response remains UNKNOWN");
        initShortWindow(directory, "2024-07-07", "2024-07-07");
        runSimulatedBatch(directory, new DayChunkSource() {
            public DayChunkResult request(long start, long end) {
                return productionEmpty();
            }
        }, 1);
        DayRecord record = dayOf(openProduction(directory).ledger, "2024-07-07");
        boolean passed = STATUS_UNKNOWN.equals(record.status)
            && record.attempts == DAY_ATTEMPTS
            && !STATUS_CONFIRMED_EMPTY.equals(record.status);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testProductionExceptionStaysFailed(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Exceptions remain FAILED");
        initShortWindow(directory, "2024-07-08", "2024-07-08");
        runSimulatedBatch(directory, new DayChunkSource() {
            public DayChunkResult request(long start, long end) {
                return productionFailure();
            }
        }, 1);
        DayRecord record = dayOf(openProduction(directory).ledger, "2024-07-08");
        boolean passed = STATUS_FAILED.equals(record.status)
            && record.attempts == DAY_ATTEMPTS;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testProductionStagesBeforeSaved(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Successful response writes staging before DATA_SAVED");
        initShortWindow(directory, "2024-07-09", "2024-07-09");
        runSimulatedBatch(directory, new DayChunkSource() {
            public DayChunkResult request(long start, long end) {
                return productionBars(start);
            }
        }, 1);
        ProductionDataset dataset = openProduction(directory);
        DayRecord record = dayOf(dataset.ledger, "2024-07-09");
        Path csv = directory.resolve(STAGING_DIRECTORY_NAME).resolve("2024-07-09.csv");
        boolean passed = Files.isRegularFile(csv)
            && STATUS_DATA_SAVED.equals(record.status)
            && record.rowCount == 1L
            && sha256Hex(Files.readAllBytes(csv)).equals(record.sha256);
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testProductionStagingFailure(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Failed staging write never marks DATA_SAVED");
        initShortWindow(directory, "2024-07-10", "2024-07-10");
        failNextStagingWrite = true;
        try {
            runSimulatedBatch(directory, new DayChunkSource() {
                public DayChunkResult request(long start, long end) {
                    return productionBars(start);
                }
            }, 1);
        } finally {
            failNextStagingWrite = false;
        }
        DayRecord record = dayOf(openProduction(directory).ledger, "2024-07-10");
        boolean passed = !STATUS_DATA_SAVED.equals(record.status)
            && !Files.exists(
                directory.resolve(STAGING_DIRECTORY_NAME).resolve("2024-07-10.csv")
            );
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testProductionMaxDays(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Maximum distinct days per run is respected");
        initShortWindow(directory, "2024-08-01", "2024-08-05");
        final List<long[]> requests = new ArrayList<long[]>();
        runSimulatedBatch(directory, new DayChunkSource() {
            public DayChunkResult request(long start, long end) {
                long day = startOfUtcDay(start);
                if (requests.isEmpty()
                        || startOfUtcDay(requests.get(requests.size() - 1)[0]) != day) {
                    requests.add(new long[] {start, end});
                }
                return productionBars(start);
            }
        }, 2);
        boolean passed = requests.size() == 2
            && startOfUtcDay(requests.get(0)[0]) == parseUtcDate("2024-08-01")
            && startOfUtcDay(requests.get(1)[0]) == parseUtcDate("2024-08-02");
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testProductionCursorAdvances(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Persistent scheduling cursor advances across batches");
        initShortWindow(directory, "2024-08-06", "2024-08-08");
        runSimulatedBatch(directory, new DayChunkSource() {
            public DayChunkResult request(long start, long end) {
                return productionEmpty();
            }
        }, 1);
        long next = openProduction(directory).schedule.nextDay;
        boolean passed = next == parseUtcDate("2024-08-07");
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testProductionNoStarvation(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("A permanently UNKNOWN first day does not starve later days");
        initShortWindow(directory, "2024-08-10", "2024-08-12");
        final List<Long> days = new ArrayList<Long>();
        DayChunkSource source = new DayChunkSource() {
            public DayChunkResult request(long start, long end) {
                long day = startOfUtcDay(start);
                if (days.isEmpty() || days.get(days.size() - 1).longValue() != day) {
                    days.add(Long.valueOf(day));
                }
                return productionEmpty();
            }
        };
        runSimulatedBatch(directory, source, 1);
        runSimulatedBatch(directory, source, 1);
        runSimulatedBatch(directory, source, 1);
        boolean passed = days.size() == 3
            && days.get(0).longValue() == parseUtcDate("2024-08-10")
            && days.get(1).longValue() == parseUtcDate("2024-08-11")
            && days.get(2).longValue() == parseUtcDate("2024-08-12")
            && STATUS_UNKNOWN.equals(
                statusOf(openProduction(directory).ledger, "2024-08-10")
            );
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testProductionCursorRestarts(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Restart resumes from the persisted scheduler cursor");
        initShortWindow(directory, "2024-08-13", "2024-08-15");
        runSimulatedBatch(directory, new DayChunkSource() {
            public DayChunkResult request(long start, long end) {
                return productionEmpty();
            }
        }, 1);
        ProductionDataset restarted = openProduction(directory);
        final List<Long> days = new ArrayList<Long>();
        runProductionBatch(
            directory,
            new DayChunkSource() {
                public DayChunkResult request(long start, long end) {
                    days.add(Long.valueOf(startOfUtcDay(start)));
                    return productionEmpty();
                }
            },
            new RecordingDelay(),
            new IncrementClock(ledgerAttempt("2026-02-01 00:00:00")),
            USABLE_SESSION,
            1
        );
        boolean passed = restarted.schedule.nextDay == parseUtcDate("2024-08-14")
            && days.size() > 0
            && days.get(0).longValue() == parseUtcDate("2024-08-14");
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testProductionWrapAround(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Wrap-around retries unresolved days");
        initShortWindow(directory, "2024-08-16", "2024-08-17");
        final List<Long> days = new ArrayList<Long>();
        DayChunkSource source = new DayChunkSource() {
            public DayChunkResult request(long start, long end) {
                long day = startOfUtcDay(start);
                if (days.isEmpty() || days.get(days.size() - 1).longValue() != day) {
                    days.add(Long.valueOf(day));
                }
                return productionEmpty();
            }
        };
        runSimulatedBatch(directory, source, 1);
        runSimulatedBatch(directory, source, 1);
        runSimulatedBatch(directory, source, 1);
        boolean passed = days.size() == 3
            && days.get(0).longValue() == parseUtcDate("2024-08-16")
            && days.get(1).longValue() == parseUtcDate("2024-08-17")
            && days.get(2).longValue() == parseUtcDate("2024-08-16")
            && STATUS_UNKNOWN.equals(
                statusOf(openProduction(directory).ledger, "2024-08-16")
            );
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testProductionCrashStaysUnresolved(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("A crash during a request leaves the day unresolved");
        initShortWindow(directory, "2024-08-18", "2024-08-18");
        long cursor = openProduction(directory).schedule.nextDay;
        boolean crashed = false;
        try {
            runSimulatedBatch(directory, new DayChunkSource() {
                public DayChunkResult request(long start, long end) {
                    throw new IllegalStateException("history request crashed");
                }
            }, 1);
        } catch (IllegalStateException ex) {
            crashed = "history request crashed".equals(ex.getMessage());
        }
        ProductionDataset dataset = openProduction(directory);
        DayRecord record = dayOf(dataset.ledger, "2024-08-18");
        boolean passed = crashed
            && dataset.schedule.nextDay == cursor
            && !STATUS_DATA_SAVED.equals(record.status)
            && !STATUS_CONFIRMED_EMPTY.equals(record.status)
            && record.attempts >= 1
            && !dataset.ledger.assess().complete;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testProductionNoFutureCandles(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("No future candles are requested");
        long start = parseUtcMinute("2024-09-01 10:00:00");
        long end = parseUtcMinute("2024-09-02 15:30:00");
        initializeProductionWindow(directory, start, end);
        final List<long[]> requests = new ArrayList<long[]>();
        runSimulatedBatch(directory, new DayChunkSource() {
            public DayChunkResult request(long from, long to) {
                requests.add(new long[] {from, to});
                return productionBars(from);
            }
        }, 5);
        boolean passed = requests.size() >= 2;
        for (int i = 0; i < requests.size(); i++) {
            passed = passed
                && requests.get(i)[0] <= end
                && requests.get(i)[1] <= end
                && requests.get(i)[1] >= requests.get(i)[0];
        }
        passed = passed
            && requests.get(0)[0] == start
            && requests.get(requests.size() - 1)[1] == end;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testProductionMergePrefix(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Final merge uses only the contiguous resolved prefix");
        initShortWindow(directory, "2024-09-03", "2024-09-05");
        ProductionDataset dataset = openProduction(directory);
        dataset.store.saveDay(
            parseUtcDate("2024-09-03"),
            mergePair("2024-09-03", "00:00:00", "00:01:00"),
            ledgerAttempt("2024-09-03 08:00:00"),
            "saved"
        );
        dataset.store.saveDay(
            parseUtcDate("2024-09-05"),
            mergePair("2024-09-05", "00:00:00", "00:01:00"),
            ledgerAttempt("2024-09-05 08:00:00"),
            "saved"
        );
        MergeReport report = mergeFinal(dataset.store);
        String csv = new String(Files.readAllBytes(report.csvPath), StandardCharsets.UTF_8);
        boolean passed = report.savedIncluded == 1
            && report.mergedCandles == 2
            && csv.indexOf("2024-09-03") >= 0
            && csv.indexOf("2024-09-05") < 0
            && !report.datasetComplete;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testProductionCompleteness(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Dataset completeness remains false while unresolved days exist");
        initShortWindow(directory, "2024-09-06", "2024-09-07");
        ProductionDataset dataset = openProduction(directory);
        dataset.store.saveDay(
            parseUtcDate("2024-09-06"),
            mergePair("2024-09-06", "00:00:00", "00:01:00"),
            ledgerAttempt("2024-09-06 08:00:00"),
            "saved"
        );
        MergeReport report = mergeFinal(openProduction(directory).store);
        boolean passed = !report.datasetComplete
            && report.unknownCount == 1
            && !openProduction(directory).ledger.assess().complete;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testProductionConfirmation(Path primary, Path other) throws Exception {
        System.out.println("================================");
        System.out.println("Production confirmation updates only the production ledger");
        initShortWindow(primary, "2024-09-08", "2024-09-08");
        initShortWindow(other, "2024-09-08", "2024-09-08");
        byte[] otherBefore = Files.readAllBytes(other.resolve(LEDGER_FILE_NAME));
        ConfirmCommand command = parseProductionConfirmCommand(new String[] {
            "confirm-empty",
            "--production",
            "2024-09-08",
            "--reason",
            CONFIRM_REASON,
            "--confirm",
            "2024-09-08"
        });
        boolean changed = confirmEmptyDay(
            primary,
            command.date,
            command.reason,
            command.confirmDate,
            confirmationNow()
        );
        DayRecord record = dayOf(openProduction(primary).ledger, "2024-09-08");
        boolean passed = changed
            && STATUS_CONFIRMED_EMPTY.equals(record.status)
            && CONFIRM_REASON.equals(record.confirmReason)
            && CONFIRM_METHOD.equals(record.confirmMethod)
            && Arrays.equals(
                otherBefore,
                Files.readAllBytes(other.resolve(LEDGER_FILE_NAME))
            )
            && productionConfirmDirectory().normalize().equals(
                PRODUCTION_DIRECTORY.normalize()
            )
            && !primary.normalize().equals(PRODUCTION_DIRECTORY.normalize());
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testProductionProgressRestarts(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("Progress counters remain consistent after restart");
        initShortWindow(directory, "2024-09-09", "2024-09-10");
        ProductionProgress first = runSimulatedBatch(directory, new DayChunkSource() {
            public DayChunkResult request(long start, long end) {
                return productionBars(start);
            }
        }, 1);
        ProductionDataset restarted = openProduction(directory);
        ProductionProgress second = progressOf(restarted);
        boolean passed = first.savedDays == 1
            && first.stagedRows == 1L
            && first.plannedDays == 2
            && !first.complete
            && second.savedDays == first.savedDays
            && second.unknownDays == first.unknownDays
            && second.failedDays == first.failedDays
            && second.confirmedEmptyDays == first.confirmedEmptyDays
            && second.unresolvedDays == first.unresolvedDays
            && second.stagedRows == first.stagedRows
            && second.nextDay == first.nextDay
            && second.complete == first.complete;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static boolean testProductionSessionStop(Path directory) throws Exception {
        System.out.println("================================");
        System.out.println("An unusable session does not request later days");
        initShortWindow(directory, "2024-09-11", "2024-09-12");
        final int[] checks = new int[1];
        final List<Long> days = new ArrayList<Long>();
        runProductionBatch(
            directory,
            new DayChunkSource() {
                public DayChunkResult request(long start, long end) {
                    days.add(Long.valueOf(startOfUtcDay(start)));
                    return productionBars(start);
                }
            },
            new RecordingDelay(),
            new IncrementClock(ledgerAttempt("2026-03-01 00:00:00")),
            new SessionProbe() {
                public boolean usable() {
                    checks[0]++;
                    return checks[0] == 1;
                }
            },
            2
        );
        ProductionDataset dataset = openProduction(directory);
        boolean passed = days.size() == 1
            && days.get(0).longValue() == parseUtcDate("2024-09-11")
            && STATUS_UNKNOWN.equals(statusOf(dataset.ledger, "2024-09-12"))
            && dayOf(dataset.ledger, "2024-09-12").attempts == 0;
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }
}
