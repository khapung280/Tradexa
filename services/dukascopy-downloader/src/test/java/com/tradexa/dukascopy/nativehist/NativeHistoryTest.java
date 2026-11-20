package com.tradexa.dukascopy.nativehist;

import com.tradexa.dukascopy.nativehist.NativeDownloadEngine.Report;
import com.tradexa.dukascopy.nativehist.NativeDownloadEngine.Request;
import com.tradexa.dukascopy.nativehist.NativeReferenceComparator.Finding;
import com.tradexa.dukascopy.nativehist.NativeReferencePlan.Day;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class NativeHistoryTest {
    @Test
    public void exactNativeBarMatchesIndependentM1Aggregation() {
        long start = NativeGrid.dateStart("2024-01-17");
        List<NativeCandle> m1 = new ArrayList<NativeCandle>();
        double[][] rows = {
            {1.10, 1.40, 1.00, 1.20, 1},
            {1.20, 1.50, 1.10, 1.30, 2},
            {1.30, 1.60, 1.20, 1.40, 3},
            {1.40, 1.70, 1.30, 1.50, 4},
            {1.50, 1.80, 1.40, 1.60, 5}
        };
        for (int i = 0; i < rows.length; i++) {
            m1.add(new NativeCandle(
                start + i * 60000L,
                rows[i][0], rows[i][1], rows[i][2], rows[i][3], rows[i][4]
            ));
        }
        NativeCandle nativeBar = new NativeCandle(start, 1.10, 1.80, 1.00, 1.60, 15);
        List<Finding> findings = NativeReferenceComparator.compare(
            NativeTimeframe.M5,
            "WEEKDAY",
            "2024-01-17",
            new UtcFloorClock(),
            start,
            start + 4 * 60000L,
            m1,
            Arrays.asList(nativeBar)
        );
        Assert.assertEquals(1, findings.size());
        Assert.assertEquals(NativeReferenceComparator.EXACT, findings.get(0).classification);
        Assert.assertTrue(findings.get(0).detail.contains("utc_floor_delta_millis=0"));
    }

    @Test
    public void priceMismatchAndEmptyResponseAreClassified() {
        long start = NativeGrid.dateStart("2024-01-17");
        List<NativeCandle> m1 = Arrays.asList(
            new NativeCandle(start, 1.1, 1.2, 1.0, 1.1, 1),
            new NativeCandle(start + 60000L, 1.1, 1.2, 1.0, 1.1, 1),
            new NativeCandle(start + 120000L, 1.1, 1.2, 1.0, 1.1, 1),
            new NativeCandle(start + 180000L, 1.1, 1.2, 1.0, 1.1, 1),
            new NativeCandle(start + 240000L, 1.1, 1.2, 1.0, 1.1, 1)
        );
        NativeCandle wrong = new NativeCandle(start, 1.1, 1.2, 1.0, 1.15, 5);
        List<Finding> mismatch = NativeReferenceComparator.compare(
            NativeTimeframe.M5, "WEEKDAY", "2024-01-17", new UtcFloorClock(),
            start, start + 240000L, m1, Arrays.asList(wrong)
        );
        Assert.assertEquals(NativeReferenceComparator.PRICE_MISMATCH, mismatch.get(0).classification);
        Assert.assertEquals("close", mismatch.get(0).field);
        List<Finding> empty = NativeReferenceComparator.compare(
            NativeTimeframe.M5, "SUNDAY_OPEN", "2024-01-17", new UtcFloorClock(),
            start, start + 240000L, m1, new ArrayList<NativeCandle>()
        );
        Assert.assertEquals(NativeReferenceComparator.MISSING_NATIVE, empty.get(0).classification);
        Assert.assertTrue(empty.get(0).detail.contains("not a confirmed market closure"));
        String reason = NativeReferenceComparator.failureReason(
            empty,
            Arrays.asList(new Day("SUNDAY_OPEN", "2024-01-17")),
            new NativeTimeframe[] {NativeTimeframe.M5}
        );
        Assert.assertNotNull(reason);
    }

    @Test
    public void partialEdgeIsKeptAndLabeled() {
        long bar = NativeGrid.dateStart("2024-01-17");
        long windowStart = bar + 2 * 60000L;
        List<NativeCandle> m1 = new ArrayList<NativeCandle>();
        for (int i = 0; i < 5; i++) {
            m1.add(new NativeCandle(bar + i * 60000L, 1.2, 1.3, 1.1, 1.2, 1));
        }
        NativeCandle candle = new NativeCandle(bar, 1.2, 1.3, 1.1, 1.2, 5);
        List<Finding> findings = NativeReferenceComparator.compare(
            NativeTimeframe.M5, "WEEKDAY", "2024-01-17", new UtcFloorClock(),
            windowStart, bar + 4 * 60000L, m1, Arrays.asList(candle)
        );
        Assert.assertEquals(NativeReferenceComparator.PARTIAL_EDGE, findings.get(0).classification);
        Assert.assertEquals(NativeGrid.formatStamp(bar), findings.get(0).nativeTimeUtc);
    }

    @Test
    public void flatZeroVolumeCandleDoesNotSetOpenHighOrLow() {
        long start = NativeGrid.dateStart("2024-01-17");
        List<NativeCandle> m1 = new ArrayList<NativeCandle>();
        m1.add(new NativeCandle(start, 1.50, 1.50, 1.50, 1.50, 0.0));
        m1.add(new NativeCandle(start + 60000L, 1.10, 1.25, 1.05, 1.20, 2.0));
        m1.add(new NativeCandle(start + 120000L, 1.20, 1.40, 1.00, 1.30, 3.0));
        m1.add(new NativeCandle(start + 180000L, 1.30, 1.35, 1.10, 1.15, 4.0));
        m1.add(new NativeCandle(start + 240000L, 1.15, 1.16, 1.14, 1.16, 1.0));
        NativeCandle nativeBar = new NativeCandle(start, 1.10, 1.40, 1.00, 1.16, 10.0);
        List<Finding> findings = compareM5(start, m1, nativeBar);
        Assert.assertEquals(NativeReferenceComparator.EXACT, findings.get(0).classification);
        NativeCandle flatOpen = new NativeCandle(start, 1.50, 1.50, 1.00, 1.16, 10.0);
        List<Finding> rejected = compareM5(start, m1, flatOpen);
        Assert.assertEquals("open", rejected.get(0).field);
    }

    @Test
    public void volumeRoundsHalfUpAfterEveryAddition() {
        long start = NativeGrid.dateStart("2024-01-17");
        double[] volumes = {180.19, 136.99, 217.32, 141.68, 131.19};
        List<NativeCandle> m1 = new ArrayList<NativeCandle>();
        double raw = 0.0;
        for (int i = 0; i < volumes.length; i++) {
            raw += volumes[i];
            m1.add(new NativeCandle(start + i * 60000L, 1.1, 1.2, 1.0, 1.1, volumes[i]));
        }
        Assert.assertTrue(Double.compare(raw, 807.37) != 0);
        NativeCandle rounded = new NativeCandle(start, 1.1, 1.2, 1.0, 1.1, 807.37);
        Assert.assertEquals(
            NativeReferenceComparator.EXACT, compareM5(start, m1, rounded).get(0).classification
        );
        NativeCandle rawNative = new NativeCandle(start, 1.1, 1.2, 1.0, 1.1, raw);
        List<Finding> residual = compareM5(start, m1, rawNative);
        Assert.assertEquals(NativeReferenceComparator.PRICE_MISMATCH, residual.get(0).classification);
        Assert.assertEquals("volume", residual.get(0).field);
    }

    @Test
    public void h4UsesRoundedH1ConstituentsAndSkipsFlatHours() {
        long start = NativeGrid.dateStart("2024-01-14") + 20L * 3600000L;
        List<NativeCandle> m1 = new ArrayList<NativeCandle>();
        List<NativeCandle> hours = new ArrayList<NativeCandle>();
        for (int hour = 0; hour < 4; hour++) {
            List<NativeCandle> hourMinutes = new ArrayList<NativeCandle>();
            for (int minute = 0; minute < 60; minute++) {
                long time = start + (hour * 60L + minute) * 60000L;
                NativeCandle candle;
                if (hour == 0) {
                    candle = new NativeCandle(time, 2.0, 2.0, 2.0, 2.0, 0.0);
                } else if (minute < 2) {
                    candle = new NativeCandle(time, 1.11, 1.2, 1.1, 1.15, 1.000000006);
                } else {
                    candle = new NativeCandle(time, 1.15, 1.15, 1.15, 1.15, 0.0);
                }
                m1.add(candle);
                hourMinutes.add(candle);
            }
            hours.add(NativeReferenceComparator.aggregateCandles(
                hourMinutes, start + hour * 3600000L, true
            ));
        }
        NativeCandle expected = NativeReferenceComparator.aggregateCandles(hours, start, true);
        BigDecimal continuous = null;
        for (int i = 0; i < m1.size(); i++) {
            if (m1.get(i).volume == 0.0) {
                continue;
            }
            BigDecimal value = BigDecimal.valueOf(m1.get(i).volume);
            continuous = continuous == null
                ? value
                : continuous.add(value).setScale(8, RoundingMode.HALF_UP);
        }
        Assert.assertTrue(Double.compare(expected.volume, continuous.doubleValue()) != 0);
        List<Finding> findings = NativeReferenceComparator.compare(
            NativeTimeframe.H4, "SUNDAY_OPEN", "2024-01-14", new UtcFloorClock(),
            start, start + 4L * 3600000L - 60000L, m1, Arrays.asList(expected)
        );
        Assert.assertEquals(NativeReferenceComparator.EXACT, findings.get(0).classification);
        Assert.assertEquals(0, Double.compare(1.11, expected.open));
        NativeCandle flatHourOpen = new NativeCandle(
            start, 2.0, 2.0, expected.low, expected.close, expected.volume
        );
        List<Finding> rejected = NativeReferenceComparator.compare(
            NativeTimeframe.H4, "SUNDAY_OPEN", "2024-01-14", new UtcFloorClock(),
            start, start + 4L * 3600000L - 60000L, m1, Arrays.asList(flatHourOpen)
        );
        Assert.assertEquals("open", rejected.get(0).field);
    }

    @Test
    public void d1VolumeResidualStaysUnverifiedWhileOhlcAndSourcePass() {
        long start = NativeGrid.dateStart("2024-01-17");
        List<NativeCandle> m1 = pricedDay(start);
        NativeCandle nativeBar = new NativeCandle(start, 1.10, 1.20, 1.00, 1.15, 1438.99);
        List<Finding> findings = compareD1(m1, nativeBar);
        Finding volume = volumeFinding(findings);
        Assert.assertEquals(NativeReferenceComparator.UNVERIFIED_SOURCE_SEMANTICS, volume.classification);
        Assert.assertTrue(volume.detail.contains("native_volume=1438.99"));
        Assert.assertTrue(volume.detail.contains("constituent_sum=1439.0"));
        Assert.assertTrue(volume.detail.contains("delta="));
        Assert.assertEquals(0, Double.compare(1438.99, nativeBar.volume));
        Assert.assertFalse(hasPriceMismatch(findings));
        String summary = NativeReferenceComparator.d1Summary(findings);
        Assert.assertTrue(summary.contains("d1_ohlc_verdict=PASS"));
        Assert.assertTrue(summary.contains("d1_volume_source_integrity=PASS"));
        Assert.assertTrue(summary.contains(
            "d1_volume_cross_timeframe_verdict=" + NativeReferenceComparator.UNVERIFIED_SOURCE_SEMANTICS
        ));
        Assert.assertTrue(summary.contains(
            "d1_volume_semantics=" + NativeReferenceComparator.UNVERIFIED_SOURCE_SEMANTICS
        ));
        Assert.assertFalse(summary.contains("d1_volume_cross_timeframe_verdict=PASS"));
        Assert.assertFalse(summary.contains("d1_volume_semantics=PASS"));
        Assert.assertNull(NativeReferenceComparator.failureReason(
            findings,
            Arrays.asList(new Day("WEEKDAY", "2024-01-17")),
            new NativeTimeframe[] {NativeTimeframe.D1}
        ));
    }

    @Test
    public void exactD1VolumeMatchRemainsUnverified() {
        long start = NativeGrid.dateStart("2024-01-17");
        List<Finding> findings = compareD1(
            pricedDay(start), new NativeCandle(start, 1.10, 1.20, 1.00, 1.15, 1439.0)
        );
        Finding volume = volumeFinding(findings);
        Assert.assertEquals(NativeReferenceComparator.UNVERIFIED_SOURCE_SEMANTICS, volume.classification);
        Assert.assertTrue(volume.detail.contains("native_volume=1439.0"));
        Assert.assertTrue(volume.detail.contains("constituent_sum=1439.0"));
        Assert.assertTrue(volume.detail.contains("delta=0.0"));
        String summary = NativeReferenceComparator.d1Summary(findings);
        Assert.assertTrue(summary.contains("d1_ohlc_verdict=PASS"));
        Assert.assertTrue(summary.contains("d1_volume_source_integrity=PASS"));
        Assert.assertFalse(summary.contains("d1_volume_cross_timeframe_verdict=PASS"));
        Assert.assertFalse(summary.contains("d1_volume_semantics=PASS"));
    }

    @Test
    public void invalidMissingOrMalformedD1VolumeFails() {
        long start = NativeGrid.dateStart("2024-01-17");
        List<NativeCandle> m1 = pricedDay(start);
        assertD1VolumeFailure(compareD1(m1, new NativeCandle(start, 1.10, 1.20, 1.00, 1.15, Double.NaN)));
        assertD1VolumeFailure(compareD1(
            m1, new NativeCandle(start, 1.10, 1.20, 1.00, 1.15, Double.POSITIVE_INFINITY)
        ));
        assertD1VolumeFailure(compareD1(m1, new NativeCandle(start, 1.10, 1.20, 1.00, 1.15, -1.0)));
        List<Finding> missing = NativeReferenceComparator.compare(
            NativeTimeframe.D1, "WEEKDAY", "2024-01-17", new UtcFloorClock(),
            start, start + 1439 * 60000L, m1, new ArrayList<NativeCandle>()
        );
        assertD1SourceFailure(missing);
        List<Finding> malformed = compareD1(
            m1, new NativeCandle(start, 1.10, 1.00, 1.20, 1.15, 1439.0)
        );
        assertD1SourceFailure(malformed);
        Assert.assertEquals(NativeReferenceComparator.INVALID_OHLC, malformed.get(0).classification);
    }

    @Test
    public void d1OpenMismatchStillFailsWhileVolumeStaysUnverified() {
        long start = NativeGrid.dateStart("2024-01-17");
        List<Finding> findings = compareD1(
            pricedDay(start), new NativeCandle(start, 1.11, 1.20, 1.00, 1.15, 1439.0)
        );
        Assert.assertEquals("open", findings.get(0).field);
        Assert.assertEquals(NativeReferenceComparator.PRICE_MISMATCH, findings.get(0).classification);
        Assert.assertEquals(
            NativeReferenceComparator.UNVERIFIED_SOURCE_SEMANTICS, volumeFinding(findings).classification
        );
        String summary = NativeReferenceComparator.d1Summary(findings);
        Assert.assertTrue(summary.contains("d1_ohlc_verdict=FAIL"));
        Assert.assertTrue(summary.contains("d1_volume_source_integrity=PASS"));
        Assert.assertFalse(summary.contains("d1_volume_semantics=PASS"));
        Assert.assertNotNull(NativeReferenceComparator.failureReason(
            findings,
            Arrays.asList(new Day("WEEKDAY", "2024-01-17")),
            new NativeTimeframe[] {NativeTimeframe.D1}
        ));
    }

    @Test
    public void readinessAcceptsUnverifiedD1SemanticsAndRejectsPassOrFailure() throws Exception {
        long start = NativeGrid.dateStart("2024-01-17");
        String summary = NativeReferenceComparator.d1Summary(compareD1(
            pricedDay(start), new NativeCandle(start, 1.10, 1.20, 1.00, 1.15, 1438.99)
        ));
        Map<String, String> integrity = passedIntegrity();
        Assert.assertTrue(NativeReadiness.allows(true, integrity, summary));
        String report = NativeReadiness.report(true, integrity, summary);
        Assert.assertTrue(report.contains("ready=YES"));
        Assert.assertTrue(report.contains("m1_integrity=PASS"));
        Assert.assertTrue(report.contains(
            "d1_volume_cross_timeframe_verdict=" + NativeReferenceComparator.UNVERIFIED_SOURCE_SEMANTICS
        ));
        Assert.assertTrue(report.contains(NativeReferenceComparator.D1_VOLUME_LIMITATION));
        Assert.assertFalse(report.contains("d1_volume_cross_timeframe_verdict=PASS"));
        Assert.assertFalse(report.contains("d1_volume_semantics=PASS"));
        Assert.assertFalse(NativeReadiness.allows(false, integrity, summary));
        integrity.put("H1", "FAIL");
        Assert.assertFalse(NativeReadiness.allows(true, integrity, summary));
        String failedVolume = NativeReferenceComparator.d1Summary(compareD1(
            pricedDay(start), new NativeCandle(start, 1.10, 1.20, 1.00, 1.15, Double.NaN)
        ));
        Assert.assertFalse(NativeReadiness.allows(true, passedIntegrity(), failedVolume));
        String forged = summary
            .replace("d1_volume_cross_timeframe_verdict=UNVERIFIED_SOURCE_SEMANTICS",
                "d1_volume_cross_timeframe_verdict=PASS")
            .replace("d1_volume_semantics=UNVERIFIED_SOURCE_SEMANTICS",
                "d1_volume_semantics=PASS");
        Assert.assertFalse(NativeReadiness.allows(true, passedIntegrity(), forged));
        String rejected = NativeReadiness.report(true, passedIntegrity(), forged);
        Assert.assertTrue(rejected.contains("ready=NO"));
        Assert.assertFalse(rejected.contains("d1_volume_cross_timeframe_verdict=PASS"));
        Assert.assertFalse(rejected.contains("d1_volume_semantics=PASS"));
        Path output = Files.createTempDirectory("m1-audit-status");
        try {
            Assert.assertFalse(NativeReadiness.latestM1AuditPasses(output));
            Path audit = output.resolve("EURUSD_M1_integrity_audit_20261119T053410Z");
            Files.createDirectories(audit);
            Files.write(
                audit.resolve("audit-summary.txt"),
                ("verdict=PASS\nfingerprint=" + NativeReadiness.M1_FINGERPRINT + "\n")
                    .getBytes(StandardCharsets.UTF_8)
            );
            Assert.assertTrue(NativeReadiness.latestM1AuditPasses(output));
            Path older = output.resolve("EURUSD_M1_integrity_audit_20261118T175715Z");
            Files.createDirectories(older);
            Files.write(
                older.resolve("audit-summary.txt"),
                "verdict=FAIL\n".getBytes(StandardCharsets.UTF_8)
            );
            Assert.assertTrue(NativeReadiness.latestM1AuditPasses(output));
            Files.write(
                audit.resolve("audit-summary.txt"),
                "verdict=FAIL\n".getBytes(StandardCharsets.UTF_8)
            );
            Assert.assertFalse(NativeReadiness.latestM1AuditPasses(output));
        } finally {
            delete(output);
        }
    }

    private static List<NativeCandle> pricedDay(long start) {
        List<NativeCandle> m1 = new ArrayList<NativeCandle>();
        m1.add(new NativeCandle(start, 1.50, 1.50, 1.50, 1.50, 0.0));
        for (int i = 1; i < 1440; i++) {
            m1.add(new NativeCandle(start + i * 60000L, 1.10, 1.20, 1.00, 1.15, 1.0));
        }
        return m1;
    }

    private static List<Finding> compareD1(List<NativeCandle> m1, NativeCandle nativeBar) {
        long start = m1.get(0).timeMillis;
        return NativeReferenceComparator.compare(
            NativeTimeframe.D1, "WEEKDAY", "2024-01-17", new UtcFloorClock(),
            start, start + 1439 * 60000L, m1, Arrays.asList(nativeBar)
        );
    }

    private static Finding volumeFinding(List<Finding> findings) {
        for (int i = 0; i < findings.size(); i++) {
            if ("volume".equals(findings.get(i).field)) {
                return findings.get(i);
            }
        }
        Assert.fail("Expected a D1 volume finding");
        return null;
    }

    private static boolean hasPriceMismatch(List<Finding> findings) {
        for (int i = 0; i < findings.size(); i++) {
            String field = findings.get(i).field;
            if ("open".equals(field) || "high".equals(field) || "low".equals(field) || "close".equals(field)) {
                return true;
            }
        }
        return false;
    }

    private static void assertD1VolumeFailure(List<Finding> findings) {
        Finding volume = volumeFinding(findings);
        Assert.assertEquals(NativeReferenceComparator.INVALID_OHLC, volume.classification);
        Assert.assertNotEquals(
            NativeReferenceComparator.UNVERIFIED_SOURCE_SEMANTICS, volume.classification
        );
        String summary = NativeReferenceComparator.d1Summary(findings);
        Assert.assertTrue(summary.contains("d1_ohlc_verdict=PASS"));
        Assert.assertTrue(summary.contains("d1_volume_source_integrity=FAIL"));
        Assert.assertTrue(summary.contains("d1_volume_cross_timeframe_verdict=FAIL"));
        Assert.assertTrue(summary.contains("d1_volume_semantics=FAIL"));
        Assert.assertFalse(summary.contains("d1_volume_semantics=PASS"));
        Assert.assertNotNull(NativeReferenceComparator.failureReason(
            findings,
            Arrays.asList(new Day("WEEKDAY", "2024-01-17")),
            new NativeTimeframe[] {NativeTimeframe.D1}
        ));
    }

    private static void assertD1SourceFailure(List<Finding> findings) {
        String summary = NativeReferenceComparator.d1Summary(findings);
        Assert.assertTrue(summary.contains("d1_ohlc_verdict=FAIL"));
        Assert.assertTrue(summary.contains("d1_volume_source_integrity=FAIL"));
        Assert.assertTrue(summary.contains("d1_volume_cross_timeframe_verdict=FAIL"));
        Assert.assertTrue(summary.contains("d1_volume_semantics=FAIL"));
        Assert.assertFalse(summary.contains("d1_volume_semantics=UNVERIFIED_SOURCE_SEMANTICS"));
        Assert.assertNotNull(NativeReferenceComparator.failureReason(
            findings,
            Arrays.asList(new Day("WEEKDAY", "2024-01-17")),
            new NativeTimeframe[] {NativeTimeframe.D1}
        ));
    }

    private static Map<String, String> passedIntegrity() {
        Map<String, String> integrity = new LinkedHashMap<String, String>();
        NativeTimeframe[] timeframes = NativeTimeframe.values();
        for (int i = 0; i < timeframes.length; i++) {
            integrity.put(timeframes[i].name(), "PASS");
        }
        return integrity;
    }

    private static List<Finding> compareM5(long start, List<NativeCandle> m1, NativeCandle nativeBar) {
        return NativeReferenceComparator.compare(
            NativeTimeframe.M5, "WEEKDAY", "2024-01-17", new UtcFloorClock(),
            start, start + 4 * 60000L, m1, Arrays.asList(nativeBar)
        );
    }

    @Test
    public void oneDateBatchFinishesEveryTimeframeBeforeTheNextDate() throws Exception {
        Path root = Files.createTempDirectory("native-batch-order");
        try {
            long start = NativeGrid.dateStart("2024-01-17");
            long end = NativeGrid.dateStart("2024-01-18");
            List<NativeCandle> m1 = minutes(start, start + 1439 * 60000L, 1.0);
            m1.add(new NativeCandle(end, 1.2, 1.4, 1.1, 1.3, 1.0));
            AggregatingHistory history = new AggregatingHistory(m1, new UtcFloorClock());
            Request request = request(root, start, end, history, true);
            request.batchSize = 1;
            Report report = NativeDownloadEngine.run(request);
            Assert.assertTrue(report.referencePassed);
            Assert.assertTrue(report.ready);
            Assert.assertEquals(
                Arrays.asList("M5", "M15", "M5", "M15"),
                history.downloadOrder.subList(2, 6)
            );
        } finally {
            delete(root);
        }
    }

    @Test
    public void batchesNeverExceedOneHundredDates() {
        long start = NativeGrid.dateStart("2024-01-01");
        long end = start + 249L * 86400000L;
        List<String> dates = NativeGrid.calendarDates(start, end);
        Assert.assertEquals(250, dates.size());
        Assert.assertEquals(3, NativeBatchPlanner.batchCount(dates.size(), 100));
        Assert.assertEquals(100, NativeBatchPlanner.batch(dates, 0, 100).size());
        Assert.assertEquals(100, NativeBatchPlanner.batch(dates, 1, 100).size());
        Assert.assertEquals(50, NativeBatchPlanner.batch(dates, 2, 100).size());
        try {
            NativeBatchPlanner.requireBatchSize(101);
            Assert.fail("Expected the batch bound to reject 101 dates");
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue(ex.getMessage().contains("100"));
        }
    }

    @Test
    public void productionReferenceRolesCoverTheRequiredSessions() {
        Assert.assertTrue(NativeReferencePlan.coversRequiredRoles(NativeReferencePlan.productionDays()));
        List<Day> missing = new ArrayList<Day>(NativeReferencePlan.productionDays());
        missing.remove(missing.size() - 1);
        Assert.assertFalse(NativeReferencePlan.coversRequiredRoles(missing));
    }

    @Test
    public void verifiedDownloadResumesWithoutRewriting() throws Exception {
        Path root = Files.createTempDirectory("native-resume");
        try {
            long start = NativeGrid.dateStart("2024-01-17");
            long end = start + 9 * 60000L;
            List<NativeCandle> m1 = minutes(start, end, 1.0);
            AggregatingHistory history = new AggregatingHistory(m1, new UtcFloorClock());
            Report first = NativeDownloadEngine.run(request(root, start, end, history, false));
            Assert.assertTrue(first.referencePassed);
            Assert.assertTrue(first.ready);
            Assert.assertTrue(Files.isRegularFile(root.resolve("READY")));
            Path csv = root.resolve("M5").resolve("2024-01-17.csv");
            byte[] before = Files.readAllBytes(csv);
            int calls = history.requests;
            history.requests = 0;
            history.breakTimeframe = "M5";
            Report second = NativeDownloadEngine.run(request(root, start, end, history, false));
            Assert.assertTrue(second.ready);
            Assert.assertEquals(0, history.requests);
            Assert.assertArrayEquals(before, Files.readAllBytes(csv));
            Assert.assertEquals(calls, calls);
        } finally {
            delete(root);
        }
    }

    @Test
    public void unresolvedTimeframeDoesNotCompleteTheOthers() throws Exception {
        Path root = Files.createTempDirectory("native-stop");
        try {
            long start = NativeGrid.dateStart("2024-01-17");
            long end = start + 14 * 60000L;
            AggregatingHistory history = new AggregatingHistory(minutes(start, end, 1.0), new UtcFloorClock());
            history.breakTimeframe = "M15";
            history.allowFirst = true;
            Report report = NativeDownloadEngine.run(request(root, start, end, history, true));
            Assert.assertTrue(report.referencePassed);
            Assert.assertFalse(report.ready);
            Assert.assertFalse(Files.exists(root.resolve("READY")));
            Assert.assertTrue(Files.isRegularFile(root.resolve("M5").resolve("2024-01-17.csv")));
            Assert.assertFalse(Files.exists(root.resolve("M15").resolve("2024-01-17.csv")));
            String ledger = new String(
                Files.readAllBytes(root.resolve("M15").resolve("M15.ledger.properties")),
                StandardCharsets.UTF_8
            );
            Assert.assertTrue(ledger.contains("2024-01-17.status=UNRESOLVED"));
            Assert.assertTrue(ledger.contains("not a confirmed market closure"));
        } finally {
            delete(root);
        }
    }

    @Test
    public void blockedTimeframeDoesNotStopIndependentTimeframes() throws Exception {
        Path root = Files.createTempDirectory("native-independent");
        try {
            long start = NativeGrid.dateStart("2024-01-17");
            long end = start + 59 * 60000L;
            AggregatingHistory history = new AggregatingHistory(minutes(start, end, 1.0), new UtcFloorClock());
            history.breakTimeframe = "M15";
            history.allowFirst = true;
            Request request = request(root, start, end, history, true);
            request.timeframes = new NativeTimeframe[] {
                NativeTimeframe.M5, NativeTimeframe.M15, NativeTimeframe.M30
            };
            Report report = NativeDownloadEngine.run(request);
            Assert.assertTrue(report.referencePassed);
            Assert.assertFalse(report.ready);
            Assert.assertFalse(Files.exists(root.resolve("READY")));
            Assert.assertTrue(Files.isRegularFile(root.resolve("M5").resolve("2024-01-17.csv")));
            Assert.assertTrue(Files.isRegularFile(root.resolve("M30").resolve("2024-01-17.csv")));
            Assert.assertFalse(Files.exists(root.resolve("M15").resolve("2024-01-17.csv")));
            String ledger = new String(
                Files.readAllBytes(root.resolve("M15").resolve("M15.ledger.properties")),
                StandardCharsets.UTF_8
            );
            Assert.assertTrue(ledger.contains("2024-01-17.status=UNRESOLVED"));
            Assert.assertTrue(report.stopped.contains("M15"));
        } finally {
            delete(root);
        }
    }

    @Test
    public void referenceFailureStopsBeforeDownload() throws Exception {
        Path root = Files.createTempDirectory("native-reference");
        try {
            long start = NativeGrid.dateStart("2024-01-17");
            long end = start + 9 * 60000L;
            AggregatingHistory history = new AggregatingHistory(minutes(start, end, 1.0), new UtcFloorClock());
            history.alterClose = true;
            Report report = NativeDownloadEngine.run(request(root, start, end, history, false));
            Assert.assertFalse(report.referencePassed);
            Assert.assertFalse(report.ready);
            Assert.assertFalse(Files.exists(root.resolve("M5")));
            String csv = new String(
                Files.readAllBytes(root.resolve("reference-validation-report.csv")),
                StandardCharsets.UTF_8
            );
            Assert.assertTrue(csv.contains("PRICE_MISMATCH"));
            Assert.assertTrue(csv.contains("close"));
        } finally {
            delete(root);
        }
    }

    private static Request request(
            Path root,
            long start,
            long end,
            final AggregatingHistory history,
            final boolean twoFrames) {
        Request request = new Request();
        request.datasetRoot = root;
        request.history = history;
        request.minutes = new NativeDownloadEngine.Minutes() {
            public List<NativeCandle> readRange(long from, long to) {
                List<NativeCandle> selected = new ArrayList<NativeCandle>();
                for (int i = 0; i < history.m1.size(); i++) {
                    NativeCandle candle = history.m1.get(i);
                    if (candle.timeMillis >= from && candle.timeMillis <= to) {
                        selected.add(candle);
                    }
                }
                return selected;
            }
        };
        request.windowStart = start;
        request.windowEnd = end;
        request.maxAttempts = 3;
        request.referenceDays = Arrays.asList(new Day("WEEKDAY", "2024-01-17"));
        request.timeframes = twoFrames
            ? new NativeTimeframe[] {NativeTimeframe.M5, NativeTimeframe.M15}
            : new NativeTimeframe[] {NativeTimeframe.M5};
        return request;
    }

    private static List<NativeCandle> minutes(long start, long end, double volume) {
        List<NativeCandle> candles = new ArrayList<NativeCandle>();
        for (long time = start; time <= end; time += 60000L) {
            candles.add(new NativeCandle(time, 1.2, 1.4, 1.1, 1.3, volume));
        }
        return candles;
    }

    private static void delete(Path path) throws Exception {
        if (path == null || !Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) {
                    delete(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    private static final class UtcFloorClock implements NativeBarClock {
        public long barStart(NativeTimeframe timeframe, long timeMillis) {
            long duration = timeframe.durationMillis();
            return Math.floorDiv(timeMillis, duration) * duration;
        }

        public long nextBarStart(NativeTimeframe timeframe, long barTimeMillis) {
            return barTimeMillis + timeframe.durationMillis();
        }
    }

    private static final class AggregatingHistory implements NativeDownloadEngine.History {
        private final List<NativeCandle> m1;
        private final NativeBarClock clock;
        private int requests;
        private final List<String> downloadOrder = new ArrayList<String>();
        private String breakTimeframe = "";
        private boolean allowFirst;
        private boolean alterClose;
        private final Map<String, Integer> seen = new LinkedHashMap<String, Integer>();

        private AggregatingHistory(List<NativeCandle> m1, NativeBarClock clock) {
            this.m1 = m1;
            this.clock = clock;
        }

        public long barStart(NativeTimeframe timeframe, long timeMillis) {
            return clock.barStart(timeframe, timeMillis);
        }

        public long nextBarStart(NativeTimeframe timeframe, long barTimeMillis) {
            return clock.nextBarStart(timeframe, barTimeMillis);
        }

        public List<NativeCandle> request(
                NativeTimeframe timeframe,
                long fromInclusive,
                long toInclusive) {
            requests++;
            downloadOrder.add(timeframe.name());
            Integer count = seen.get(timeframe.name());
            int next = count == null ? 1 : count.intValue() + 1;
            seen.put(timeframe.name(), Integer.valueOf(next));
            if (timeframe.name().equals(breakTimeframe) && (!allowFirst || next > 1)) {
                return new ArrayList<NativeCandle>();
            }
            List<Long> bars = NativeGrid.walk(clock, timeframe, fromInclusive, toInclusive);
            List<NativeCandle> candles = new ArrayList<NativeCandle>();
            for (int i = 0; i < bars.size(); i++) {
                long bar = bars.get(i).longValue();
                long nextBar = clock.nextBarStart(timeframe, bar);
                NativeCandle first = null;
                NativeCandle last = null;
                double high = -Double.MAX_VALUE;
                double low = Double.MAX_VALUE;
                double volume = 0.0;
                for (int m = 0; m < m1.size(); m++) {
                    NativeCandle minute = m1.get(m);
                    if (minute.timeMillis < bar || minute.timeMillis >= nextBar) {
                        continue;
                    }
                    if (first == null) {
                        first = minute;
                    }
                    last = minute;
                    high = Math.max(high, minute.high);
                    low = Math.min(low, minute.low);
                    volume += minute.volume;
                }
                double close = last.close;
                if (alterClose) {
                    close = last.close - 0.01;
                }
                candles.add(new NativeCandle(bar, first.open, high, low, close, volume));
            }
            return candles;
        }
    }
}
