package com.bsxu.carlyrics.companion;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.bsxu.carlyrics.bridge.HelloMessage;

import java.util.UUID;

final class HeadUnitIdentityStore {

    private static final String PREFS = "headunit_identity";
    private static final String KEY_LOCAL_APP_DEVICE_ID = "local_app_device_id";
    private static final String KEY_PRIMARY_REMOTE_APP_DEVICE_ID = "primary_remote_app_device_id";
    private static final String KEY_PRIMARY_REMOTE_DEVICE_NAME = "primary_remote_device_name";
    private static final String KEY_PRIMARY_REMOTE_BLUETOOTH_ADDRESS = "primary_remote_bluetooth_address";

    private final SharedPreferences sharedPreferences;

    HeadUnitIdentityStore(Context context) {
        this.sharedPreferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String getOrCreateLocalAppDeviceId() {
        String existing = sharedPreferences.getString(KEY_LOCAL_APP_DEVICE_ID, "");
        if (!TextUtils.isEmpty(existing)) {
            return existing;
        }
        String created = "headunit-" + UUID.randomUUID().toString();
        sharedPreferences.edit().putString(KEY_LOCAL_APP_DEVICE_ID, created).apply();
        return created;
    }

    void rememberTrustedRemote(HelloMessage helloMessage, String bluetoothAddress, String bluetoothName) {
        if (helloMessage == null
                || TextUtils.isEmpty(helloMessage.appDeviceId)
                || TextUtils.isEmpty(bluetoothAddress)) {
            return;
        }
        sharedPreferences.edit()
                .putString(KEY_PRIMARY_REMOTE_APP_DEVICE_ID, helloMessage.appDeviceId)
                .putString(
                        KEY_PRIMARY_REMOTE_DEVICE_NAME,
                        TextUtils.isEmpty(bluetoothName) ? helloMessage.deviceName : bluetoothName
                )
                .putString(KEY_PRIMARY_REMOTE_BLUETOOTH_ADDRESS, bluetoothAddress)
                .apply();
    }

    String getPrimaryTrustedRemoteAppDeviceId() {
        return sharedPreferences.getString(KEY_PRIMARY_REMOTE_APP_DEVICE_ID, "");
    }

    String getPrimaryTrustedBluetoothAddress() {
        return sharedPreferences.getString(KEY_PRIMARY_REMOTE_BLUETOOTH_ADDRESS, "");
    }

    String getPrimaryTrustedDeviceName() {
        return sharedPreferences.getString(KEY_PRIMARY_REMOTE_DEVICE_NAME, "");
    }

    boolean hasPrimaryTrustedDevice() {
        return !TextUtils.isEmpty(getPrimaryTrustedRemoteAppDeviceId())
                && !TextUtils.isEmpty(getPrimaryTrustedBluetoothAddress());
    }

    boolean isPrimaryTrustedAddress(String bluetoothAddress) {
        return !TextUtils.isEmpty(bluetoothAddress)
                && TextUtils.equals(getPrimaryTrustedBluetoothAddress(), bluetoothAddress);
    }

    void clearPrimaryTrustedRemote() {
        sharedPreferences.edit()
                .remove(KEY_PRIMARY_REMOTE_APP_DEVICE_ID)
                .remove(KEY_PRIMARY_REMOTE_DEVICE_NAME)
                .remove(KEY_PRIMARY_REMOTE_BLUETOOTH_ADDRESS)
                .apply();
    }
}
