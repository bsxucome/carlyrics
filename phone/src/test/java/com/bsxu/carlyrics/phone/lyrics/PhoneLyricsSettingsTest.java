package com.bsxu.carlyrics.phone.lyrics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class PhoneLyricsSettingsTest {

    @Test
    public void normalizesMirrorBaseUrl() {
        assertEquals(
                "https://lyrics.example.com",
                PhoneLyricsSettings.normalizeBaseUrl(" https://lyrics.example.com/// ")
        );
    }

    @Test
    public void blankValueRestoresOfficialServer() {
        assertEquals(
                PhoneLyricsSettings.OFFICIAL_LRCLIB_BASE_URL,
                PhoneLyricsSettings.normalizeBaseUrl(" ")
        );
    }

    @Test
    public void rejectsUnsupportedOrAmbiguousUrls() {
        assertNull(PhoneLyricsSettings.normalizeBaseUrl("ftp://lyrics.example.com"));
        assertNull(PhoneLyricsSettings.normalizeBaseUrl("http://lyrics.example.com"));
        assertNull(PhoneLyricsSettings.normalizeBaseUrl("not a url"));
        assertNull(PhoneLyricsSettings.normalizeBaseUrl("https://lyrics.example.com?target=other"));
        assertNull(PhoneLyricsSettings.normalizeBaseUrl("https://user@lyrics.example.com"));
    }
}
