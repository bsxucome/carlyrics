package com.bsxu.carlyrics.bridge;

public final class RemotePlaybackPayload {

    public final String trackKey;
    public final String packageName;
    public final String title;
    public final String artist;
    public final String album;
    public final long durationMs;
    public final long positionMs;
    public final boolean playing;
    public final String artworkBase64;

    public RemotePlaybackPayload(
            String trackKey,
            String packageName,
            String title,
            String artist,
            String album,
            long durationMs,
            long positionMs,
            boolean playing,
            String artworkBase64
    ) {
        this.trackKey = trackKey == null ? "" : trackKey;
        this.packageName = packageName == null ? "" : packageName;
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        this.album = album == null ? "" : album;
        this.durationMs = Math.max(durationMs, 0L);
        this.positionMs = Math.max(positionMs, 0L);
        this.playing = playing;
        this.artworkBase64 = artworkBase64 == null ? "" : artworkBase64;
    }
}

