package edu.multisense.collector;

/** Explicit periods in microseconds; zero means event driven. No Android dependency. */
final class SamplingPolicy {
    static int motionHz(int hz) { return hz == 100 ? 100 : 200; }
    static boolean isMotion(int type) { return type == 1 || type == 4 || type == 9 || type == 10; }
    static int periodUs(int type, int hz, int reportingMode) {
        if (reportingMode != 0) return 0;
        if (isMotion(type) || type == 16 || type == 35) return 1_000_000 / motionHz(hz);
        return switch (type) {
            case 2, 14 -> 20_000; // magnetic field: 50 Hz
            case 11, 15, 20 -> 10_000; // rotation vectors: 100 Hz
            default -> 200_000; // SENSOR_DELAY_NORMAL, including slow environmental channels
        };
    }
}
