package com.bsxu.carlyrics.model;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.text.TextUtils;

public final class PlaybackSnapshot {

    public static final int SOURCE_UNKNOWN = 0;
    public static final int SOURCE_MEDIA_SESSION = 1;
    public static final int SOURCE_NOTIFICATION = 2;

    private final String packageName;
    private final String title;
    private final String artist;
    private final String album;
    private final long durationMs;
    private final long positionMs;
    private final long lastPositionUpdateTimeMs;
    private final boolean playing;
    private final Bitmap artwork;
    private final int sourceType;

    public PlaybackSnapshot(
            String packageName,
            String title,
            String artist,
            String album,
            long durationMs,
            long positionMs,
            long lastPositionUpdateTimeMs,
            boolean playing,
            Bitmap artwork,
            int sourceType
    ) {
        this.packageName = packageName == null ? "" : packageName;
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        this.album = album == null ? "" : album;
        this.durationMs = Math.max(durationMs, 0L);
        this.positionMs = Math.max(positionMs, 0L);
        this.lastPositionUpdateTimeMs = Math.max(lastPositionUpdateTimeMs, 0L);
        this.playing = playing;
        this.artwork = artwork;
        this.sourceType = sourceType;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getTitle() {
        return title;
    }

    public String getArtist() {
        return artist;
    }

    public String getAlbum() {
        return album;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public long getPositionMs() {
        return positionMs;
    }

    public long getLastPositionUpdateTimeMs() {
        return lastPositionUpdateTimeMs;
    }

    public boolean isPlaying() {
        return playing;
    }

    public Bitmap getArtwork() {
        return artwork;
    }

    public int getSourceType() {
        return sourceType;
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

    public boolean isSameTrack(PlaybackSnapshot other) {
        if (other == null) {
            return false;
        }
        return TextUtils.equals(getTrackKey(), other.getTrackKey());
    }

    public boolean hasArtwork() {
        return artwork != null;
    }

    public PlaybackSnapshot mergeWithSupplementalData(Bitmap newArtwork, String newAlbum) {
        Bitmap mergedArtwork = artwork != null ? artwork : newArtwork;
        String mergedAlbum = !TextUtils.isEmpty(album) ? album : newAlbum;
        return new PlaybackSnapshot(
                packageName,
                title,
                artist,
                mergedAlbum,
                durationMs,
                positionMs,
                lastPositionUpdateTimeMs,
                playing,
                mergedArtwork,
                sourceType
        );
    }

    public PlaybackSnapshot copyWithArtwork(Bitmap newArtwork) {
        return new PlaybackSnapshot(
                packageName,
                title,
                artist,
                album,
                durationMs,
                positionMs,
                lastPositionUpdateTimeMs,
                playing,
                newArtwork,
                sourceType
        );
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase();
    }

    private static String durationBucket(long durationMs) {
        if (durationMs <= 0L) {
            return "0";
        }
        return Long.toString(durationMs / 5000L);
    }
}
