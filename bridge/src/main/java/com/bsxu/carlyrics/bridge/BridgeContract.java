package com.bsxu.carlyrics.bridge;

public final class BridgeContract {

    public static final String APP_UUID = "26cc8509-2f07-4f09-8c13-4c0d6035d391";
    public static final int PROTOCOL_VERSION = 2;

    public static final String TYPE_HELLO = "hello";
    public static final String TYPE_SESSION_STATUS = "session_status";
    public static final String TYPE_PLAYBACK = "playback";
    public static final String TYPE_LYRICS = "lyrics";
    public static final String TYPE_CONTROL = "control";
    public static final String TYPE_PING = "ping";
    public static final String TYPE_PONG = "pong";

    public static final String ROLE_HEADUNIT = "headunit";
    public static final String ROLE_PHONE = "phone";

    public static final String ACTION_PLAY_PAUSE = "play_pause";
    public static final String ACTION_NEXT = "next";
    public static final String ACTION_PREVIOUS = "previous";
    public static final String ACTION_RESEND_LYRICS = "resend_lyrics";

    private BridgeContract() {
    }
}
