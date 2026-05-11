package com.bsxu.carlyrics.phone;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.app.NotificationManager;

import com.bsxu.carlyrics.phone.companion.PhoneDebugScenarioStore;
import com.bsxu.carlyrics.phone.companion.PhoneConnectionService;
import com.bsxu.carlyrics.phone.companion.PhoneCompanionService;

public class PhoneMainActivity extends Activity {

    private static final String TAG = "PhoneMain";
    private static final String NOTIFICATION_LISTENER_SETTINGS_ACTION =
            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS";
    private static final int REQUEST_BLUETOOTH_CONNECT = 501;
    private static final int REQUEST_POST_NOTIFICATIONS = 502;
    private static final String EXTRA_DEBUG_CLEAR_OVERRIDES = "debug_clear_overrides";
    private static final String EXTRA_DEBUG_NOTIFICATION_ACCESS = "debug_notification_access";
    private static final String EXTRA_DEBUG_MEDIA_SESSION = "debug_media_session";
    private static final String EXTRA_DEBUG_PLAYBACK = "debug_playback";
    private static final String EXTRA_DEBUG_LYRICS = "debug_lyrics";
    private static final int NOTIFICATION_STEP_NONE = 0;
    private static final int NOTIFICATION_STEP_APP_NOTIFICATIONS = 1;
    private static final int NOTIFICATION_STEP_LISTENER_ACCESS = 2;

    private TextView statusView;
    private Button notificationAccessButton;
    private Button bluetoothPermissionButton;
    private PhoneDebugScenarioStore debugScenarioStore;
    private boolean continueNotificationSetupOnResume;
    private int lastNotificationSetupStep = NOTIFICATION_STEP_NONE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_phone_main);

        statusView = (TextView) findViewById(R.id.statusView);
        notificationAccessButton = (Button) findViewById(R.id.notificationAccessButton);
        bluetoothPermissionButton = (Button) findViewById(R.id.bluetoothPermissionButton);
        debugScenarioStore = new PhoneDebugScenarioStore(this);

        notificationAccessButton.setOnClickListener(v -> beginNotificationSetupFlow());
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
        maybeContinueNotificationSetupFlow();
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
            return;
        }
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                continueNotificationSetupOnResume = true;
                lastNotificationSetupStep = NOTIFICATION_STEP_APP_NOTIFICATIONS;
                continueNotificationSetupFlow();
            } else {
                continueNotificationSetupOnResume = false;
                lastNotificationSetupStep = NOTIFICATION_STEP_NONE;
            }
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
        boolean appNotificationsGranted = hasAppNotificationPermission();
        boolean bluetoothGranted = hasBluetoothPermission();

        if (!notificationAccess) {
            statusView.setText(R.string.status_permission_missing);
        } else if (!appNotificationsGranted) {
            statusView.setText(R.string.status_app_notifications_missing);
        } else if (!bluetoothGranted) {
            statusView.setText(R.string.status_bluetooth_missing);
        } else {
            String serviceStatus = PhoneConnectionService.getUiStatus();
            statusView.setText(TextUtils.isEmpty(serviceStatus) ? getString(R.string.status_ready) : serviceStatus);
        }

        notificationAccessButton.setEnabled(!notificationAccess || !appNotificationsGranted);
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

    private boolean hasAppNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean areAppNotificationsEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            return notificationManager == null || notificationManager.areNotificationsEnabled();
        }
        return true;
    }

    private void ensureConnectionServiceRunning() {
        startService(new Intent(this, PhoneConnectionService.class));
    }

    private void beginNotificationSetupFlow() {
        continueNotificationSetupOnResume = true;
        lastNotificationSetupStep = NOTIFICATION_STEP_NONE;
        continueNotificationSetupFlow();
    }

    private void maybeContinueNotificationSetupFlow() {
        if (!continueNotificationSetupOnResume) {
            return;
        }
        boolean shouldContinue = false;
        if (lastNotificationSetupStep == NOTIFICATION_STEP_NONE) {
            shouldContinue = true;
        } else if (lastNotificationSetupStep == NOTIFICATION_STEP_APP_NOTIFICATIONS) {
            shouldContinue = hasAppNotificationPermission() && areAppNotificationsEnabled();
        } else if (lastNotificationSetupStep == NOTIFICATION_STEP_LISTENER_ACCESS) {
            shouldContinue = hasNotificationAccess();
        }

        if (shouldContinue) {
            continueNotificationSetupFlow();
        } else {
            continueNotificationSetupOnResume = false;
            lastNotificationSetupStep = NOTIFICATION_STEP_NONE;
        }
    }

    private void continueNotificationSetupFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
            return;
        }

        if (!areAppNotificationsEnabled()) {
            lastNotificationSetupStep = NOTIFICATION_STEP_APP_NOTIFICATIONS;
            openAppNotificationSettings();
            return;
        }

        if (!hasNotificationAccess()) {
            lastNotificationSetupStep = NOTIFICATION_STEP_LISTENER_ACCESS;
            openNotificationAccessSettings();
            return;
        }

        continueNotificationSetupOnResume = false;
        lastNotificationSetupStep = NOTIFICATION_STEP_NONE;
    }

    private void openNotificationAccessSettings() {
        ComponentName componentName = new ComponentName(this, PhoneCompanionService.class);

        Intent detailIntent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
        detailIntent.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, componentName.flattenToString());
        detailIntent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        detailIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (tryStart(detailIntent)) {
            return;
        }

        Intent listIntent = new Intent(NOTIFICATION_LISTENER_SETTINGS_ACTION);
        listIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tryStart(listIntent)) {
            return;
        }

        Intent appDetailsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        appDetailsIntent.setData(Uri.fromParts("package", getPackageName(), null));
        appDetailsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        tryStart(appDetailsIntent);
    }

    private boolean tryStart(Intent intent) {
        if (intent == null) {
            return false;
        }
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException ignored) {
            return false;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void openAppNotificationSettings() {
        Intent appNotificationIntent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        appNotificationIntent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        appNotificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tryStart(appNotificationIntent)) {
            return;
        }

        Intent appDetailsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        appDetailsIntent.setData(Uri.fromParts("package", getPackageName(), null));
        appDetailsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        tryStart(appDetailsIntent);
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
