package com.tradexa.dukascopy.nativehist;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic candle CSV: UTF-8, no BOM, LF, stable header.
 */
public final class NativeCsv {
    public static final String HEADER = "time_utc,open,high,low,close,volume";
    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private NativeCsv() {
    }

    public static byte[] bytes(List<NativeCandle> candles) {
        StringBuilder text = new StringBuilder();
        text.append(HEADER).append('\n');
        for (int i = 0; i < candles.size(); i++) {
            NativeCandle candle = candles.get(i);
            text.append(STAMP.format(Instant.ofEpochMilli(candle.timeMillis)));
            text.append(',');
            text.append(Double.toString(candle.open));
            text.append(',');
            text.append(Double.toString(candle.high));
            text.append(',');
            text.append(Double.toString(candle.low));
            text.append(',');
            text.append(Double.toString(candle.close));
            text.append(',');
            text.append(Double.toString(candle.volume));
            text.append('\n');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static List<NativeCandle> parse(byte[] payload) throws IOException {
        String text = new String(payload, StandardCharsets.UTF_8);
        if (text.indexOf('\r') >= 0 || text.startsWith("\uFEFF")) {
            throw new IOException("Candle CSV is not LF UTF-8");
        }
        String[] lines = text.split("\n", -1);
        if (lines.length < 2 || lines[lines.length - 1].length() != 0) {
            throw new IOException("Candle CSV is incomplete");
        }
        if (!HEADER.equals(lines[0])) {
            throw new IOException("Candle CSV header is wrong");
        }
        List<NativeCandle> candles = new ArrayList<NativeCandle>();
        for (int i = 1; i < lines.length - 1; i++) {
            String[] parts = lines[i].split(",", -1);
            if (parts.length != 6) {
                throw new IOException("Candle CSV row is malformed");
            }
            long time = LocalDateTime.parse(parts[0], STAMP)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli();
            candles.add(new NativeCandle(
                time,
                Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2]),
                Double.parseDouble(parts[3]),
                Double.parseDouble(parts[4]),
                Double.parseDouble(parts[5])
            ));
        }
        return candles;
    }

    public static String sha256(byte[] payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload);
            StringBuilder text = new StringBuilder(hash.length * 2);
            for (int i = 0; i < hash.length; i++) {
                int value = hash[i] & 0xff;
                text.append(Character.forDigit(value >>> 4, 16));
                text.append(Character.forDigit(value & 0x0f, 16));
            }
            return text.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    public static void atomicWrite(Path target, byte[] content) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName().toString() + ".tmp");
        if (Files.exists(temporary)) {
            throw new IOException("Interrupted staging update");
        }
        try (FileChannel channel = FileChannel.open(
                temporary,
                StandardOpenOption.CREATE_NEW,
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
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
