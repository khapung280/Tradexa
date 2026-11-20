package com.tradexa.dukascopy.nativehist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

/**
 * Decides whether a completed native download may be marked ready.
 * D1 cross-timeframe volume semantics are accepted only as
 * UNVERIFIED_SOURCE_SEMANTICS and are never treated as a pass.
 */
public final class NativeReadiness {
    public static final String M1_FINGERPRINT =
        "92540b5a473769a828644c8493368052a9480897517985f502b0cbf1800cdbc6";
    private static final String[] TIMEFRAMES = {"M5", "M15", "M30", "H1", "H4", "D1"};

    private NativeReadiness() {
    }

    public static boolean allows(
            boolean m1IntegrityPass,
            Map<String, String> integrity,
            String d1Summary) {
        if (!m1IntegrityPass || integrity == null || d1Summary == null) {
            return false;
        }
        for (int i = 0; i < TIMEFRAMES.length; i++) {
            if (!"PASS".equals(integrity.get(TIMEFRAMES[i]))) {
                return false;
            }
        }
        if (!"PASS".equals(value(d1Summary, "d1_ohlc_verdict"))) {
            return false;
        }
        if (!"PASS".equals(value(d1Summary, "d1_volume_source_integrity"))) {
            return false;
        }
        String cross = value(d1Summary, "d1_volume_cross_timeframe_verdict");
        String semantics = value(d1Summary, "d1_volume_semantics");
        if (!NativeReferenceComparator.UNVERIFIED_SOURCE_SEMANTICS.equals(cross)
                || !NativeReferenceComparator.UNVERIFIED_SOURCE_SEMANTICS.equals(semantics)) {
            return false;
        }
        return d1Summary.contains("d1_volume_limitation=" + NativeReferenceComparator.D1_VOLUME_LIMITATION);
    }

    public static String report(
            boolean m1IntegrityPass,
            Map<String, String> integrity,
            String d1Summary) {
        StringBuilder text = new StringBuilder();
        boolean ready = allows(m1IntegrityPass, integrity, d1Summary);
        text.append("ready=").append(ready ? "YES" : "NO").append('\n');
        text.append("m1_integrity=").append(m1IntegrityPass ? "PASS" : "FAIL").append('\n');
        for (int i = 0; i < TIMEFRAMES.length; i++) {
            String verdict = integrity == null ? null : integrity.get(TIMEFRAMES[i]);
            text.append("integrity.").append(TIMEFRAMES[i]).append('=')
                .append(verdict == null ? "NOT_RUN" : verdict).append('\n');
        }
        text.append("d1_ohlc_verdict=").append(shown(d1Summary, "d1_ohlc_verdict")).append('\n');
        text.append("d1_volume_source_integrity=")
            .append(shown(d1Summary, "d1_volume_source_integrity")).append('\n');
        text.append("d1_volume_cross_timeframe_verdict=")
            .append(unverifiedOrFailure(d1Summary, "d1_volume_cross_timeframe_verdict")).append('\n');
        text.append("d1_volume_semantics=")
            .append(unverifiedOrFailure(d1Summary, "d1_volume_semantics")).append('\n');
        text.append("d1_volume_limitation=")
            .append(NativeReferenceComparator.D1_VOLUME_LIMITATION).append('\n');
        return text.toString();
    }

    public static boolean latestM1AuditPasses(Path outputDirectory) throws IOException {
        if (outputDirectory == null || !Files.isDirectory(outputDirectory)) {
            return false;
        }
        String newest = null;
        Path newestSummary = null;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(outputDirectory)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                if (!name.startsWith("EURUSD_M1_integrity_audit_") || !Files.isDirectory(child)) {
                    continue;
                }
                Path summary = child.resolve("audit-summary.txt");
                if (!Files.isRegularFile(summary)) {
                    continue;
                }
                if (newest == null || name.compareTo(newest) > 0) {
                    newest = name;
                    newestSummary = summary;
                }
            }
        }
        if (newestSummary == null) {
            return false;
        }
        String text = new String(Files.readAllBytes(newestSummary), StandardCharsets.UTF_8);
        return containsLine(text, "verdict=PASS")
            && containsLine(text, "fingerprint=" + M1_FINGERPRINT);
    }

    private static boolean containsLine(String text, String expected) {
        return Arrays.asList(text.split("\n", -1)).contains(expected);
    }

    private static String shown(String summary, String key) {
        String found = value(summary, key);
        return found == null ? "NOT_RUN" : found;
    }

    private static String unverifiedOrFailure(String summary, String key) {
        String found = shown(summary, key);
        if ("PASS".equals(found)) {
            return "FAIL";
        }
        return found;
    }

    private static String value(String summary, String key) {
        if (summary == null) {
            return null;
        }
        String prefix = key + "=";
        String[] lines = summary.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith(prefix)) {
                return lines[i].substring(prefix.length());
            }
        }
        return null;
    }
}
