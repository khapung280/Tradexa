package com.tradexa.dukascopy.diagnostic;

import com.dukascopy.api.Filter;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IHistory;
import com.dukascopy.api.IMessage;
import com.dukascopy.api.IStrategy;
import com.dukascopy.api.ITick;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.JFException;
import com.dukascopy.api.LoadingDataListener;
import com.dukascopy.api.LoadingProgressListener;
import com.dukascopy.api.OfferSide;
import com.dukascopy.api.Period;
import com.dukascopy.api.system.ClientFactory;
import com.dukascopy.api.system.IClient;
import com.dukascopy.api.system.ISystemListener;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Isolated history-status probe. It does not read or write the production
 * ledger, scheduler, or staging files. Run getbars and readbars in separate
 * processes so each pass gets a fresh JForex cache.
 */
public class HistoryStatusProbe {
    static final String JNLP_URL =
        "http://platform.dukascopy.com/demo_3/jforex_3.jnlp";
    static final Instrument INSTRUMENT = Instrument.EURUSD;
    static final Period PERIOD = Period.ONE_MIN;

    private static final Path DIAGNOSTIC_ROOT =
        Paths.get("output", "EURUSD_M1_diagnostic");
    private static final String[] FULL_DATES = {
        "2023-11-18",
        "2023-11-19",
        "2023-11-28",
        "2023-11-30",
        "2023-12-09",
        "2023-12-14",
        "2024-05-21",
        "2024-05-22",
        "2024-05-23",
        "2024-05-25",
        "2024-05-27",
        "2024-05-28",
        "2024-06-23",
        "2024-06-24",
        "2024-12-07",
        "2024-12-09",
        "2025-04-14",
        "2025-04-16",
        "2025-05-10",
        "2025-05-11",
        "2025-10-03",
        "2026-08-07",
        "2026-08-09",
        "2026-08-11",
        "2026-08-12",
        "2026-08-13",
        "2026-08-14",
        "2026-11-13",
        "2026-11-14",
        "2026-11-15"
    };

    public static void main(String[] args) throws Exception {
        if (args.length != 1
                || (!"getbars".equals(args[0]) && !"readbars".equals(args[0]))) {
            System.out.println("FAIL: Usage: HistoryStatusProbe getbars|readbars");
            System.exit(2);
            return;
        }
        boolean readBars = "readbars".equals(args[0]);
        String pass = readBars ? "readbars" : "getbars";
        Path cache = DIAGNOSTIC_ROOT.resolve(
            readBars ? "cache-readbars" : "cache-getbars"
        );
        String resolved;
        try {
            resolved = DiagnosticCache.isolate(DIAGNOSTIC_ROOT, cache);
        } catch (IOException error) {
            System.out.println("FAIL: Diagnostic cache setup.");
            System.exit(2);
            return;
        }
        if (resolved == null) {
            System.out.println("FAIL: Cache directory is outside the diagnostic root.");
            System.exit(2);
            return;
        }
        System.out.println("Diagnostic cache verified.");

        String username = System.getenv("JFOREX_USERNAME");
        String password = System.getenv("JFOREX_PASSWORD");
        if (username == null || username.trim().isEmpty()
                || password == null || password.isEmpty()) {
            System.out.println("FAIL: Credentials not found.");
            System.exit(2);
            return;
        }

        HttpObservation observation = new HttpObservation();
        ObservingURLStreamHandlerFactory.install(observation);

        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final CountDownLatch stopped = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        final AtomicInteger exitCode = new AtomicInteger(1);
        final long[] strategyId = new long[] {-1L};
        final IClient client = ClientFactory.getDefaultInstance();

        client.setSystemListener(new ISystemListener() {
            public void onStart(long processId) {}

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

        Path report = DIAGNOSTIC_ROOT.resolve(pass + ".csv");
        try {
            System.out.println("Connecting to Dukascopy Demo...");
            client.connect(JNLP_URL, username, password);
            boolean connectedInTime = connected.await(90, TimeUnit.SECONDS);
            if (!connectedInTime || !client.isConnected()) {
                System.out.println("FAIL: Connection timeout.");
                exitCode.set(1);
                return;
            }

            client.setSubscribedInstruments(Collections.singleton(INSTRUMENT));
            strategyId[0] = client.startStrategy(new ProbeStrategy(
                pass,
                observation,
                report,
                finished,
                exitCode
            ));
            if (!finished.await(3, TimeUnit.HOURS)) {
                System.out.println("FAIL: Probe timed out.");
                exitCode.set(1);
            }
        } finally {
            shutdown(client, strategyId[0], stopped, disconnected, exitCode);
        }
    }

    static List<ProbeDay> days() {
        List<ProbeDay> days = new ArrayList<ProbeDay>();
        days.add(new ProbeDay(
            "2023-11-16",
            "2023-11-16 06:41:00",
            "2023-11-16 23:59:00"
        ));
        for (int i = 0; i < FULL_DATES.length; i++) {
            String date = FULL_DATES[i];
            days.add(new ProbeDay(
                date,
                date + " 00:00:00",
                date + " 23:59:00"
            ));
        }
        days.add(new ProbeDay(
            "2026-11-16",
            "2026-11-16 00:00:00",
            "2026-11-16 06:41:00"
        ));
        return days;
    }

    private static void shutdown(
            IClient client,
            long strategyId,
            CountDownLatch stopped,
            CountDownLatch disconnected,
            AtomicInteger exitCode) {
        try {
            if (strategyId >= 0
                    && client.getStartedStrategies().containsKey(Long.valueOf(strategyId))) {
                client.stopStrategy(strategyId);
            }
        } catch (Exception error) {
            System.out.println(
                "Strategy stop error: " + error.getClass().getSimpleName()
            );
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
                        "Disconnect error: " + error.getClass().getSimpleName()
                    );
                }
            }
        }, "dukascopy-diagnostic-disconnect");
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
        if (!blockers.isEmpty()) {
            System.out.println(
                "JForex left non-daemon threads after disconnect: " + blockers
            );
            System.out.println(
                "IClient.disconnect() does not stop the non-daemon ClientConnector thread. Exiting."
            );
            System.exit(exitCode.get());
        }
    }

    static final class ProbeDay {
        final String date;
        final String startText;
        final String endText;

        ProbeDay(String date, String startText, String endText) {
            this.date = date;
            this.startText = startText;
            this.endText = endText;
        }

        long startMillis() {
            return parse(startText);
        }

        long endMillis() {
            return parse(endText);
        }

        private static long parse(String text) {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            try {
                return format.parse(text).getTime();
            } catch (ParseException error) {
                throw new IllegalStateException("Bad probe timestamp");
            }
        }
    }

    private static final class ProbeStrategy implements IStrategy {
        private final String pass;
        private final HttpObservation observation;
        private final Path report;
        private final CountDownLatch finished;
        private final AtomicInteger exitCode;

        private ProbeStrategy(
                String pass,
                HttpObservation observation,
                Path report,
                CountDownLatch finished,
                AtomicInteger exitCode) {
            this.pass = pass;
            this.observation = observation;
            this.report = report;
            this.finished = finished;
            this.exitCode = exitCode;
        }

        public void onStart(com.dukascopy.api.IContext context) throws JFException {
            try {
                run(context.getHistory());
                exitCode.set(0);
            } catch (Exception error) {
                System.out.println(
                    "FAIL: Probe error: " + error.getClass().getSimpleName()
                );
                exitCode.set(1);
            } finally {
                finished.countDown();
            }
        }

        private void run(IHistory history) throws Exception {
            Files.createDirectories(report.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(
                    report,
                    StandardCharsets.UTF_8)) {
                writer.write(
                    "date,range_start_utc,range_end_utc,pass,attempt,"
                    + "response_code,exception_class,bytes,bar_count,all_data_loaded"
                );
                writer.newLine();
                List<ProbeDay> days = days();
                for (int i = 0; i < days.size(); i++) {
                    writeDay(writer, history, days.get(i));
                }
            }
            System.out.println("Probe report written.");
        }

        private void writeDay(BufferedWriter writer, IHistory history, ProbeDay day)
                throws Exception {
            observation.drain();
            observation.setRecording(true);
            int bars = 0;
            String allDataLoaded = "";
            String callException = "";
            try {
                if ("readbars".equals(pass)) {
                    ReadBarsResult result = readBars(history, day);
                    bars = result.bars;
                    allDataLoaded = result.finished
                        ? Boolean.toString(result.allDataLoaded)
                        : "";
                    if (!result.finished) {
                        callException = "ReadBarsTimeout";
                    }
                } else {
                    List<IBar> loaded = history.getBars(
                        INSTRUMENT,
                        PERIOD,
                        OfferSide.BID,
                        Filter.NO_FILTER,
                        day.startMillis(),
                        day.endMillis()
                    );
                    bars = loaded == null ? -1 : loaded.size();
                }
            } catch (Exception error) {
                callException = error.getClass().getSimpleName();
            } finally {
                observation.setRecording(false);
            }
            List<HttpAttempt> attempts = observation.drain();
            if (attempts.isEmpty()) {
                writeRow(
                    writer, day, pass, "", "", callException, "", bars, allDataLoaded
                );
            } else {
                for (int i = 0; i < attempts.size(); i++) {
                    HttpAttempt attempt = attempts.get(i);
                    String code = attempt.responseCode < 0
                        ? ""
                        : Integer.toString(attempt.responseCode);
                    String exception = attempt.exceptionClass;
                    if (exception.length() == 0 && i == attempts.size() - 1) {
                        exception = callException;
                    }
                    writeRow(
                        writer,
                        day,
                        pass,
                        Integer.toString(i + 1),
                        code,
                        exception,
                        Long.toString(attempt.bytes),
                        bars,
                        allDataLoaded
                    );
                }
            }
            writer.flush();
            System.out.println(
                "date=" + day.date
                + " pass=" + pass
                + " attempts=" + attempts.size()
                + " bars=" + bars
                + " allDataLoaded=" + allDataLoaded
            );
        }

        private static void writeRow(
                BufferedWriter writer,
                ProbeDay day,
                String pass,
                String attempt,
                String code,
                String exceptionClass,
                String bytes,
                int bars,
                String allDataLoaded) throws IOException {
            writer.write(day.date);
            writer.write(',');
            writer.write(day.startText);
            writer.write(',');
            writer.write(day.endText);
            writer.write(',');
            writer.write(pass);
            writer.write(',');
            writer.write(attempt);
            writer.write(',');
            writer.write(code);
            writer.write(',');
            writer.write(exceptionClass);
            writer.write(',');
            writer.write(bytes);
            writer.write(',');
            writer.write(Integer.toString(bars));
            writer.write(',');
            writer.write(allDataLoaded);
            writer.newLine();
        }

        private ReadBarsResult readBars(IHistory history, final ProbeDay day)
                throws JFException, InterruptedException {
            final int[] bars = new int[] {0};
            final boolean[] allDataLoaded = new boolean[] {false};
            final boolean[] finishedFlag = new boolean[] {false};
            final CountDownLatch done = new CountDownLatch(1);
            history.readBars(
                INSTRUMENT,
                PERIOD,
                OfferSide.BID,
                Filter.NO_FILTER,
                day.startMillis(),
                day.endMillis(),
                new LoadingDataListener() {
                    public void newTick(
                            Instrument instrument,
                            long time,
                            double ask,
                            double bid,
                            double askVolume,
                            double bidVolume) {}

                    public void newBar(
                            Instrument instrument,
                            Period period,
                            OfferSide side,
                            long time,
                            double open,
                            double close,
                            double low,
                            double high,
                            double volume) {
                        bars[0]++;
                    }
                },
                new LoadingProgressListener() {
                    public void dataLoaded(
                            long start,
                            long end,
                            long currentPosition,
                            String information) {}

                    public void loadingFinished(
                            boolean loaded,
                            long start,
                            long end,
                            long currentPosition) {
                        allDataLoaded[0] = loaded;
                        finishedFlag[0] = true;
                        done.countDown();
                    }

                    public boolean stopJob() {
                        return false;
                    }
                }
            );
            done.await(180, TimeUnit.SECONDS);
            ReadBarsResult result = new ReadBarsResult();
            result.bars = bars[0];
            result.allDataLoaded = allDataLoaded[0];
            result.finished = finishedFlag[0];
            return result;
        }

        public void onTick(Instrument instrument, ITick tick) {}

        public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) {}

        public void onMessage(IMessage message) {}

        public void onAccount(com.dukascopy.api.IAccount account) {}

        public void onStop() {}
    }

    private static final class ReadBarsResult {
        private int bars;
        private boolean allDataLoaded;
        private boolean finished;
    }
}
