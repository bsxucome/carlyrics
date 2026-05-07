package com.bsxu.carlyrics.bridge;

public final class RemoteLyricLine {

    public final long timeMs;
    public final String text;

    public RemoteLyricLine(long timeMs, String text) {
        this.timeMs = timeMs;
        this.text = text == null ? "" : text;
    }
}

