package com.bsxu.carlyrics.bridge;

public final class PingMessage {

    public final long nonce;

    public PingMessage(long nonce) {
        this.nonce = Math.max(0L, nonce);
    }
}
