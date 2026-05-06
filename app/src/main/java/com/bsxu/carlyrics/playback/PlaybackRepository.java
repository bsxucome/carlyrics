package com.bsxu.carlyrics.playback;

import android.content.ComponentName;
import android.content.Context;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;

import com.bsxu.carlyrics.model.PlaybackSnapshot;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public final class PlaybackRepository {

    public interface PlaybackListener {
        void onPlaybackUpdated(PlaybackSnapshot snapshot, boolean permissionEnabled);
    }

    private static volatile PlaybackRepository instance;

    private final Context appContext;
    private final AudioManager audioManager;
    private final Handler mainHandler;
    private final Set<PlaybackListener> listeners;

    private volatile PlaybackSnapshot currentSnapshot;
    private volatile MediaController currentController;
    private volatile boolean notificationAccessGranted;

    private PlaybackRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.listeners = new CopyOnWriteArraySet<PlaybackListener>();
        this.notificationAccessGranted = isNotificationAccessEnabled(appContext);
    }

    public static PlaybackRepository getInstance(Context context) {
        if (instance == null) {
            synchronized (PlaybackRepository.class) {
                if (instance == null) {
                    instance = new PlaybackRepository(context);
                }
            }
        }
        return instance;
    }

    public void registerListener(PlaybackListener listener) {
        if (listener == null) {
            return;
        }
        listeners.add(listener);
        notificationAccessGranted = isNotificationAccessEnabled(appContext);
        dispatchState(listener);
    }

    public void unregisterListener(PlaybackListener listener) {
        if (listener == null) {
            return;
        }
        listeners.remove(listener);
    }

    public PlaybackSnapshot getCurrentSnapshot() {
        return currentSnapshot;
    }

    public void setNotificationAccessGranted(boolean granted) {
        notificationAccessGranted = granted;
        dispatchState();
    }

    public void updateFromMediaSession(PlaybackSnapshot snapshot, MediaController controller) {
        currentController = controller;
        PlaybackSnapshot previousSnapshot = currentSnapshot;
        if (snapshot != null
                && previousSnapshot != null
                && snapshot.isSameTrack(previousSnapshot)
                && !snapshot.hasArtwork()
                && previousSnapshot.hasArtwork()) {
            snapshot = snapshot.mergeWithSupplementalData(
                    previousSnapshot.getArtwork(),
                    previousSnapshot.getAlbum()
            );
        }
        currentSnapshot = snapshot;
        dispatchState();
    }

    public void clearCurrentController() {
        currentController = null;
        PlaybackSnapshot snapshot = currentSnapshot;
        if (snapshot != null && snapshot.getSourceType() == PlaybackSnapshot.SOURCE_MEDIA_SESSION) {
            currentSnapshot = null;
        }
        dispatchState();
    }

    public void clearPlayback() {
        currentController = null;
        currentSnapshot = null;
        dispatchState();
    }

    public void updateFromNotification(PlaybackSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }

        PlaybackSnapshot activeSnapshot = currentSnapshot;
        if (currentController != null && activeSnapshot != null) {
            if (!canMergeNotificationSnapshot(activeSnapshot, snapshot)) {
                return;
            }
            if (!activeSnapshot.hasArtwork() && snapshot.hasArtwork()) {
                currentSnapshot = activeSnapshot.mergeWithSupplementalData(
                        snapshot.getArtwork(),
                        snapshot.getAlbum()
                );
                dispatchState();
            }
            return;
        }

        currentSnapshot = snapshot;
        dispatchState();
    }

    public void togglePlayPause() {
        MediaController controller = currentController;
        PlaybackSnapshot snapshot = currentSnapshot;
        if (controller != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (snapshot != null && snapshot.isPlaying()) {
                controller.getTransportControls().pause();
            } else {
                controller.getTransportControls().play();
            }
            return;
        }
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
    }

    public void skipNext() {
        MediaController controller = currentController;
        if (controller != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            controller.getTransportControls().skipToNext();
            return;
        }
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    public void skipPrevious() {
        MediaController controller = currentController;
        if (controller != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            controller.getTransportControls().skipToPrevious();
            return;
        }
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    public static boolean isNotificationAccessEnabled(Context context) {
        String enabledListeners = Settings.Secure.getString(
                context.getContentResolver(),
                "enabled_notification_listeners"
        );
        if (TextUtils.isEmpty(enabledListeners)) {
            return false;
        }
        ComponentName componentName = new ComponentName(context, MediaObserverService.class);
        return enabledListeners.contains(componentName.flattenToString())
                || enabledListeners.contains(componentName.flattenToShortString())
                || enabledListeners.contains(context.getPackageName());
    }

    private boolean canMergeNotificationSnapshot(PlaybackSnapshot sessionSnapshot, PlaybackSnapshot notificationSnapshot) {
        if (sessionSnapshot == null || notificationSnapshot == null) {
            return false;
        }
        if (sessionSnapshot.isSameTrack(notificationSnapshot)) {
            return true;
        }

        boolean samePackage = TextUtils.equals(
                safeNormalize(sessionSnapshot.getPackageName()),
                safeNormalize(notificationSnapshot.getPackageName())
        );
        boolean titleMatches = safeNormalize(sessionSnapshot.getTitle())
                .equals(safeNormalize(notificationSnapshot.getTitle()));
        boolean titleOverlaps = !TextUtils.isEmpty(sessionSnapshot.getTitle())
                && !TextUtils.isEmpty(notificationSnapshot.getTitle())
                && (safeNormalize(sessionSnapshot.getTitle()).contains(safeNormalize(notificationSnapshot.getTitle()))
                || safeNormalize(notificationSnapshot.getTitle()).contains(safeNormalize(sessionSnapshot.getTitle())));
        boolean artistCompatible = TextUtils.isEmpty(sessionSnapshot.getArtist())
                || TextUtils.isEmpty(notificationSnapshot.getArtist())
                || safeNormalize(sessionSnapshot.getArtist()).equals(safeNormalize(notificationSnapshot.getArtist()));

        return samePackage && (titleMatches || titleOverlaps) && artistCompatible;
    }

    private String safeNormalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase();
    }

    private void dispatchMediaKey(int keyCode) {
        if (audioManager == null) {
            return;
        }
        KeyEvent down = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
        KeyEvent up = new KeyEvent(KeyEvent.ACTION_UP, keyCode);
        audioManager.dispatchMediaKeyEvent(down);
        audioManager.dispatchMediaKeyEvent(up);
    }

    private void dispatchState() {
        for (PlaybackListener listener : listeners) {
            dispatchState(listener);
        }
    }

    private void dispatchState(final PlaybackListener listener) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onPlaybackUpdated(currentSnapshot, notificationAccessGranted);
            }
        });
    }
}
