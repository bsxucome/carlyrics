package com.bsxu.carlyrics.playback;

import android.app.Notification;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import com.bsxu.carlyrics.model.PlaybackSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class MediaObserverService extends NotificationListenerService {

    private PlaybackRepository repository;
    private MediaSessionManager mediaSessionManager;
    private final ArrayList<MediaController> activeControllers = new ArrayList<MediaController>();
    private final HashMap<MediaController, MediaController.Callback> controllerCallbacks =
            new HashMap<MediaController, MediaController.Callback>();

    private final MediaSessionManager.OnActiveSessionsChangedListener activeSessionsChangedListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> controllers) {
                    updateActiveControllers(controllers);
                    publishBestControllerSnapshot();
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        repository = PlaybackRepository.getInstance(this);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        repository.setNotificationAccessGranted(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            attachMediaSessionListener();
        }
        publishFromActiveNotifications();
        publishBestControllerSnapshot();
    }

    @Override
    public void onListenerDisconnected() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            detachMediaSessionListener();
        }
        repository.setNotificationAccessGranted(false);
        repository.clearPlayback();
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            detachMediaSessionListener();
        }
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        publishNotificationSnapshot(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        PlaybackSnapshot currentSnapshot = repository.getCurrentSnapshot();
        if (currentSnapshot != null && TextUtils.equals(currentSnapshot.getPackageName(), sbn.getPackageName())) {
            publishFromActiveNotifications();
            publishBestControllerSnapshot();
        }
    }

    private void publishBestControllerSnapshot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }

        MediaController bestController = chooseBestController();
        if (bestController == null) {
            repository.clearCurrentController();
            return;
        }

        PlaybackSnapshot snapshot = buildSnapshotFromController(bestController);
        if (snapshot != null) {
            repository.updateFromMediaSession(snapshot, bestController);
        }
    }

    private void publishFromActiveNotifications() {
        try {
            StatusBarNotification[] notifications = getActiveNotifications();
            if (notifications == null) {
                return;
            }
            PlaybackSnapshot bestSnapshot = null;
            for (StatusBarNotification notification : notifications) {
                PlaybackSnapshot candidate = buildSnapshotFromNotification(notification);
                if (candidate == null) {
                    continue;
                }
                if (bestSnapshot == null || scoreNotification(candidate) > scoreNotification(bestSnapshot)) {
                    bestSnapshot = candidate;
                }
            }
            if (bestSnapshot != null) {
                repository.updateFromNotification(bestSnapshot);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void publishNotificationSnapshot(StatusBarNotification sbn) {
        PlaybackSnapshot snapshot = buildSnapshotFromNotification(sbn);
        if (snapshot != null) {
            repository.updateFromNotification(snapshot);
        }
    }

    private void attachMediaSessionListener() {
        try {
            mediaSessionManager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            if (mediaSessionManager == null) {
                return;
            }
            mediaSessionManager.addOnActiveSessionsChangedListener(
                    activeSessionsChangedListener,
                    new ComponentName(this, MediaObserverService.class)
            );
            updateActiveControllers(
                    mediaSessionManager.getActiveSessions(
                            new ComponentName(this, MediaObserverService.class)
                    )
            );
        } catch (SecurityException ignored) {
        } catch (RuntimeException ignored) {
        }
    }

    private void detachMediaSessionListener() {
        if (mediaSessionManager != null) {
            try {
                mediaSessionManager.removeOnActiveSessionsChangedListener(activeSessionsChangedListener);
            } catch (RuntimeException ignored) {
            }
        }

        for (MediaController controller : new ArrayList<MediaController>(activeControllers)) {
            MediaController.Callback callback = controllerCallbacks.remove(controller);
            if (callback != null) {
                controller.unregisterCallback(callback);
            }
        }
        activeControllers.clear();
    }

    private void updateActiveControllers(List<MediaController> controllers) {
        for (MediaController controller : new ArrayList<MediaController>(activeControllers)) {
            MediaController.Callback callback = controllerCallbacks.remove(controller);
            if (callback != null) {
                controller.unregisterCallback(callback);
            }
        }
        activeControllers.clear();

        if (controllers == null) {
            return;
        }

        for (final MediaController controller : controllers) {
            if (controller == null) {
                continue;
            }
            activeControllers.add(controller);
            MediaController.Callback callback = new MediaController.Callback() {
                @Override
                public void onMetadataChanged(MediaMetadata metadata) {
                    publishBestControllerSnapshot();
                }

                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
                    publishBestControllerSnapshot();
                }
            };
            controller.registerCallback(callback);
            controllerCallbacks.put(controller, callback);
        }
    }

    private MediaController chooseBestController() {
        MediaController bestController = null;
        int bestScore = Integer.MIN_VALUE;
        for (MediaController controller : activeControllers) {
            int score = scoreController(controller);
            if (score > bestScore) {
                bestScore = score;
                bestController = controller;
            }
        }
        return bestController;
    }

    private int scoreController(MediaController controller) {
        int score = 0;
        PlaybackState playbackState = controller.getPlaybackState();
        if (playbackState != null) {
            if (playbackState.getState() == PlaybackState.STATE_PLAYING) {
                score += 50;
            } else if (playbackState.getState() == PlaybackState.STATE_PAUSED) {
                score += 30;
            }
        }

        MediaMetadata metadata = controller.getMetadata();
        if (metadata != null) {
            if (!TextUtils.isEmpty(metadata.getString(MediaMetadata.METADATA_KEY_TITLE))) {
                score += 30;
            }
            if (!TextUtils.isEmpty(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))) {
                score += 10;
            }
            if (metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null
                    || metadata.getBitmap(MediaMetadata.METADATA_KEY_ART) != null) {
                score += 10;
            }
        }

        String packageName = controller.getPackageName();
        if (packageName != null && packageName.toLowerCase().contains("bluetooth")) {
            score += 20;
        }
        return score;
    }

    private int scoreNotification(PlaybackSnapshot snapshot) {
        int score = snapshot.isPlaying() ? 30 : 10;
        if (!TextUtils.isEmpty(snapshot.getTitle())) {
            score += 20;
        }
        if (!TextUtils.isEmpty(snapshot.getArtist())) {
            score += 10;
        }
        if (snapshot.getArtwork() != null) {
            score += 10;
        }
        if (snapshot.getPackageName().toLowerCase().contains("bluetooth")) {
            score += 10;
        }
        return score;
    }

    private PlaybackSnapshot buildSnapshotFromController(MediaController controller) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return null;
        }

        MediaMetadata metadata = controller.getMetadata();
        PlaybackState playbackState = controller.getPlaybackState();
        if (metadata == null && playbackState == null) {
            return null;
        }

        String title = metadata == null ? "" : metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata == null ? "" : metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        String album = metadata == null ? "" : metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
        long duration = metadata == null ? 0L : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        Bitmap artwork = null;
        if (metadata != null) {
            artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (artwork == null) {
                artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            }
        }

        int state = playbackState == null ? PlaybackState.STATE_NONE : playbackState.getState();
        boolean playing = state == PlaybackState.STATE_PLAYING
                || state == PlaybackState.STATE_BUFFERING
                || state == PlaybackState.STATE_FAST_FORWARDING
                || state == PlaybackState.STATE_REWINDING;
        long position = playbackState == null ? 0L : playbackState.getPosition();
        long lastPositionUpdateTime = playbackState == null ? 0L : playbackState.getLastPositionUpdateTime();

        PlaybackSnapshot snapshot = new PlaybackSnapshot(
                controller.getPackageName(),
                title,
                artist,
                album,
                duration,
                position,
                lastPositionUpdateTime,
                playing,
                artwork,
                PlaybackSnapshot.SOURCE_MEDIA_SESSION
        );
        return snapshot.hasTrackData() ? snapshot : null;
    }

    private PlaybackSnapshot buildSnapshotFromNotification(StatusBarNotification sbn) {
        if (sbn == null) {
            return null;
        }
        Notification notification = sbn.getNotification();
        if (notification == null) {
            return null;
        }
        Bundle extras = notification.extras;
        if (extras == null) {
            return null;
        }

        CharSequence titleText = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence artistText = extras.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
        if (TextUtils.isEmpty(titleText) && TextUtils.isEmpty(artistText)) {
            return null;
        }

        Bitmap artwork = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            IconHolder iconHolder = readIcon(notification);
            if (iconHolder != null) {
                artwork = iconHolder.bitmap;
            }
        } else if (extras.getParcelable(Notification.EXTRA_LARGE_ICON) instanceof Bitmap) {
            artwork = (Bitmap) extras.getParcelable(Notification.EXTRA_LARGE_ICON);
        }

        boolean playing = (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0;
        return new PlaybackSnapshot(
                sbn.getPackageName(),
                titleText == null ? "" : titleText.toString(),
                artistText == null ? "" : artistText.toString(),
                subText == null ? "" : subText.toString(),
                0L,
                0L,
                0L,
                playing,
                artwork,
                PlaybackSnapshot.SOURCE_NOTIFICATION
        );
    }

    private IconHolder readIcon(Notification notification) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null;
        }
        if (notification.getLargeIcon() == null) {
            return null;
        }
        try {
            Drawable drawable = notification.getLargeIcon().loadDrawable(this);
            if (drawable instanceof BitmapDrawable) {
                return new IconHolder(((BitmapDrawable) drawable).getBitmap());
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static final class IconHolder {
        private final Bitmap bitmap;

        private IconHolder(Bitmap bitmap) {
            this.bitmap = bitmap;
        }
    }
}
