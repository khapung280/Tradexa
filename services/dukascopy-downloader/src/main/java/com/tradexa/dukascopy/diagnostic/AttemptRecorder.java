package com.tradexa.dukascopy.diagnostic;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;

/**
 * Forwards response observation onto the real JDK connection.
 * Exception messages are kept only after URL text is removed.
 */
final class AttemptRecorder {
    private final HttpURLConnection delegate;
    private final HttpObservation observation;
    private HttpAttempt attempt;

    AttemptRecorder(HttpURLConnection delegate, HttpObservation observation) {
        this.delegate = delegate;
        this.observation = observation;
    }

    HttpURLConnection delegate() {
        return delegate;
    }

    int responseCode() throws IOException {
        try {
            int code = delegate.getResponseCode();
            HttpAttempt current = touch();
            if (current != null && current.responseCode < 0) {
                current.responseCode = code;
                capturePermittedHeaders(current);
            }
            return code;
        } catch (IOException error) {
            note(error);
            throw error;
        }
    }

    /**
     * Reads only Content-Length and Retry-After, and only while a date
     * request is being recorded. Does not call getHeaderFields().
     */
    private void capturePermittedHeaders(HttpAttempt current) {
        current.contentLength = readPermitted(delegate, "Content-Length", true);
        current.retryAfter = readPermitted(delegate, "Retry-After", false);
    }

    static String readPermitted(
            HttpURLConnection connection,
            String name,
            boolean contentLength) {
        String value;
        try {
            value = connection.getHeaderField(name);
        } catch (RuntimeException error) {
            return "invalid";
        }
        if (contentLength) {
            return sanitizeContentLength(value);
        }
        return sanitizeRetryAfter(value);
    }

    static String sanitizeContentLength(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() == 0) {
            return "";
        }
        if (trimmed.matches("[0-9]{1,15}")) {
            return trimmed;
        }
        return "invalid";
    }

    static String sanitizeRetryAfter(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() == 0) {
            return "";
        }
        if (trimmed.matches("[0-9]{1,10}")) {
            return trimmed;
        }
        if (trimmed.matches(
                "(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun), [0-9]{2} "
                + "(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) "
                + "[0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} GMT")) {
            return trimmed;
        }
        return "invalid";
    }

    void note(IOException error) {
        HttpAttempt current = touch();
        if (current != null && current.exceptionClass.length() == 0) {
            current.exceptionClass = error.getClass().getSimpleName();
            current.exceptionMessage = redact(error.getMessage());
        }
    }

    InputStream input() throws IOException {
        try {
            responseCode();
            InputStream in = delegate.getInputStream();
            return observe(in);
        } catch (IOException error) {
            note(error);
            throw error;
        }
    }

    InputStream errorStream() {
        return observe(delegate.getErrorStream());
    }

    private InputStream observe(InputStream in) {
        HttpAttempt current = touch();
        if (current == null || in == null) {
            return in;
        }
        return new CountingInputStream(in, current);
    }

    static String redact(String message) {
        if (message == null || message.length() == 0) {
            return "";
        }
        return message.replaceAll("(?i)https?://\\S+", "[url]");
    }

    private HttpAttempt touch() {
        if (!observation.isRecording()) {
            return null;
        }
        if (attempt == null) {
            attempt = observation.begin();
        }
        return attempt;
    }
}
