package com.tradexa.dukascopy.diagnostic;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.junit.Assert;
import org.junit.Test;

public class NativeTransportLogTest {
    @Test
    public void reportKeepsStatusAndDropsSecrets() throws Exception {
        NativeTransportLog log = NativeTransportLog.openForTests();
        log.begin("M5", "2024-01-17");
        log.observe(connection(429, new byte[] {9, 9}, "3", "120"));
        log.end("M5", "2024-01-17");
        log.begin("H1", "2024-01-18");
        log.observe(connection(200, new byte[0], "0", null));
        log.end("H1", "2024-01-18");
        String text = log.reportText();
        Assert.assertTrue(text.contains("\"429\""));
        Assert.assertTrue(text.contains("\"http-429\""));
        Assert.assertTrue(text.contains("\"M5\""));
        Assert.assertTrue(text.contains("\"0\""));
        Assert.assertFalse(text.contains("secret"));
        Assert.assertFalse(text.contains("http://"));
        Assert.assertFalse(text.contains("Authorization"));
        Path directory = Files.createTempDirectory("native-transport");
        try {
            Path report = directory.resolve("transport-report.csv");
            log.write(report);
            String file = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
            Assert.assertTrue(file.startsWith(
                "date,timeframe,attempt,http_opened,response_code,bytes,content_length,"
                + "retry_after,exception_class,exception_category\n"
            ));
            Assert.assertFalse(file.contains("\r"));
        } finally {
            Files.deleteIfExists(directory.resolve("transport-report.csv"));
            Files.deleteIfExists(directory);
        }
    }

    private static HttpURLConnection connection(
            final int code,
            final byte[] body,
            final String contentLength,
            final String retryAfter) throws Exception {
        return new HttpURLConnection(new URL("http://127.0.0.1/unused")) {
            private final LinkedHashMap<String, String> headers = new LinkedHashMap<String, String>();
            {
                headers.put("Authorization", "Bearer secret-token");
                headers.put("Cookie", "session=secret-cookie");
            }

            public void connect() {
            }

            public void disconnect() {
            }

            public boolean usingProxy() {
                return false;
            }

            public int getResponseCode() {
                return code;
            }

            public String getHeaderField(String name) {
                if ("Content-Length".equals(name)) {
                    return contentLength;
                }
                if ("Retry-After".equals(name)) {
                    return retryAfter;
                }
                return headers.get(name);
            }

            public InputStream getInputStream() throws IOException {
                return new ByteArrayInputStream(body);
            }
        };
    }
}
