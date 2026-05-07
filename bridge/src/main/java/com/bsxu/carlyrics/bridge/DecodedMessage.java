package com.bsxu.carlyrics.bridge;

public final class DecodedMessage {

    public final String type;
    public final HelloMessage helloMessage;
    public final RemotePlaybackPayload playbackPayload;
    public final RemoteLyricsPayload lyricsPayload;
    public final ControlMessage controlMessage;

    private DecodedMessage(
            String type,
            HelloMessage helloMessage,
            RemotePlaybackPayload playbackPayload,
            RemoteLyricsPayload lyricsPayload,
            ControlMessage controlMessage
    ) {
        this.type = type;
        this.helloMessage = helloMessage;
        this.playbackPayload = playbackPayload;
        this.lyricsPayload = lyricsPayload;
        this.controlMessage = controlMessage;
    }

    public static DecodedMessage forHello(HelloMessage message) {
        return new DecodedMessage(BridgeContract.TYPE_HELLO, message, null, null, null);
    }

    public static DecodedMessage forPlayback(RemotePlaybackPayload payload) {
        return new DecodedMessage(BridgeContract.TYPE_PLAYBACK, null, payload, null, null);
    }

    public static DecodedMessage forLyrics(RemoteLyricsPayload payload) {
        return new DecodedMessage(BridgeContract.TYPE_LYRICS, null, null, payload, null);
    }

    public static DecodedMessage forControl(ControlMessage message) {
        return new DecodedMessage(BridgeContract.TYPE_CONTROL, null, null, null, message);
    }
}

