package com.tradexa.dukascopy.diagnostic;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Sanitized HTTP log for the native timeframe download.
 * Adds the timeframe column. Does not store URLs, credentials,
 * cookies, authorization, or response bodies.
 */
public final class NativeTransportLog {
    public static final String REPORT_NAME = "transport-report.csv";

    private final HttpObservation observation;
    private final List<String> rows = new ArrayList<String>();
    private String recordingDate;
    private String recordingTimeframe;
    private boolean written;

    private NativeTransportLog(HttpObservation observation) {
        this.observation = observation;
    }

    public static NativeTransportLog install() throws IOException {
        HttpObservation observation = new HttpObservation();
        try {
            ObservingURLStreamHandlerFactory.install(observation);
        } catch (Error error) {
            if (RecoveryTransportLog.isFactoryAlreadyDefined(error)) {
                throw new IOException(RecoveryTransportLog.ALREADY_INSTALLED);
            }
            throw error;
        }
        return new NativeTransportLog(observation);
    }

    public static NativeTransportLog openForTests() {
        return new NativeTransportLog(new HttpObservation());
    }

    public void begin(String timeframe, String date) {
        observation.setRecording(false);
        observation.drain();
        recordingTimeframe = safeTimeframe(timeframe);
        recordingDate = date;
        observation.setRecording(true);
    }

    public void end(String timeframe, String date) {
        observation.setRecording(false);
        String labeledDate = date == null ? recordingDate : date;
        String labeledTimeframe = timeframe == null ? recordingTimeframe : safeTimeframe(timeframe);
        recordingDate = null;
        recordingTimeframe = null;
        List<HttpAttempt> attempts = observation.drain();
        if (labeledDate == null) {
            return;
        }
        if (attempts.isEmpty()) {
            rows.add(row(labeledDate, labeledTimeframe, "", false, "", "0", "", "", "", "missing-http-response"));
            return;
        }
        for (int i = 0; i < attempts.size(); i++) {
            HttpAttempt attempt = attempts.get(i);
            String code = attempt.responseCode < 0 ? "" : Integer.toString(attempt.responseCode);
            rows.add(row(
                labeledDate,
                labeledTimeframe,
                Integer.toString(i + 1),
                true,
                code,
                Long.toString(attempt.bytes),
                AttemptRecorder.sanitizeContentLength(attempt.contentLength),
                AttemptRecorder.sanitizeRetryAfter(attempt.retryAfter),
                safeExceptionClass(attempt.exceptionClass),
                category(attempt.responseCode, attempt.exceptionClass, attempt.exceptionMessage)
            ));
        }
    }

    public void cleanup() {
        if (recordingDate != null) {
            end(recordingTimeframe, recordingDate);
        }
        observation.setRecording(false);
        observation.drain();
    }

    public void observe(HttpURLConnection connection) throws IOException {
        AttemptRecorder recorder = new AttemptRecorder(connection, observation);
        try {
            recorder.responseCode();
        } catch (IOException error) {
            return;
        }
        InputStream input = null;
        try {
            input = recorder.input();
            if (input != null) {
                byte[] buffer = new byte[4096];
                while (input.read(buffer) >= 0) {
                    // Counted and discarded.
                }
            }
        } catch (IOException error) {
            return;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException error) {
                    recorder.note(error);
                }
            }
        }
    }

    public void write(Path report) throws IOException {
        Path parent = report.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        BufferedWriter writer = Files.newBufferedWriter(report, StandardCharsets.UTF_8);
        try {
            writer.write(
                "date,timeframe,attempt,http_opened,response_code,bytes,content_length,"
                + "retry_after,exception_class,exception_category\n"
            );
            for (int i = 0; i < rows.size(); i++) {
                writer.write(rows.get(i));
                writer.write('\n');
            }
        } finally {
            writer.close();
        }
        written = true;
    }

    public void writeIfAbsent(Path report) throws IOException {
        if (!written) {
            write(report);
        }
    }

    public String reportText() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            text.append(rows.get(i));
            text.append('\n');
        }
        return text.toString();
    }

    static String category(int responseCode, String exceptionClass, String exceptionMessage) {
        String type = exceptionClass == null ? "" : exceptionClass;
        String message = exceptionMessage == null ? "" : exceptionMessage.toLowerCase();
        if (responseCode == 503) {
            return "http-503";
        }
        if (responseCode == 429) {
            return "http-429";
        }
        if (responseCode >= 400) {
            return "http-error";
        }
        if ("ConnectException".equals(type) && message.contains("timed out")) {
            return "connect-timeout";
        }
        if ("SocketException".equals(type) && message.contains("connection reset")) {
            return "connection-reset";
        }
        if (responseCode < 0) {
            return "missing-http-response";
        }
        if (type.length() > 0) {
            return "io-error";
        }
        return "";
    }

    private static String row(
            String date,
            String timeframe,
            String attempt,
            boolean httpOpened,
            String code,
            String bytes,
            String contentLength,
            String retryAfter,
            String exceptionClass,
            String category) {
        return csv(safeDate(date)) + ","
            + csv(timeframe) + ","
            + csv(attempt) + ","
            + csv(Boolean.toString(httpOpened)) + ","
            + csv(code) + ","
            + csv(bytes) + ","
            + csv(contentLength) + ","
            + csv(retryAfter) + ","
            + csv(exceptionClass) + ","
            + csv(category);
    }

    private static String safeDate(String date) {
        if (date != null && date.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return date;
        }
        return "invalid";
    }

    private static String safeTimeframe(String timeframe) {
        if (timeframe != null && timeframe.matches("M5|M15|M30|H1|H4|D1")) {
            return timeframe;
        }
        return "invalid";
    }

    private static String safeExceptionClass(String value) {
        if (value == null || value.length() == 0) {
            return "";
        }
        if (value.matches("[A-Za-z_][A-Za-z0-9_]{0,79}")) {
            return value;
        }
        return "invalid";
    }

    private static String csv(String value) {
        String text = value == null ? "" : value;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
