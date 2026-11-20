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
 * Observes HTTP attempts for one explicit recovery run.
 * The report stores status, body size, two allowlisted headers, the
 * exception class, and a fixed exception category. It does not store
 * URLs, request headers, cookies, credentials, or response bodies.
 */
public final class RecoveryTransportLog {
    public static final String REPORT_NAME = "transport-report.csv";
    public static final String ALREADY_INSTALLED = "HTTP observer already installed";
    public static final String INSTALL_FAILED = "HTTP observer installation failed";

    private final HttpObservation observation;
    private final List<String> rows = new ArrayList<String>();
    private String recordingDate;
    private boolean written;

    private RecoveryTransportLog(HttpObservation observation) {
        this.observation = observation;
    }

    public static RecoveryTransportLog installForRecovery() throws IOException {
        HttpObservation observation = new HttpObservation();
        try {
            ObservingURLStreamHandlerFactory.install(observation);
        } catch (Error error) {
            if (isFactoryAlreadyDefined(error)) {
                throw new IOException(ALREADY_INSTALLED);
            }
            throw error;
        }
        return new RecoveryTransportLog(observation);
    }

    public static RecoveryTransportLog openForTests() {
        return new RecoveryTransportLog(new HttpObservation());
    }

    public static boolean isFactoryAlreadyDefined(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message != null && message.contains("factory already defined");
    }

    public void beginDate(String date) {
        observation.setRecording(false);
        observation.drain();
        recordingDate = date;
        observation.setRecording(true);
    }

    public void endDate(String date) {
        observation.setRecording(false);
        String labeled = date == null ? recordingDate : date;
        recordingDate = null;
        List<HttpAttempt> attempts = observation.drain();
        if (labeled == null) {
            return;
        }
        if (attempts.isEmpty()) {
            rows.add(row(labeled, "", false, "", "0", "", "", "", "missing-http-response"));
            return;
        }
        for (int i = 0; i < attempts.size(); i++) {
            HttpAttempt attempt = attempts.get(i);
            String code = attempt.responseCode < 0
                ? ""
                : Integer.toString(attempt.responseCode);
            rows.add(row(
                labeled,
                Integer.toString(i + 1),
                true,
                code,
                Long.toString(attempt.bytes),
                AttemptRecorder.sanitizeContentLength(attempt.contentLength),
                AttemptRecorder.sanitizeRetryAfter(attempt.retryAfter),
                safeExceptionClass(attempt.exceptionClass),
                exceptionCategory(
                    attempt.responseCode,
                    attempt.exceptionClass,
                    attempt.exceptionMessage
                )
            ));
        }
    }

    public void cleanup() {
        if (recordingDate != null) {
            endDate(recordingDate);
        }
        observation.setRecording(false);
        observation.drain();
    }

    public boolean isRecording() {
        return observation.isRecording();
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
                    // Body bytes are counted and discarded.
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

    public void write(Path cacheRoot) throws IOException {
        if (cacheRoot == null) {
            throw new IOException(INSTALL_FAILED);
        }
        Path report = cacheRoot.resolve(REPORT_NAME);
        BufferedWriter writer = Files.newBufferedWriter(report, StandardCharsets.UTF_8);
        try {
            writer.write(
                "date,attempt,http_opened,response_code,bytes,content_length,"
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

    public void writeIfAbsent(Path cacheRoot) throws IOException {
        if (!written) {
            write(cacheRoot);
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

    static String exceptionCategory(
            int responseCode,
            String exceptionClass,
            String exceptionMessage) {
        String type = exceptionClass == null ? "" : exceptionClass;
        String message = exceptionMessage == null
            ? ""
            : exceptionMessage.toLowerCase();
        if (responseCode == 503) {
            return "http-503";
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
            String attempt,
            boolean httpOpened,
            String code,
            String bytes,
            String contentLength,
            String retryAfter,
            String exceptionClass,
            String category) {
        return csv(safeDate(date)) + ","
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
