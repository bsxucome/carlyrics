package com.bsxu.carlyrics.phone.companion;

import android.content.Context;
import android.content.SharedPreferences;

public final class PhoneDebugScenarioStore {

    private static final String PREFS = "phone_debug_scenarios";
    private static final String KEY_NOTIFICATION_ACCESS = "notification_access";
    private static final String KEY_MEDIA_SESSION = "media_session";
    private static final String KEY_PLAYBACK = "playback";
    private static final String KEY_LYRICS = "lyrics";

    private final SharedPreferences sharedPreferences;

    public PhoneDebugScenarioStore(Context context) {
        this.sharedPreferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void clearOverrides() {
        sharedPreferences.edit().clear().commit();
    }

    public void setOverrides(Boolean notificationAccess, Boolean mediaSession, Boolean playback, Boolean lyrics) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        putNullable(editor, KEY_NOTIFICATION_ACCESS, notificationAccess);
        putNullable(editor, KEY_MEDIA_SESSION, mediaSession);
        putNullable(editor, KEY_PLAYBACK, playback);
        putNullable(editor, KEY_LYRICS, lyrics);
        editor.commit();
    }

    public boolean resolveNotificationAccess(boolean actual) {
        Boolean override = getNullable(KEY_NOTIFICATION_ACCESS);
        return override == null ? actual : override.booleanValue();
    }

    public boolean resolveMediaSession(boolean actual) {
        Boolean override = getNullable(KEY_MEDIA_SESSION);
        return override == null ? actual : override.booleanValue();
    }

    public boolean resolvePlayback(boolean actual) {
        Boolean override = getNullable(KEY_PLAYBACK);
        return override == null ? actual : override.booleanValue();
    }

    public boolean resolveLyrics(boolean actual) {
        Boolean override = getNullable(KEY_LYRICS);
        return override == null ? actual : override.booleanValue();
    }

    private void putNullable(SharedPreferences.Editor editor, String key, Boolean value) {
        if (value == null) {
            editor.remove(key);
        } else {
            editor.putString(key, Boolean.toString(value.booleanValue()));
        }
    }

    private Boolean getNullable(String key) {
        if (!sharedPreferences.contains(key)) {
            return null;
        }
        return Boolean.valueOf(sharedPreferences.getString(key, "false"));
    }
}
