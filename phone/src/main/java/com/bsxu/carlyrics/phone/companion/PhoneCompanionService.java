package com.bsxu.carlyrics.phone.companion;

import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

import com.bsxu.carlyrics.phone.lyrics.PhoneLyricsRepository;
import com.bsxu.carlyrics.phone.lyrics.PhoneLyricsResult;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PhoneCompanionService extends NotificationListenerService {

    private static final String TAG = "PhoneCompanion";
    private static final long ARTWORK_RETRY_DELAY_MS = 350L;
    private static final int ARTWORK_RETRY_MAX_ATTEMPTS = 12;
    private static final long LYRICS_RETRY_DELAY_MS = 2500L;
    private static final int LYRICS_RETRY_MAX_ATTEMPTS = 8;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
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

    private final Runnable artworkRetryRunnable = new Runnable() {
        @Override
        public void run() {
            performArtworkRetry();
        }
    };

    private final Runnable lyricsRetryRunnable = new Runnable() {
        @Override
        public void run() {
            performLyricsRetry();
        }
    };

    private final PhoneConnectionManager.ControlDelegate controlDelegate =
            new PhoneConnectionManager.ControlDelegate() {
                @Override
                public void onPlayPauseRequested() {
                    if (currentController == null) {
                        return;
                    }
                    if (currentSnapshot != null && currentSnapshot.playing) {
                        currentController.getTransportControls().pause();
                    } else {
                        currentController.getTransportControls().play();
                    }
                }

                @Override
                public void onNextRequested() {
                    if (currentController != null) {
                        currentController.getTransportControls().skipToNext();
                    }
                }

                @Override
                public void onPreviousRequested() {
                    if (currentController != null) {
                        currentController.getTransportControls().skipToPrevious();
                    }
                }

                @Override
                public void onResendLyricsRequested() {
                    if (currentLyricsResult != null) {
                        connectionManager.publishLyrics(currentLyricsResult);
                    } else {
                        requestLyricsForCurrentTrack(true);
                    }
                }
            };

    private MediaSessionManager mediaSessionManager;
    private PhoneLyricsRepository lyricsRepository;
    private PhoneConnectionManager connectionManager;
    private final Map<String, Bitmap> artworkCache = new ConcurrentHashMap<String, Bitmap>();

    private volatile MediaController currentController;
    private volatile ObservedPlaybackSnapshot currentSnapshot;
    private volatile PhoneLyricsResult currentLyricsResult;
    private volatile String currentTrackKey = "";
    private volatile long lastLyricsAttemptElapsedMs;
    private volatile String pendingArtworkTrackKey = "";
    private volatile int pendingArtworkRetryAttempt;
    private volatile String pendingLyricsTrackKey = "";
    private volatile int pendingLyricsRetryAttempt;

    @Override
    public void onCreate() {
        super.onCreate();
        lyricsRepository = new PhoneLyricsRepository();
        connectionManager = PhoneConnectionManager.getInstance(this);
        connectionManager.setNotificationAccessGranted(hasNotificationAccessConfigured());
        connectionManager.setNotificationListenerActive(false);
        connectionManager.setMediaState(false, false, false);
        Log.i(TAG, "PhoneCompanionService created");
    }

    @Override
    public void onDestroy() {
        cancelArtworkRetry();
        cancelLyricsRetry();
        connectionManager.clearControlDelegate(controlDelegate);
        detachMediaSessionListener();
        super.onDestroy();
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        attachMediaSessionListener();
        connectionManager.setControlDelegate(controlDelegate);
        connectionManager.start();
        connectionManager.setNotificationAccessGranted(true);
        connectionManager.setNotificationListenerActive(true);
        publishBestControllerSnapshot();
        publishFromNotifications();
        Log.i(TAG, "Notification listener connected");
    }

    @Override
    public void onListenerDisconnected() {
        connectionManager.clearControlDelegate(controlDelegate);
        detachMediaSessionListener();
        currentController = null;
        currentSnapshot = null;
        currentLyricsResult = null;
        currentTrackKey = "";
        cancelArtworkRetry();
        cancelLyricsRetry();
        connectionManager.setNotificationAccessGranted(hasNotificationAccessConfigured());
        connectionManager.setNotificationListenerActive(false);
        connectionManager.setMediaState(false, false, false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && hasNotificationAccessConfigured()) {
            NotificationListenerService.requestRebind(
                    new ComponentName(this, PhoneCompanionService.class)
            );
        }
        Log.i(TAG, "Notification listener disconnected");
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        publishFromNotifications();
    }

    private void attachMediaSessionListener() {
        try {
            mediaSessionManager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            if (mediaSessionManager == null) {
                return;
            }
            ComponentName componentName = new ComponentName(this, PhoneCompanionService.class);
            mediaSessionManager.addOnActiveSessionsChangedListener(activeSessionsChangedListener, componentName);
            updateActiveControllers(mediaSessionManager.getActiveSessions(componentName));
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

    private void publishBestControllerSnapshot() {
        MediaController bestController = chooseBestController();
        if (bestController == null) {
            currentController = null;
            currentSnapshot = null;
            currentLyricsResult = null;
            currentTrackKey = "";
            cancelArtworkRetry();
            cancelLyricsRetry();
            connectionManager.setMediaState(false, false, false);
            return;
        }
        currentController = bestController;
        ObservedPlaybackSnapshot snapshot = buildSnapshotFromController(bestController);
        if (snapshot == null || !snapshot.hasTrackData()) {
            currentSnapshot = null;
            currentLyricsResult = null;
            cancelArtworkRetry();
            cancelLyricsRetry();
            connectionManager.setMediaState(true, false, false);
            return;
        }
        publishSnapshot(snapshot);
    }

    private boolean publishFromNotifications() {
        if (currentSnapshot == null || currentSnapshot.artwork != null) {
            return false;
        }
        try {
            StatusBarNotification[] notifications = getActiveNotifications();
            if (notifications == null) {
                return false;
            }
            for (StatusBarNotification notification : notifications) {
                if (notification == null || notification.getNotification() == null) {
                    continue;
                }
                if (!TextUtils.equals(notification.getPackageName(), currentSnapshot.packageName)) {
                    continue;
                }
                Bitmap artwork = readNotificationArtwork(notification);
                if (artwork != null) {
                    publishSnapshot(new ObservedPlaybackSnapshot(
                            currentSnapshot.packageName,
                            currentSnapshot.title,
                            currentSnapshot.artist,
                            currentSnapshot.album,
                            currentSnapshot.durationMs,
                            currentSnapshot.positionMs,
                            currentSnapshot.lastPositionUpdateTimeMs,
                            currentSnapshot.playing,
                            artwork
                    ));
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return false;
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
                score += 20;
            }
        }

        MediaMetadata metadata = controller.getMetadata();
        if (metadata != null) {
            if (!TextUtils.isEmpty(firstNonEmpty(
                    metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                    metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)))) {
                score += 30;
            }
            if (!TextUtils.isEmpty(firstNonEmpty(
                    metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                    metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)))) {
                score += 10;
            }
            if (metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null
                    || metadata.getBitmap(MediaMetadata.METADATA_KEY_ART) != null) {
                score += 10;
            }
        }
        return score;
    }

    private ObservedPlaybackSnapshot buildSnapshotFromController(MediaController controller) {
        MediaMetadata metadata = controller.getMetadata();
        PlaybackState playbackState = controller.getPlaybackState();
        if (metadata == null && playbackState == null) {
            return null;
        }

        String title = metadata == null ? "" : firstNonEmpty(
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        );
        String artist = metadata == null ? "" : firstNonEmpty(
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        );
        String album = metadata == null ? "" : firstNonEmpty(
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)
        );
        MediaDescription description = metadata == null ? null : metadata.getDescription();
        Bitmap artwork = resolveArtwork(metadata, description);

        long durationMs = metadata == null ? 0L : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        int state = playbackState == null ? PlaybackState.STATE_NONE : playbackState.getState();
        boolean playing = state == PlaybackState.STATE_PLAYING
                || state == PlaybackState.STATE_BUFFERING
                || state == PlaybackState.STATE_FAST_FORWARDING
                || state == PlaybackState.STATE_REWINDING;
        long positionMs = playbackState == null ? 0L : playbackState.getPosition();
        long lastPositionTimeMs = playbackState == null ? 0L : playbackState.getLastPositionUpdateTime();

        return new ObservedPlaybackSnapshot(
                controller.getPackageName(),
                title,
                artist,
                album,
                durationMs,
                positionMs,
                lastPositionTimeMs,
                playing,
                artwork
        );
    }

    private void publishSnapshot(ObservedPlaybackSnapshot snapshot) {
        snapshot = enrichSnapshotWithCachedArtwork(snapshot);
        if (snapshot == null) {
            return;
        }
        currentSnapshot = snapshot;
        boolean trackChanged = !TextUtils.equals(currentTrackKey, snapshot.getTrackKey());
        currentTrackKey = snapshot.getTrackKey();
        if (trackChanged) {
            currentLyricsResult = null;
            lastLyricsAttemptElapsedMs = 0L;
            cancelArtworkRetry();
            cancelLyricsRetry();
        }
        cacheArtwork(snapshot);
        Log.i(
                TAG,
                "publishSnapshot track=" + snapshot.title
                        + " artist=" + snapshot.artist
                        + " artwork=" + (snapshot.artwork != null)
                        + " playing=" + snapshot.playing
                        + " package=" + snapshot.packageName
        );
        if (snapshot.artwork == null) {
            scheduleArtworkRetry(snapshot.getTrackKey());
        } else {
            cancelArtworkRetry();
        }
        connectionManager.publishSnapshot(snapshot);
        connectionManager.setMediaState(true, true, currentLyricsResult != null);
        if (trackChanged || shouldRetryLyricsLookup()) {
            requestLyricsForCurrentTrack(true);
        }
    }

    private void requestLyricsForCurrentTrack(boolean forceRefresh) {
        final ObservedPlaybackSnapshot snapshot = currentSnapshot;
        if (snapshot == null || !snapshot.hasTrackData()) {
            return;
        }
        lastLyricsAttemptElapsedMs = android.os.SystemClock.elapsedRealtime();
        final String expectedTrackKey = snapshot.getTrackKey();
        lyricsRepository.requestLyrics(snapshot, forceRefresh, new PhoneLyricsRepository.Callback() {
            @Override
            public void onLoaded(PhoneLyricsResult result) {
                if (currentSnapshot == null || !TextUtils.equals(expectedTrackKey, currentSnapshot.getTrackKey())) {
                    return;
                }
                currentLyricsResult = result;
                if (result == null) {
                    Log.i(TAG, "Lyrics lookup returned null for trackKey=" + expectedTrackKey);
                    connectionManager.clearLyricsForTrack(expectedTrackKey);
                    connectionManager.setMediaState(
                            true,
                            currentSnapshot != null && currentSnapshot.hasTrackData(),
                            false
                    );
                    scheduleLyricsRetry(expectedTrackKey);
                } else {
                    cancelLyricsRetry();
                    Log.i(
                            TAG,
                            "Lyrics loaded source=" + result.sourceLabel
                                    + " synced=" + result.synced
                                    + " lines=" + result.lines.size()
                    );
                    connectionManager.publishLyrics(result);
                    connectionManager.setMediaState(true, true, true);
                }
            }
        });
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

    private void scheduleArtworkRetry(String trackKey) {
        if (TextUtils.isEmpty(trackKey)) {
            return;
        }
        if (TextUtils.equals(pendingArtworkTrackKey, trackKey)) {
            return;
        }
        pendingArtworkTrackKey = trackKey;
        pendingArtworkRetryAttempt = 0;
        mainHandler.removeCallbacks(artworkRetryRunnable);
        mainHandler.postDelayed(artworkRetryRunnable, ARTWORK_RETRY_DELAY_MS);
    }

    private void cancelArtworkRetry() {
        pendingArtworkTrackKey = "";
        pendingArtworkRetryAttempt = 0;
        mainHandler.removeCallbacks(artworkRetryRunnable);
    }

    private void scheduleLyricsRetry(String trackKey) {
        if (TextUtils.isEmpty(trackKey)) {
            return;
        }
        if (!TextUtils.equals(pendingLyricsTrackKey, trackKey)) {
            pendingLyricsTrackKey = trackKey;
            pendingLyricsRetryAttempt = 0;
        }
        if (pendingLyricsRetryAttempt >= LYRICS_RETRY_MAX_ATTEMPTS) {
            return;
        }
        mainHandler.removeCallbacks(lyricsRetryRunnable);
        mainHandler.postDelayed(lyricsRetryRunnable, LYRICS_RETRY_DELAY_MS);
    }

    private void cancelLyricsRetry() {
        pendingLyricsTrackKey = "";
        pendingLyricsRetryAttempt = 0;
        mainHandler.removeCallbacks(lyricsRetryRunnable);
    }

    private void performLyricsRetry() {
        ObservedPlaybackSnapshot snapshot = currentSnapshot;
        if (snapshot == null || !snapshot.hasTrackData()) {
            cancelLyricsRetry();
            return;
        }
        if (currentLyricsResult != null) {
            cancelLyricsRetry();
            return;
        }
        String trackKey = snapshot.getTrackKey();
        if (!TextUtils.equals(trackKey, pendingLyricsTrackKey)) {
            cancelLyricsRetry();
            return;
        }
        if (pendingLyricsRetryAttempt >= LYRICS_RETRY_MAX_ATTEMPTS) {
            cancelLyricsRetry();
            return;
        }

        pendingLyricsRetryAttempt++;
        Log.d(
                TAG,
                "Lyrics retry " + pendingLyricsRetryAttempt + "/" + LYRICS_RETRY_MAX_ATTEMPTS
                        + " for trackKey=" + trackKey
        );
        requestLyricsForCurrentTrack(true);
    }

    private void performArtworkRetry() {
        ObservedPlaybackSnapshot snapshot = currentSnapshot;
        if (snapshot == null || !snapshot.hasTrackData()) {
            cancelArtworkRetry();
            return;
        }
        String trackKey = snapshot.getTrackKey();
        if (!TextUtils.equals(trackKey, pendingArtworkTrackKey)) {
            return;
        }
        if (snapshot.artwork != null) {
            cancelArtworkRetry();
            return;
        }

        if (tryRefreshArtwork(trackKey)) {
            cancelArtworkRetry();
            return;
        }

        pendingArtworkRetryAttempt++;
        Log.d(
                TAG,
                "Artwork retry " + pendingArtworkRetryAttempt + "/" + ARTWORK_RETRY_MAX_ATTEMPTS
                        + " for trackKey=" + trackKey
        );
        if (pendingArtworkRetryAttempt >= ARTWORK_RETRY_MAX_ATTEMPTS) {
            cancelArtworkRetry();
            return;
        }
        mainHandler.postDelayed(artworkRetryRunnable, ARTWORK_RETRY_DELAY_MS);
    }

    private boolean tryRefreshArtwork(String expectedTrackKey) {
        MediaController controller = currentController;
        if (controller != null) {
            ObservedPlaybackSnapshot refreshedSnapshot = buildSnapshotFromController(controller);
            if (refreshedSnapshot != null
                    && refreshedSnapshot.artwork != null
                    && TextUtils.equals(expectedTrackKey, refreshedSnapshot.getTrackKey())) {
                publishSnapshot(refreshedSnapshot);
                return true;
            }
        }

        if (publishFromNotifications()) {
            return currentSnapshot != null
                    && currentSnapshot.artwork != null
                    && TextUtils.equals(expectedTrackKey, currentSnapshot.getTrackKey());
        }
        return false;
    }

    private Bitmap readNotificationArtwork(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) {
            return null;
        }
        Bundle extras = sbn.getNotification().extras;
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && sbn.getNotification().getLargeIcon() != null) {
            try {
                Drawable drawable = sbn.getNotification().getLargeIcon().loadDrawable(this);
                if (drawable instanceof BitmapDrawable) {
                    return ((BitmapDrawable) drawable).getBitmap();
                }
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    private Bitmap resolveArtwork(MediaMetadata metadata, MediaDescription description) {
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
            addUriIfPresent(candidateUris, metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI));
            addUriIfPresent(candidateUris, metadata.getString(MediaMetadata.METADATA_KEY_ART_URI));
            addUriIfPresent(candidateUris, metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI));
        }

        for (Uri candidateUri : candidateUris) {
            artwork = loadArtworkFromUri(candidateUri);
            if (artwork != null) {
                return artwork;
            }
        }
        return null;
    }

    private void addUriIfPresent(List<Uri> target, String uriString) {
        if (target == null || TextUtils.isEmpty(uriString)) {
            return;
        }
        try {
            Uri uri = Uri.parse(uriString);
            if (uri != null) {
                target.add(uri);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private Bitmap loadArtworkFromUri(Uri uri) {
        if (uri == null) {
            return null;
        }
        String scheme = uri.getScheme();
        try {
            if ("content".equalsIgnoreCase(scheme)
                    || "android.resource".equalsIgnoreCase(scheme)
                    || "file".equalsIgnoreCase(scheme)) {
                InputStream inputStream = getContentResolver().openInputStream(uri);
                if (inputStream == null) {
                    return null;
                }
                try {
                    return decodeArtworkStream(inputStream);
                } finally {
                    inputStream.close();
                }
            }
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                InputStream inputStream = new URL(uri.toString()).openStream();
                try {
                    return decodeArtworkStream(inputStream);
                } finally {
                    inputStream.close();
                }
            }
        } catch (IOException ignored) {
        } catch (SecurityException ignored) {
        }
        return null;
    }

    private Bitmap decodeArtworkStream(InputStream inputStream) {
        if (inputStream == null) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeStream(inputStream, null, options);
    }

    private void cacheArtwork(ObservedPlaybackSnapshot snapshot) {
        if (snapshot == null || snapshot.artwork == null) {
            return;
        }
        String trackKey = snapshot.getTrackKey();
        if (TextUtils.isEmpty(trackKey)) {
            return;
        }
        artworkCache.put(trackKey, snapshot.artwork);
    }

    private ObservedPlaybackSnapshot enrichSnapshotWithCachedArtwork(ObservedPlaybackSnapshot snapshot) {
        if (snapshot == null || snapshot.artwork != null) {
            return snapshot;
        }
        String trackKey = snapshot.getTrackKey();
        if (TextUtils.isEmpty(trackKey)) {
            return snapshot;
        }
        Bitmap cachedArtwork = artworkCache.get(trackKey);
        if (cachedArtwork == null) {
            return snapshot;
        }
        return snapshot.copyWithArtwork(cachedArtwork);
    }

    private String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) {
                return value;
            }
        }
        return "";
    }

    private boolean shouldRetryLyricsLookup() {
        if (currentSnapshot == null || !currentSnapshot.hasTrackData()) {
            return false;
        }
        if (currentLyricsResult != null) {
            return false;
        }
        if (!currentSnapshot.playing) {
            return false;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        return lastLyricsAttemptElapsedMs == 0L || now - lastLyricsAttemptElapsedMs >= 8000L;
    }
}
