package com.tradexa.dukascopy.diagnostic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records HTTP status observed while a single date query is active.
 * Stores no URL or host. The only headers kept are allowlisted
 * Content-Length and Retry-After values. Exception text has URLs removed.
 */
final class HttpObservation {
    private final List<HttpAttempt> attempts =
        Collections.synchronizedList(new ArrayList<HttpAttempt>());
    private volatile boolean recording;

    void setRecording(boolean recording) {
        this.recording = recording;
    }

    boolean isRecording() {
        return recording;
    }

    HttpAttempt begin() {
        HttpAttempt attempt = new HttpAttempt();
        attempts.add(attempt);
        return attempt;
    }

    List<HttpAttempt> drain() {
        synchronized (attempts) {
            List<HttpAttempt> copy = new ArrayList<HttpAttempt>(attempts);
            attempts.clear();
            return copy;
        }
    }
}
