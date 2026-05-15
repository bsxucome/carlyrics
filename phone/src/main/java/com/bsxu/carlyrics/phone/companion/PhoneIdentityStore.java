package com.bsxu.carlyrics.phone.companion;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.bsxu.carlyrics.bridge.HelloMessage;

import java.util.UUID;

final class PhoneIdentityStore {

    private static final String PREFS = "phone_companion_identity";
    private static final String KEY_LOCAL_APP_DEVICE_ID = "local_app_device_id";
    private static final String KEY_TRUSTED_REMOTE_APP_DEVICE_ID = "trusted_remote_app_device_id";
    private static final String KEY_TRUSTED_REMOTE_DEVICE_NAME = "trusted_remote_device_name";

    private final SharedPreferences sharedPreferences;

    PhoneIdentityStore(Context context) {
        this.sharedPreferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String getOrCreateLocalAppDeviceId() {
        String existing = sharedPreferences.getString(KEY_LOCAL_APP_DEVICE_ID, "");
        if (!TextUtils.isEmpty(existing)) {
            return existing;
        }
        String created = "phone-" + UUID.randomUUID().toString();
        sharedPreferences.edit().putString(KEY_LOCAL_APP_DEVICE_ID, created).apply();
        return created;
    }

    void rememberTrustedRemote(HelloMessage helloMessage) {
        if (helloMessage == null || TextUtils.isEmpty(helloMessage.appDeviceId)) {
            return;
        }
        sharedPreferences.edit()
                .putString(KEY_TRUSTED_REMOTE_APP_DEVICE_ID, helloMessage.appDeviceId)
                .putString(KEY_TRUSTED_REMOTE_DEVICE_NAME, helloMessage.deviceName)
                .apply();
    }

    String getTrustedRemoteAppDeviceId() {
        return sharedPreferences.getString(KEY_TRUSTED_REMOTE_APP_DEVICE_ID, "");
    }

    boolean hasTrustedRemoteAppDeviceId() {
        return !TextUtils.isEmpty(getTrustedRemoteAppDeviceId());
    }
}
