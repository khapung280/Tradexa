package com.tradexa.dukascopy.diagnostic;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class SafeHeaderCaptureTest {
    @Test
    public void permittedHeadersAreStoredAndSensitiveHeadersAreNotRead() throws Exception {
        HeaderProbeConnection connection = new HeaderProbeConnection();
        connection.code = 503;
        connection.headers.put("Content-Length", "1888");
        connection.headers.put("Retry-After", "120");
        connection.headers.put("Authorization", "Bearer secret-token");
        connection.headers.put("Cookie", "session=secret-cookie");
        connection.headers.put("Set-Cookie", "sid=secret-set");
        HttpObservation observation = new HttpObservation();
        observation.setRecording(true);
        AttemptRecorder recorder = new AttemptRecorder(connection, observation);

        Assert.assertEquals(503, recorder.responseCode());

        List<HttpAttempt> attempts = observation.drain();
        Assert.assertEquals(1, attempts.size());
        HttpAttempt attempt = attempts.get(0);
        Assert.assertEquals(503, attempt.responseCode);
        Assert.assertEquals("1888", attempt.contentLength);
        Assert.assertEquals("120", attempt.retryAfter);
        Assert.assertEquals("", attempt.exceptionMessage);
        Assert.assertEquals(
            java.util.Arrays.asList("Content-Length", "Retry-After"),
            connection.requested
        );
        Assert.assertEquals(0, connection.headerFieldMapCalls);
        String recorded = allFieldText(attempt);
        Assert.assertFalse(recorded.contains("secret-token"));
        Assert.assertFalse(recorded.contains("secret-cookie"));
        Assert.assertFalse(recorded.contains("secret-set"));
        Assert.assertFalse(recorded.contains("Bearer"));
        Assert.assertFalse(recorded.contains("Authorization"));
        Assert.assertFalse(recorded.contains("Cookie"));
        Assert.assertFalse(recorded.toLowerCase().contains("http://"));
        Assert.assertFalse(recorded.toLowerCase().contains("https://"));
    }

    @Test
    public void headersAreNotReadBeforeRecordingOrAfterATransportFailure() throws Exception {
        HeaderProbeConnection idle = new HeaderProbeConnection();
        idle.headers.put("Retry-After", "30");
        idle.headers.put("Cookie", "session=secret-cookie");
        HttpObservation observation = new HttpObservation();
        observation.setRecording(false);
        AttemptRecorder recorder = new AttemptRecorder(idle, observation);
        Assert.assertEquals(200, recorder.responseCode());
        Assert.assertTrue(idle.requested.isEmpty());
        Assert.assertTrue(observation.drain().isEmpty());

        HeaderProbeConnection failed = new HeaderProbeConnection();
        failed.failure = new IOException(
            "Connection reset https://secret.example/chunk"
        );
        failed.headers.put("Authorization", "Bearer secret-token");
        HttpObservation failing = new HttpObservation();
        failing.setRecording(true);
        AttemptRecorder failingRecorder = new AttemptRecorder(failed, failing);
        try {
            failingRecorder.responseCode();
            Assert.fail("transport failure should surface");
        } catch (IOException expected) {
            Assert.assertEquals("IOException", expected.getClass().getSimpleName());
        }
        Assert.assertTrue(failed.requested.isEmpty());
        Assert.assertEquals(0, failed.headerFieldMapCalls);
        HttpAttempt attempt = failing.drain().get(0);
        Assert.assertEquals(-1, attempt.responseCode);
        Assert.assertEquals("", attempt.contentLength);
        Assert.assertEquals("", attempt.retryAfter);
        Assert.assertEquals("IOException", attempt.exceptionClass);
        String recorded = allFieldText(attempt);
        Assert.assertTrue(recorded.contains("[url]"));
        Assert.assertFalse(recorded.contains("secret.example"));
        Assert.assertFalse(recorded.contains("secret-cookie"));
        Assert.assertFalse(recorded.contains("secret-token"));
        Assert.assertFalse(recorded.contains("https://"));
    }

    @Test
    public void unsafeHeaderValuesAreDiscarded() throws Exception {
        Assert.assertEquals("", AttemptRecorder.sanitizeContentLength(null));
        Assert.assertEquals("", AttemptRecorder.sanitizeContentLength("  "));
        Assert.assertEquals("0", AttemptRecorder.sanitizeContentLength("0"));
        Assert.assertEquals("1888", AttemptRecorder.sanitizeContentLength(" 1888 "));
        Assert.assertEquals(
            "invalid",
            AttemptRecorder.sanitizeContentLength("1888 http://secret.example/a")
        );
        Assert.assertEquals("", AttemptRecorder.sanitizeRetryAfter(null));
        Assert.assertEquals("30", AttemptRecorder.sanitizeRetryAfter("30"));
        Assert.assertEquals(
            "Sun, 19 Nov 2023 00:00:00 GMT",
            AttemptRecorder.sanitizeRetryAfter("Sun, 19 Nov 2023 00:00:00 GMT")
        );
        String rejected = AttemptRecorder.sanitizeRetryAfter(
            "30; url=https://secret.example/retry; Cookie=secret-cookie"
        );
        Assert.assertEquals("invalid", rejected);
        Assert.assertFalse(rejected.contains("secret"));
        Assert.assertFalse(rejected.contains("http"));

        HeaderProbeConnection connection = new HeaderProbeConnection();
        connection.headers.put("Content-Length", "1888; Cookie=secret-cookie");
        connection.headers.put("Retry-After", "https://secret.example/retry");
        connection.headers.put("Set-Cookie", "sid=secret-set");
        HttpObservation observation = new HttpObservation();
        observation.setRecording(true);
        AttemptRecorder recorder = new AttemptRecorder(connection, observation);
        Assert.assertEquals(200, recorder.responseCode());
        HttpAttempt attempt = observation.drain().get(0);
        Assert.assertEquals("invalid", attempt.contentLength);
        Assert.assertEquals("invalid", attempt.retryAfter);
        String recorded = allFieldText(attempt);
        Assert.assertFalse(recorded.contains("secret-cookie"));
        Assert.assertFalse(recorded.contains("secret-set"));
        Assert.assertFalse(recorded.contains("secret.example"));
        Assert.assertFalse(recorded.contains("https://"));
        Assert.assertEquals(0, connection.headerFieldMapCalls);
    }

    private static String allFieldText(HttpAttempt attempt) throws Exception {
        StringBuilder text = new StringBuilder();
        Field[] fields = HttpAttempt.class.getDeclaredFields();
        for (int i = 0; i < fields.length; i++) {
            fields[i].setAccessible(true);
            text.append(fields[i].getName());
            text.append('=');
            text.append(fields[i].get(attempt));
            text.append('\n');
        }
        return text.toString();
    }

    private static final class HeaderProbeConnection extends HttpURLConnection {
        private final java.util.LinkedHashMap<String, String> headers =
            new java.util.LinkedHashMap<String, String>();
        private final List<String> requested = new ArrayList<String>();
        private int code = 200;
        private int headerFieldMapCalls;
        private IOException failure;

        private HeaderProbeConnection() throws Exception {
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
            if (failure != null) {
                throw failure;
            }
            return code;
        }

        public String getHeaderField(String name) {
            requested.add(name);
            return headers.get(name);
        }

        public Map<String, List<String>> getHeaderFields() {
            headerFieldMapCalls++;
            throw new AssertionError("getHeaderFields must not be called");
        }

        public InputStream getInputStream() {
            return new InputStream() {
                public int read() {
                    return -1;
                }
            };
        }

        public OutputStream getOutputStream() {
            return new OutputStream() {
                public void write(int value) {
                }
            };
        }
    }
}
