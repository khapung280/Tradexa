package com.tradexa.dukascopy.diagnostic;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class RecoveryTransportLogTest {
    @Test
    public void httpOutcomesStaySeparatedFromSecrets() throws Exception {
        RecoveryTransportLog log = RecoveryTransportLog.openForTests();
        log.beginDate("2023-11-18");
        log.observe(response(200, new byte[0], "0", "", null));
        log.endDate("2023-11-18");

        log.beginDate("2026-11-15");
        log.observe(response(200, new byte[] {1, 2, 3, 4}, "4", "", null));
        log.observe(failure(
            503,
            "30",
            "1888; Cookie=secret-cookie",
            new IOException("HTTP 503 for URL: https://secret.example/chunk")
        ));
        log.endDate("2026-11-15");

        Path directory = Files.createTempDirectory("recovery-transport-log");
        try {
            log.write(directory);
            String text = new String(
                Files.readAllBytes(directory.resolve(RecoveryTransportLog.REPORT_NAME)),
                StandardCharsets.UTF_8
            );
            Assert.assertTrue(text.contains("\"2023-11-18\",\"1\",\"true\",\"200\",\"0\",\"0\""));
            Assert.assertTrue(text.contains("\"2026-11-15\",\"1\",\"true\",\"200\",\"4\",\"4\""));
            Assert.assertTrue(text.contains("\"2026-11-15\",\"2\",\"true\",\"503\""));
            Assert.assertTrue(text.contains("\"30\""));
            Assert.assertTrue(text.contains("\"http-503\""));
            Assert.assertTrue(text.contains("\"invalid\""));
            Assert.assertFalse(text.contains("secret-cookie"));
            Assert.assertFalse(text.contains("secret.example"));
            Assert.assertFalse(text.toLowerCase().contains("https://"));
            Assert.assertFalse(text.toLowerCase().contains("authorization"));
            Assert.assertFalse(text.toLowerCase().contains("cookie"));
            Assert.assertFalse(log.isRecording());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void timeoutResetAndMissingResponseUseFixedCategories() throws Exception {
        RecoveryTransportLog log = RecoveryTransportLog.openForTests();
        log.beginDate("2023-11-18");
        log.observe(thrown(new ConnectException(
            "Connection timed out: getsockopt https://secret.example/a"
        )));
        log.endDate("2023-11-18");

        log.beginDate("2026-11-15");
        log.observe(thrown(new SocketException(
            "Connection reset https://secret.example/b"
        )));
        log.endDate("2026-11-15");

        log.beginDate("2023-11-19");
        log.endDate("2023-11-19");

        String text = log.reportText();
        Assert.assertTrue(text.contains("\"ConnectException\",\"connect-timeout\""));
        Assert.assertTrue(text.contains("\"SocketException\",\"connection-reset\""));
        Assert.assertTrue(text.contains(
            "\"2023-11-19\",\"\",\"false\",\"\",\"0\",\"\",\"\",\"\",\"missing-http-response\""
        ));
        Assert.assertFalse(text.contains("secret.example"));
        Assert.assertFalse(text.contains("https://"));
        Assert.assertEquals(
            "connect-timeout",
            RecoveryTransportLog.exceptionCategory(
                -1,
                "ConnectException",
                "Connection timed out: getsockopt [url]"
            )
        );
        Assert.assertEquals(
            "connection-reset",
            RecoveryTransportLog.exceptionCategory(-1, "SocketException", "Connection reset")
        );
        Assert.assertEquals(
            "missing-http-response",
            RecoveryTransportLog.exceptionCategory(-1, "", "")
        );
        Assert.assertEquals(
            "",
            RecoveryTransportLog.exceptionCategory(200, "", "")
        );
    }

    @Test
    public void headerValuesThatAreNotAllowlistedAreDiscarded() throws Exception {
        RecoveryTransportLog log = RecoveryTransportLog.openForTests();
        log.beginDate("2023-11-18");
        log.observe(response(
            200,
            new byte[] {9},
            "12 https://secret.example/len",
            "https://secret.example/retry",
            null
        ));
        log.endDate("2023-11-18");
        String text = log.reportText();
        Assert.assertTrue(text.contains("\"invalid\",\"invalid\""));
        Assert.assertFalse(text.contains("secret.example"));
        Assert.assertFalse(text.contains("https://"));
        Assert.assertEquals("", AttemptRecorder.sanitizeContentLength(null));
        Assert.assertEquals("0", AttemptRecorder.sanitizeContentLength("0"));
        Assert.assertEquals(
            "Sun, 19 Nov 2023 00:00:00 GMT",
            AttemptRecorder.sanitizeRetryAfter("Sun, 19 Nov 2023 00:00:00 GMT")
        );
    }

    @Test
    public void cleanupDropsRecordingAndDoesNotAttachALaterConnection() throws Exception {
        RecoveryTransportLog log = RecoveryTransportLog.openForTests();
        log.beginDate("2023-11-18");
        log.observe(response(200, new byte[] {1}, "1", "", null));
        log.cleanup();
        Assert.assertFalse(log.isRecording());
        log.observe(response(
            503,
            new byte[0],
            "0",
            "15",
            new IOException("https://secret.example/late")
        ));
        log.beginDate("2026-11-15");
        log.endDate("2026-11-15");
        String text = log.reportText();
        Assert.assertTrue(text.contains("\"2023-11-18\""));
        Assert.assertTrue(text.contains("\"200\""));
        Assert.assertTrue(text.contains("\"missing-http-response\""));
        Assert.assertFalse(text.contains("\"503\""));
        Assert.assertFalse(text.contains("secret.example"));
        Assert.assertFalse(log.isRecording());
    }

    @Test
    public void anExistingFactoryIsRejectedWithoutUsingItsMessage() {
        Assert.assertTrue(RecoveryTransportLog.isFactoryAlreadyDefined(
            new Error("factory already defined")
        ));
        Assert.assertFalse(RecoveryTransportLog.isFactoryAlreadyDefined(
            new Error("https://secret.example/factory")
        ));
        Assert.assertFalse(RecoveryTransportLog.isFactoryAlreadyDefined(null));
        Assert.assertEquals(
            "HTTP observer already installed",
            RecoveryTransportLog.ALREADY_INSTALLED
        );
        Assert.assertFalse(RecoveryTransportLog.ALREADY_INSTALLED.contains("http"));
    }

    private static FakeConnection response(
            int code,
            byte[] body,
            String contentLength,
            String retryAfter,
            IOException streamFailure) throws Exception {
        FakeConnection connection = new FakeConnection();
        connection.code = code;
        connection.body = body;
        connection.contentLength = contentLength;
        connection.retryAfter = retryAfter;
        connection.streamFailure = streamFailure;
        connection.headers.put("Authorization", "Bearer secret-token");
        connection.headers.put("Cookie", "session=secret-cookie");
        connection.headers.put("Set-Cookie", "sid=secret-set");
        return connection;
    }

    private static FakeConnection failure(
            int code,
            String retryAfter,
            String contentLength,
            IOException streamFailure) throws Exception {
        return response(code, new byte[0], contentLength, retryAfter, streamFailure);
    }

    private static FakeConnection thrown(IOException failure) throws Exception {
        FakeConnection connection = new FakeConnection();
        connection.responseFailure = failure;
        connection.headers.put("Authorization", "Bearer secret-token");
        return connection;
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

    private static final class FakeConnection extends HttpURLConnection {
        private final java.util.LinkedHashMap<String, String> headers =
            new java.util.LinkedHashMap<String, String>();
        private final List<String> requested = new ArrayList<String>();
        private int code = 200;
        private byte[] body = new byte[0];
        private String contentLength = "";
        private String retryAfter = "";
        private IOException responseFailure;
        private IOException streamFailure;

        private FakeConnection() throws Exception {
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
            requested.add(name);
            if ("Content-Length".equals(name)) {
                return contentLength;
            }
            if ("Retry-After".equals(name)) {
                return retryAfter;
            }
            return headers.get(name);
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
}
