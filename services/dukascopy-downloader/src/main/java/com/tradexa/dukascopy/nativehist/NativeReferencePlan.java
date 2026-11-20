package com.tradexa.dukascopy.nativehist;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Interior dates used to compare native candles with the verified M1 set
 * before a full historical download starts.
 */
public final class NativeReferencePlan {
    public static final class Day {
        public final String role;
        public final String date;

        Day(String role, String date) {
            this.role = role;
            this.date = date;
        }
    }

    private NativeReferencePlan() {
    }

    public static List<Day> productionDays() {
        return Arrays.asList(
            new Day("WEEKDAY", "2024-01-17"),
            new Day("SUNDAY_OPEN", "2024-01-14"),
            new Day("MONDAY_OPEN", "2024-01-15"),
            new Day("FRIDAY_CLOSE", "2024-01-19"),
            new Day("US_DST_SPRING", "2024-03-10"),
            new Day("US_DST_SPRING_NEXT", "2024-03-11"),
            new Day("US_DST_FALL", "2024-11-03"),
            new Day("US_DST_FALL_NEXT", "2024-11-04"),
            new Day("EU_DST_SPRING", "2024-03-31"),
            new Day("EU_DST_FALL", "2024-10-27")
        );
    }

    public static boolean coversRequiredRoles(List<Day> days) {
        List<Day> required = productionDays();
        if (days == null || days.size() != required.size()) {
            return false;
        }
        for (int i = 0; i < required.size(); i++) {
            if (!required.get(i).role.equals(days.get(i).role)
                    || !required.get(i).date.equals(days.get(i).date)) {
                return false;
            }
        }
        return true;
    }

    public static List<Day> copy(List<Day> days) {
        return new ArrayList<Day>(days);
    }
}
