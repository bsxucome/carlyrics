package com.bsxu.carlyrics.phone.lyrics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PhoneLrcLibClientTest {

    @Test
    public void cleansCommonTitleSuffixes() {
        assertEquals("Song", PhoneLrcLibClient.cleanTitle("Song (Live Version)"));
        assertEquals("Song", PhoneLrcLibClient.cleanTitle("Song - Remastered 2025"));
        assertEquals("Song", PhoneLrcLibClient.cleanTitle("Song feat. Guest"));
    }

    @Test
    public void cleansAndSelectsFirstArtist() {
        assertEquals("Artist", PhoneLrcLibClient.cleanArtist("Artist feat. Guest"));
        assertEquals("Artist", PhoneLrcLibClient.firstArtistOnly("Artist, Guest"));
        assertEquals("Artist", PhoneLrcLibClient.firstArtistOnly("Artist & Guest"));
    }

    @Test
    public void normalizesPunctuationAndAmpersands() {
        assertEquals("rockandroll", PhoneLrcLibClient.normalize(" Rock & Roll! "));
        assertEquals("周杰伦晴天", PhoneLrcLibClient.normalize("周杰伦 - 晴天"));
    }
}
