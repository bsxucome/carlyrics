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
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.TextView;

import com.bsxu.carlyrics.phone.companion.PhoneConnectionManager;
import com.bsxu.carlyrics.phone.companion.PhoneConnectionService;
import com.bsxu.carlyrics.phone.companion.PhoneCompanionService;

public class PhoneMainActivity extends Activity {

    private static final String NOTIFICATION_LISTENER_SETTINGS_ACTION =
            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS";
    private static final int REQUEST_BLUETOOTH_CONNECT = 501;
    private static final long STATUS_REFRESH_INTERVAL_MS = 1000L;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusRefreshTicker = new Runnable() {
        @Override
        public void run() {
            refreshConnectionPermissionState();
            renderStatus();
            uiHandler.postDelayed(this, STATUS_REFRESH_INTERVAL_MS);
        }
    };

    private TextView statusView;
    private Button notificationAccessButton;
    private Button bluetoothPermissionButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_phone_main);

        statusView = (TextView) findViewById(R.id.statusView);
        notificationAccessButton = (Button) findViewById(R.id.notificationAccessButton);
        bluetoothPermissionButton = (Button) findViewById(R.id.bluetoothPermissionButton);

        notificationAccessButton.setOnClickListener(v -> beginNotificationSetupFlow());
        bluetoothPermissionButton.setOnClickListener(v -> requestBluetoothPermissionIfNeeded());

        ensureConnectionServiceRunning();
        refreshConnectionPermissionState();
        renderStatus();
        startStatusRefreshTicker();
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
        refreshConnectionPermissionState();
        renderStatus();
        startStatusRefreshTicker();
    }

    @Override
    protected void onPause() {
        uiHandler.removeCallbacks(statusRefreshTicker);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        refreshConnectionPermissionState();
        renderStatus();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH_CONNECT) {
            refreshConnectionPermissionState();
            renderStatus();
            startStatusRefreshTicker();
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
            statusView.setText(TextUtils.isEmpty(serviceStatus)
                    ? getString(R.string.status_ready)
                    : serviceStatus);
        }

        notificationAccessButton.setVisibility(notificationAccess ? android.view.View.GONE : android.view.View.VISIBLE);
        notificationAccessButton.setEnabled(!notificationAccess);

        boolean needsBluetoothButton = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !bluetoothGranted;
        bluetoothPermissionButton.setVisibility(needsBluetoothButton ? android.view.View.VISIBLE : android.view.View.GONE);
        bluetoothPermissionButton.setEnabled(needsBluetoothButton);
    }

    private void startStatusRefreshTicker() {
        uiHandler.removeCallbacks(statusRefreshTicker);
        uiHandler.post(statusRefreshTicker);
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
        Intent serviceIntent = new Intent(this, PhoneConnectionService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
            return;
        }
        startService(serviceIntent);
    }

    private void refreshConnectionPermissionState() {
        PhoneConnectionManager.getInstance(this).setNotificationAccessGranted(hasNotificationAccess());
    }

    private void beginNotificationSetupFlow() {
        if (!hasNotificationAccess()) {
            openNotificationAccessSettings();
        }
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
}
