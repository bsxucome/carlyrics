package com.bsxu.carlyrics.phone.companion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

public class PhoneConnectionService extends Service {

    private static final String CHANNEL_ID = "phone_connection_service";
    private static final int NOTIFICATION_ID = 2001;

    public static String getUiStatus() {
        return PhoneConnectionManager.getUiStatus();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        PhoneConnectionManager.getInstance(this).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        PhoneConnectionManager.getInstance(this).start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        PhoneConnectionManager.getInstance(this).stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

}
