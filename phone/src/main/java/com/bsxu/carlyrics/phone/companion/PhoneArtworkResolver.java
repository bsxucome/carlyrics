package com.bsxu.carlyrics.phone.companion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

final class PhoneArtworkResolver {

    private static final int NETWORK_TIMEOUT_MS = 2500;

    private final Context appContext;

    PhoneArtworkResolver(Context context) {
        appContext = context.getApplicationContext();
    }

    Bitmap fromNotification(StatusBarNotification notification) {
        if (notification == null || notification.getNotification() == null) {
            return null;
        }
        Bundle extras = notification.getNotification().extras;
        if (extras == null) {
            return null;
        }
        Object largeIcon = extras.getParcelable("android.largeIcon");
        if (largeIcon instanceof Bitmap) {
            return (Bitmap) largeIcon;
        }
        Object largeIconBig = extras.getParcelable("android.largeIcon.big");
        if (largeIconBig instanceof Bitmap) {
            return (Bitmap) largeIconBig;
        }
        Object picture = extras.getParcelable("android.picture");
        if (picture instanceof Bitmap) {
            return (Bitmap) picture;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && notification.getNotification().getLargeIcon() != null) {
            try {
                Drawable drawable = notification.getNotification()
                        .getLargeIcon()
                        .loadDrawable(appContext);
                if (drawable instanceof BitmapDrawable) {
                    return ((BitmapDrawable) drawable).getBitmap();
                }
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    Bitmap fromMedia(MediaMetadata metadata, MediaDescription description) {
        Bitmap artwork = null;
        if (metadata != null) {
            artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (artwork == null) {
                artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            }
        }
        if (artwork == null && description != null) {
            artwork = description.getIconBitmap();
        }
        if (artwork != null) {
            return artwork;
        }

        ArrayList<Uri> candidateUris = new ArrayList<Uri>();
        if (description != null && description.getIconUri() != null) {
            candidateUris.add(description.getIconUri());
        }
        if (metadata != null) {
            addUri(candidateUris, metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI));
            addUri(candidateUris, metadata.getString(MediaMetadata.METADATA_KEY_ART_URI));
            addUri(candidateUris, metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI));
        }
        for (Uri candidateUri : candidateUris) {
            artwork = load(candidateUri);
            if (artwork != null) {
                return artwork;
            }
        }
        return null;
    }

    private static void addUri(ArrayList<Uri> target, String value) {
        if (TextUtils.isEmpty(value)) {
            return;
        }
        try {
            target.add(Uri.parse(value));
        } catch (RuntimeException ignored) {
        }
    }

    private Bitmap load(Uri uri) {
        if (uri == null) {
            return null;
        }
        String scheme = uri.getScheme();
        try {
            if ("content".equalsIgnoreCase(scheme)
                    || "android.resource".equalsIgnoreCase(scheme)
                    || "file".equalsIgnoreCase(scheme)) {
                InputStream inputStream = appContext.getContentResolver().openInputStream(uri);
                return decodeAndClose(inputStream);
            }
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                HttpURLConnection connection =
                        (HttpURLConnection) new URL(uri.toString()).openConnection();
                connection.setConnectTimeout(NETWORK_TIMEOUT_MS);
                connection.setReadTimeout(NETWORK_TIMEOUT_MS);
                connection.setInstanceFollowRedirects(false);
                try {
                    int responseCode = connection.getResponseCode();
                    if (responseCode < 200 || responseCode >= 300) {
                        return null;
                    }
                    return decodeAndClose(connection.getInputStream());
                } finally {
                    connection.disconnect();
                }
            }
        } catch (IOException ignored) {
        } catch (SecurityException ignored) {
        }
        return null;
    }

    private static Bitmap decodeAndClose(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return null;
        }
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeStream(inputStream, null, options);
        } finally {
            inputStream.close();
        }
    }
}
