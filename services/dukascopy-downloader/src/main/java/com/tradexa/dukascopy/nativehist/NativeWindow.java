package com.tradexa.dukascopy.nativehist;

/**
 * The verified EURUSD M1 production window. Native requests use this
 * same instant range, then snap to native bar starts.
 */
public final class NativeWindow {
    public static final long START_MILLIS = 1700116860000L;
    public static final long END_MILLIS = 1794811260000L;

    private NativeWindow() {
    }
}
