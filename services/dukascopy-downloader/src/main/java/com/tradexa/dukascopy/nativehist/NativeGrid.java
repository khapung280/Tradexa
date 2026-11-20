package com.tradexa.dukascopy.nativehist;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Walks native bar starts. A bar is kept when its own timestamp falls on
 * the UTC date being saved. Bars are not moved onto a different date.
 */
public final class NativeGrid {
    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private NativeGrid() {
    }

    public static String formatDate(long millis) {
        return DATE.format(Instant.ofEpochMilli(millis));
    }

    public static String formatStamp(long millis) {
        return STAMP.format(Instant.ofEpochMilli(millis));
    }

    public static long dateStart(String date) {
        LocalDate parsed = LocalDate.parse(date);
        return parsed.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    public static List<String> calendarDates(long windowStart, long windowEnd) {
        if (windowEnd < windowStart) {
            throw new IllegalArgumentException("Window end is before the start");
        }
        List<String> dates = new ArrayList<String>();
        long cursor = dateStart(formatDate(windowStart));
        long last = dateStart(formatDate(windowEnd));
        while (cursor <= last) {
            dates.add(formatDate(cursor));
            cursor += 86400000L;
        }
        return dates;
    }

    public static List<Long> walk(
            NativeBarClock clock,
            NativeTimeframe timeframe,
            long fromInclusive,
            long toInclusive) {
        if (toInclusive < fromInclusive) {
            throw new IllegalArgumentException("Bar walk is reversed");
        }
        List<Long> bars = new ArrayList<Long>();
        long cursor = fromInclusive;
        int guard = 0;
        while (cursor <= toInclusive) {
            bars.add(Long.valueOf(cursor));
            long next = clock.nextBarStart(timeframe, cursor);
            if (next <= cursor) {
                throw new IllegalArgumentException("Native bar clock did not advance");
            }
            cursor = next;
            guard++;
            if (guard > 2000000) {
                throw new IllegalArgumentException("Native bar walk exceeded the safety bound");
            }
        }
        return bars;
    }

    public static List<Long> barsInWindow(
            NativeBarClock clock,
            NativeTimeframe timeframe,
            long windowStart,
            long windowEnd) {
        long from = clock.barStart(timeframe, windowStart);
        long to = clock.barStart(timeframe, windowEnd);
        return walk(clock, timeframe, from, to);
    }

    public static List<Long> barsOnDate(
            NativeBarClock clock,
            NativeTimeframe timeframe,
            String date,
            long windowStart,
            long windowEnd) {
        long dayStart = dateStart(date);
        long dayEnd = dayStart + 86400000L - 60000L;
        long clippedStart = Math.max(dayStart, windowStart);
        long clippedEnd = Math.min(dayEnd, windowEnd);
        List<Long> kept = new ArrayList<Long>();
        if (clippedStart > clippedEnd) {
            return kept;
        }
        long firstAllowed = clock.barStart(timeframe, windowStart);
        long lastAllowed = clock.barStart(timeframe, windowEnd);
        long from = clock.barStart(timeframe, clippedStart);
        long to = clock.barStart(timeframe, clippedEnd);
        if (from < firstAllowed) {
            from = firstAllowed;
        }
        if (to > lastAllowed) {
            to = lastAllowed;
        }
        if (from > to) {
            return kept;
        }
        List<Long> walked = walk(clock, timeframe, from, to);
        for (int i = 0; i < walked.size(); i++) {
            long bar = walked.get(i).longValue();
            if (bar < firstAllowed || bar > lastAllowed) {
                continue;
            }
            if (date.equals(formatDate(bar))) {
                kept.add(Long.valueOf(bar));
            }
        }
        return kept;
    }
}
