package com.tradexa.dukascopy.diagnostic;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class ObservingHttpConnectionTest {
    private static HttpObservation observation;

    @BeforeClass
    public static void installFactory() {
        observation = new HttpObservation();
        ObservingURLStreamHandlerFactory.install(observation);
    }

    @Test
    public void httpWrapperOverridesPublicInstanceMethods() {
        assertOverrides(
            DelegatingHttpURLConnection.class,
            HttpURLConnection.class
        );
    }

    @Test
    public void httpsWrapperOverridesPublicInstanceMethods() {
        assertOverrides(
            DelegatingHttpsURLConnection.class,
            HttpsURLConnection.class
        );
    }

    @Test
    public void factoryLeavesNonHttpProtocolsToTheJdk() throws Exception {
        ObservingURLStreamHandlerFactory factory =
            new ObservingURLStreamHandlerFactory(new HttpObservation());
        Assert.assertNull(factory.createURLStreamHandler("file"));
        Assert.assertNull(factory.createURLStreamHandler("jar"));
        Assert.assertNotNull(factory.createURLStreamHandler("http"));
        Assert.assertNotNull(factory.createURLStreamHandler("https"));

        Path file = Files.createTempFile("probe-file", ".txt");
        Files.write(file, "file-body".getBytes(StandardCharsets.UTF_8));
        URL url = file.toUri().toURL();
        try (InputStream in = url.openStream()) {
            Assert.assertEquals("file-body", readString(in));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void wrapperPassesThroughMethodHeadersBodyStatusAndBytes() throws Exception {
        try (ScriptedServer server = new ScriptedServer()) {
            server.enqueue("200 OK", "hello");
            observation.drain();
            observation.setRecording(true);
            HttpURLConnection connection = open(server, "/body");
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("X-Probe", "keep");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            Assert.assertEquals(5000, connection.getConnectTimeout());
            Assert.assertEquals(5000, connection.getReadTimeout());
            OutputStream out = connection.getOutputStream();
            out.write("abc".getBytes(StandardCharsets.UTF_8));
            out.close();
            Assert.assertEquals(200, connection.getResponseCode());
            Assert.assertEquals("hello", readString(connection.getInputStream()));
            observation.setRecording(false);
            Assert.assertEquals("POST", server.method);
            Assert.assertEquals("keep", server.header);
            Assert.assertEquals("abc", server.body);
            List<HttpAttempt> attempts = observation.drain();
            Assert.assertFalse(attempts.isEmpty());
            HttpAttempt success = null;
            for (int i = 0; i < attempts.size(); i++) {
                HttpAttempt attempt = attempts.get(i);
                Assert.assertFalse(attempt.exceptionClass.contains("http"));
                Assert.assertFalse(attempt.exceptionClass.contains("127.0.0.1"));
                if (attempt.responseCode == 200 && attempt.bytes == 5L) {
                    success = attempt;
                }
            }
            Assert.assertNotNull(success);
            Assert.assertEquals("", success.exceptionClass);
        }
    }

    @Test
    public void wrapperPreservesNotFoundAndUnavailableExceptions() throws Exception {
        try (ScriptedServer server = new ScriptedServer()) {
            server.enqueue("404 Not Found", "");
            observation.drain();
            observation.setRecording(true);
            HttpURLConnection missing = open(server, "/missing");
            Assert.assertEquals(404, missing.getResponseCode());
            try {
                missing.getInputStream();
                Assert.fail("404 input stream should fail");
            } catch (FileNotFoundException expected) {
                Assert.assertEquals(
                    "FileNotFoundException",
                    expected.getClass().getSimpleName()
                );
            }
            List<HttpAttempt> missingAttempts = observation.drain();
            Assert.assertEquals(404, missingAttempts.get(0).responseCode);
            Assert.assertEquals(
                "FileNotFoundException",
                missingAttempts.get(0).exceptionClass
            );

            server.enqueue("503 Service Unavailable", "");
            HttpURLConnection unavailable = open(server, "/unavailable");
            Assert.assertEquals(503, unavailable.getResponseCode());
            try {
                unavailable.getInputStream();
                Assert.fail("503 input stream should fail");
            } catch (IOException expected) {
                Assert.assertFalse(expected instanceof FileNotFoundException);
            }
            observation.setRecording(false);
            List<HttpAttempt> unavailableAttempts = observation.drain();
            Assert.assertEquals(503, unavailableAttempts.get(0).responseCode);
            Assert.assertEquals(
                "IOException",
                unavailableAttempts.get(0).exceptionClass
            );
        }
    }

    @Test
    public void wrapperFollowsSameProtocolRedirect() throws Exception {
        try (ScriptedServer server = new ScriptedServer()) {
            server.enqueue(
                "302 Found",
                "",
                "Location: http://127.0.0.1:" + server.port + "/next\r\n"
            );
            server.enqueue("200 OK", "done");
            observation.drain();
            observation.setRecording(true);
            HttpURLConnection connection = open(server, "/old");
            connection.setInstanceFollowRedirects(true);
            Assert.assertEquals(200, connection.getResponseCode());
            Assert.assertEquals("done", readString(connection.getInputStream()));
            observation.setRecording(false);
            List<HttpAttempt> attempts = observation.drain();
            Assert.assertFalse(attempts.isEmpty());
            Assert.assertEquals(200, attempts.get(attempts.size() - 1).responseCode);
            Assert.assertEquals(4L, attempts.get(attempts.size() - 1).bytes);
        }
    }

    @Test
    public void wrapperRecordsOnlyContentLengthAndRetryAfter() throws Exception {
        try (ScriptedServer server = new ScriptedServer()) {
            server.enqueue(
                "503 Service Unavailable",
                "hidden-body",
                "Retry-After: 45\r\n"
                    + "Authorization: Bearer secret-token\r\n"
                    + "Cookie: session=secret-cookie\r\n"
                    + "Set-Cookie: sid=secret-set\r\n"
            );
            observation.drain();
            observation.setRecording(true);
            HttpURLConnection connection = open(server, "/limited");
            Assert.assertEquals(503, connection.getResponseCode());
            observation.setRecording(false);
            List<HttpAttempt> attempts = observation.drain();
            Assert.assertFalse(attempts.isEmpty());
            HttpAttempt attempt = attempts.get(0);
            Assert.assertEquals(503, attempt.responseCode);
            Assert.assertEquals("11", attempt.contentLength);
            Assert.assertEquals("45", attempt.retryAfter);
            String recorded = attempt.responseCode
                + attempt.exceptionClass
                + attempt.exceptionMessage
                + attempt.contentLength
                + attempt.retryAfter;
            Assert.assertFalse(recorded.contains("secret-token"));
            Assert.assertFalse(recorded.contains("secret-cookie"));
            Assert.assertFalse(recorded.contains("secret-set"));
            Assert.assertFalse(recorded.contains("hidden-body"));
            Assert.assertFalse(recorded.contains("Bearer"));
            Assert.assertFalse(recorded.toLowerCase().contains("authorization"));
            Assert.assertFalse(recorded.toLowerCase().contains("cookie"));
        }
    }

    @Test
    public void urlFactoryUsesTheObservingHandler() throws Exception {
        try (ScriptedServer server = new ScriptedServer()) {
            server.enqueue("200 OK", "via-url");
            URL url = new URL("http://127.0.0.1:" + server.port + "/wrapped");
            URLConnection connection = url.openConnection();
            Assert.assertTrue(connection instanceof DelegatingHttpURLConnection);
            HttpURLConnection http = (HttpURLConnection) connection;
            Assert.assertEquals(200, http.getResponseCode());
            Assert.assertEquals("via-url", readString(http.getInputStream()));
        }
    }

    @Test
    public void probeDatesMatchTheProductionRanges() {
        List<HistoryStatusProbe.ProbeDay> days = HistoryStatusProbe.days();
        Assert.assertEquals(32, days.size());
        Assert.assertEquals("2023-11-16 06:41:00", days.get(0).startText);
        Assert.assertEquals("2023-11-16 23:59:00", days.get(0).endText);
        Assert.assertEquals("2026-11-16 00:00:00", days.get(31).startText);
        Assert.assertEquals("2026-11-16 06:41:00", days.get(31).endText);
        Assert.assertEquals("2024-05-21 00:00:00", days.get(7).startText);
        Assert.assertEquals("2024-05-21 23:59:00", days.get(7).endText);
        Assert.assertEquals(86340000L, days.get(7).endMillis() - days.get(7).startMillis());
        Assert.assertTrue(days.get(0).endMillis() > days.get(0).startMillis());
        Assert.assertTrue(days.get(31).endMillis() > days.get(31).startMillis());
    }

    @Test
    public void cacheIsolationRejectsPathsOutsideTheDiagnosticRoot() throws Exception {
        Path root = Files.createTempDirectory("eurusd-diagnostic");
        Path cache = root.resolve("cache-getbars");
        String resolved = DiagnosticCache.isolate(root, cache);
        Assert.assertNotNull(resolved);
        Assert.assertTrue(resolved.contains("cache-getbars"));
        try {
            DiagnosticCache.isolate(root, root.getParent());
            Assert.fail("parent cache should be rejected");
        } catch (IOException expected) {
            Assert.assertNotNull(expected);
        }
    }

    private static HttpURLConnection open(ScriptedServer server, String path)
            throws IOException {
        ObservingHttpHandler handler = new ObservingHttpHandler(observation);
        URL url = new URL("http://127.0.0.1:" + server.port + path);
        return (HttpURLConnection) handler.openConnection(url);
    }

    private static void assertOverrides(Class<?> wrapper, Class<?> api) {
        for (Method method : api.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            Method found;
            try {
                found = wrapper.getMethod(method.getName(), method.getParameterTypes());
            } catch (NoSuchMethodException error) {
                Assert.fail("Missing " + method);
                return;
            }
            Assert.assertEquals(method.toString(), wrapper, found.getDeclaringClass());
        }
    }

    private static String readString(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64];
        int count;
        while ((count = in.read(buffer)) >= 0) {
            out.write(buffer, 0, count);
        }
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static final class ScriptedServer implements AutoCloseable {
        private final ServerSocket socket;
        private final int port;
        private final List<Script> scripts = new ArrayList<Script>();
        private volatile String method = "";
        private volatile String header = "";
        private volatile String body = "";
        private int served;

        private ScriptedServer() throws IOException {
            socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            port = socket.getLocalPort();
            Thread thread = new Thread(new Runnable() {
                public void run() {
                    serve();
                }
            }, "probe-test-http");
            thread.setDaemon(true);
            thread.start();
        }

        private void enqueue(String status, String responseBody) {
            enqueue(status, responseBody, "");
        }

        private void enqueue(String status, String responseBody, String extraHeaders) {
            Script script = new Script();
            script.status = status;
            script.body = responseBody.getBytes(StandardCharsets.UTF_8);
            script.extraHeaders = extraHeaders;
            scripts.add(script);
        }

        private void serve() {
            while (!socket.isClosed()) {
                try {
                    Socket client = socket.accept();
                    handle(client);
                } catch (IOException error) {
                    return;
                }
            }
        }

        private void handle(Socket client) throws IOException {
            client.setSoTimeout(5000);
            InputStream in = client.getInputStream();
            ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
            int previous = -1;
            while (true) {
                int value = in.read();
                if (value < 0) {
                    break;
                }
                headerBytes.write(value);
                if (previous == '\n' && value == '\r') {
                    int next = in.read();
                    headerBytes.write(next);
                    if (next == '\n') {
                        break;
                    }
                    previous = next;
                    continue;
                }
                previous = value;
            }
            String head = new String(headerBytes.toByteArray(), StandardCharsets.US_ASCII);
            String[] lines = head.split("\r\n");
            if (lines.length > 0) {
                String[] parts = lines[0].split(" ");
                method = parts.length > 0 ? parts[0] : "";
            }
            int contentLength = 0;
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.regionMatches(true, 0, "X-Probe:", 0, 8)) {
                    header = line.substring(8).trim();
                }
                if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }
            byte[] requestBody = new byte[contentLength];
            int offset = 0;
            while (offset < contentLength) {
                int count = in.read(requestBody, offset, contentLength - offset);
                if (count < 0) {
                    break;
                }
                offset += count;
            }
            body = new String(requestBody, StandardCharsets.UTF_8);
            Script script = scripts.get(Math.min(served, scripts.size() - 1));
            served++;
            byte[] payload = script.body;
            String response = "HTTP/1.1 " + script.status + "\r\n"
                + script.extraHeaders
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n\r\n";
            OutputStream out = client.getOutputStream();
            out.write(response.getBytes(StandardCharsets.US_ASCII));
            out.write(payload);
            out.flush();
            client.close();
        }

        public void close() throws IOException {
            socket.close();
        }
    }

    private static final class Script {
        private String status;
        private byte[] body;
        private String extraHeaders;
    }
}
