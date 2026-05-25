package com.bsxu.carlyrics.phone.companion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;
import android.util.Log;

import com.bsxu.carlyrics.phone.PhoneMainActivity;
import com.bsxu.carlyrics.phone.R;

public class PhoneConnectionService extends Service {

    private static final String TAG = "PhoneConnService";
    private static final String CHANNEL_ID = "phone_connection_service";
    private static final int NOTIFICATION_ID = 2001;
    private static final long LISTENER_WATCHDOG_INTERVAL_MS = 5000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable notificationListenerWatchdog = new Runnable() {
        @Override
        public void run() {
            try {
                ensureNotificationListenerBound();
            } finally {
                mainHandler.postDelayed(this, LISTENER_WATCHDOG_INTERVAL_MS);
            }
        }
    };

    public static String getUiStatus() {
        return PhoneConnectionManager.getUiStatus();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startInForeground();
        PhoneConnectionManager.getInstance(this).start();
        startNotificationListenerWatchdog();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground();
        PhoneConnectionManager.getInstance(this).start();
        startNotificationListenerWatchdog();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(notificationListenerWatchdog);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        PhoneConnectionManager.getInstance(this).stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startNotificationListenerWatchdog() {
        mainHandler.removeCallbacks(notificationListenerWatchdog);
        mainHandler.post(notificationListenerWatchdog);
    }

    private void ensureNotificationListenerBound() {
        PhoneConnectionManager connectionManager = PhoneConnectionManager.getInstance(this);
        boolean notificationAccessConfigured = hasNotificationAccessConfigured();
        connectionManager.setNotificationAccessGranted(notificationAccessConfigured);
        if (!notificationAccessConfigured || connectionManager.isNotificationListenerActive()) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return;
        }
        try {
            NotificationListenerService.requestRebind(
                    new ComponentName(this, PhoneCompanionService.class)
            );
            Log.i(TAG, "Requested notification listener rebind from foreground connection service");
        } catch (RuntimeException error) {
            Log.w(TAG, "Failed to request notification listener rebind", error);
        }
    }

    private boolean hasNotificationAccessConfigured() {
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

    private void startInForeground() {
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (notificationManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.connection_service_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.connection_service_channel_desc));
            notificationManager.createNotificationChannel(channel);
        }
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        Intent launchIntent = new Intent(this, PhoneMainActivity.class);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                pendingIntentFlags
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(getString(R.string.connection_service_notification_title))
                .setContentText(getString(R.string.connection_service_notification_text))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(Notification.CATEGORY_SERVICE);
        }
        return builder.build();
    }
}
