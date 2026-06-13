package com.bsxu.carlyrics.lyrics;

import com.bsxu.carlyrics.bridge.RemotePlaybackPayload;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class HeadUnitLyricsClientTest {

    @Test
    public void normalizesLatinAndChineseMetadata() {
        assertEquals("helloandworld", HeadUnitLyricsClient.normalize(" Hello & World! "));
        assertEquals("晴天", HeadUnitLyricsClient.normalize("《晴天》"));
    }

    @Test
    public void reportsNotFoundWhenTitleIsMissing() {
        RemotePlaybackPayload playback = new RemotePlaybackPayload(
                "track",
                "player",
                "",
                "artist",
                "",
                0L,
                0L,
                false,
                ""
        );

        HeadUnitLyricsResult result = new HeadUnitLyricsClient().fetch(playback, 1000L);

        assertEquals(HeadUnitLyricsResult.Status.NOT_FOUND, result.status);
        assertNull(result.payload);
    }
}
