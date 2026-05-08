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
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.bsxu.carlyrics.R;
import com.bsxu.carlyrics.bridge.BridgeCodec;
import com.bsxu.carlyrics.bridge.BridgeContract;
import com.bsxu.carlyrics.bridge.ControlMessage;
import com.bsxu.carlyrics.bridge.DecodedMessage;
import com.bsxu.carlyrics.bridge.HelloMessage;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

public final class HeadUnitCompanionManager {

    private static final String TAG = "HeadUnitBt";

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
    private final SharedPreferences sharedPreferences;
    private final HeadUnitIdentityStore identityStore;

    private volatile BluetoothSocket socket;
    private volatile BufferedWriter writer;
    private volatile Thread readThread;
    private volatile Thread connectThread;

    private volatile int connectionState;
    private volatile String connectionLabel;
    private volatile RemoteSessionStatusPayload sessionStatusPayload;
    private volatile RemotePlaybackPayload playbackPayload;
    private volatile RemoteLyricsPayload lyricsPayload;
    private volatile Bitmap artworkBitmap;
    private volatile long playbackReceivedElapsedMs;
    private volatile String connectedDeviceAddress = "";
    private volatile String connectedDeviceName = "";

    private HeadUnitCompanionManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.listeners = new CopyOnWriteArraySet<Listener>();
        this.writeLock = new Object();
        this.sharedPreferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.identityStore = new HeadUnitIdentityStore(appContext);
        this.connectionState = ConnectionState.DISCONNECTED;
        this.connectionLabel = string(R.string.connection_state_not_connected);
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
        connectCandidates(addresses, string(R.string.connection_state_connecting_generic), false);
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
        connectCandidates(addresses, string(R.string.connection_state_trying_paired), false);
    }

    private void connectCandidates(final List<String> deviceAddresses, String startingLabel, final boolean forgetCandidatesOnFailure) {
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

        disconnect();
        updateConnectionState(ConnectionState.CONNECTING, startingLabel);

        connectThread = new Thread(new Runnable() {
            @Override
            public void run() {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                for (int index = 0; index < deviceAddresses.size(); index++) {
                    String deviceAddress = deviceAddresses.get(index);
                    BluetoothSocket localSocket = null;
                    try {
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
                        Log.d(TAG, "Bluetooth socket connected to " + deviceName + " / " + deviceAddress);
                        onSocketConnected(localSocket, deviceAddress, deviceName);
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
                if (forgetCandidatesOnFailure) {
                    clearRememberedDevice(deviceAddresses);
                }
                updateConnectionState(ConnectionState.DISCONNECTED, string(R.string.connection_state_unreachable));
            }
        }, "headunit-bt-connect");
        connectThread.start();
    }

    public void reconnectLastDevice() {
        String trustedAddress = getPrimaryTrustedDeviceAddress();
        if (!TextUtils.isEmpty(trustedAddress)) {
            Log.d(TAG, "Reconnecting primary trusted device " + trustedAddress);
            ArrayList<String> addresses = new ArrayList<String>();
            addresses.add(trustedAddress);
            connectCandidates(addresses, string(R.string.connection_state_reconnecting_last), true);
        }
    }

    public void disconnect() {
        Log.d(
                TAG,
                "disconnect() state=" + connectionState
                        + " connectedDevice=" + connectedDeviceName
                        + " / " + connectedDeviceAddress
        );
        closeSocketQuietly(socket);
        socket = null;
        writer = null;
        readThread = null;
        playbackPayload = null;
        lyricsPayload = null;
        sessionStatusPayload = null;
        artworkBitmap = null;
        playbackReceivedElapsedMs = 0L;
        connectedDeviceAddress = "";
        connectedDeviceName = "";
        String deviceName = sharedPreferences.getString(KEY_LAST_DEVICE_NAME, "");
        if (TextUtils.isEmpty(deviceName)) {
            updateConnectionState(ConnectionState.DISCONNECTED, string(R.string.connection_state_not_connected));
        } else {
            updateConnectionState(ConnectionState.DISCONNECTED, string(R.string.connection_state_disconnected_from, deviceName));
        }
    }

    public void sendControl(String action) {
        if (TextUtils.isEmpty(action)) {
            return;
        }
        BufferedWriter activeWriter = writer;
        if (activeWriter == null) {
            return;
        }
        synchronized (writeLock) {
            try {
                activeWriter.write(BridgeCodec.encodeControl(new ControlMessage(action)));
                activeWriter.write('\n');
                activeWriter.flush();
            } catch (IOException ignored) {
                disconnect();
            }
        }
    }

    private void onSocketConnected(BluetoothSocket localSocket, String deviceAddress, String deviceName) throws IOException {
        socket = localSocket;
        connectedDeviceAddress = deviceAddress == null ? "" : deviceAddress;
        connectedDeviceName = deviceName == null ? "" : deviceName;
        writer = new BufferedWriter(new OutputStreamWriter(localSocket.getOutputStream(), "UTF-8"));
        updateConnectionState(ConnectionState.CONNECTED, string(R.string.connection_state_connected_to, deviceName));

        synchronized (writeLock) {
            writer.write(BridgeCodec.encodeHello(new HelloMessage(
                    BridgeContract.PROTOCOL_VERSION,
                    identityStore.getOrCreateLocalAppDeviceId(),
                    BridgeContract.ROLE_HEADUNIT,
                    Build.MODEL == null ? string(R.string.head_unit_device_fallback) : Build.MODEL,
                    "0.2.0"
            )));
            writer.write('\n');
            writer.flush();
        }

        readThread = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "headunit-bt-read");
        readThread.start();
    }

    private void readLoop() {
        BluetoothSocket localSocket = socket;
        if (localSocket == null) {
            return;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(localSocket.getInputStream(), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                handleIncomingLine(line);
            }
        } catch (IOException ignored) {
        } finally {
            Log.d(TAG, "readLoop() finished for " + connectedDeviceName + " / " + connectedDeviceAddress);
            closeReaderQuietly(reader);
            disconnect();
        }
    }

    private void handleIncomingLine(String line) {
        try {
            DecodedMessage decodedMessage = BridgeCodec.decode(line);
            if (BridgeContract.TYPE_HELLO.equals(decodedMessage.type) && decodedMessage.helloMessage != null) {
                handleRemoteHello(decodedMessage.helloMessage);
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
                playbackPayload = decodedMessage.playbackPayload;
                playbackReceivedElapsedMs = android.os.SystemClock.elapsedRealtime();
                artworkBitmap = decodeArtwork(decodedMessage.playbackPayload.artworkBase64, artworkBitmap);
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

    private void handleRemoteHello(HelloMessage helloMessage) {
        if (helloMessage == null) {
            return;
        }
        identityStore.rememberTrustedRemote(helloMessage, connectedDeviceAddress, connectedDeviceName);
        rememberLastSuccessfulDevice(connectedDeviceAddress, connectedDeviceName);
        Log.d(
                TAG,
                "Received hello role=" + helloMessage.role
                        + " device=" + helloMessage.deviceName
                        + " protocol=" + helloMessage.protocolVersion
                        + " remoteAppDeviceId=" + helloMessage.appDeviceId
        );
    }

    private Bitmap decodeArtwork(String artworkBase64, Bitmap fallback) {
        if (TextUtils.isEmpty(artworkBase64)) {
            return fallback;
        }
        try {
            byte[] bytes = Base64.decode(artworkBase64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (IllegalArgumentException ignored) {
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

    private void clearRememberedDevice(List<String> attemptedAddresses) {
        String rememberedAddress = getLastDeviceAddress();
        if (TextUtils.isEmpty(rememberedAddress) || attemptedAddresses == null || attemptedAddresses.isEmpty()) {
            return;
        }
        for (String attemptedAddress : attemptedAddresses) {
            if (TextUtils.equals(rememberedAddress, attemptedAddress)) {
                Log.d(TAG, "Forgetting unreachable remembered device " + rememberedAddress);
                sharedPreferences.edit()
                        .remove(KEY_LAST_DEVICE_ADDRESS)
                        .remove(KEY_LAST_DEVICE_NAME)
                        .apply();
                if (identityStore.isPrimaryTrustedAddress(rememberedAddress)) {
                    identityStore.clearPrimaryTrustedRemote();
                }
                return;
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
