package com.bsxu.carlyrics.bridge;

public final class DecodedMessage {

    public final String type;
    public final HelloMessage helloMessage;
    public final RemoteSessionStatusPayload sessionStatusPayload;
    public final RemotePlaybackPayload playbackPayload;
    public final RemoteLyricsPayload lyricsPayload;
    public final ControlMessage controlMessage;
    public final PingMessage pingMessage;

    private DecodedMessage(
            String type,
            HelloMessage helloMessage,
            RemoteSessionStatusPayload sessionStatusPayload,
            RemotePlaybackPayload playbackPayload,
            RemoteLyricsPayload lyricsPayload,
            ControlMessage controlMessage,
            PingMessage pingMessage
    ) {
        this.type = type;
        this.helloMessage = helloMessage;
        this.sessionStatusPayload = sessionStatusPayload;
        this.playbackPayload = playbackPayload;
        this.lyricsPayload = lyricsPayload;
        this.controlMessage = controlMessage;
        this.pingMessage = pingMessage;
    }

    public static DecodedMessage forHello(HelloMessage message) {
        return new DecodedMessage(BridgeContract.TYPE_HELLO, message, null, null, null, null, null);
    }

    public static DecodedMessage forSessionStatus(RemoteSessionStatusPayload payload) {
        return new DecodedMessage(BridgeContract.TYPE_SESSION_STATUS, null, payload, null, null, null, null);
    }

    public static DecodedMessage forPlayback(RemotePlaybackPayload payload) {
        return new DecodedMessage(BridgeContract.TYPE_PLAYBACK, null, null, payload, null, null, null);
    }

    public static DecodedMessage forLyrics(RemoteLyricsPayload payload) {
        return new DecodedMessage(BridgeContract.TYPE_LYRICS, null, null, null, payload, null, null);
    }

    public static DecodedMessage forControl(ControlMessage message) {
        return new DecodedMessage(BridgeContract.TYPE_CONTROL, null, null, null, null, message, null);
    }

    public static DecodedMessage forPing(PingMessage message) {
        return new DecodedMessage(BridgeContract.TYPE_PING, null, null, null, null, null, message);
    }

    public static DecodedMessage forPong(PingMessage message) {
        return new DecodedMessage(BridgeContract.TYPE_PONG, null, null, null, null, null, message);
    }
}
