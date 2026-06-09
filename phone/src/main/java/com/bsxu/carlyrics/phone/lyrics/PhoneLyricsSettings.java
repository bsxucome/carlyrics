package com.bsxu.carlyrics.phone.lyrics;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public final class PhoneLyricsSettings {

    public static final String OFFICIAL_LRCLIB_BASE_URL = "https://lrclib.net";

    private static final String PREFS = "lyrics_settings";
    private static final String KEY_CUSTOM_LRCLIB_BASE_URL = "custom_lrclib_base_url";

    private final SharedPreferences sharedPreferences;

    public PhoneLyricsSettings(Context context) {
        sharedPreferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getCustomLrcLibBaseUrl() {
        return sharedPreferences.getString(KEY_CUSTOM_LRCLIB_BASE_URL, "");
    }

    public String getDisplayBaseUrl() {
        String customBaseUrl = getCustomLrcLibBaseUrl();
        return TextUtils.isEmpty(customBaseUrl)
                ? OFFICIAL_LRCLIB_BASE_URL
                : customBaseUrl;
    }

    public boolean setCustomLrcLibBaseUrl(String value) {
        String normalized = normalizeBaseUrl(value);
        if (normalized == null) {
            return false;
        }
        if (OFFICIAL_LRCLIB_BASE_URL.equalsIgnoreCase(normalized)) {
            clearCustomLrcLibBaseUrl();
            return true;
        }
        sharedPreferences.edit()
                .putString(KEY_CUSTOM_LRCLIB_BASE_URL, normalized)
                .apply();
        return true;
    }

    public void clearCustomLrcLibBaseUrl() {
        sharedPreferences.edit().remove(KEY_CUSTOM_LRCLIB_BASE_URL).apply();
    }

    public List<ProviderEndpoint> getProviderEndpoints() {
        ArrayList<ProviderEndpoint> endpoints = new ArrayList<ProviderEndpoint>();
        String customBaseUrl = getCustomLrcLibBaseUrl();
        if (!TextUtils.isEmpty(customBaseUrl)) {
            endpoints.add(new ProviderEndpoint(customBaseUrl, "LRCLIB mirror"));
        }
        endpoints.add(new ProviderEndpoint(OFFICIAL_LRCLIB_BASE_URL, "LRCLIB"));
        return endpoints;
    }

    static String normalizeBaseUrl(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            return OFFICIAL_LRCLIB_BASE_URL;
        }
        try {
            URL url = new URL(trimmed);
            String protocol = url.getProtocol();
            if (!"https".equalsIgnoreCase(protocol)) {
                return null;
            }
            if (isBlank(url.getHost())
                    || !isBlank(url.getQuery())
                    || !isBlank(url.getRef())
                    || !isBlank(url.getUserInfo())) {
                return null;
            }
            return trimmed;
        } catch (MalformedURLException ignored) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static final class ProviderEndpoint {
        public final String baseUrl;
        public final String sourceLabel;

        ProviderEndpoint(String baseUrl, String sourceLabel) {
            this.baseUrl = baseUrl;
            this.sourceLabel = sourceLabel;
        }
    }
}
