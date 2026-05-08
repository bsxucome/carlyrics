package com.bsxu.carlyrics.phone;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import com.bsxu.carlyrics.phone.companion.PhoneDebugScenarioStore;
import com.bsxu.carlyrics.phone.companion.PhoneConnectionService;
import com.bsxu.carlyrics.phone.companion.PhoneCompanionService;

public class PhoneMainActivity extends Activity {

    private static final String TAG = "PhoneMain";
    private static final String NOTIFICATION_LISTENER_SETTINGS_ACTION =
            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS";
    private static final int REQUEST_BLUETOOTH_CONNECT = 501;
    private static final String EXTRA_DEBUG_CLEAR_OVERRIDES = "debug_clear_overrides";
    private static final String EXTRA_DEBUG_NOTIFICATION_ACCESS = "debug_notification_access";
    private static final String EXTRA_DEBUG_MEDIA_SESSION = "debug_media_session";
    private static final String EXTRA_DEBUG_PLAYBACK = "debug_playback";
    private static final String EXTRA_DEBUG_LYRICS = "debug_lyrics";

    private TextView statusView;
    private Button notificationAccessButton;
    private Button bluetoothPermissionButton;
    private PhoneDebugScenarioStore debugScenarioStore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_phone_main);

        statusView = (TextView) findViewById(R.id.statusView);
        notificationAccessButton = (Button) findViewById(R.id.notificationAccessButton);
        bluetoothPermissionButton = (Button) findViewById(R.id.bluetoothPermissionButton);
        debugScenarioStore = new PhoneDebugScenarioStore(this);

        notificationAccessButton.setOnClickListener(v ->
                startActivity(new Intent(NOTIFICATION_LISTENER_SETTINGS_ACTION)));
        bluetoothPermissionButton.setOnClickListener(v -> requestBluetoothPermissionIfNeeded());

        handleDebugOverrides(getIntent());
        ensureConnectionServiceRunning();
        renderStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasNotificationAccess() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            NotificationListenerService.requestRebind(
                    new ComponentName(this, PhoneCompanionService.class)
            );
        }
        ensureConnectionServiceRunning();
        renderStatus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDebugOverrides(intent);
        renderStatus();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH_CONNECT) {
            renderStatus();
        }
    }

    private void requestBluetoothPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            renderStatus();
            return;
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            renderStatus();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH_CONNECT);
    }

    private void renderStatus() {
        boolean notificationAccess = hasNotificationAccess();
        boolean bluetoothGranted = hasBluetoothPermission();

        if (!notificationAccess) {
            statusView.setText(R.string.status_permission_missing);
        } else if (!bluetoothGranted) {
            statusView.setText(R.string.status_bluetooth_missing);
        } else {
            String serviceStatus = PhoneConnectionService.getUiStatus();
            statusView.setText(TextUtils.isEmpty(serviceStatus) ? getString(R.string.status_ready) : serviceStatus);
        }

        bluetoothPermissionButton.setEnabled(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED);
    }

    private boolean hasNotificationAccess() {
        String enabledListeners = Settings.Secure.getString(
                getContentResolver(),
                "enabled_notification_listeners"
        );
        if (TextUtils.isEmpty(enabledListeners)) {
            return false;
        }
        ComponentName componentName = new ComponentName(this, PhoneCompanionService.class);
        return enabledListeners.contains(componentName.flattenToString())
                || enabledListeners.contains(componentName.flattenToShortString())
                || enabledListeners.contains(getPackageName());
    }

    private boolean hasBluetoothPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureConnectionServiceRunning() {
        startService(new Intent(this, PhoneConnectionService.class));
    }

    private void handleDebugOverrides(Intent intent) {
        if (intent == null || debugScenarioStore == null) {
            return;
        }
        if (intent.getBooleanExtra(EXTRA_DEBUG_CLEAR_OVERRIDES, false)) {
            debugScenarioStore.clearOverrides();
            Log.i(TAG, "Cleared debug scenario overrides");
            intent.removeExtra(EXTRA_DEBUG_CLEAR_OVERRIDES);
            return;
        }

        boolean hasAnyOverride = intent.hasExtra(EXTRA_DEBUG_NOTIFICATION_ACCESS)
                || intent.hasExtra(EXTRA_DEBUG_MEDIA_SESSION)
                || intent.hasExtra(EXTRA_DEBUG_PLAYBACK)
                || intent.hasExtra(EXTRA_DEBUG_LYRICS);
        if (!hasAnyOverride) {
            return;
        }

        debugScenarioStore.setOverrides(
                intent.hasExtra(EXTRA_DEBUG_NOTIFICATION_ACCESS)
                        ? Boolean.valueOf(intent.getBooleanExtra(EXTRA_DEBUG_NOTIFICATION_ACCESS, false))
                        : null,
                intent.hasExtra(EXTRA_DEBUG_MEDIA_SESSION)
                        ? Boolean.valueOf(intent.getBooleanExtra(EXTRA_DEBUG_MEDIA_SESSION, false))
                        : null,
                intent.hasExtra(EXTRA_DEBUG_PLAYBACK)
                        ? Boolean.valueOf(intent.getBooleanExtra(EXTRA_DEBUG_PLAYBACK, false))
                        : null,
                intent.hasExtra(EXTRA_DEBUG_LYRICS)
                        ? Boolean.valueOf(intent.getBooleanExtra(EXTRA_DEBUG_LYRICS, false))
                        : null
        );
        Log.i(
                TAG,
                "Applied debug overrides notif="
                        + (intent.hasExtra(EXTRA_DEBUG_NOTIFICATION_ACCESS)
                        ? intent.getBooleanExtra(EXTRA_DEBUG_NOTIFICATION_ACCESS, false)
                        : null)
                        + " media="
                        + (intent.hasExtra(EXTRA_DEBUG_MEDIA_SESSION)
                        ? intent.getBooleanExtra(EXTRA_DEBUG_MEDIA_SESSION, false)
                        : null)
                        + " playback="
                        + (intent.hasExtra(EXTRA_DEBUG_PLAYBACK)
                        ? intent.getBooleanExtra(EXTRA_DEBUG_PLAYBACK, false)
                        : null)
                        + " lyrics="
                        + (intent.hasExtra(EXTRA_DEBUG_LYRICS)
                        ? intent.getBooleanExtra(EXTRA_DEBUG_LYRICS, false)
                        : null)
        );
        intent.removeExtra(EXTRA_DEBUG_NOTIFICATION_ACCESS);
        intent.removeExtra(EXTRA_DEBUG_MEDIA_SESSION);
        intent.removeExtra(EXTRA_DEBUG_PLAYBACK);
        intent.removeExtra(EXTRA_DEBUG_LYRICS);
    }
}
