package com.bsxu.carlyrics.phone.lyrics;

import com.bsxu.carlyrics.bridge.RemoteLyricLine;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PhoneLrcParserTest {

    @Test
    public void parseSyncedLyricsSupportsFractionsAndSortsLines() {
        List<RemoteLyricLine> lines = PhoneLrcParser.parseSyncedLyrics(
                "[01:02.345]third\n"
                        + "[00:03.4]first\n"
                        + "[00:12.34]second"
        );

        assertEquals(3, lines.size());
        assertLine(lines.get(0), 3_400L, "first");
        assertLine(lines.get(1), 12_340L, "second");
        assertLine(lines.get(2), 62_345L, "third");
    }

    @Test
    public void parseSyncedLyricsExpandsMultipleTimestamps() {
        List<RemoteLyricLine> lines =
                PhoneLrcParser.parseSyncedLyrics("[00:01.00][00:02.50]repeat");

        assertEquals(2, lines.size());
        assertLine(lines.get(0), 1_000L, "repeat");
        assertLine(lines.get(1), 2_500L, "repeat");
    }

    @Test
    public void parseSyncedLyricsKeepsTimedBlankLinesForGapRendering() {
        List<RemoteLyricLine> lines =
                PhoneLrcParser.parseSyncedLyrics("[00:01.00]line\n[00:02.00]");

        assertEquals(2, lines.size());
        assertLine(lines.get(1), 2_000L, "");
    }

    @Test
    public void parsePlainLyricsTrimsAndSkipsBlankLines() {
        List<RemoteLyricLine> lines =
                PhoneLrcParser.parsePlainLyrics(" first \n\n  second  ");

        assertEquals(2, lines.size());
        assertLine(lines.get(0), -1L, "first");
        assertLine(lines.get(1), -1L, "second");
    }

    @Test
    public void parseEmptyLyricsReturnsEmptyList() {
        assertTrue(PhoneLrcParser.parseSyncedLyrics("  ").isEmpty());
        assertTrue(PhoneLrcParser.parsePlainLyrics(null).isEmpty());
    }

    private static void assertLine(RemoteLyricLine line, long timeMs, String text) {
        assertEquals(timeMs, line.timeMs);
        assertEquals(text, line.text);
    }
}
