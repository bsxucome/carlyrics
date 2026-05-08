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
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.bsxu.carlyrics.phone.R;
import com.bsxu.carlyrics.bridge.BridgeCodec;
import com.bsxu.carlyrics.bridge.BridgeContract;
import com.bsxu.carlyrics.bridge.ControlMessage;
import com.bsxu.carlyrics.bridge.DecodedMessage;
import com.bsxu.carlyrics.bridge.HelloMessage;
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
import java.util.UUID;

public final class PhoneConnectionManager {

    public interface ControlDelegate {
        void onPlayPauseRequested();

        void onNextRequested();

        void onPreviousRequested();

        void onResendLyricsRequested();
    }

    private static final String TAG = "PhoneConnMgr";

    private static volatile PhoneConnectionManager instance;
    private static volatile String uiStatus = "";

    private final Context appContext;
    private final Handler mainHandler;
    private final PhoneIdentityStore identityStore;
    private final Object writeLock;

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

    private volatile BluetoothServerSocket serverSocket;
    private volatile BluetoothServerSocket insecureServerSocket;
    private volatile BluetoothSocket clientSocket;
    private volatile BufferedWriter writer;
    private volatile Thread acceptThread;
    private volatile Thread insecureAcceptThread;
    private volatile Thread readThread;
    private volatile ControlDelegate controlDelegate;
    private volatile ObservedPlaybackSnapshot currentSnapshot;
    private volatile PhoneLyricsResult currentLyricsResult;
    private volatile String currentTrackKey = "";
    private volatile String connectedClientName = "";
    private volatile String lastArtworkSentTrackKey = "";
    private volatile String lastLyricsSentTrackKey = "";
    private volatile long lastLyricsSentElapsedMs;
    private volatile RemoteSessionStatusPayload currentSessionStatus =
            new RemoteSessionStatusPayload(false, false, false, false);
    private volatile boolean shouldRun = false;

    private PhoneConnectionManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.identityStore = new PhoneIdentityStore(appContext);
        this.writeLock = new Object();
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
        mainHandler.post(heartbeatSender);
        startBluetoothServer();
    }

    public void stop() {
        shouldRun = false;
        mainHandler.removeCallbacks(heartbeatSender);
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
        updateSessionStatus(
                currentSessionStatus.notificationAccessGranted,
                currentSessionStatus.mediaSessionReadable,
                true,
                currentLyricsResult != null
        );
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
        updateSessionStatus(
                currentSessionStatus.notificationAccessGranted,
                currentSessionStatus.mediaSessionReadable,
                currentSnapshot != null && currentSnapshot.hasTrackData(),
                true
        );
    }

    public void clearLyricsForTrack(String trackKey) {
        if (TextUtils.isEmpty(trackKey) || TextUtils.equals(trackKey, currentTrackKey)) {
            currentLyricsResult = null;
            lastLyricsSentTrackKey = "";
            updateSessionStatus(
                    currentSessionStatus.notificationAccessGranted,
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
        updateSessionStatus(false, false, false, false);
    }

    public void updateSessionStatus(
            boolean notificationAccessGranted,
            boolean mediaSessionReadable,
            boolean playbackAvailable,
            boolean lyricsAvailable
    ) {
        currentSessionStatus = new RemoteSessionStatusPayload(
                notificationAccessGranted,
                mediaSessionReadable,
                playbackAvailable,
                lyricsAvailable
        );
        sendSessionStatus();
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

        if (serverSocket != null) {
            acceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop(serverSocket, "secure");
                }
            }, "phone-connection-accept-secure");
            acceptThread.start();
        }
        if (insecureServerSocket != null) {
            insecureAcceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop(insecureServerSocket, "insecure");
                }
            }, "phone-connection-accept-insecure");
            insecureAcceptThread.start();
        }
        if (clientSocket == null) {
            updateUiStatus(appContext.getString(R.string.status_ready));
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
                scheduleServerRestartIfNeeded();
                return;
            }
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
                    BridgeContract.PROTOCOL_VERSION,
                    identityStore.getOrCreateLocalAppDeviceId(),
                    BridgeContract.ROLE_PHONE,
                    safeName(Build.MODEL),
                    "0.2.0"
            )));
            sendSessionStatus();
            sendPlaybackSnapshot(true);
            sendLyricsPayload();
            Log.i(TAG, "Client connected via " + modeLabel + " RFCOMM from " + connectedClientName);
            updateUiStatus(appContext.getString(R.string.status_connected_to, connectedClientName));
        } catch (IOException error) {
            Log.e(TAG, "Failed while preparing connected client session", error);
            closeClientConnection();
            updateUiStatus(appContext.getString(R.string.status_waiting_reconnect));
            return;
        }

        readThread = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "phone-connection-read");
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
            Log.d(TAG, "readLoop() finished for " + connectedClientName);
            closeReaderQuietly(reader);
            closeClientConnection();
            updateUiStatus(appContext.getString(R.string.status_ready));
        }
    }

    private void handleIncomingLine(String line) {
        try {
            DecodedMessage message = BridgeCodec.decode(line);
            if (BridgeContract.TYPE_HELLO.equals(message.type) && message.helloMessage != null) {
                identityStore.rememberTrustedRemote(message.helloMessage);
                Log.d(
                        TAG,
                        "Received hello role=" + message.helloMessage.role
                                + " device=" + message.helloMessage.deviceName
                                + " protocol=" + message.helloMessage.protocolVersion
                                + " remoteAppDeviceId=" + message.helloMessage.appDeviceId
                );
                return;
            }
            if (!BridgeContract.TYPE_CONTROL.equals(message.type) || message.controlMessage == null) {
                return;
            }
            handleControlMessage(message.controlMessage);
        } catch (JSONException ignored) {
        }
    }

    private void handleControlMessage(ControlMessage message) {
        ControlDelegate delegate = controlDelegate;
        if (delegate == null || message == null) {
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
        writeLine(BridgeCodec.encodePlayback(payload));
    }

    private void sendSessionStatus() {
        if (writer == null || currentSessionStatus == null) {
            return;
        }
        Log.d(
                TAG,
                "sendSessionStatus notif=" + currentSessionStatus.notificationAccessGranted
                        + " media=" + currentSessionStatus.mediaSessionReadable
                        + " playback=" + currentSessionStatus.playbackAvailable
                        + " lyrics=" + currentSessionStatus.lyricsAvailable
        );
        writeLine(BridgeCodec.encodeSessionStatus(currentSessionStatus));
    }

    private void sendLyricsPayload() {
        if (writer == null || currentSnapshot == null || currentLyricsResult == null) {
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
        writeLine(BridgeCodec.encodeLyrics(payload));
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

    private void writeLine(String line) {
        BufferedWriter activeWriter = writer;
        if (activeWriter == null || TextUtils.isEmpty(line)) {
            return;
        }
        synchronized (writeLock) {
            try {
                activeWriter.write(line);
                activeWriter.write('\n');
                activeWriter.flush();
            } catch (IOException ignored) {
                closeClientConnection();
                updateUiStatus(appContext.getString(R.string.status_ready));
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
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!shouldRun) {
                    return;
                }
                if (clientSocket != null) {
                    return;
                }
                stopBluetoothServer();
                startBluetoothServer();
            }
        }, 1200L);
    }
}
