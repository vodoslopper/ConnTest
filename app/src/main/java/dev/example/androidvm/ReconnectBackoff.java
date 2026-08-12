package dev.example.androidvm;

final class ReconnectBackoff {
    private static final long BASE_DELAY_MILLIS = 1_000L;
    private static final long MAX_DELAY_MILLIS = 60_000L;

    private ReconnectBackoff() {}

    static long delayMillis(int consecutiveFailures) {
        int shift = Math.max(0, Math.min(consecutiveFailures, 6));
        return Math.min(BASE_DELAY_MILLIS << shift, MAX_DELAY_MILLIS);
    }
}
