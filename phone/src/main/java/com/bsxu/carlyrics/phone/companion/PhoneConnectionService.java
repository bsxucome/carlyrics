package com.bsxu.carlyrics.phone.companion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import com.bsxu.carlyrics.phone.PhoneMainActivity;
import com.bsxu.carlyrics.phone.R;

public class PhoneConnectionService extends Service {

    private static final String CHANNEL_ID = "phone_connection_service";
    private static final int NOTIFICATION_ID = 2001;

    public static String getUiStatus() {
        return PhoneConnectionManager.getUiStatus();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startInForeground();
        PhoneConnectionManager.getInstance(this).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground();
        PhoneConnectionManager.getInstance(this).start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
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
