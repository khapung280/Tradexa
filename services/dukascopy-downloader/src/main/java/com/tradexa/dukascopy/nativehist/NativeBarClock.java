package com.tradexa.dukascopy.nativehist;

/**
 * Native bar boundary source. The live clock is IHistory.getBarStart
 * and IHistory.getNextBarStart. Tests supply a deterministic clock.
 */
public interface NativeBarClock {
    long barStart(NativeTimeframe timeframe, long timeMillis);

    long nextBarStart(NativeTimeframe timeframe, long barTimeMillis);
}
