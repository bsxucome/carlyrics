package com.bsxu.carlyrics.bridge;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class BridgeCodec {

    private BridgeCodec() {
    }

    public static String encodeHello(HelloMessage message) {
        try {
            JSONObject object = new JSONObject();
            object.put("type", BridgeContract.TYPE_HELLO);
            object.put("protocolVersion", message.protocolVersion);
            object.put("appDeviceId", message.appDeviceId);
            object.put("role", message.role);
            object.put("deviceName", message.deviceName);
            object.put("versionName", message.versionName);
            return object.toString();
        } catch (JSONException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static String encodeSessionStatus(RemoteSessionStatusPayload payload) {
        try {
            JSONObject object = new JSONObject();
            object.put("type", BridgeContract.TYPE_SESSION_STATUS);
            object.put("notificationAccessGranted", payload.notificationAccessGranted);
            object.put("notificationListenerActive", payload.notificationListenerActive);
            object.put("mediaSessionReadable", payload.mediaSessionReadable);
            object.put("playbackAvailable", payload.playbackAvailable);
            object.put("lyricsAvailable", payload.lyricsAvailable);
            return object.toString();
        } catch (JSONException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static String encodePlayback(RemotePlaybackPayload payload) {
        try {
            JSONObject object = new JSONObject();
            object.put("type", BridgeContract.TYPE_PLAYBACK);
            object.put("trackKey", payload.trackKey);
            object.put("packageName", payload.packageName);
            object.put("title", payload.title);
            object.put("artist", payload.artist);
            object.put("album", payload.album);
            object.put("durationMs", payload.durationMs);
            object.put("positionMs", payload.positionMs);
            object.put("playing", payload.playing);
            object.put("artworkBase64", payload.artworkBase64);
            return object.toString();
        } catch (JSONException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static String encodeLyrics(RemoteLyricsPayload payload) {
        try {
            JSONObject object = new JSONObject();
            object.put("type", BridgeContract.TYPE_LYRICS);
            object.put("trackKey", payload.trackKey);
            object.put("sourceLabel", payload.sourceLabel);
            object.put("synced", payload.synced);
            JSONArray lines = new JSONArray();
            for (RemoteLyricLine line : payload.lines) {
                JSONObject lineObject = new JSONObject();
                lineObject.put("timeMs", line.timeMs);
                lineObject.put("text", line.text);
                lines.put(lineObject);
            }
            object.put("lines", lines);
            return object.toString();
        } catch (JSONException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static String encodeControl(ControlMessage message) {
        try {
            JSONObject object = new JSONObject();
            object.put("type", BridgeContract.TYPE_CONTROL);
            object.put("action", message.action);
            return object.toString();
        } catch (JSONException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static String encodePing(PingMessage message) {
        return encodePingLikeMessage(BridgeContract.TYPE_PING, message);
    }

    public static String encodePong(PingMessage message) {
        return encodePingLikeMessage(BridgeContract.TYPE_PONG, message);
    }

    public static DecodedMessage decode(String line) throws JSONException {
        if (line == null) {
            throw new JSONException("Message must not be null");
        }
        if (line.length() > BridgeContract.MAX_MESSAGE_CHARS) {
            throw new JSONException("Message exceeds protocol size limit");
        }
        JSONObject object = new JSONObject(line);
        String type = object.optString("type", "");
        if (BridgeContract.TYPE_HELLO.equals(type)) {
            return DecodedMessage.forHello(new HelloMessage(
                    object.optInt("protocolVersion", 1),
                    object.optString("appDeviceId", ""),
                    object.optString("role", ""),
                    object.optString("deviceName", ""),
                    object.optString("versionName", "")
            ));
        }
        if (BridgeContract.TYPE_SESSION_STATUS.equals(type)) {
            return DecodedMessage.forSessionStatus(new RemoteSessionStatusPayload(
                    object.optBoolean("notificationAccessGranted", false),
                    object.optBoolean("notificationListenerActive", false),
                    object.optBoolean("mediaSessionReadable", false),
                    object.optBoolean("playbackAvailable", false),
                    object.optBoolean("lyricsAvailable", false)
            ));
        }
        if (BridgeContract.TYPE_PLAYBACK.equals(type)) {
            return DecodedMessage.forPlayback(new RemotePlaybackPayload(
                    object.optString("trackKey", ""),
                    object.optString("packageName", ""),
                    object.optString("title", ""),
                    object.optString("artist", ""),
                    object.optString("album", ""),
                    object.optLong("durationMs", 0L),
                    object.optLong("positionMs", 0L),
                    object.optBoolean("playing", false),
                    object.optString("artworkBase64", "")
            ));
        }
        if (BridgeContract.TYPE_LYRICS.equals(type)) {
            JSONArray lineArray = object.optJSONArray("lines");
            List<RemoteLyricLine> lines = new ArrayList<RemoteLyricLine>();
            if (lineArray != null) {
                for (int i = 0; i < lineArray.length(); i++) {
                    JSONObject lineObject = lineArray.optJSONObject(i);
                    if (lineObject == null) {
                        continue;
                    }
                    lines.add(new RemoteLyricLine(
                            lineObject.optLong("timeMs", -1L),
                            lineObject.optString("text", "")
                    ));
                }
            }
            return DecodedMessage.forLyrics(new RemoteLyricsPayload(
                    object.optString("trackKey", ""),
                    object.optString("sourceLabel", ""),
                    object.optBoolean("synced", false),
                    lines
            ));
        }
        if (BridgeContract.TYPE_CONTROL.equals(type)) {
            return DecodedMessage.forControl(new ControlMessage(object.optString("action", "")));
        }
        if (BridgeContract.TYPE_PING.equals(type)) {
            return DecodedMessage.forPing(new PingMessage(object.optLong("nonce", 0L)));
        }
        if (BridgeContract.TYPE_PONG.equals(type)) {
            return DecodedMessage.forPong(new PingMessage(object.optLong("nonce", 0L)));
        }
        throw new JSONException("Unknown message type: " + type);
    }

    private static String encodePingLikeMessage(String type, PingMessage message) {
        try {
            JSONObject object = new JSONObject();
            object.put("type", type);
            object.put("nonce", message == null ? 0L : message.nonce);
            return object.toString();
        } catch (JSONException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
