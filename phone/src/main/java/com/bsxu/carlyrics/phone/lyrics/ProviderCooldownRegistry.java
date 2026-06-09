package com.bsxu.carlyrics.phone.lyrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ProviderCooldownRegistry {

    private static final long DEFAULT_COOLDOWN_MS = 30000L;
    private static final long MAX_COOLDOWN_MS = 5L * 60L * 1000L;
    private static final Map<String, Long> BLOCKED_UNTIL_MS =
            new ConcurrentHashMap<String, Long>();

    private ProviderCooldownRegistry() {
    }

    static boolean isBlocked(String providerKey, long nowMs) {
        Long blockedUntilMs = BLOCKED_UNTIL_MS.get(providerKey);
        if (blockedUntilMs == null) {
            return false;
        }
        if (blockedUntilMs <= nowMs) {
            Long currentValue = BLOCKED_UNTIL_MS.get(providerKey);
            if (blockedUntilMs.equals(currentValue)) {
                BLOCKED_UNTIL_MS.remove(providerKey);
            }
            return false;
        }
        return true;
    }

    static void recordResponse(
            String providerKey,
            int responseCode,
            String retryAfterHeader,
            long nowMs
    ) {
        if (responseCode >= 200 && responseCode < 300) {
            BLOCKED_UNTIL_MS.remove(providerKey);
            return;
        }
        if (responseCode != 429 && responseCode != 503) {
            return;
        }
        long cooldownMs = parseRetryAfterMs(retryAfterHeader);
        BLOCKED_UNTIL_MS.put(providerKey, nowMs + cooldownMs);
    }

    static long parseRetryAfterMs(String retryAfterHeader) {
        if (retryAfterHeader == null) {
            return DEFAULT_COOLDOWN_MS;
        }
        try {
            long seconds = Long.parseLong(retryAfterHeader.trim());
            if (seconds <= 0L) {
                return DEFAULT_COOLDOWN_MS;
            }
            if (seconds >= MAX_COOLDOWN_MS / 1000L) {
                return MAX_COOLDOWN_MS;
            }
            return seconds * 1000L;
        } catch (NumberFormatException ignored) {
            return DEFAULT_COOLDOWN_MS;
        }
    }

    static void clearForTests() {
        BLOCKED_UNTIL_MS.clear();
    }
}
