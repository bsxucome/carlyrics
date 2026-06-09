package com.bsxu.carlyrics.bridge;

public final class ConnectionMaintenancePolicy {

    public static final long HANDSHAKE_TIMEOUT_MS = 6000L;
    public static final long KEEPALIVE_INTERVAL_MS = 4000L;
    public static final long IDLE_TIMEOUT_MS = 15000L;

    public enum Action {
        NONE,
        HANDSHAKE_TIMEOUT,
        IDLE_TIMEOUT,
        SEND_KEEPALIVE
    }

    private ConnectionMaintenancePolicy() {
    }

    public static Action evaluate(
            boolean handshakeComplete,
            long handshakeStartedElapsedMs,
            long lastInboundElapsedMs,
            long lastOutboundElapsedMs,
            long nowElapsedMs
    ) {
        if (!handshakeComplete) {
            return hasElapsed(
                    handshakeStartedElapsedMs,
                    nowElapsedMs,
                    HANDSHAKE_TIMEOUT_MS
            ) ? Action.HANDSHAKE_TIMEOUT : Action.NONE;
        }
        if (hasElapsed(lastInboundElapsedMs, nowElapsedMs, IDLE_TIMEOUT_MS)) {
            return Action.IDLE_TIMEOUT;
        }
        if (hasElapsedIncludingZero(
                lastOutboundElapsedMs,
                nowElapsedMs,
                KEEPALIVE_INTERVAL_MS
        )) {
            return Action.SEND_KEEPALIVE;
        }
        return Action.NONE;
    }

    public static long computeBackoffDelay(
            long initialDelayMs,
            long maxDelayMs,
            int attempt
    ) {
        if (initialDelayMs <= 0L || maxDelayMs < initialDelayMs) {
            throw new IllegalArgumentException("Invalid backoff bounds");
        }
        int remainingDoublings = Math.max(0, attempt);
        long delayMs = initialDelayMs;
        while (remainingDoublings > 0 && delayMs < maxDelayMs) {
            if (delayMs > maxDelayMs / 2L) {
                return maxDelayMs;
            }
            delayMs *= 2L;
            remainingDoublings--;
        }
        return Math.min(delayMs, maxDelayMs);
    }

    private static boolean hasElapsed(long startedAtMs, long nowMs, long intervalMs) {
        return startedAtMs > 0L && hasElapsedIncludingZero(startedAtMs, nowMs, intervalMs);
    }

    private static boolean hasElapsedIncludingZero(long startedAtMs, long nowMs, long intervalMs) {
        return nowMs >= startedAtMs && nowMs - startedAtMs >= intervalMs;
    }
}
