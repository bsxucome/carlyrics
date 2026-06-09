package com.bsxu.carlyrics.phone.lyrics;

public interface PhoneLyricsProvider {

    PhoneLyricsResult fetch(
            String trackKey,
            String title,
            String artist,
            String album,
            long durationMs,
            long timeoutMs
    );
}
