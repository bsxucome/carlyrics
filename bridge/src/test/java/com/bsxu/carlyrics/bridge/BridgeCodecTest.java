package com.bsxu.carlyrics.bridge;

import org.json.JSONException;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class BridgeCodecTest {

    @Test
    public void helloRoundTripPreservesIdentityAndVersion() throws JSONException {
        HelloMessage source = new HelloMessage(2, "device-id", "phone", "Pixel", "0.2.12");

        DecodedMessage decoded = BridgeCodec.decode(BridgeCodec.encodeHello(source));

        assertEquals(BridgeContract.TYPE_HELLO, decoded.type);
        assertNotNull(decoded.helloMessage);
        assertEquals(2, decoded.helloMessage.protocolVersion);
        assertEquals("device-id", decoded.helloMessage.appDeviceId);
        assertEquals("phone", decoded.helloMessage.role);
        assertEquals("Pixel", decoded.helloMessage.deviceName);
        assertEquals("0.2.12", decoded.helloMessage.versionName);
    }

    @Test
    public void playbackRoundTripPreservesTimelineAndArtwork() throws JSONException {
        RemotePlaybackPayload source = new RemotePlaybackPayload(
                "track",
                "player.package",
                "Title",
                "Artist",
                "Album",
                180_000L,
                42_000L,
                true,
                "base64"
        );

        RemotePlaybackPayload decoded =
                BridgeCodec.decode(BridgeCodec.encodePlayback(source)).playbackPayload;

        assertNotNull(decoded);
        assertEquals("track", decoded.trackKey);
        assertEquals("player.package", decoded.packageName);
        assertEquals(180_000L, decoded.durationMs);
        assertEquals(42_000L, decoded.positionMs);
        assertTrue(decoded.playing);
        assertEquals("base64", decoded.artworkBase64);
    }

    @Test
    public void playbackDropsOversizedArtworkWithoutLosingMetadata() throws JSONException {
        StringBuilder oversizedArtwork =
                new StringBuilder(BridgeContract.MAX_ARTWORK_BASE64_CHARS + 1);
        for (int i = 0; i <= BridgeContract.MAX_ARTWORK_BASE64_CHARS; i++) {
            oversizedArtwork.append('A');
        }
        RemotePlaybackPayload source = new RemotePlaybackPayload(
                "track",
                "player.package",
                "Title",
                "Artist",
                "Album",
                180_000L,
                42_000L,
                true,
                oversizedArtwork.toString()
        );

        RemotePlaybackPayload decoded =
                BridgeCodec.decode(BridgeCodec.encodePlayback(source)).playbackPayload;

        assertNotNull(decoded);
        assertEquals("Title", decoded.title);
        assertEquals("", decoded.artworkBase64);
    }

    @Test
    public void lyricsRoundTripPreservesLines() throws JSONException {
        RemoteLyricsPayload source = new RemoteLyricsPayload(
                "track",
                "test",
                true,
                Arrays.asList(
                        new RemoteLyricLine(1_000L, "first"),
                        new RemoteLyricLine(2_000L, "")
                )
        );

        RemoteLyricsPayload decoded =
                BridgeCodec.decode(BridgeCodec.encodeLyrics(source)).lyricsPayload;

        assertNotNull(decoded);
        assertTrue(decoded.synced);
        assertEquals(2, decoded.lines.size());
        assertEquals(1_000L, decoded.lines.get(0).timeMs);
        assertEquals("first", decoded.lines.get(0).text);
        assertEquals("", decoded.lines.get(1).text);
    }

    @Test
    public void sessionControlAndHeartbeatRoundTrip() throws JSONException {
        RemoteSessionStatusPayload status = BridgeCodec.decode(
                BridgeCodec.encodeSessionStatus(
                        new RemoteSessionStatusPayload(true, true, true, true, false)
                )
        ).sessionStatusPayload;
        ControlMessage control = BridgeCodec.decode(
                BridgeCodec.encodeControl(new ControlMessage(BridgeContract.ACTION_NEXT))
        ).controlMessage;
        DecodedMessage ping = BridgeCodec.decode(BridgeCodec.encodePing(new PingMessage(123L)));
        DecodedMessage pong = BridgeCodec.decode(BridgeCodec.encodePong(new PingMessage(456L)));

        assertTrue(status.notificationAccessGranted);
        assertFalse(status.lyricsAvailable);
        assertEquals(BridgeContract.ACTION_NEXT, control.action);
        assertEquals(123L, ping.pingMessage.nonce);
        assertEquals(456L, pong.pingMessage.nonce);
    }

    @Test(expected = JSONException.class)
    public void unknownMessageTypeIsRejected() throws JSONException {
        BridgeCodec.decode("{\"type\":\"unsupported\"}");
    }

    @Test(expected = JSONException.class)
    public void oversizedMessageIsRejectedBeforeParsing() throws JSONException {
        StringBuilder message = new StringBuilder(BridgeContract.MAX_MESSAGE_CHARS + 1);
        for (int i = 0; i <= BridgeContract.MAX_MESSAGE_CHARS; i++) {
            message.append('x');
        }

        BridgeCodec.decode(message.toString());
    }

    @Test
    public void protocolCompatibilityUsesDeclaredRange() {
        assertFalse(BridgeContract.isProtocolSupported(
                BridgeContract.MIN_SUPPORTED_PROTOCOL_VERSION - 1
        ));
        assertTrue(BridgeContract.isProtocolSupported(BridgeContract.PROTOCOL_VERSION));
        assertFalse(BridgeContract.isProtocolSupported(
                BridgeContract.MAX_SUPPORTED_PROTOCOL_VERSION + 1
        ));
    }
}
