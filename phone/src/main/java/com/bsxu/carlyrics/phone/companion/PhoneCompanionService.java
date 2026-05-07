package com.bsxu.carlyrics.phone.companion;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.bsxu.carlyrics.bridge.BridgeCodec;
import com.bsxu.carlyrics.bridge.BridgeContract;
import com.bsxu.carlyrics.bridge.ControlMessage;
import com.bsxu.carlyrics.bridge.DecodedMessage;
import com.bsxu.carlyrics.bridge.HelloMessage;
import com.bsxu.carlyrics.bridge.RemoteLyricsPayload;
import com.bsxu.carlyrics.bridge.RemotePlaybackPayload;
import com.bsxu.carlyrics.phone.lyrics.PhoneLyricsRepository;
import com.bsxu.carlyrics.phone.lyrics.PhoneLyricsResult;

import org.json.JSONException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class PhoneCompanionService extends NotificationListenerService {

    private static final String TAG = "PhoneCompanion";
    private static volatile String uiStatus = "";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ArrayList<MediaController> activeControllers = new ArrayList<MediaController>();
    private final HashMap<MediaController, MediaController.Callback> controllerCallbacks =
            new HashMap<MediaController, MediaController.Callback>();

    private final Runnable heartbeatSender = new Runnable() {
        @Override
        public void run() {
            if (currentSnapshot != null && currentSnapshot.playing) {
                sendPlaybackSnapshot(false);
                maybeResendLyrics();
            }
            mainHandler.postDelayed(this, 1000L);
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener activeSessionsChangedListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> controllers) {
                    updateActiveControllers(controllers);
                    publishBestControllerSnapshot();
                }
            };

    private MediaSessionManager mediaSessionManager;
    private PhoneLyricsRepository lyricsRepository;

    private volatile BluetoothServerSocket serverSocket;
    private volatile BluetoothServerSocket insecureServerSocket;
    private volatile BluetoothSocket clientSocket;
    private volatile BufferedWriter writer;
    private volatile Thread acceptThread;
    private volatile Thread insecureAcceptThread;
    private volatile Thread readThread;
    private volatile MediaController currentController;
    private volatile ObservedPlaybackSnapshot currentSnapshot;
    private volatile PhoneLyricsResult currentLyricsResult;
    private volatile String currentTrackKey = "";
    private volatile String connectedClientName = "";
    private volatile long lastLyricsAttemptElapsedMs;
    private volatile String lastArtworkSentTrackKey = "";
    private volatile String lastLyricsSentTrackKey = "";
    private volatile long lastLyricsSentElapsedMs;

    public static String getUiStatus() {
        return uiStatus;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        lyricsRepository = new PhoneLyricsRepository();
        mainHandler.post(heartbeatSender);
        updateUiStatus("Status: waiting for notification access");
        Log.i(TAG, "PhoneCompanionService created");
    }

    @Override
    public void onDestroy() {
        stopBluetoothServer();
        detachMediaSessionListener();
        mainHandler.removeCallbacks(heartbeatSender);
        super.onDestroy();
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        attachMediaSessionListener();
        startBluetoothServer();
        publishFromNotifications();
        publishBestControllerSnapshot();
        Log.i(TAG, "Notification listener connected");
        updateUiStatus(clientSocket == null
                ? "Status: ready, waiting for the head unit to connect"
                : "Status: connected to " + connectedClientName);
    }

    @Override
    public void onListenerDisconnected() {
        stopBluetoothServer();
        detachMediaSessionListener();
        currentController = null;
        currentSnapshot = null;
        currentLyricsResult = null;
        currentTrackKey = "";
        Log.i(TAG, "Notification listener disconnected");
        updateUiStatus("Status: notification access disconnected");
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
            return;
        }
        currentController = bestController;
        ObservedPlaybackSnapshot snapshot = buildSnapshotFromController(bestController);
        if (snapshot == null || !snapshot.hasTrackData()) {
            return;
        }
        publishSnapshot(snapshot);
    }

    private void publishFromNotifications() {
        if (currentSnapshot == null || currentSnapshot.artwork != null) {
            return;
        }
        try {
            StatusBarNotification[] notifications = getActiveNotifications();
            if (notifications == null) {
                return;
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
                    return;
                }
            }
        } catch (RuntimeException ignored) {
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
        Bitmap artwork = null;
        if (metadata != null) {
            artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (artwork == null) {
                artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            }
            if (artwork == null) {
                MediaDescription description = metadata.getDescription();
                if (description != null) {
                    artwork = description.getIconBitmap();
                }
            }
        }

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
        currentSnapshot = snapshot;
        boolean trackChanged = !TextUtils.equals(currentTrackKey, snapshot.getTrackKey());
        currentTrackKey = snapshot.getTrackKey();
        if (trackChanged) {
            currentLyricsResult = null;
            lastLyricsAttemptElapsedMs = 0L;
            lastLyricsSentTrackKey = "";
            lastArtworkSentTrackKey = "";
        }
        Log.i(
                TAG,
                "publishSnapshot track=" + snapshot.title
                        + " artist=" + snapshot.artist
                        + " artwork=" + (snapshot.artwork != null)
                        + " playing=" + snapshot.playing
                        + " package=" + snapshot.packageName
        );
        sendPlaybackSnapshot(trackChanged);
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
                } else {
                    Log.i(
                            TAG,
                            "Lyrics loaded source=" + result.sourceLabel
                                    + " synced=" + result.synced
                                    + " lines=" + result.lines.size()
                        );
                }
                sendLyricsPayload();
            }
        });
    }

    private void startBluetoothServer() {
        if (!hasBluetoothPermission()) {
            Log.w(TAG, "Bluetooth permission missing");
            updateUiStatus("Status: Bluetooth permission is missing");
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Log.w(TAG, "Bluetooth adapter not available");
            updateUiStatus("Status: Bluetooth is not available");
            return;
        }
        if (!adapter.isEnabled()) {
            Log.w(TAG, "Bluetooth adapter disabled");
            updateUiStatus("Status: turn Bluetooth on, then wait for the head unit");
            return;
        }
        if (serverSocket != null || insecureServerSocket != null) {
            return;
        }

        try {
            serverSocket = adapter.listenUsingRfcommWithServiceRecord(
                    "CarLyricsPhoneCompanion",
                    UUID.fromString(BridgeContract.APP_UUID)
            );
            Log.i(TAG, "Secure RFCOMM server opened");
        } catch (IOException error) {
            Log.e(TAG, "Failed to open secure Bluetooth server", error);
            updateUiStatus("Status: failed to open Bluetooth server");
        }

        try {
            insecureServerSocket = adapter.listenUsingInsecureRfcommWithServiceRecord(
                    "CarLyricsPhoneCompanionInsecure",
                    UUID.fromString(BridgeContract.APP_UUID)
            );
            Log.i(TAG, "Insecure RFCOMM server opened");
        } catch (IOException error) {
            Log.e(TAG, "Failed to open insecure Bluetooth server", error);
        }

        if (serverSocket == null && insecureServerSocket == null) {
            updateUiStatus("Status: failed to open Bluetooth server");
            return;
        }

        if (serverSocket != null) {
            acceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop(serverSocket, "secure");
                }
            }, "phone-companion-accept-secure");
            acceptThread.start();
        }
        if (insecureServerSocket != null) {
            insecureAcceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop(insecureServerSocket, "insecure");
                }
            }, "phone-companion-accept-insecure");
            insecureAcceptThread.start();
        }
    }

    private void stopBluetoothServer() {
        closeClientConnection();
        closeServerSocket(serverSocket);
        closeServerSocket(insecureServerSocket);
        serverSocket = null;
        insecureServerSocket = null;
        acceptThread = null;
        insecureAcceptThread = null;
    }

    private void acceptLoop(BluetoothServerSocket activeServerSocket, String modeLabel) {
        if (activeServerSocket == null) {
            return;
        }
        while (activeServerSocket == serverSocket || activeServerSocket == insecureServerSocket) {
            try {
                BluetoothSocket acceptedSocket = activeServerSocket.accept();
                if (acceptedSocket != null) {
                    Log.i(TAG, "Accepted " + modeLabel + " RFCOMM connection");
                    onClientConnected(acceptedSocket, modeLabel);
                }
            } catch (IOException acceptError) {
                Log.w(TAG, "Accept loop ended for " + modeLabel + " RFCOMM", acceptError);
                return;
            }
        }
    }

    private void closeServerSocket(BluetoothServerSocket activeServerSocket) {
        if (activeServerSocket == null) {
            return;
        }
        try {
            activeServerSocket.close();
        } catch (IOException ignored) {
        }
    }

    private void onClientConnected(BluetoothSocket acceptedSocket, String modeLabel) {
        closeClientConnection();
        clientSocket = acceptedSocket;
        connectedClientName = safeName(acceptedSocket.getRemoteDevice() == null
                ? ""
                : acceptedSocket.getRemoteDevice().getName());
        try {
            writer = new BufferedWriter(new OutputStreamWriter(acceptedSocket.getOutputStream(), "UTF-8"));
            writeLine(BridgeCodec.encodeHello(new HelloMessage(
                    BridgeContract.ROLE_PHONE,
                    safeName(Build.MODEL),
                    "0.1.0"
            )));
            sendPlaybackSnapshot(true);
            sendLyricsPayload();
            if (currentSnapshot != null && currentLyricsResult == null) {
                Log.i(TAG, "Client connected before lyrics were ready, retrying lyrics lookup");
                requestLyricsForCurrentTrack(false);
            }
            Log.i(TAG, "Client connected via " + modeLabel + " RFCOMM from " + connectedClientName);
            updateUiStatus("Status: connected to " + connectedClientName);
        } catch (IOException error) {
            Log.e(TAG, "Failed while preparing connected client session", error);
            closeClientConnection();
            updateUiStatus("Status: waiting for the head unit to reconnect");
            return;
        }

        readThread = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "phone-companion-read");
        readThread.start();
    }

    private void readLoop() {
        BluetoothSocket activeSocket = clientSocket;
        if (activeSocket == null) {
            return;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(activeSocket.getInputStream(), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                handleIncomingLine(line);
            }
        } catch (IOException ignored) {
        } finally {
            closeReaderQuietly(reader);
            closeClientConnection();
            Log.i(TAG, "Client disconnected");
            updateUiStatus("Status: ready, waiting for the head unit to connect");
        }
    }

    private void handleIncomingLine(String line) {
        try {
            DecodedMessage message = BridgeCodec.decode(line);
            if (!BridgeContract.TYPE_CONTROL.equals(message.type) || message.controlMessage == null) {
                return;
            }
            handleControlMessage(message.controlMessage);
        } catch (JSONException ignored) {
        }
    }

    private void handleControlMessage(ControlMessage message) {
        if (message == null || currentController == null) {
            return;
        }
        if (BridgeContract.ACTION_PLAY_PAUSE.equals(message.action)) {
            if (currentSnapshot != null && currentSnapshot.playing) {
                currentController.getTransportControls().pause();
            } else {
                currentController.getTransportControls().play();
            }
            return;
        }
        if (BridgeContract.ACTION_NEXT.equals(message.action)) {
            currentController.getTransportControls().skipToNext();
            return;
        }
        if (BridgeContract.ACTION_PREVIOUS.equals(message.action)) {
            currentController.getTransportControls().skipToPrevious();
            return;
        }
        if (BridgeContract.ACTION_RESEND_LYRICS.equals(message.action)) {
            if (currentLyricsResult != null) {
                sendLyricsPayload();
            } else {
                requestLyricsForCurrentTrack(true);
            }
        }
    }

    private void sendPlaybackSnapshot(boolean includeArtwork) {
        if (writer == null || currentSnapshot == null || !currentSnapshot.hasTrackData()) {
            return;
        }
        boolean shouldIncludeArtwork = includeArtwork
                || (currentSnapshot.artwork != null
                && !TextUtils.equals(lastArtworkSentTrackKey, currentSnapshot.getTrackKey()));
        String artworkBase64 = shouldIncludeArtwork ? encodeArtworkToBase64(currentSnapshot.artwork) : "";
        RemotePlaybackPayload payload = new RemotePlaybackPayload(
                currentSnapshot.getTrackKey(),
                currentSnapshot.packageName,
                currentSnapshot.title,
                currentSnapshot.artist,
                currentSnapshot.album,
                currentSnapshot.durationMs,
                currentSnapshot.getEstimatedPositionMs(),
                currentSnapshot.playing,
                artworkBase64
        );
        if (shouldIncludeArtwork && !TextUtils.isEmpty(artworkBase64)) {
            lastArtworkSentTrackKey = currentSnapshot.getTrackKey();
        }
        Log.d(TAG, "sendPlaybackSnapshot includeArtwork=" + shouldIncludeArtwork + " artworkBytes=" + artworkBase64.length());
        writeLine(BridgeCodec.encodePlayback(payload));
    }

    private void sendLyricsPayload() {
        if (writer == null || currentSnapshot == null) {
            return;
        }
        if (currentLyricsResult == null) {
            return;
        }
        RemoteLyricsPayload payload = new RemoteLyricsPayload(
                currentLyricsResult.trackKey,
                currentLyricsResult.sourceLabel,
                currentLyricsResult.synced,
                currentLyricsResult.lines
        );
        lastLyricsSentTrackKey = currentLyricsResult.trackKey;
        lastLyricsSentElapsedMs = android.os.SystemClock.elapsedRealtime();
        Log.d(TAG, "sendLyricsPayload lines=" + currentLyricsResult.lines.size() + " synced=" + currentLyricsResult.synced);
        writeLine(BridgeCodec.encodeLyrics(payload));
    }

    private void writeLine(String line) {
        BufferedWriter activeWriter = writer;
        if (activeWriter == null || TextUtils.isEmpty(line)) {
            return;
        }
        try {
            activeWriter.write(line);
            activeWriter.write('\n');
            activeWriter.flush();
        } catch (IOException ignored) {
            closeClientConnection();
            updateUiStatus("Status: ready, waiting for the head unit to connect");
        }
    }

    private String encodeArtworkToBase64(Bitmap artwork) {
        if (artwork == null) {
            return "";
        }
        Bitmap scaled = Bitmap.createScaledBitmap(
                artwork,
                Math.max(1, artwork.getWidth() > artwork.getHeight() ? 320 : Math.round(320f * artwork.getWidth() / artwork.getHeight())),
                Math.max(1, artwork.getHeight() >= artwork.getWidth() ? 320 : Math.round(320f * artwork.getHeight() / artwork.getWidth())),
                true
        );
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        scaled.compress(CompressFormat.JPEG, 82, outputStream);
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP);
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

    private boolean hasBluetoothPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void closeClientConnection() {
        if (clientSocket != null) {
            try {
                clientSocket.close();
            } catch (IOException ignored) {
            }
        }
        clientSocket = null;
        writer = null;
        readThread = null;
        connectedClientName = "";
    }

    private void closeReaderQuietly(BufferedReader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (IOException ignored) {
        }
    }

    private String safeName(String value) {
        return TextUtils.isEmpty(value) ? "Phone" : value;
    }

    private void updateUiStatus(String value) {
        uiStatus = value == null ? "" : value;
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

    private void maybeResendLyrics() {
        if (currentLyricsResult == null || writer == null) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (!TextUtils.equals(lastLyricsSentTrackKey, currentLyricsResult.trackKey)
                || now - lastLyricsSentElapsedMs >= 5000L) {
            sendLyricsPayload();
        }
    }
}
