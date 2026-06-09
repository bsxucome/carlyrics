package com.bsxu.carlyrics.bridge;

public final class ConnectionSessionTracker {

    private volatile boolean handshakeComplete;
    private volatile long handshakeStartedElapsedMs;
    private volatile long handshakeCompletedElapsedMs;
    private volatile long lastInboundElapsedMs;
    private volatile long lastOutboundElapsedMs;

    public void begin(long nowElapsedMs) {
        handshakeComplete = false;
        handshakeStartedElapsedMs = nowElapsedMs;
        handshakeCompletedElapsedMs = 0L;
        lastInboundElapsedMs = nowElapsedMs;
        lastOutboundElapsedMs = 0L;
    }

    public void completeHandshake(long nowElapsedMs) {
        handshakeComplete = true;
        handshakeStartedElapsedMs = 0L;
        handshakeCompletedElapsedMs = nowElapsedMs;
        lastInboundElapsedMs = nowElapsedMs;
        lastOutboundElapsedMs = 0L;
    }

    public void noteInbound(long nowElapsedMs) {
        lastInboundElapsedMs = nowElapsedMs;
    }

    public void noteOutbound(long nowElapsedMs) {
        lastOutboundElapsedMs = nowElapsedMs;
    }

    public void reset() {
        handshakeComplete = false;
        handshakeStartedElapsedMs = 0L;
        handshakeCompletedElapsedMs = 0L;
        lastInboundElapsedMs = 0L;
        lastOutboundElapsedMs = 0L;
    }

    public boolean isHandshakeComplete() {
        return handshakeComplete;
    }

    public long getHandshakeCompletedElapsedMs() {
        return handshakeCompletedElapsedMs;
    }

    public ConnectionMaintenancePolicy.Action evaluate(long nowElapsedMs) {
        return ConnectionMaintenancePolicy.evaluate(
                handshakeComplete,
                handshakeStartedElapsedMs,
                lastInboundElapsedMs,
                lastOutboundElapsedMs,
                nowElapsedMs
        );
    }
}
