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
            object.put("role", message.role);
            object.put("deviceName", message.deviceName);
            object.put("versionName", message.versionName);
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

    public static DecodedMessage decode(String line) throws JSONException {
        JSONObject object = new JSONObject(line);
        String type = object.optString("type", "");
        if (BridgeContract.TYPE_HELLO.equals(type)) {
            return DecodedMessage.forHello(new HelloMessage(
                    object.optString("role", ""),
                    object.optString("deviceName", ""),
                    object.optString("versionName", "")
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
        throw new JSONException("Unknown message type: " + type);
    }
}
