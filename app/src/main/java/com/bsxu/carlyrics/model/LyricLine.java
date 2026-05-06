package com.bsxu.carlyrics.model;

public final class LyricLine {

    private final long timeMs;
    private final String text;

    public LyricLine(long timeMs, String text) {
        this.timeMs = timeMs;
        this.text = text == null ? "" : text;
    }

    public long getTimeMs() {
        return timeMs;
    }

    public String getText() {
        return text;
    }
}

