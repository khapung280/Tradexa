package com.tradexa.dukascopy.diagnostic;

final class HttpAttempt {
    int responseCode = -1;
    String exceptionClass = "";
    String exceptionMessage = "";
    long bytes;
    /** Allowlisted response header, or empty when absent. Never a raw unsafe value. */
    String contentLength = "";
    /** Allowlisted response header, or empty when absent. Never a raw unsafe value. */
    String retryAfter = "";

    void addBytes(long count) {
        if (count > 0) {
            bytes += count;
        }
    }
}
