package com.bsxu.carlyrics.lyrics;

import com.bsxu.carlyrics.bridge.RemoteLyricsPayload;

public final class HeadUnitLyricsResult {

    public enum Status {
        SUCCESS,
        NOT_FOUND,
        NETWORK_ERROR,
        TIMEOUT,
        RESPONSE_ERROR
    }

    public final Status status;
    public final RemoteLyricsPayload payload;

    private HeadUnitLyricsResult(Status status, RemoteLyricsPayload payload) {
        this.status = status;
        this.payload = payload;
    }

    public static HeadUnitLyricsResult success(RemoteLyricsPayload payload) {
        return new HeadUnitLyricsResult(Status.SUCCESS, payload);
    }

    public static HeadUnitLyricsResult failure(Status status) {
        return new HeadUnitLyricsResult(status, null);
    }
}
