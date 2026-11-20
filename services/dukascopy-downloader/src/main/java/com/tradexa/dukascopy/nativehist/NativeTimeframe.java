package com.tradexa.dukascopy.nativehist;

import com.dukascopy.api.Period;

/**
 * The six native Dukascopy periods. M1 is intentionally absent.
 */
public enum NativeTimeframe {
    M5(Period.FIVE_MINS, 5),
    M15(Period.FIFTEEN_MINS, 15),
    M30(Period.THIRTY_MINS, 30),
    H1(Period.ONE_HOUR, 60),
    H4(Period.FOUR_HOURS, 240),
    D1(Period.DAILY, 1440);

    private final Period period;
    private final int minutes;

    NativeTimeframe(Period period, int minutes) {
        this.period = period;
        this.minutes = minutes;
    }

    public Period period() {
        return period;
    }

    public int minutes() {
        return minutes;
    }

    public long durationMillis() {
        return minutes * 60000L;
    }

    public static NativeTimeframe parse(String text) {
        NativeTimeframe[] values = values();
        for (int i = 0; i < values.length; i++) {
            if (values[i].name().equals(text)) {
                return values[i];
            }
        }
        throw new IllegalArgumentException("Unsupported timeframe");
    }
}
