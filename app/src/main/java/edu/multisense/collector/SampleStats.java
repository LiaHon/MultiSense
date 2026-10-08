package edu.multisense.collector;

/** Sensor timestamps are elapsed-realtime nanoseconds, not wall-clock timestamps. */
final class SampleStats {
    long count, validCount, firstNs, lastNs, maxGapNs, nonMonotonic;
    double mean, m2;
    void add(long timestampNs, float[] values) {
        if (count == 0) firstNs = timestampNs;
        else {
            long gap = timestampNs - lastNs;
            if (gap <= 0) nonMonotonic++;
            else maxGapNs = Math.max(maxGapNs, gap);
        }
        lastNs = timestampNs;
        count++;
        if (values.length > 0 && Float.isFinite(values[0])) {
            validCount++;
            double delta = values[0] - mean;
            mean += delta / validCount;
            m2 += delta * (values[0] - mean);
        }
    }
    double hz() { return count > 1 && lastNs > firstNs ? (count - 1) * 1e9 / (lastNs - firstNs) : 0; }
    double variance() { return validCount > 1 ? m2 / (validCount - 1) : 0; }
}
