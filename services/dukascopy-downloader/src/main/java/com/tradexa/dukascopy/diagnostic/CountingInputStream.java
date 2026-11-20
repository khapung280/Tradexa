package com.tradexa.dukascopy.diagnostic;

import java.io.IOException;
import java.io.InputStream;

final class CountingInputStream extends InputStream {
    private final InputStream delegate;
    private final HttpAttempt attempt;

    CountingInputStream(InputStream delegate, HttpAttempt attempt) {
        this.delegate = delegate;
        this.attempt = attempt;
    }

    public int read() throws IOException {
        int value = delegate.read();
        if (value >= 0) {
            attempt.addBytes(1);
        }
        return value;
    }

    public int read(byte[] buffer) throws IOException {
        int count = delegate.read(buffer);
        attempt.addBytes(count);
        return count;
    }

    public int read(byte[] buffer, int offset, int length) throws IOException {
        int count = delegate.read(buffer, offset, length);
        attempt.addBytes(count);
        return count;
    }

    public long skip(long count) throws IOException {
        long skipped = delegate.skip(count);
        attempt.addBytes(skipped);
        return skipped;
    }

    public int available() throws IOException {
        return delegate.available();
    }

    public void close() throws IOException {
        delegate.close();
    }

    public synchronized void mark(int readLimit) {
        delegate.mark(readLimit);
    }

    public synchronized void reset() throws IOException {
        delegate.reset();
    }

    public boolean markSupported() {
        return delegate.markSupported();
    }
}
