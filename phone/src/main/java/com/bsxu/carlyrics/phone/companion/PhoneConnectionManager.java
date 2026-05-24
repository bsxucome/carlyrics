package com.bsxu.carlyrics.phone.companion;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.bsxu.carlyrics.phone.R;
import com.bsxu.carlyrics.bridge.BridgeCodec;
import com.bsxu.carlyrics.bridge.BridgeContract;
import com.bsxu.carlyrics.bridge.ControlMessage;
import com.bsxu.carlyrics.bridge.DecodedMessage;
import com.bsxu.carlyrics.bridge.HelloMessage;
import com.bsxu.carlyrics.bridge.PingMessage;
import com.bsxu.carlyrics.bridge.RemoteLyricsPayload;
import com.bsxu.carlyrics.bridge.RemotePlaybackPayload;
import com.bsxu.carlyrics.bridge.RemoteSessionStatusPayload;
import com.bsxu.carlyrics.phone.lyrics.PhoneLyricsResult;

import org.json.JSONException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PhoneConnectionManager {

    private static final String TAG = "PhoneConnMgr";
    private static final long HANDSHAKE_TIMEOUT_MS = 6000L;
    private static final long KEEPALIVE_INTERVAL_MS = 4000L;
    private static final long IDLE_TIMEOUT_MS = 15000L;
    private static final long SERVER_RESTART_INITIAL_DELAY_MS = 1200L;
    private static final long SERVER_RESTART_MAX_DELAY_MS = 15000L;
    private static final long CONNECTION_MAINTENANCE_TICK_MS = 1000L;

    public interface ControlDelegate {
        void onPlayPauseRequested();

        void onNextRequested();

        void onPreviousRequested();

        void onResendLyricsRequested();
    }

    private static volatile PhoneConnectionManager instance;
    private static volatile String uiStatus = "";

    private final Context appContext;
    private final Handler mainHandler;
    private final PhoneIdentityStore identityStore;
    private final Object writeLock;
    private final Object sessionLock;

    private final Runnable heartbeatSender = new Runnable() {
        @Override
        public void run() {
            try {
                if (currentSnapshot != null && currentSnapshot.playing) {
                    sendPlaybackSnapshot(false);
                    maybeResendLyrics();
                }
            } finally {
                if (shouldRun) {
                    mainHandler.postDelayed(this, 1000L);
                }
            }
        }
    };

    private final Runnable connectionMaintenanceRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                maintainConnection();
            } finally {
                if (shouldRun) {
                    mainHandler.postDelayed(this, CONNECTION_MAINTENANCE_TICK_MS);
                }
            }
        }
    };

    private final Runnable restartServerSocketsRunnable = new Runnable() {
        @Override
        public void run() {
            if (!shouldRun) {
                return;
            }
            restartBluetoothServerSockets();
        }
    };

    private volatile BluetoothServerSocket serverSocket;
    private volatile BluetoothServerSocket insecureServerSocket;
    private volatile BluetoothSocket clientSocket;
    private volatile BufferedWriter writer;
    private volatile Thread acceptThread;
    private volatile Thread insecureAcceptThread;
    private volatile Thread readThread;
    private volatile int connectionGeneration;
    private volatile int serverRestartAttempt;
    private volatile boolean handshakeComplete;
    private volatile long handshakeStartedElapsedMs;
    private volatile long lastInboundElapsedMs;
    private volatile long lastOutboundElapsedMs;
    private volatile long pendingPingNonce;
    private volatile ControlDelegate controlDelegate;
    private volatile ObservedPlaybackSnapshot currentSnapshot;
    private volatile PhoneLyricsResult currentLyricsResult;
    private volatile String currentTrackKey = "";
    private volatile String connectedClientName = "";
    private volatile String lastArtworkSentTrackKey = "";
    private volatile String lastLyricsSentTrackKey = "";
    private volatile long lastLyricsSentElapsedMs;
    private final Map<String, String> artworkPayloadCache;
    private volatile RemoteSessionStatusPayload currentSessionStatus =
            new RemoteSessionStatusPayload(false, false, false, false, false);
    private volatile boolean shouldRun = false;

    private PhoneConnectionManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.identityStore = new PhoneIdentityStore(appContext);
        this.writeLock = new Object();
        this.sessionLock = new Object();
        this.artworkPayloadCache = new ConcurrentHashMap<String, String>();
        updateUiStatus(appContext.getString(R.string.status_idle));
    }

    public static PhoneConnectionManager getInstance(Context context) {
        if (instance == null) {
            synchronized (PhoneConnectionManager.class) {
                if (instance == null) {
                    instance = new PhoneConnectionManager(context);
                }
            }
        }
        return instance;
    }

    public static String getUiStatus() {
        return uiStatus;
    }

    public void start() {
        shouldRun = true;
        mainHandler.removeCallbacks(heartbeatSender);
        mainHandler.removeCallbacks(connectionMaintenanceRunnable);
        mainHandler.removeCallbacks(restartServerSocketsRunnable);
        mainHandler.post(heartbeatSender);
        mainHandler.post(connectionMaintenanceRunnable);
        startBluetoothServer();
    }

    public void stop() {
        shouldRun = false;
        serverRestartAttempt = 0;
        mainHandler.removeCallbacks(heartbeatSender);
        mainHandler.removeCallbacks(connectionMaintenanceRunnable);
        mainHandler.removeCallbacks(restartServerSocketsRunnable);
        stopBluetoothServer();
    }

    public void setControlDelegate(ControlDelegate delegate) {
        this.controlDelegate = delegate;
    }

    public void clearControlDelegate(ControlDelegate delegate) {
        if (this.controlDelegate == delegate) {
            this.controlDelegate = null;
        }
    }

    public void publishSnapshot(ObservedPlaybackSnapshot snapshot) {
        if (snapshot == null || !snapshot.hasTrackData()) {
            return;
        }
        currentSnapshot = snapshot;
        boolean trackChanged = !TextUtils.equals(currentTrackKey, snapshot.getTrackKey());
        currentTrackKey = snapshot.getTrackKey();
        if (trackChanged) {
            currentLyricsResult = null;
            lastLyricsSentTrackKey = "";
            lastArtworkSentTrackKey = "";
        }
        sendPlaybackSnapshot(trackChanged);
        setMediaState(true, true, currentLyricsResult != null);
    }

    public void publishLyrics(PhoneLyricsResult result) {
        if (result == null) {
            return;
        }
        if (currentSnapshot != null && !TextUtils.equals(result.trackKey, currentSnapshot.getTrackKey())) {
            return;
        }
        currentLyricsResult = result;
        sendLyricsPayload();
        setMediaState(
                currentSessionStatus.mediaSessionReadable,
                currentSnapshot != null && currentSnapshot.hasTrackData(),
                true
        );
    }

    public void clearLyricsForTrack(String trackKey) {
        if (TextUtils.isEmpty(trackKey) || TextUtils.equals(trackKey, currentTrackKey)) {
            currentLyricsResult = null;
            lastLyricsSentTrackKey = "";
            setMediaState(
                    currentSessionStatus.mediaSessionReadable,
                    currentSnapshot != null && currentSnapshot.hasTrackData(),
                    false
            );
        }
    }

    public void clearPublishedState() {
        currentSnapshot = null;
        currentLyricsResult = null;
        currentTrackKey = "";
        lastArtworkSentTrackKey = "";
        lastLyricsSentTrackKey = "";
        lastLyricsSentElapsedMs = 0L;
        setMediaState(false, false, false);
    }

    public void setNotificationAccessGranted(boolean granted) {
        updateSessionStatus(
                granted,
                currentSessionStatus.notificationListenerActive,
                currentSessionStatus.mediaSessionReadable,
                currentSessionStatus.playbackAvailable,
                currentSessionStatus.lyricsAvailable
        );
    }

    public void setNotificationListenerActive(boolean active) {
        updateSessionStatus(
                currentSessionStatus.notificationAccessGranted,
                active,
                currentSessionStatus.mediaSessionReadable,
                currentSessionStatus.playbackAvailable,
                currentSessionStatus.lyricsAvailable
        );
    }

    public void setMediaState(boolean mediaSessionReadable, boolean playbackAvailable, boolean lyricsAvailable) {
        updateSessionStatus(
                currentSessionStatus.notificationAccessGranted,
                currentSessionStatus.notificationListenerActive,
                mediaSessionReadable,
                playbackAvailable,
                lyricsAvailable
        );
    }

    public void updateSessionStatus(
            boolean notificationAccessGranted,
            boolean notificationListenerActive,
            boolean mediaSessionReadable,
            boolean playbackAvailable,
            boolean lyricsAvailable
    ) {
        currentSessionStatus = new RemoteSessionStatusPayload(
                notificationAccessGranted,
                notificationListenerActive,
                mediaSessionReadable,
                playbackAvailable,
                lyricsAvailable
        );
        sendSessionStatus();
    }

    public void sendFullStateReplay() {
        sendSessionStatus();
        sendPlaybackSnapshot(true);
        sendLyricsPayload();
    }

    private void startBluetoothServer() {
        if (!hasBluetoothPermission()) {
            updateUiStatus(appContext.getString(R.string.status_bluetooth_missing_runtime));
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            updateUiStatus(appContext.getString(R.string.status_bluetooth_not_available));
            return;
        }
        if (!adapter.isEnabled()) {
            updateUiStatus(appContext.getString(R.string.status_turn_on_bluetooth));
            return;
        }
        if (serverSocket != null || insecureServerSocket != null) {
            if (clientSocket == null) {
                updateUiStatus(appContext.getString(R.string.status_ready));
            }
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
            updateUiStatus(appContext.getString(R.string.status_bluetooth_server_failed));
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
            updateUiStatus(appContext.getString(R.string.status_bluetooth_server_failed));
            return;
        }

        serverRestartAttempt = 0;
        mainHandler.removeCallbacks(restartServerSocketsRunnable);

        if (serverSocket != null) {
            final BluetoothServerSocket secureServerSocket = serverSocket;
            acceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop(secureServerSocket, "secure");
                }
            }, "phone-connection-accept-secure");
            acceptThread.start();
        }
        if (insecureServerSocket != null) {
            final BluetoothServerSocket fallbackServerSocket = insecureServerSocket;
            insecureAcceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop(fallbackServerSocket, "insecure");
                }
            }, "phone-connection-accept-insecure");
            insecureAcceptThread.start();
        }
        if (clientSocket == null) {
            updateUiStatus(appContext.getString(R.string.status_ready));
        }
    }

    private void stopBluetoothServer() {
        closeCurrentClientConnection(null, false);
        closeServerSocketsOnly();
    }

    private void restartBluetoothServerSockets() {
        closeServerSocketsOnly();
        startBluetoothServer();
    }

    private void closeServerSocketsOnly() {
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
                scheduleServerRestartIfNeeded();
                return;
            }
        }
    }

    private void onClientConnected(BluetoothSocket acceptedSocket, String modeLabel) {
        final int sessionGeneration;
        synchronized (sessionLock) {
            connectionGeneration++;
            closeSocketQuietly(clientSocket);
            clearClientSessionLocked();
            clientSocket = acceptedSocket;
            connectedClientName = safeName(acceptedSocket.getRemoteDevice() == null
                    ? ""
                    : acceptedSocket.getRemoteDevice().getName());
            handshakeStartedElapsedMs = SystemClock.elapsedRealtime();
            lastInboundElapsedMs = handshakeStartedElapsedMs;
            lastOutboundElapsedMs = 0L;
            pendingPingNonce = 0L;
            handshakeComplete = false;
            sessionGeneration = connectionGeneration;
            try {
                writer = new BufferedWriter(new OutputStreamWriter(acceptedSocket.getOutputStream(), "UTF-8"));
            } catch (IOException error) {
                closeSocketQuietly(acceptedSocket);
                clearClientSessionLocked();
                writer = null;
                updateUiStatus(appContext.getString(R.string.status_ready));
                return;
            }
        }

        if (!sendHello(acceptedSocket)) {
            Log.e(TAG, "Failed to send local hello to " + connectedClientName);
            closeCurrentClientConnection(acceptedSocket, true);
            return;
        }
        Log.i(TAG, "Client connected via " + modeLabel + " RFCOMM from " + connectedClientName + ", awaiting handshake");

        readThread = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop(acceptedSocket, sessionGeneration);
            }
        }, "phone-connection-read");
        readThread.start();
    }

    private boolean sendHello(BluetoothSocket targetSocket) {
        return writeLine(
                targetSocket,
                BridgeCodec.encodeHello(new HelloMessage(
                        BridgeContract.PROTOCOL_VERSION,
                        identityStore.getOrCreateLocalAppDeviceId(),
                        BridgeContract.ROLE_PHONE,
                        safeName(Build.MODEL),
                        "0.2.0"
                )),
                false
        );
    }

    private void readLoop(BluetoothSocket activeSocket, int sessionGeneration) {
        if (activeSocket == null) {
            return;
        }
        String sessionClientName = connectedClientName;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(activeSocket.getInputStream(), "UTF-8"));
            String line;
            while (sessionGeneration == connectionGeneration
                    && isCurrentClientSocket(activeSocket)
                    && (line = reader.readLine()) != null) {
                noteInbound();
                handleIncomingLine(activeSocket, line);
            }
        } catch (IOException ignored) {
        } finally {
            Log.d(TAG, "readLoop() finished for " + sessionClientName);
            closeReaderQuietly(reader);
            closeCurrentClientConnection(activeSocket, true);
        }
    }

    private void handleIncomingLine(BluetoothSocket sourceSocket, String line) {
        if (sourceSocket == null || !isCurrentClientSocket(sourceSocket)) {
            return;
        }
        try {
            DecodedMessage message = BridgeCodec.decode(line);
            if (BridgeContract.TYPE_PING.equals(message.type) && message.pingMessage != null) {
                writeLine(sourceSocket, BridgeCodec.encodePong(message.pingMessage), false);
                return;
            }
            if (BridgeContract.TYPE_PONG.equals(message.type)) {
                return;
            }
            if (BridgeContract.TYPE_HELLO.equals(message.type) && message.helloMessage != null) {
                handleRemoteHello(sourceSocket, message.helloMessage);
                return;
            }
            if (!handshakeComplete) {
                Log.w(TAG, "Rejecting pre-handshake message type=" + message.type);
                closeCurrentClientConnection(sourceSocket, true);
                return;
            }
            if (!BridgeContract.TYPE_CONTROL.equals(message.type) || message.controlMessage == null) {
                return;
            }
            handleControlMessage(message.controlMessage);
        } catch (JSONException ignored) {
        }
    }

    private void handleRemoteHello(BluetoothSocket sourceSocket, HelloMessage helloMessage) {
        if (helloMessage == null || !isCurrentClientSocket(sourceSocket)) {
            return;
        }
        if (helloMessage.protocolVersion != BridgeContract.PROTOCOL_VERSION) {
            Log.w(TAG, "Rejecting remote hello due to protocol mismatch: " + helloMessage.protocolVersion);
            closeCurrentClientConnection(sourceSocket, true);
            return;
        }
        if (!TextUtils.equals(helloMessage.role, BridgeContract.ROLE_HEADUNIT)) {
            Log.w(TAG, "Rejecting remote hello due to role mismatch: " + helloMessage.role);
            closeCurrentClientConnection(sourceSocket, true);
            return;
        }
        if (TextUtils.isEmpty(helloMessage.appDeviceId)) {
            Log.w(TAG, "Rejecting remote hello because appDeviceId is empty");
            closeCurrentClientConnection(sourceSocket, true);
            return;
        }
        String trustedRemoteAppDeviceId = identityStore.getTrustedRemoteAppDeviceId();
        if (!TextUtils.isEmpty(trustedRemoteAppDeviceId)
                && !TextUtils.equals(trustedRemoteAppDeviceId, helloMessage.appDeviceId)) {
            Log.w(TAG, "Rejecting remote hello due to trusted appDeviceId mismatch");
            closeCurrentClientConnection(sourceSocket, true);
            return;
        }

        identityStore.rememberTrustedRemote(helloMessage);
        synchronized (sessionLock) {
            handshakeComplete = true;
            handshakeStartedElapsedMs = 0L;
            lastInboundElapsedMs = SystemClock.elapsedRealtime();
            lastOutboundElapsedMs = 0L;
        }
        serverRestartAttempt = 0;
        updateUiStatus(appContext.getString(R.string.status_connected_to, connectedClientName));
        Log.d(
                TAG,
                "Received hello role=" + helloMessage.role
                        + " device=" + helloMessage.deviceName
                        + " protocol=" + helloMessage.protocolVersion
                        + " remoteAppDeviceId=" + helloMessage.appDeviceId
        );
        sendFullStateReplay();
    }

    private void handleControlMessage(ControlMessage message) {
        if (message == null) {
            return;
        }
        if (BridgeContract.ACTION_RESEND_STATE.equals(message.action)) {
            Log.d(TAG, "Received full state replay request from head unit");
            sendFullStateReplay();
            return;
        }
        ControlDelegate delegate = controlDelegate;
        if (delegate == null) {
            return;
        }
        if (BridgeContract.ACTION_PLAY_PAUSE.equals(message.action)) {
            delegate.onPlayPauseRequested();
            return;
        }
        if (BridgeContract.ACTION_NEXT.equals(message.action)) {
            delegate.onNextRequested();
            return;
        }
        if (BridgeContract.ACTION_PREVIOUS.equals(message.action)) {
            delegate.onPreviousRequested();
            return;
        }
        if (BridgeContract.ACTION_RESEND_LYRICS.equals(message.action)) {
            delegate.onResendLyricsRequested();
        }
    }

    private void sendPlaybackSnapshot(boolean includeArtwork) {
        BluetoothSocket activeSocket = clientSocket;
        if (activeSocket == null || !handshakeComplete || currentSnapshot == null || !currentSnapshot.hasTrackData()) {
            return;
        }
        boolean shouldIncludeArtwork = includeArtwork
                || (currentSnapshot.artwork != null
                && !TextUtils.equals(lastArtworkSentTrackKey, currentSnapshot.getTrackKey()));
        String artworkBase64 = shouldIncludeArtwork ? resolveArtworkPayload(currentSnapshot) : "";
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
        writeLine(activeSocket, BridgeCodec.encodePlayback(payload), true);
    }

    private String resolveArtworkPayload(ObservedPlaybackSnapshot snapshot) {
        if (snapshot == null) {
            return "";
        }
        String trackKey = snapshot.getTrackKey();
        if (snapshot.artwork != null) {
            String encodedArtwork = encodeArtworkToBase64(snapshot.artwork);
            if (!TextUtils.isEmpty(encodedArtwork) && !TextUtils.isEmpty(trackKey)) {
                artworkPayloadCache.put(trackKey, encodedArtwork);
            }
            return encodedArtwork;
        }
        if (TextUtils.isEmpty(trackKey)) {
            return "";
        }
        String cachedPayload = artworkPayloadCache.get(trackKey);
        return cachedPayload == null ? "" : cachedPayload;
    }

    private void sendSessionStatus() {
        BluetoothSocket activeSocket = clientSocket;
        if (activeSocket == null || !handshakeComplete || currentSessionStatus == null) {
            return;
        }
        Log.d(
                TAG,
                "sendSessionStatus notif=" + currentSessionStatus.notificationAccessGranted
                        + " listener=" + currentSessionStatus.notificationListenerActive
                        + " media=" + currentSessionStatus.mediaSessionReadable
                        + " playback=" + currentSessionStatus.playbackAvailable
                        + " lyrics=" + currentSessionStatus.lyricsAvailable
        );
        writeLine(activeSocket, BridgeCodec.encodeSessionStatus(currentSessionStatus), true);
    }

    private void sendLyricsPayload() {
        BluetoothSocket activeSocket = clientSocket;
        if (activeSocket == null || !handshakeComplete || currentSnapshot == null || currentLyricsResult == null) {
            return;
        }
        RemoteLyricsPayload payload = new RemoteLyricsPayload(
                currentLyricsResult.trackKey,
                currentLyricsResult.sourceLabel,
                currentLyricsResult.synced,
                currentLyricsResult.lines
        );
        lastLyricsSentTrackKey = currentLyricsResult.trackKey;
        lastLyricsSentElapsedMs = SystemClock.elapsedRealtime();
        writeLine(activeSocket, BridgeCodec.encodeLyrics(payload), true);
    }

    private void maybeResendLyrics() {
        if (currentLyricsResult == null || clientSocket == null || !handshakeComplete) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!TextUtils.equals(lastLyricsSentTrackKey, currentLyricsResult.trackKey)
                || now - lastLyricsSentElapsedMs >= 5000L) {
            sendLyricsPayload();
        }
    }

    private void maintainConnection() {
        BluetoothSocket activeSocket = clientSocket;
        if (activeSocket == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!handshakeComplete) {
            if (handshakeStartedElapsedMs > 0L && now - handshakeStartedElapsedMs >= HANDSHAKE_TIMEOUT_MS) {
                Log.w(TAG, "Handshake timed out for " + connectedClientName);
                closeCurrentClientConnection(activeSocket, true);
            }
            return;
        }
        if (lastInboundElapsedMs > 0L && now - lastInboundElapsedMs >= IDLE_TIMEOUT_MS) {
            Log.w(TAG, "Connection idle timeout reached for " + connectedClientName);
            closeCurrentClientConnection(activeSocket, true);
            return;
        }
        if (now - lastOutboundElapsedMs >= KEEPALIVE_INTERVAL_MS) {
            pendingPingNonce = now;
            writeLine(activeSocket, BridgeCodec.encodePing(new PingMessage(pendingPingNonce)), false);
        }
    }

    private void noteInbound() {
        lastInboundElapsedMs = SystemClock.elapsedRealtime();
    }

    private void noteOutbound() {
        lastOutboundElapsedMs = SystemClock.elapsedRealtime();
    }

    private boolean writeLine(BluetoothSocket targetSocket, String line, boolean requireHandshake) {
        BufferedWriter activeWriter = writer;
        if (targetSocket == null || activeWriter == null || TextUtils.isEmpty(line) || !isCurrentClientSocket(targetSocket)) {
            return false;
        }
        if (requireHandshake && !handshakeComplete) {
            return false;
        }
        synchronized (writeLock) {
            if (targetSocket != clientSocket || writer == null) {
                return false;
            }
            try {
                activeWriter.write(line);
                activeWriter.write('\n');
                activeWriter.flush();
                noteOutbound();
                return true;
            } catch (IOException ignored) {
                closeCurrentClientConnection(targetSocket, true);
                return false;
            }
        }
    }

    private String encodeArtworkToBase64(Bitmap artwork) {
        if (artwork == null) {
            return "";
        }
        Bitmap scaled = Bitmap.createScaledBitmap(
                artwork,
                Math.max(1, artwork.getWidth() > artwork.getHeight()
                        ? 320
                        : Math.round(320f * artwork.getWidth() / artwork.getHeight())),
                Math.max(1, artwork.getHeight() >= artwork.getWidth()
                        ? 320
                        : Math.round(320f * artwork.getHeight() / artwork.getWidth())),
                true
        );
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        scaled.compress(CompressFormat.JPEG, 82, outputStream);
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP);
    }

    private boolean hasBluetoothPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean closeCurrentClientConnection(BluetoothSocket expectedSocket, boolean updateReadyStatus) {
        BluetoothSocket socketToClose;
        synchronized (sessionLock) {
            if (expectedSocket != null && expectedSocket != clientSocket) {
                return false;
            }
            socketToClose = clientSocket;
            if (socketToClose == null) {
                return false;
            }
            closeSocketQuietly(socketToClose);
            connectionGeneration++;
            clearClientSessionLocked();
        }
        if (shouldRun && updateReadyStatus) {
            updateUiStatus(appContext.getString(R.string.status_ready));
        }
        if (shouldRun && serverSocket == null && insecureServerSocket == null) {
            scheduleServerRestartIfNeeded();
        }
        return true;
    }

    private void clearClientSessionLocked() {
        clientSocket = null;
        writer = null;
        readThread = null;
        connectedClientName = "";
        handshakeComplete = false;
        handshakeStartedElapsedMs = 0L;
        lastInboundElapsedMs = 0L;
        lastOutboundElapsedMs = 0L;
        pendingPingNonce = 0L;
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

    private void closeReaderQuietly(BufferedReader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (IOException ignored) {
        }
    }

    private void closeSocketQuietly(BluetoothSocket bluetoothSocket) {
        if (bluetoothSocket == null) {
            return;
        }
        try {
            bluetoothSocket.close();
        } catch (IOException ignored) {
        }
    }

    private boolean isCurrentClientSocket(BluetoothSocket candidate) {
        return candidate != null && candidate == clientSocket;
    }

    private String safeName(String value) {
        return TextUtils.isEmpty(value) ? appContext.getString(R.string.generic_phone_device) : value;
    }

    private void updateUiStatus(String value) {
        uiStatus = value == null ? "" : value;
    }

    private void scheduleServerRestartIfNeeded() {
        if (!shouldRun) {
            return;
        }
        long delayMs = computeServerRestartDelay(serverRestartAttempt);
        serverRestartAttempt = Math.min(serverRestartAttempt + 1, 8);
        mainHandler.removeCallbacks(restartServerSocketsRunnable);
        mainHandler.postDelayed(restartServerSocketsRunnable, delayMs);
    }

    private long computeServerRestartDelay(int attempt) {
        int safeAttempt = Math.max(0, Math.min(attempt, 6));
        long delay = SERVER_RESTART_INITIAL_DELAY_MS * (1L << safeAttempt);
        return Math.min(delay, SERVER_RESTART_MAX_DELAY_MS);
    }
}
