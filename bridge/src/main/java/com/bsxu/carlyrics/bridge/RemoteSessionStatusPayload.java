package com.bsxu.carlyrics.bridge;

public final class RemoteSessionStatusPayload {

    public final boolean notificationAccessGranted;
    public final boolean notificationListenerActive;
    public final boolean mediaSessionReadable;
    public final boolean playbackAvailable;
    public final boolean lyricsAvailable;

    public RemoteSessionStatusPayload(
            boolean notificationAccessGranted,
            boolean notificationListenerActive,
            boolean mediaSessionReadable,
            boolean playbackAvailable,
            boolean lyricsAvailable
    ) {
        this.notificationAccessGranted = notificationAccessGranted;
        this.notificationListenerActive = notificationListenerActive;
        this.mediaSessionReadable = mediaSessionReadable;
        this.playbackAvailable = playbackAvailable;
        this.lyricsAvailable = lyricsAvailable;
    }
}
