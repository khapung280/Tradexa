package com.tradexa.dukascopy.nativehist;

/**
 * One OHLC candle. Validation is performed by the caller so an invalid
 * feed value can be classified instead of being dropped.
 */
public final class NativeCandle {
    public final long timeMillis;
    public final double open;
    public final double high;
    public final double low;
    public final double close;
    public final double volume;

    public NativeCandle(
            long timeMillis,
            double open,
            double high,
            double low,
            double close,
            double volume) {
        this.timeMillis = timeMillis;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }
}
