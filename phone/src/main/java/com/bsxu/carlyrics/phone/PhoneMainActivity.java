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
import android.widget.Button;
import android.widget.TextView;

import com.bsxu.carlyrics.phone.companion.PhoneCompanionService;

public class PhoneMainActivity extends Activity {

    private static final String NOTIFICATION_LISTENER_SETTINGS_ACTION =
            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS";
    private static final int REQUEST_BLUETOOTH_CONNECT = 501;

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

        notificationAccessButton.setOnClickListener(v ->
                startActivity(new Intent(NOTIFICATION_LISTENER_SETTINGS_ACTION)));
        bluetoothPermissionButton.setOnClickListener(v -> requestBluetoothPermissionIfNeeded());

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
            String serviceStatus = PhoneCompanionService.getUiStatus();
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
}

