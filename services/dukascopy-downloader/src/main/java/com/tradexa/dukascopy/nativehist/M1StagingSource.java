package com.tradexa.dukascopy.nativehist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only view of the verified M1 staging files.
 */
public final class M1StagingSource implements NativeDownloadEngine.Minutes {
    private final Path staging;

    public M1StagingSource(Path staging) {
        this.staging = staging.toAbsolutePath().normalize();
    }

    public List<NativeCandle> readRange(long startInclusive, long endInclusive) throws IOException {
        List<NativeCandle> candles = new ArrayList<NativeCandle>();
        if (endInclusive < startInclusive) {
            return candles;
        }
        long day = NativeGrid.dateStart(NativeGrid.formatDate(startInclusive));
        long lastDay = NativeGrid.dateStart(NativeGrid.formatDate(endInclusive));
        while (day <= lastDay) {
            String date = NativeGrid.formatDate(day);
            Path file = staging.resolve(date + ".csv").toAbsolutePath().normalize();
            if (!file.startsWith(staging)) {
                throw new IOException("M1 read escaped the staging directory");
            }
            if (Files.isRegularFile(file)) {
                List<NativeCandle> parsed = NativeCsv.parse(Files.readAllBytes(file));
                for (int i = 0; i < parsed.size(); i++) {
                    NativeCandle candle = parsed.get(i);
                    if (candle.timeMillis >= startInclusive && candle.timeMillis <= endInclusive) {
                        candles.add(candle);
                    }
                }
            }
            day += 86400000L;
        }
        return candles;
    }

    public static boolean containsTemp(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                if (child.getFileName().toString().endsWith(".tmp")) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void writeText(Path path, String text) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        NativeCsv.atomicWrite(path, text.getBytes(StandardCharsets.UTF_8));
    }
}
