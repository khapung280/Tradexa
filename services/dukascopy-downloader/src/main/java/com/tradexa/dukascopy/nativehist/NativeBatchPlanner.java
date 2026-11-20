package com.tradexa.dukascopy.nativehist;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits UTC dates into sequential batches of at most 100 dates.
 */
public final class NativeBatchPlanner {
    public static final int MAX_BATCH_DATES = 100;

    private NativeBatchPlanner() {
    }

    public static int batchCount(int dateCount, int batchSize) {
        requireBatchSize(batchSize);
        if (dateCount < 0) {
            throw new IllegalArgumentException("Date count is negative");
        }
        if (dateCount == 0) {
            return 0;
        }
        return (dateCount + batchSize - 1) / batchSize;
    }

    public static List<String> batch(List<String> dates, int batchIndex, int batchSize) {
        requireBatchSize(batchSize);
        if (batchIndex < 0) {
            throw new IllegalArgumentException("Batch index is negative");
        }
        int from = batchIndex * batchSize;
        if (from >= dates.size()) {
            return new ArrayList<String>();
        }
        int to = Math.min(dates.size(), from + batchSize);
        return new ArrayList<String>(dates.subList(from, to));
    }

    public static void requireBatchSize(int batchSize) {
        if (batchSize < 1 || batchSize > MAX_BATCH_DATES) {
            throw new IllegalArgumentException("Batch size must be from 1 through 100");
        }
    }
}
