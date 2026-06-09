package com.bsxu.carlyrics.companion;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.bsxu.carlyrics.BuildConfig;
import com.bsxu.carlyrics.R;
import com.bsxu.carlyrics.bridge.BridgeCodec;
import com.bsxu.carlyrics.bridge.BridgeContract;
import com.bsxu.carlyrics.bridge.ConnectionMaintenancePolicy;
import com.bsxu.carlyrics.bridge.ConnectionSessionTracker;
import com.bsxu.carlyrics.bridge.ControlMessage;
import com.bsxu.carlyrics.bridge.DecodedMessage;
import com.bsxu.carlyrics.bridge.HelloMessage;
import com.bsxu.carlyrics.bridge.LimitedLineReader;
import com.bsxu.carlyrics.bridge.PingMessage;
import com.bsxu.carlyrics.bridge.RemoteLyricsPayload;
import com.bsxu.carlyrics.bridge.RemotePlaybackPayload;
import com.bsxu.carlyrics.bridge.RemoteSessionStatusPayload;

import org.json.JSONException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

public final class HeadUnitCompanionManager {

    private static final String TAG = "HeadUnitBt";
    private static final long RECONNECT_INITIAL_DELAY_MS = 1200L;
    private static final long RECONNECT_MAX_DELAY_MS = 15000L;
    private static final long CONNECTION_MAINTENANCE_TICK_MS = 1000L;
    private static final long INITIAL_STATE_REPLAY_GRACE_MS = 2500L;
    private static final long LYRICS_STATE_REPLAY_GRACE_MS = 1500L;
    private static final long STATE_REPLAY_REQUEST_INTERVAL_MS = 3000L;

    public interface Listener {
        void onSessionUpdated(HeadUnitSessionSnapshot snapshot);
    }

    private static final String PREFS = "headunit_companion";
    private static final String KEY_LAST_DEVICE_ADDRESS = "last_device_address";
    private static final String KEY_LAST_DEVICE_NAME = "last_device_name";

    private static volatile HeadUnitCompanionManager instance;

    private final Context appContext;
    private final Handler mainHandler;
    private final CopyOnWriteArraySet<Listener> listeners;
    private final Object writeLock;
    private final Object sessionLock;
    private final SharedPreferences sharedPreferences;
    private final HeadUnitIdentityStore identityStore;
    private final ConnectionSessionTracker sessionTracker;

    private final Runnable connectionMaintenanceRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                maintainConnection();
            } finally {
                mainHandler.postDelayed(this, CONNECTION_MAINTENANCE_TICK_MS);
            }
        }
    };

    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            ArrayList<String> reconnectCandidates = buildReconnectCandidateAddresses();
            if (reconnectCandidates.isEmpty() || manualDisconnectRequested) {
                return;
            }
            connectCandidates(reconnectCandidates, string(R.string.connection_state_reconnecting_last), true, false);
        }
    };

    private volatile BluetoothSocket socket;
    private volatile BufferedWriter writer;
    private volatile Thread readThread;
    private volatile Thread connectThread;
    private volatile int connectionGeneration;

    private volatile int connectionState;
    private volatile String connectionLabel;
    private volatile RemoteSessionStatusPayload sessionStatusPayload;
    private volatile RemotePlaybackPayload playbackPayload;
    private volatile RemoteLyricsPayload lyricsPayload;
    private volatile Bitmap artworkBitmap;
    private volatile long playbackReceivedElapsedMs;
    private volatile String connectedDeviceAddress = "";
    private volatile String connectedDeviceName = "";
    private volatile int reconnectAttempt;
    private volatile boolean manualDisconnectRequested;
    private volatile long pendingPingNonce;
    private volatile long lastStateReplayRequestElapsedMs;
    private volatile boolean allowTrustedIdentityReplacement;

    private HeadUnitCompanionManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.listeners = new CopyOnWriteArraySet<Listener>();
        this.writeLock = new Object();
        this.sessionLock = new Object();
        this.sharedPreferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.identityStore = new HeadUnitIdentityStore(appContext);
        this.sessionTracker = new ConnectionSessionTracker();
        this.connectionState = ConnectionState.DISCONNECTED;
        this.connectionLabel = string(R.string.connection_state_not_connected);
        this.mainHandler.post(connectionMaintenanceRunnable);
    }

    public static HeadUnitCompanionManager getInstance(Context context) {
        if (instance == null) {
            synchronized (HeadUnitCompanionManager.class) {
                if (instance == null) {
                    instance = new HeadUnitCompanionManager(context);
                }
            }
        }
        return instance;
    }

    public void registerListener(Listener listener) {
        if (listener == null) {
            return;
        }
        listeners.add(listener);
        dispatchSnapshot(listener);
    }

    public void unregisterListener(Listener listener) {
        if (listener == null) {
            return;
        }
        listeners.remove(listener);
    }

    public HeadUnitSessionSnapshot getSnapshot() {
        return new HeadUnitSessionSnapshot(
                connectionState,
                connectionLabel,
                sessionStatusPayload,
                playbackPayload,
                lyricsPayload,
                artworkBitmap,
                playbackReceivedElapsedMs
        );
    }

    public boolean hasBluetoothAdapter() {
        return BluetoothAdapter.getDefaultAdapter() != null;
    }

    public boolean isBluetoothEnabled() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null && adapter.isEnabled();
    }

    public boolean hasRequiredBluetoothPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED
                && appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED;
    }

    public List<BluetoothDevice> getBondedDevices() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !hasRequiredBluetoothPermission()) {
            return Collections.emptyList();
        }
        try {
            Set<BluetoothDevice> bondedDevices = adapter.getBondedDevices();
            if (bondedDevices == null || bondedDevices.isEmpty()) {
                return Collections.emptyList();
            }
            return new ArrayList<BluetoothDevice>(bondedDevices);
        } catch (SecurityException ignored) {
            return Collections.emptyList();
        }
    }

    public String getLastDeviceAddress() {
        return sharedPreferences.getString(KEY_LAST_DEVICE_ADDRESS, "");
    }

    public String getPrimaryTrustedDeviceAddress() {
        return identityStore.getPrimaryTrustedBluetoothAddress();
    }

    public String getPrimaryTrustedDeviceName() {
        return identityStore.getPrimaryTrustedDeviceName();
    }

    public boolean hasPrimaryTrustedDevice() {
        return identityStore.hasPrimaryTrustedDevice();
    }

    public boolean isPrimaryTrustedAddress(String address) {
        return identityStore.isPrimaryTrustedAddress(address);
    }

    public void forgetPrimaryTrustedDevice() {
        String trustedAddress = identityStore.getPrimaryTrustedBluetoothAddress();
        identityStore.clearPrimaryTrustedRemote();
        if (TextUtils.equals(trustedAddress, getLastDeviceAddress())) {
            sharedPreferences.edit()
                    .remove(KEY_LAST_DEVICE_ADDRESS)
                    .remove(KEY_LAST_DEVICE_NAME)
                    .apply();
        }
        if (TextUtils.equals(trustedAddress, connectedDeviceAddress)) {
            disconnect();
        } else {
            updateConnectionState(ConnectionState.DISCONNECTED, string(R.string.connection_state_not_connected));
        }
    }

    public void connect(final String deviceAddress) {
        if (TextUtils.isEmpty(deviceAddress)) {
            return;
        }
        ArrayList<String> addresses = new ArrayList<String>();
        addresses.add(deviceAddress);
        connectCandidates(addresses, string(R.string.connection_state_connecting_generic), false, true);
    }

    public void connectBondedDevicesInPriorityOrder(List<BluetoothDevice> devices) {
        if (devices == null || devices.isEmpty()) {
            updateConnectionState(ConnectionState.DISCONNECTED, string(R.string.no_paired_devices));
            return;
        }
        List<BluetoothDevice> orderedDevices = new ArrayList<BluetoothDevice>(devices);
        Collections.sort(orderedDevices, new Comparator<BluetoothDevice>() {
            @Override
            public int compare(BluetoothDevice first, BluetoothDevice second) {
                return scoreDevice(second) - scoreDevice(first);
            }
        });
        ArrayList<String> addresses = new ArrayList<String>();
        for (BluetoothDevice device : orderedDevices) {
            try {
                String address = device.getAddress();
                if (!TextUtils.isEmpty(address)) {
                    addresses.add(address);
                }
            } catch (SecurityException ignored) {
            }
        }
        connectCandidates(addresses, string(R.string.connection_state_trying_paired), false, true);
    }

    private void connectCandidates(
            final List<String> deviceAddresses,
            String startingLabel,
            final boolean autoReconnect,
            final boolean allowTrustedIdentityReplacement
    ) {
        if (deviceAddresses == null || deviceAddresses.isEmpty()) {
            updateConnectionState(ConnectionState.DISCONNECTED, string(R.string.no_paired_devices));
            return;
        }
        Log.d(TAG, "connectCandidates size=" + deviceAddresses.size());
        if (!hasBluetoothAdapter()) {
            updateConnectionState(ConnectionState.DISCONNECTED, string(R.string.bluetooth_not_available));
            return;
        }
        if (!isBluetoothEnabled()) {
            updateConnectionState(ConnectionState.DISCONNECTED, string(R.string.bluetooth_disabled_state));
            return;
        }
        if (!hasRequiredBluetoothPermission()) {
            updateConnectionState(ConnectionState.DISCONNECTED, string(R.string.bluetooth_permission_required));
            return;
        }

        synchronized (sessionLock) {
            manualDisconnectRequested = false;
            mainHandler.removeCallbacks(reconnectRunnable);
            if (!autoReconnect) {
                reconnectAttempt = 0;
            }
            connectionGeneration++;
            closeSocketQuietly(socket);
            clearSessionStateLocked();
            socket = null;
            writer = null;
            readThread = null;
            this.allowTrustedIdentityReplacement = allowTrustedIdentityReplacement;
        }
        updateConnectionState(ConnectionState.CONNECTING, startingLabel);

        final int attemptGeneration = connectionGeneration;
        connectThread = new Thread(new Runnable() {
            @Override
            public void run() {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                for (int index = 0; index < deviceAddresses.size(); index++) {
                    String deviceAddress = deviceAddresses.get(index);
                    BluetoothSocket localSocket = null;
                    try {
                        if (attemptGeneration != connectionGeneration) {
                            closeSocketQuietly(localSocket);
                            return;
                        }
                        BluetoothDevice device = adapter.getRemoteDevice(deviceAddress);
                        String deviceName = safeName(device.getName());
                        Log.d(TAG, "Trying candidate " + (index + 1) + "/" + deviceAddresses.size() + " address=" + deviceAddress + " name=" + deviceName);
                        updateConnectionState(
                                ConnectionState.CONNECTING,
                                string(
                                        R.string.connection_state_trying_device,
                                        deviceName,
                                        index + 1,
                                        deviceAddresses.size()
                                )
                        );
                        localSocket = connectSocketWithFallback(adapter, device);
                        if (attemptGeneration != connectionGeneration) {
                            closeSocketQuietly(localSocket);
                            return;
                        }
                        Log.d(TAG, "Bluetooth socket connected to " + deviceName + " / " + deviceAddress);
                        onSocketConnected(localSocket, deviceAddress, deviceName, attemptGeneration);
                        return;
                    } catch (IOException connectionError) {
                        Log.w(TAG, "Connection failed for candidate address=" + deviceAddress, connectionError);
                        closeSocketQuietly(localSocket);
                    } catch (IllegalArgumentException invalidAddress) {
                        Log.w(TAG, "Invalid Bluetooth address candidate=" + deviceAddress, invalidAddress);
                        closeSocketQuietly(localSocket);
                    } catch (SecurityException permissionError) {
                        Log.e(TAG, "Bluetooth permission missing during connect", permissionError);
                        closeSocketQuietly(localSocket);
                        updateConnectionState(ConnectionState.DISCONNECTED, string(R.string.bluetooth_permission_required));
                        return;
                    }
                }
                Log.d(TAG, "No paired device accepted the RFCOMM connection");
                if (autoReconnect && hasPrimaryTrustedDevice()) {
                    scheduleReconnect();
                } else {
                    updateConnectionState(ConnectionState.DISCONNECTED, string(R.string.connection_state_unreachable));
                }
            }
        }, "headunit-bt-connect");
        connectThread.start();
    }

    public void reconnectLastDevice() {
        ArrayList<String> reconnectCandidates = buildReconnectCandidateAddresses();
        if (!reconnectCandidates.isEmpty()) {
            Log.d(TAG, "Reconnecting phone companion candidates=" + reconnectCandidates.size());
            connectCandidates(reconnectCandidates, string(R.string.connection_state_reconnecting_last), true, false);
        }
    }

    public void disconnect() {
        Log.d(
                TAG,
                "disconnect() state=" + connectionState
                        + " connectedDevice=" + connectedDeviceName
                        + " / " + connectedDeviceAddress
        );
        manualDisconnectRequested = true;
        reconnectAttempt = 0;
        mainHandler.removeCallbacks(reconnectRunnable);
        BluetoothSocket activeSocket = socket;
        connectionGeneration++;
        if (activeSocket != null) {
            closeCurrentConnection(activeSocket, false, string(R.string.connection_state_not_connected), false);
        } else {
            synchronized (sessionLock) {
                clearSessionStateLocked();
            }
            updateConnectionState(ConnectionState.DISCONNECTED, string(R.string.connection_state_not_connected));
        }
    }

    public void sendControl(String action) {
        if (TextUtils.isEmpty(action)) {
            return;
        }
        BluetoothSocket activeSocket = socket;
        if (activeSocket == null || !sessionTracker.isHandshakeComplete()) {
            return;
        }
        writeLine(activeSocket, BridgeCodec.encodeControl(new ControlMessage(action)), true);
    }

    private void onSocketConnected(BluetoothSocket localSocket, String deviceAddress, String deviceName, int attemptGeneration) throws IOException {
        synchronized (sessionLock) {
            if (attemptGeneration != connectionGeneration || manualDisconnectRequested) {
                closeSocketQuietly(localSocket);
                return;
            }
            socket = localSocket;
            connectedDeviceAddress = deviceAddress == null ? "" : deviceAddress;
            connectedDeviceName = deviceName == null ? "" : deviceName;
            sessionTracker.begin(SystemClock.elapsedRealtime());
            pendingPingNonce = 0L;
            lastStateReplayRequestElapsedMs = 0L;
            sessionStatusPayload = null;
            playbackPayload = null;
            lyricsPayload = null;
            artworkBitmap = null;
            playbackReceivedElapsedMs = 0L;
            writer = new BufferedWriter(new OutputStreamWriter(localSocket.getOutputStream(), "UTF-8"));
        }

        updateConnectionState(ConnectionState.CONNECTING, string(R.string.connection_state_connecting_generic));
        if (!sendHello(localSocket)) {
            closeCurrentConnection(localSocket, true, string(R.string.connection_state_unreachable), false);
            return;
        }

        readThread = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop(localSocket, attemptGeneration);
            }
        }, "headunit-bt-read");
        readThread.start();
    }

    private boolean sendHello(BluetoothSocket targetSocket) {
        return writeLine(
                targetSocket,
                BridgeCodec.encodeHello(new HelloMessage(
                        BridgeContract.PROTOCOL_VERSION,
                        identityStore.getOrCreateLocalAppDeviceId(),
                        BridgeContract.ROLE_HEADUNIT,
                        Build.MODEL == null ? string(R.string.head_unit_device_fallback) : Build.MODEL,
                        BuildConfig.VERSION_NAME
                )),
                false
        );
    }

    private void readLoop(BluetoothSocket expectedSocket, int attemptGeneration) {
        if (expectedSocket == null) {
            return;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(expectedSocket.getInputStream(), "UTF-8"));
            LimitedLineReader lineReader =
                    new LimitedLineReader(reader, BridgeContract.MAX_MESSAGE_CHARS);
            String line;
            while (attemptGeneration == connectionGeneration
                    && isCurrentSocket(expectedSocket)
                    && (line = lineReader.readLine()) != null) {
                noteInbound();
                handleIncomingLine(expectedSocket, line);
            }
        } catch (LimitedLineReader.MessageTooLargeException error) {
            Log.w(TAG, "Closing connection after oversized bridge message", error);
        } catch (IOException ignored) {
        } finally {
            Log.d(TAG, "readLoop() finished for " + connectedDeviceName + " / " + connectedDeviceAddress);
            closeReaderQuietly(reader);
            closeCurrentConnection(expectedSocket, true, string(R.string.connection_state_not_connected), false);
        }
    }

    private void handleIncomingLine(BluetoothSocket sourceSocket, String line) {
        if (sourceSocket == null || !isCurrentSocket(sourceSocket)) {
            return;
        }
        try {
            DecodedMessage decodedMessage = BridgeCodec.decode(line);
            if (BridgeContract.TYPE_PING.equals(decodedMessage.type) && decodedMessage.pingMessage != null) {
                sendPong(sourceSocket, decodedMessage.pingMessage);
                return;
            }
            if (BridgeContract.TYPE_PONG.equals(decodedMessage.type)) {
                return;
            }
            if (BridgeContract.TYPE_HELLO.equals(decodedMessage.type) && decodedMessage.helloMessage != null) {
                handleRemoteHello(sourceSocket, decodedMessage.helloMessage);
                return;
            }
            if (!sessionTracker.isHandshakeComplete()) {
                Log.w(TAG, "Rejecting pre-handshake message type=" + decodedMessage.type);
                closeCurrentConnection(sourceSocket, false, string(R.string.connection_state_unreachable), false);
                return;
            }
            if (BridgeContract.TYPE_SESSION_STATUS.equals(decodedMessage.type) && decodedMessage.sessionStatusPayload != null) {
                sessionStatusPayload = decodedMessage.sessionStatusPayload;
                Log.d(
                        TAG,
                        "Received session status notif=" + sessionStatusPayload.notificationAccessGranted
                                + " media=" + sessionStatusPayload.mediaSessionReadable
                                + " playback=" + sessionStatusPayload.playbackAvailable
                                + " lyrics=" + sessionStatusPayload.lyricsAvailable
                );
                dispatchSnapshot();
                return;
            }
            if (BridgeContract.TYPE_PLAYBACK.equals(decodedMessage.type)) {
                String previousTrackKey = playbackPayload == null ? "" : playbackPayload.trackKey;
                playbackPayload = decodedMessage.playbackPayload;
                playbackReceivedElapsedMs = SystemClock.elapsedRealtime();
                boolean trackChanged = !TextUtils.equals(previousTrackKey, decodedMessage.playbackPayload.trackKey);
                if (trackChanged && TextUtils.isEmpty(decodedMessage.playbackPayload.artworkBase64)) {
                    artworkBitmap = null;
                } else {
                    artworkBitmap = decodeArtwork(
                            decodedMessage.playbackPayload.artworkBase64,
                            trackChanged ? null : artworkBitmap
                    );
                }
                Log.d(
                        TAG,
                        "Received playback title=" + decodedMessage.playbackPayload.title
                                + " artist=" + decodedMessage.playbackPayload.artist
                                + " artworkPayload=" + decodedMessage.playbackPayload.artworkBase64.length()
                                + " artworkDecoded=" + (artworkBitmap != null)
                );
                if (lyricsPayload != null
                        && !TextUtils.equals(lyricsPayload.trackKey, decodedMessage.playbackPayload.trackKey)) {
                    lyricsPayload = null;
                }
                dispatchSnapshot();
                return;
            }
            if (BridgeContract.TYPE_LYRICS.equals(decodedMessage.type)) {
                if (playbackPayload == null
                        || TextUtils.equals(decodedMessage.lyricsPayload.trackKey, playbackPayload.trackKey)) {
                    lyricsPayload = decodedMessage.lyricsPayload;
                    Log.d(
                            TAG,
                            "Received lyrics source=" + decodedMessage.lyricsPayload.sourceLabel
                                    + " synced=" + decodedMessage.lyricsPayload.synced
                                    + " lines=" + decodedMessage.lyricsPayload.lines.size()
                    );
                    dispatchSnapshot();
                }
            }
        } catch (JSONException ignored) {
        }
    }

    private void handleRemoteHello(BluetoothSocket sourceSocket, HelloMessage helloMessage) {
        if (helloMessage == null || !isCurrentSocket(sourceSocket)) {
            return;
        }
        if (!BridgeContract.isProtocolSupported(helloMessage.protocolVersion)) {
            Log.w(TAG, "Rejecting remote hello due to protocol mismatch: " + helloMessage.protocolVersion);
            closeCurrentConnection(
                    sourceSocket,
                    false,
                    string(
                            R.string.connection_state_protocol_mismatch,
                            helloMessage.versionName,
                            helloMessage.protocolVersion,
                            BridgeContract.MIN_SUPPORTED_PROTOCOL_VERSION,
                            BridgeContract.MAX_SUPPORTED_PROTOCOL_VERSION
                    ),
                    false
            );
            return;
        }
        if (!TextUtils.equals(helloMessage.role, BridgeContract.ROLE_PHONE)) {
            Log.w(TAG, "Rejecting remote hello due to role mismatch: " + helloMessage.role);
            closeCurrentConnection(sourceSocket, false, string(R.string.connection_state_unreachable), false);
            return;
        }
        if (TextUtils.isEmpty(helloMessage.appDeviceId)) {
            Log.w(TAG, "Rejecting remote hello because appDeviceId is empty");
            closeCurrentConnection(sourceSocket, false, string(R.string.connection_state_unreachable), false);
            return;
        }
        String trustedAppDeviceId = identityStore.getPrimaryTrustedRemoteAppDeviceId();
        if (!TextUtils.isEmpty(trustedAppDeviceId)
                && !TextUtils.equals(trustedAppDeviceId, helloMessage.appDeviceId)) {
            if (allowTrustedIdentityReplacement) {
                Log.w(TAG, "Replacing trusted remote appDeviceId after explicit connect selection");
            } else {
                Log.w(TAG, "Rejecting remote hello due to trusted appDeviceId mismatch");
                closeCurrentConnection(sourceSocket, false, string(R.string.connection_state_unreachable), false);
                return;
            }
        }

        identityStore.rememberTrustedRemote(helloMessage, connectedDeviceAddress, connectedDeviceName);
        rememberLastSuccessfulDevice(connectedDeviceAddress, connectedDeviceName);
        synchronized (sessionLock) {
            sessionTracker.completeHandshake(SystemClock.elapsedRealtime());
            reconnectAttempt = 0;
            lastStateReplayRequestElapsedMs = 0L;
        }
        mainHandler.removeCallbacks(reconnectRunnable);
        updateConnectionState(ConnectionState.CONNECTED, string(R.string.connection_state_connected_to, connectedDeviceName));
        Log.d(
                TAG,
                "Received hello role=" + helloMessage.role
                        + " device=" + helloMessage.deviceName
                        + " protocol=" + helloMessage.protocolVersion
                        + " remoteAppDeviceId=" + helloMessage.appDeviceId
        );
    }

    private void sendPong(BluetoothSocket targetSocket, PingMessage pingMessage) {
        writeLine(targetSocket, BridgeCodec.encodePong(pingMessage), false);
    }

    private Bitmap decodeArtwork(String artworkBase64, Bitmap fallback) {
        if (TextUtils.isEmpty(artworkBase64)) {
            return fallback;
        }
        if (artworkBase64.length() > BridgeContract.MAX_ARTWORK_BASE64_CHARS) {
            Log.w(TAG, "Ignoring oversized artwork payload: " + artworkBase64.length());
            return fallback;
        }
        try {
            byte[] bytes = Base64.decode(artworkBase64, Base64.DEFAULT);
            if (bytes.length > BridgeContract.MAX_ARTWORK_BYTES) {
                Log.w(TAG, "Ignoring oversized decoded artwork: " + bytes.length);
                return fallback;
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (IllegalArgumentException error) {
            Log.w(TAG, "Ignoring invalid artwork payload", error);
            return fallback;
        }
    }

    private void updateConnectionState(int newState, String label) {
        connectionState = newState;
        connectionLabel = label == null ? "" : label;
        dispatchSnapshot();
    }

    private void dispatchSnapshot() {
        for (Listener listener : listeners) {
            dispatchSnapshot(listener);
        }
    }

    private void dispatchSnapshot(final Listener listener) {
        final HeadUnitSessionSnapshot snapshot = getSnapshot();
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onSessionUpdated(snapshot);
            }
        });
    }

    private String safeName(String name) {
        return TextUtils.isEmpty(name) ? string(R.string.generic_phone_device) : name;
    }

    private String string(int resId, Object... args) {
        return appContext.getString(resId, args);
    }

    private void clearSessionStateLocked() {
        sessionStatusPayload = null;
        playbackPayload = null;
        lyricsPayload = null;
        artworkBitmap = null;
        playbackReceivedElapsedMs = 0L;
        connectedDeviceAddress = "";
        connectedDeviceName = "";
        sessionTracker.reset();
        pendingPingNonce = 0L;
        lastStateReplayRequestElapsedMs = 0L;
        allowTrustedIdentityReplacement = false;
    }

    private void closeCurrentConnection(BluetoothSocket expectedSocket, boolean allowReconnect, String disconnectedLabel, boolean fromMaintenance) {
        boolean shouldReconnect;
        synchronized (sessionLock) {
            if (!isCurrentSocket(expectedSocket)) {
                return;
            }
            shouldReconnect = allowReconnect && !manualDisconnectRequested && hasPrimaryTrustedDevice();
            closeSocketQuietly(socket);
            socket = null;
            writer = null;
            readThread = null;
            clearSessionStateLocked();
            if (!shouldReconnect) {
                reconnectAttempt = 0;
                connectionGeneration++;
            }
        }
        if (shouldReconnect) {
            scheduleReconnect();
            return;
        }
        if (fromMaintenance) {
            Log.w(TAG, "Connection closed by maintenance: " + disconnectedLabel);
        }
        updateConnectionState(ConnectionState.DISCONNECTED, disconnectedLabel);
    }

    private void scheduleReconnect() {
        if (buildReconnectCandidateAddresses().isEmpty() || manualDisconnectRequested) {
            updateConnectionState(ConnectionState.DISCONNECTED, string(R.string.connection_state_not_connected));
            return;
        }
        final long delayMs = computeBackoffDelay(reconnectAttempt);
        reconnectAttempt = Math.min(reconnectAttempt + 1, 8);
        final String reconnectLabel = string(R.string.connection_state_reconnecting_last);
        updateConnectionState(ConnectionState.CONNECTING, reconnectLabel);
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.postDelayed(reconnectRunnable, delayMs);
    }

    private long computeBackoffDelay(int attempt) {
        return ConnectionMaintenancePolicy.computeBackoffDelay(
                RECONNECT_INITIAL_DELAY_MS,
                RECONNECT_MAX_DELAY_MS,
                attempt
        );
    }

    private ArrayList<String> buildReconnectCandidateAddresses() {
        LinkedHashSet<String> orderedAddresses = new LinkedHashSet<String>();

        String primaryTrustedAddress = getPrimaryTrustedDeviceAddress();
        if (!TextUtils.isEmpty(primaryTrustedAddress)) {
            orderedAddresses.add(primaryTrustedAddress);
        }

        List<BluetoothDevice> bondedDevices = getBondedDevices();
        if (bondedDevices != null && !bondedDevices.isEmpty()) {
            List<BluetoothDevice> orderedDevices = new ArrayList<BluetoothDevice>(bondedDevices);
            Collections.sort(orderedDevices, new Comparator<BluetoothDevice>() {
                @Override
                public int compare(BluetoothDevice first, BluetoothDevice second) {
                    return scoreDevice(second) - scoreDevice(first);
                }
            });
            for (BluetoothDevice device : orderedDevices) {
                if (device == null) {
                    continue;
                }
                try {
                    String address = device.getAddress();
                    if (!TextUtils.isEmpty(address)) {
                        orderedAddresses.add(address);
                    }
                } catch (SecurityException ignored) {
                }
            }
        }

        return new ArrayList<String>(orderedAddresses);
    }

    private void maintainConnection() {
        BluetoothSocket activeSocket = socket;
        if (activeSocket == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        ConnectionMaintenancePolicy.Action action = sessionTracker.evaluate(now);
        if (action == ConnectionMaintenancePolicy.Action.HANDSHAKE_TIMEOUT) {
            Log.w(TAG, "Handshake timed out");
            closeCurrentConnection(activeSocket, true, string(R.string.connection_state_unreachable), true);
            return;
        }
        if (action == ConnectionMaintenancePolicy.Action.IDLE_TIMEOUT) {
            Log.w(TAG, "Connection idle timeout reached");
            closeCurrentConnection(activeSocket, true, string(R.string.connection_state_unreachable), true);
            return;
        }
        if (!sessionTracker.isHandshakeComplete()) {
            return;
        }
        maybeRequestStateReplay(activeSocket, now);
        if (action == ConnectionMaintenancePolicy.Action.SEND_KEEPALIVE) {
            pendingPingNonce = now;
            writeLine(activeSocket, BridgeCodec.encodePing(new PingMessage(pendingPingNonce)), false);
        }
    }

    private void maybeRequestStateReplay(BluetoothSocket activeSocket, long now) {
        if (activeSocket == null || !sessionTracker.isHandshakeComplete()) {
            return;
        }
        boolean awaitingInitialSnapshot = playbackPayload == null
                && sessionStatusPayload == null
                && sessionTracker.getHandshakeCompletedElapsedMs() > 0L
                && now - sessionTracker.getHandshakeCompletedElapsedMs()
                >= INITIAL_STATE_REPLAY_GRACE_MS;
        boolean statusClaimsPlaybackButSnapshotMissing = playbackPayload == null
                && sessionStatusPayload != null
                && sessionStatusPayload.playbackAvailable;
        boolean playbackMissing = awaitingInitialSnapshot || statusClaimsPlaybackButSnapshotMissing;
        boolean lyricsMissing = playbackPayload != null
                && lyricsPayload == null
                && sessionStatusPayload != null
                && sessionStatusPayload.lyricsAvailable
                && playbackReceivedElapsedMs > 0L
                && now - playbackReceivedElapsedMs >= LYRICS_STATE_REPLAY_GRACE_MS;
        if (!playbackMissing && !lyricsMissing) {
            return;
        }
        if (lastStateReplayRequestElapsedMs > 0L
                && now - lastStateReplayRequestElapsedMs < STATE_REPLAY_REQUEST_INTERVAL_MS) {
            return;
        }
        lastStateReplayRequestElapsedMs = now;
        Log.d(
                TAG,
                "Requesting full state replay playbackMissing=" + playbackMissing
                        + " lyricsMissing=" + lyricsMissing
        );
        sendControl(BridgeContract.ACTION_RESEND_STATE);
    }

    private void noteInbound() {
        sessionTracker.noteInbound(SystemClock.elapsedRealtime());
    }

    private void noteOutbound() {
        sessionTracker.noteOutbound(SystemClock.elapsedRealtime());
    }

    private boolean writeLine(BluetoothSocket targetSocket, String line, boolean requireHandshake) {
        BufferedWriter activeWriter = writer;
        if (targetSocket == null || activeWriter == null || TextUtils.isEmpty(line) || !isCurrentSocket(targetSocket)) {
            return false;
        }
        if (line.length() > BridgeContract.MAX_MESSAGE_CHARS) {
            Log.w(TAG, "Rejecting oversized outbound bridge message: " + line.length());
            return false;
        }
        if (requireHandshake && !sessionTracker.isHandshakeComplete()) {
            return false;
        }
        synchronized (writeLock) {
            if (targetSocket != socket || writer == null) {
                return false;
            }
            try {
                activeWriter.write(line);
                activeWriter.write('\n');
                activeWriter.flush();
                noteOutbound();
                return true;
            } catch (IOException ignored) {
                closeCurrentConnection(targetSocket, true, string(R.string.connection_state_unreachable), true);
                return false;
            }
        }
    }

    private void rememberLastSuccessfulDevice(String deviceAddress, String deviceName) {
        if (TextUtils.isEmpty(deviceAddress)) {
            return;
        }
        sharedPreferences.edit()
                .putString(KEY_LAST_DEVICE_ADDRESS, deviceAddress)
                .putString(KEY_LAST_DEVICE_NAME, safeName(deviceName))
                .apply();
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

    private void closeReaderQuietly(BufferedReader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (IOException ignored) {
        }
    }

    private BluetoothSocket connectSocketWithFallback(BluetoothAdapter adapter, BluetoothDevice device) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Bluetooth connect permission is required");
        }
        IOException lastError = null;
        try {
            BluetoothSocket secureSocket = device.createRfcommSocketToServiceRecord(
                    UUID.fromString(BridgeContract.APP_UUID)
            );
            try {
                adapter.cancelDiscovery();
            } catch (SecurityException ignored) {
            }
            secureSocket.connect();
            Log.d(TAG, "Connected via secure RFCOMM");
            return secureSocket;
        } catch (IOException secureError) {
            lastError = secureError;
            Log.w(TAG, "Secure RFCOMM failed, trying insecure fallback", secureError);
        }

        BluetoothSocket insecureSocket = null;
        try {
            insecureSocket = device.createInsecureRfcommSocketToServiceRecord(
                    UUID.fromString(BridgeContract.APP_UUID)
            );
            try {
                adapter.cancelDiscovery();
            } catch (SecurityException ignored) {
            }
            insecureSocket.connect();
            Log.d(TAG, "Connected via insecure RFCOMM fallback");
            return insecureSocket;
        } catch (IOException insecureError) {
            closeSocketQuietly(insecureSocket);
            Log.w(TAG, "Insecure RFCOMM fallback also failed", insecureError);
            if (lastError != null) {
                throw lastError;
            }
            throw insecureError;
        }
    }

    private boolean isCurrentSocket(BluetoothSocket candidate) {
        return candidate != null && candidate == socket;
    }

    private int scoreDevice(BluetoothDevice device) {
        if (device == null) {
            return 0;
        }
        int score = 0;
        try {
            String deviceAddress = device.getAddress();
            if (TextUtils.equals(deviceAddress, getPrimaryTrustedDeviceAddress())) {
                score += 1500;
            } else if (TextUtils.equals(deviceAddress, getLastDeviceAddress())) {
                score += 1000;
            }
        } catch (SecurityException ignored) {
        }
        try {
            if (device.getBluetoothClass() != null
                    && device.getBluetoothClass().getMajorDeviceClass()
                    == android.bluetooth.BluetoothClass.Device.Major.PHONE) {
                score += 100;
            }
        } catch (SecurityException ignored) {
        }
        try {
            String name = device.getName();
            if (!TextUtils.isEmpty(name)) {
                String lowered = name.toLowerCase();
                if (lowered.contains("phone")) {
                    score += 20;
                }
                if (lowered.contains("huawei") || lowered.contains("android")) {
                    score += 10;
                }
            }
        } catch (SecurityException ignored) {
        }
        return score;
    }
}
