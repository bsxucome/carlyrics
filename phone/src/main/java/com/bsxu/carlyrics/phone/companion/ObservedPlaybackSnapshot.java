package com.bsxu.carlyrics.phone.companion;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.text.TextUtils;

public final class ObservedPlaybackSnapshot {

    public final String packageName;
    public final String title;
    public final String artist;
    public final String album;
    public final long durationMs;
    public final long positionMs;
    public final long lastPositionUpdateTimeMs;
    public final boolean playing;
    public final Bitmap artwork;

    public ObservedPlaybackSnapshot(
            String packageName,
            String title,
            String artist,
            String album,
            long durationMs,
            long positionMs,
            long lastPositionUpdateTimeMs,
            boolean playing,
            Bitmap artwork
    ) {
        this.packageName = safe(packageName);
        this.title = sanitizeTitle(title);
        this.artist = sanitizeArtist(artist);
        this.album = sanitizeAlbum(album);
        this.durationMs = Math.max(durationMs, 0L);
        this.positionMs = Math.max(positionMs, 0L);
        this.lastPositionUpdateTimeMs = Math.max(lastPositionUpdateTimeMs, 0L);
        this.playing = playing;
        this.artwork = artwork;
    }

    public boolean hasTrackData() {
        return !TextUtils.isEmpty(title) || !TextUtils.isEmpty(artist);
    }

    public long getEstimatedPositionMs() {
        if (!playing) {
            return positionMs;
        }
        long elapsed = lastPositionUpdateTimeMs > 0L
                ? Math.max(0L, SystemClock.elapsedRealtime() - lastPositionUpdateTimeMs)
                : 0L;
        long estimated = positionMs + elapsed;
        if (durationMs > 0L) {
            estimated = Math.min(estimated, durationMs);
        }
        return Math.max(0L, estimated);
    }

    public String getTrackKey() {
        return normalize(title)
                + "__"
                + normalize(artist)
                + "__"
                + normalize(album)
                + "__"
                + durationBucket(durationMs);
    }

    public ObservedPlaybackSnapshot copyWithArtwork(Bitmap newArtwork) {
        return new ObservedPlaybackSnapshot(
                packageName,
                title,
                artist,
                album,
                durationMs,
                positionMs,
                lastPositionUpdateTimeMs,
                playing,
                newArtwork
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String normalize(String value) {
        return safe(value).trim().toLowerCase();
    }

    private static String durationBucket(long durationMs) {
        if (durationMs <= 0L) {
            return "0";
        }
        return Long.toString(durationMs / 5000L);
    }

    private static String sanitizeTitle(String value) {
        String cleaned = stripSpotifySuffix(safe(value).trim());
        cleaned = cleaned.replaceAll("\\s*[-\\u2013\\u2014]\\s*Spotify$", "");
        return cleaned;
    }

    private static String sanitizeArtist(String value) {
        String cleaned = stripSpotifySuffix(safe(value).trim());
        cleaned = cleaned.replaceAll("\\s*[-\\u2013\\u2014]\\s*Spotify$", "");
        return cleaned;
    }

    private static String sanitizeAlbum(String value) {
        return stripSpotifySuffix(safe(value).trim());
    }

    private static String stripSpotifySuffix(String value) {
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        String[] separators = new String[]{
                " \u2022 ",
                " \u00b7 ",
                " \u2219 "
        };
        for (String separator : separators) {
            int separatorIndex = value.indexOf(separator);
            if (separatorIndex > 0) {
                return value.substring(0, separatorIndex).trim();
            }
        }
        return value;
    }
}
