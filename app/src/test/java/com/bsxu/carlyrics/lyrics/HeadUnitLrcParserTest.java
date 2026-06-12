package com.bsxu.carlyrics.lyrics;

import com.bsxu.carlyrics.bridge.RemoteLyricLine;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class HeadUnitLrcParserTest {

    @Test
    public void parsesAndSortsSyncedLyrics() {
        List<RemoteLyricLine> lines = HeadUnitLrcParser.parseSynced(
                "[00:02.50]second\n[00:01.005]first"
        );

        assertEquals(2, lines.size());
        assertEquals(1005L, lines.get(0).timeMs);
        assertEquals("first", lines.get(0).text);
        assertEquals(2500L, lines.get(1).timeMs);
    }

    @Test
    public void parsesPlainLyrics() {
        List<RemoteLyricLine> lines = HeadUnitLrcParser.parsePlain("first\n\nsecond");

        assertEquals(2, lines.size());
        assertEquals(-1L, lines.get(0).timeMs);
        assertEquals("second", lines.get(1).text);
    }
}
