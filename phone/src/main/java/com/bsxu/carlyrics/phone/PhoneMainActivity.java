package com.bsxu.carlyrics.phone;

import android.Manifest;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.bsxu.carlyrics.phone.companion.PhoneConnectionManager;
import com.bsxu.carlyrics.phone.companion.PhoneConnectionService;
import com.bsxu.carlyrics.phone.companion.PhoneCompanionService;
import com.bsxu.carlyrics.phone.lyrics.PhoneLyricsSettings;

import java.util.ArrayList;

public class PhoneMainActivity extends ComponentActivity {

    private static final String NOTIFICATION_LISTENER_SETTINGS_ACTION =
            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS";
    private static final int ACTION_PRIMARY_TINT = 0xFF2D92E8;
    private static final int ACTION_SECONDARY_TINT = 0xFF263648;
    private static final int ACTION_PRIMARY_TEXT = 0xFFFFFFFF;
    private static final int ACTION_SECONDARY_TEXT = 0xFFE2F5FF;
    private static final long STATUS_REFRESH_INTERVAL_MS = 1000L;
    private static final long RECOVERY_STATUS_REFRESH_INTERVAL_MS = 250L;
    private static final long AUTOMATIC_RECOVERY_UI_GRACE_MS = 5000L;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<String> bluetoothPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) {
                            ensureConnectionServiceRunning(true);
                        }
                        refreshConnectionPermissionState();
                        renderStatus();
                        startStatusRefreshTicker();
                    }
            );
    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> renderStatus()
            );
    private final Runnable statusRefreshTicker = new Runnable() {
        @Override
        public void run() {
            refreshConnectionPermissionState();
            renderStatus();
            boolean listenerRecoveryPending = hasNotificationAccess()
                    && !PhoneConnectionManager.getInstance(PhoneMainActivity.this)
                    .isNotificationListenerActive()
                    && automaticRecoveryStartedElapsedMs > 0L
                    && SystemClock.elapsedRealtime() - automaticRecoveryStartedElapsedMs
                    < AUTOMATIC_RECOVERY_UI_GRACE_MS;
            uiHandler.postDelayed(
                    this,
                    listenerRecoveryPending
                            ? RECOVERY_STATUS_REFRESH_INTERVAL_MS
                            : STATUS_REFRESH_INTERVAL_MS
            );
        }
    };

    private TextView statusView;
    private TextView actionHintView;
    private LinearLayout actionButtonsContainer;
    private Button notificationAccessButton;
    private Button bluetoothPermissionButton;
    private Button resetTrustedHeadUnitButton;
    private TextView lyricsServerView;
    private Button configureLyricsServerButton;
    private Button resetLyricsServerButton;
    private TextView aboutView;
    private boolean fastListenerRecoveryRequested;
    private long automaticRecoveryStartedElapsedMs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_phone_main);

        statusView = (TextView) findViewById(R.id.statusView);
        actionHintView = (TextView) findViewById(R.id.actionHintView);
        actionButtonsContainer = (LinearLayout) findViewById(R.id.actionButtonsContainer);
        notificationAccessButton = (Button) findViewById(R.id.notificationAccessButton);
        bluetoothPermissionButton = (Button) findViewById(R.id.bluetoothPermissionButton);
        resetTrustedHeadUnitButton = (Button) findViewById(R.id.resetTrustedHeadUnitButton);
        lyricsServerView = (TextView) findViewById(R.id.lyricsServerView);
        configureLyricsServerButton = (Button) findViewById(R.id.configureLyricsServerButton);
        resetLyricsServerButton = (Button) findViewById(R.id.resetLyricsServerButton);
        aboutView = (TextView) findViewById(R.id.aboutView);

        notificationAccessButton.setOnClickListener(v -> handleNotificationButtonClick());
        bluetoothPermissionButton.setOnClickListener(v -> requestBluetoothPermissionIfNeeded());
        resetTrustedHeadUnitButton.setOnClickListener(v -> resetTrustedHeadUnit());
        configureLyricsServerButton.setOnClickListener(v -> showLyricsServerDialog());
        resetLyricsServerButton.setOnClickListener(v -> resetLyricsServer());
        aboutView.setOnClickListener(v -> showAboutDialog());

        ensureConnectionServiceRunningIfPermitted(true);
        requestNotificationPermissionIfNeeded();
        refreshConnectionPermissionState();
        renderStatus();
        startStatusRefreshTicker();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ensureConnectionServiceRunningIfPermitted(true);
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

    private void requestBluetoothPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            renderStatus();
            return;
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            renderStatus();
            return;
        }
        bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private void renderStatus() {
        PhoneConnectionManager connectionManager = PhoneConnectionManager.getInstance(this);
        PhoneLyricsSettings lyricsSettings = new PhoneLyricsSettings(this);
        boolean notificationAccess = hasNotificationAccess();
        boolean bluetoothGranted = hasBluetoothPermission();
        boolean listenerActive = connectionManager.isNotificationListenerActive();
        boolean trustedMismatch = connectionManager.isTrustedHeadUnitMismatchPending();
        if (listenerActive) {
            fastListenerRecoveryRequested = false;
            automaticRecoveryStartedElapsedMs = 0L;
        }
        boolean listenerRecoveryInProgress = notificationAccess
                && !listenerActive
                && automaticRecoveryStartedElapsedMs > 0L
                && SystemClock.elapsedRealtime() - automaticRecoveryStartedElapsedMs
                < AUTOMATIC_RECOVERY_UI_GRACE_MS;

        if (!notificationAccess) {
            statusView.setText(R.string.status_permission_missing);
        } else if (trustedMismatch) {
            statusView.setText(R.string.status_trusted_head_unit_mismatch);
        } else if (!bluetoothGranted) {
            statusView.setText(R.string.status_bluetooth_missing);
        } else if (listenerRecoveryInProgress) {
            statusView.setText(R.string.status_notification_listener_recovering);
        } else if (!listenerActive) {
            statusView.setText(R.string.status_notification_listener_inactive);
        } else {
            String serviceStatus = PhoneConnectionService.getUiStatus();
            statusView.setText(TextUtils.isEmpty(serviceStatus)
                    ? getString(R.string.status_ready)
                    : serviceStatus);
        }

        boolean showNotificationButton = !notificationAccess
                || (!listenerActive && !listenerRecoveryInProgress);
        notificationAccessButton.setVisibility(showNotificationButton
                ? android.view.View.VISIBLE
                : android.view.View.GONE);
        notificationAccessButton.setEnabled(showNotificationButton);
        notificationAccessButton.setText(notificationAccess
                ? R.string.repair_notification_listener
                : R.string.open_notification_access);

        boolean needsBluetoothButton = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !bluetoothGranted;
        bluetoothPermissionButton.setVisibility(needsBluetoothButton ? android.view.View.VISIBLE : android.view.View.GONE);
        bluetoothPermissionButton.setEnabled(needsBluetoothButton);

        boolean hasTrustedHeadUnit = PhoneConnectionManager.getInstance(this).hasTrustedHeadUnit();
        resetTrustedHeadUnitButton.setVisibility(hasTrustedHeadUnit
                ? android.view.View.VISIBLE
                : android.view.View.GONE);
        resetTrustedHeadUnitButton.setEnabled(hasTrustedHeadUnit);
        lyricsServerView.setText(getString(
                R.string.lyrics_server_current,
                lyricsSettings.getDisplayBaseUrl()
        ));
        resetLyricsServerButton.setEnabled(
                !TextUtils.isEmpty(lyricsSettings.getCustomLrcLibBaseUrl())
        );

        updateRecommendedAction(
                notificationAccess,
                listenerActive,
                bluetoothGranted,
                hasTrustedHeadUnit,
                trustedMismatch,
                listenerRecoveryInProgress
        );
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

    private void ensureConnectionServiceRunning(boolean requestFastListenerRecovery) {
        Intent serviceIntent = new Intent(this, PhoneConnectionService.class);
        if (requestFastListenerRecovery
                && !fastListenerRecoveryRequested
                && hasNotificationAccess()
                && !PhoneConnectionManager.getInstance(this).isNotificationListenerActive()) {
            serviceIntent.setAction(PhoneConnectionService.ACTION_FAST_RECOVER_LISTENER);
            fastListenerRecoveryRequested = true;
            automaticRecoveryStartedElapsedMs = SystemClock.elapsedRealtime();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
            return;
        }
        startService(serviceIntent);
    }

    private void ensureConnectionServiceRunningIfPermitted(boolean requestFastListenerRecovery) {
        if (hasBluetoothPermission()) {
            ensureConnectionServiceRunning(requestFastListenerRecovery);
        }
    }

    private void refreshConnectionPermissionState() {
        PhoneConnectionManager.getInstance(this).setNotificationAccessGranted(hasNotificationAccess());
    }

    private void resetTrustedHeadUnit() {
        PhoneConnectionManager.getInstance(this).resetTrustedHeadUnit();
        renderStatus();
        Toast.makeText(this, R.string.trusted_head_unit_reset, Toast.LENGTH_SHORT).show();
    }

    private void showLyricsServerDialog() {
        final PhoneLyricsSettings settings = new PhoneLyricsSettings(this);
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(PhoneLyricsSettings.OFFICIAL_LRCLIB_BASE_URL);
        input.setText(settings.getCustomLrcLibBaseUrl());
        int padding = Math.round(20f * getResources().getDisplayMetrics().density);
        LinearLayout inputContainer = new LinearLayout(this);
        inputContainer.setPadding(padding, 0, padding, 0);
        inputContainer.addView(
                input,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.configure_lyrics_server)
                .setMessage(R.string.lyrics_server_dialog_message)
                .setView(inputContainer)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.save_lyrics_server, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (!settings.setCustomLrcLibBaseUrl(input.getText().toString())) {
                        input.setError(getString(R.string.lyrics_server_invalid));
                        return;
                    }
                    dialog.dismiss();
                    renderStatus();
                    Toast.makeText(
                            PhoneMainActivity.this,
                            R.string.lyrics_server_saved,
                            Toast.LENGTH_SHORT
                    ).show();
                }));
        dialog.show();
    }

    private void resetLyricsServer() {
        new PhoneLyricsSettings(this).clearCustomLrcLibBaseUrl();
        renderStatus();
        Toast.makeText(this, R.string.lyrics_server_reset, Toast.LENGTH_SHORT).show();
    }

    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_software)
                .setMessage(
                        getString(R.string.about_version, BuildConfig.VERSION_NAME)
                                + "\n\n"
                                + getString(R.string.about_project)
                )
                .setNegativeButton(android.R.string.ok, null)
                .setPositiveButton(R.string.open_github, (dialog, which) -> openGitHub())
                .show();
    }

    private void openGitHub() {
        try {
            startActivity(new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/bsxucome/carlyrics")
            ));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(
                    this,
                    "https://github.com/bsxucome/carlyrics",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void updateRecommendedAction(
            boolean notificationAccess,
            boolean listenerActive,
            boolean bluetoothGranted,
            boolean hasTrustedHeadUnit,
            boolean trustedMismatch,
            boolean listenerRecoveryInProgress
    ) {
        Button primaryButton = null;
        int hintResId = 0;

        if (trustedMismatch && hasTrustedHeadUnit) {
            primaryButton = resetTrustedHeadUnitButton;
            hintResId = R.string.recommended_reset_trusted_head_unit;
        } else if (!notificationAccess) {
            primaryButton = notificationAccessButton;
            hintResId = R.string.recommended_open_notification_access;
        } else if (!listenerActive && !listenerRecoveryInProgress) {
            primaryButton = notificationAccessButton;
            hintResId = R.string.recommended_repair_notification_listener;
        } else if (!bluetoothGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            primaryButton = bluetoothPermissionButton;
            hintResId = R.string.recommended_grant_bluetooth_permission;
        }

        ArrayList<Button> orderedButtons = new ArrayList<Button>();
        if (primaryButton != null && primaryButton.getVisibility() == android.view.View.VISIBLE) {
            orderedButtons.add(primaryButton);
        }
        addButtonIfVisible(orderedButtons, notificationAccessButton, primaryButton);
        addButtonIfVisible(orderedButtons, bluetoothPermissionButton, primaryButton);
        addButtonIfVisible(orderedButtons, resetTrustedHeadUnitButton, primaryButton);

        actionButtonsContainer.removeAllViews();
        if (hintResId != 0) {
            actionHintView.setText(hintResId);
            actionHintView.setVisibility(android.view.View.VISIBLE);
            actionButtonsContainer.addView(actionHintView);
        } else {
            actionHintView.setVisibility(android.view.View.GONE);
        }

        for (Button button : orderedButtons) {
            styleActionButton(button, button == primaryButton);
            actionButtonsContainer.addView(button);
        }
    }

    private void addButtonIfVisible(ArrayList<Button> orderedButtons, Button button, Button primaryButton) {
        if (button == null || button == primaryButton || button.getVisibility() != android.view.View.VISIBLE) {
            return;
        }
        orderedButtons.add(button);
    }

    private void styleActionButton(Button button, boolean primary) {
        if (button == null) {
            return;
        }
        button.setBackgroundTintList(ColorStateList.valueOf(primary ? ACTION_PRIMARY_TINT : ACTION_SECONDARY_TINT));
        button.setTextColor(primary ? ACTION_PRIMARY_TEXT : ACTION_SECONDARY_TEXT);
        button.setAlpha(primary ? 1f : 0.92f);
    }

    private void beginNotificationSetupFlow() {
        if (!hasNotificationAccess()) {
            openNotificationAccessSettings();
        }
    }

    private void handleNotificationButtonClick() {
        if (!hasNotificationAccess()) {
            beginNotificationSetupFlow();
            return;
        }
        if (!hasBluetoothPermission()) {
            requestBluetoothPermissionIfNeeded();
            return;
        }
        Intent repairIntent = new Intent(this, PhoneConnectionService.class);
        repairIntent.setAction(PhoneConnectionService.ACTION_FORCE_RECOVER_LISTENER);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(repairIntent);
        } else {
            startService(repairIntent);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            NotificationListenerService.requestRebind(
                    new ComponentName(this, PhoneCompanionService.class)
            );
        }
        Toast.makeText(this, R.string.repair_notification_listener_started, Toast.LENGTH_SHORT).show();
        renderStatus();
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
