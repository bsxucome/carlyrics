package com.bsxu.carlyrics.lyrics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HeadUnitLyricsClientTest {

    @Test
    public void normalizesLatinAndChineseMetadata() {
        assertEquals("helloandworld", HeadUnitLyricsClient.normalize(" Hello & World! "));
        assertEquals("晴天", HeadUnitLyricsClient.normalize("《晴天》"));
    }
}
