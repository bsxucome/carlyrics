package com.bsxu.carlyrics.companion;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.text.TextUtils;

import com.bsxu.carlyrics.bridge.RemoteLyricsPayload;
import com.bsxu.carlyrics.bridge.RemotePlaybackPayload;
import com.bsxu.carlyrics.bridge.RemoteSessionStatusPayload;

public final class HeadUnitSessionSnapshot {

    public final int connectionState;
    public final String connectionLabel;
    public final RemoteSessionStatusPayload sessionStatusPayload;
    public final RemotePlaybackPayload playbackPayload;
    public final RemoteLyricsPayload lyricsPayload;
    public final Bitmap artworkBitmap;
    public final long playbackReceivedElapsedMs;

    public HeadUnitSessionSnapshot(
            int connectionState,
            String connectionLabel,
            RemoteSessionStatusPayload sessionStatusPayload,
            RemotePlaybackPayload playbackPayload,
            RemoteLyricsPayload lyricsPayload,
            Bitmap artworkBitmap,
            long playbackReceivedElapsedMs
    ) {
        this.connectionState = connectionState;
        this.connectionLabel = connectionLabel == null ? "" : connectionLabel;
        this.sessionStatusPayload = sessionStatusPayload;
        this.playbackPayload = playbackPayload;
        this.lyricsPayload = lyricsPayload;
        this.artworkBitmap = artworkBitmap;
        this.playbackReceivedElapsedMs = playbackReceivedElapsedMs;
    }

    public boolean isConnected() {
        return connectionState == ConnectionState.CONNECTED;
    }

    public boolean hasTrackData() {
        return playbackPayload != null
                && (!TextUtils.isEmpty(playbackPayload.title) || !TextUtils.isEmpty(playbackPayload.artist));
    }

    public long getEstimatedPositionMs() {
        if (playbackPayload == null) {
            return 0L;
        }
        if (!playbackPayload.playing) {
            return playbackPayload.positionMs;
        }
        long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - playbackReceivedElapsedMs);
        long estimated = playbackPayload.positionMs + elapsed;
        if (playbackPayload.durationMs > 0L) {
            estimated = Math.min(estimated, playbackPayload.durationMs);
        }
        return Math.max(0L, estimated);
    }
}
