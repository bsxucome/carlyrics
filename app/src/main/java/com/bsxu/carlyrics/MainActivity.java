package com.bsxu.carlyrics;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.bsxu.carlyrics.bridge.BridgeContract;
import com.bsxu.carlyrics.bridge.RemoteLyricLine;
import com.bsxu.carlyrics.bridge.RemoteLyricsPayload;
import com.bsxu.carlyrics.bridge.RemotePlaybackPayload;
import com.bsxu.carlyrics.bridge.RemoteSessionStatusPayload;
import com.bsxu.carlyrics.companion.ConnectionState;
import com.bsxu.carlyrics.companion.HeadUnitCompanionManager;
import com.bsxu.carlyrics.companion.HeadUnitSessionSnapshot;
import com.bsxu.carlyrics.lyrics.HeadUnitLyricsRepository;
import com.bsxu.carlyrics.lyrics.HeadUnitLyricsResult;
import com.bsxu.carlyrics.model.LyricLine;
import com.bsxu.carlyrics.ui.ArtworkBackdropFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends ComponentActivity implements HeadUnitCompanionManager.Listener {

    private static final String TAG = "HeadUnitMain";
    private static final String EXTRA_DEBUG_CONNECT_ADDRESS = "connect_address";
    private static final String GITHUB_URL = "https://github.com/bsxucome/carlyrics";
    private static final String HOME_SHORTCUT_ID = "carlyrics-head-unit-home";
    private static final long AUTO_HEAD_UNIT_LYRICS_DELAY_MS = 2500L;
    private boolean awaitingReconnectAfterPermission;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<Intent> enableBluetoothLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK) {
                            openBondedDevicePicker();
                        } else {
                            Toast.makeText(
                                    this,
                                    R.string.bluetooth_enable_required,
                                    Toast.LENGTH_SHORT
                            ).show();
                        }
                    }
            );
    private final ActivityResultLauncher<String[]> bluetoothPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        boolean granted = !result.isEmpty();
                        for (Boolean permissionGranted : result.values()) {
                            if (!Boolean.TRUE.equals(permissionGranted)) {
                                granted = false;
                                break;
                            }
                        }
                        if (granted && awaitingReconnectAfterPermission) {
                            awaitingReconnectAfterPermission = false;
                            openBondedDevicePicker();
                        } else if (!granted) {
                            awaitingReconnectAfterPermission = false;
                            Toast.makeText(
                                    this,
                                    R.string.bluetooth_permission_required,
                                    Toast.LENGTH_SHORT
                            ).show();
                        }
                    }
            );
    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            HeadUnitSessionSnapshot latestSnapshot = companionManager == null ? null : companionManager.getSnapshot();
            if (latestSnapshot != null) {
                renderSession(latestSnapshot);
            } else {
                updateProgressAndLyrics();
            }
            uiHandler.postDelayed(this, 500L);
        }
    };
    private final Runnable headUnitLyricsLookupRunnable = new Runnable() {
        @Override
        public void run() {
            scheduledLyricsTrackKey = "";
            if (currentSession != null) {
                requestHeadUnitLyrics(currentSession.playbackPayload, false);
            }
        }
    };

    private View permissionPanel;
    private TextView permissionDescriptionView;
    private Button openPermissionButton;
    private ImageView backgroundArtworkView;
    private ImageView artworkView;
    private TextView titleView;
    private TextView artistView;
    private TextView sourceView;
    private TextView currentTimeView;
    private TextView totalTimeView;
    private ProgressBar progressBar;
    private ImageButton previousButton;
    private ImageButton playPauseButton;
    private ImageButton nextButton;
    private Button retryLyricsButton;
    private Button disconnectButton;
    private TextView createShortcutView;
    private TextView aboutView;
    private TextView lyricsStatusView;
    private TextView diagnosticsView;
    private TextView lyricLineTopView;
    private TextView lyricLineUpperView;
    private TextView lyricLineCurrentView;
    private TextView lyricLineLowerView;
    private TextView lyricLineBottomView;

    private HeadUnitCompanionManager companionManager;
    private HeadUnitLyricsRepository headUnitLyricsRepository;

    private HeadUnitSessionSnapshot currentSession;
    private RemoteLyricsPayload localLyricsPayload;
    private boolean headUnitLyricsSearching;
    private HeadUnitLyricsResult.Status headUnitLyricsStatus;
    private String scheduledLyricsTrackKey = "";
    private String attemptedLyricsTrackKey = "";
    private String currentTrackKey = "";
    private List<LyricLine> currentLyricsLines = new ArrayList<LyricLine>();
    private boolean currentLyricsSynced;
    private int currentLyricIndex = -1;
    private boolean diagnosticsVisible;
    private int backdropRequestVersion;
    private String backdropTrackKey = "";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();

        companionManager = HeadUnitCompanionManager.getInstance(this);
        headUnitLyricsRepository = new HeadUnitLyricsRepository();

        openPermissionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentSession != null && currentSession.connectionState == ConnectionState.CONNECTING) {
                    companionManager.disconnect();
                    Toast.makeText(
                            MainActivity.this,
                            R.string.phone_companion_connecting_cancelled,
                            Toast.LENGTH_SHORT
                    ).show();
                    return;
                }
                beginConnectionFlow();
            }
        });
        previousButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                companionManager.sendControl(BridgeContract.ACTION_PREVIOUS);
            }
        });
        playPauseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                companionManager.sendControl(BridgeContract.ACTION_PLAY_PAUSE);
            }
        });
        nextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                companionManager.sendControl(BridgeContract.ACTION_NEXT);
            }
        });
        retryLyricsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentSession != null
                        && currentSession.isConnected()
                        && currentSession.hasTrackData()
                        && !hasCurrentLyricsPayload()) {
                    companionManager.sendControl(BridgeContract.ACTION_REFRESH_LYRICS);
                    requestHeadUnitLyrics(currentSession.playbackPayload, true);
                }
            }
        });
        createShortcutView.setOnClickListener(v -> requestHomeShortcut());
        aboutView.setOnClickListener(v -> showAboutDialog());
        disconnectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                companionManager.disconnect();
                Toast.makeText(
                        MainActivity.this,
                        R.string.phone_companion_disconnected,
                        Toast.LENGTH_SHORT
                ).show();
            }
        });
        sourceView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                diagnosticsVisible = !diagnosticsVisible;
                diagnosticsView.setVisibility(diagnosticsVisible ? View.VISIBLE : View.GONE);
                renderDiagnostics();
                Toast.makeText(
                        MainActivity.this,
                        diagnosticsVisible ? getString(R.string.debug_shown) : getString(R.string.debug_hidden),
                        Toast.LENGTH_SHORT
                ).show();
                return true;
            }
        });

        installPressFeedback(previousButton, 0.94f);
        installPressFeedback(playPauseButton, 0.96f);
        installPressFeedback(nextButton, 0.94f);
        installPressFeedback(retryLyricsButton, 0.98f);
        installPressFeedback(disconnectButton, 0.98f);
        renderSession(HeadUnitCompanionManager.getInstance(this).getSnapshot());
        handleDebugConnectIntent(getIntent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.i(TAG, "onStart()");
        companionManager.registerListener(this);
        uiHandler.post(progressTicker);
        maybeReconnectLastDevice();
    }

    @Override
    protected void onStop() {
        uiHandler.removeCallbacks(progressTicker);
        companionManager.unregisterListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacks(headUnitLyricsLookupRunnable);
        if (headUnitLyricsRepository != null) {
            headUnitLyricsRepository.close();
        }
        super.onDestroy();
    }

    @Override
    public void onSessionUpdated(HeadUnitSessionSnapshot snapshot) {
        renderSession(snapshot);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDebugConnectIntent(intent);
    }

    private void bindViews() {
        permissionPanel = findViewById(R.id.permissionPanel);
        permissionDescriptionView = (TextView) findViewById(R.id.permissionDescription);
        openPermissionButton = (Button) findViewById(R.id.openPermissionButton);
        backgroundArtworkView = (ImageView) findViewById(R.id.backgroundArtworkView);
        artworkView = (ImageView) findViewById(R.id.artworkView);
        titleView = (TextView) findViewById(R.id.titleView);
        artistView = (TextView) findViewById(R.id.artistView);
        sourceView = (TextView) findViewById(R.id.sourceView);
        currentTimeView = (TextView) findViewById(R.id.currentTimeView);
        totalTimeView = (TextView) findViewById(R.id.totalTimeView);
        progressBar = (ProgressBar) findViewById(R.id.progressBar);
        previousButton = (ImageButton) findViewById(R.id.previousButton);
        playPauseButton = (ImageButton) findViewById(R.id.playPauseButton);
        nextButton = (ImageButton) findViewById(R.id.nextButton);
        retryLyricsButton = (Button) findViewById(R.id.retryLyricsButton);
        disconnectButton = (Button) findViewById(R.id.disconnectButton);
        createShortcutView = (TextView) findViewById(R.id.createShortcutView);
        aboutView = (TextView) findViewById(R.id.aboutView);
        lyricsStatusView = (TextView) findViewById(R.id.lyricsStatusView);
        diagnosticsView = (TextView) findViewById(R.id.diagnosticsView);
        lyricLineTopView = (TextView) findViewById(R.id.lyricLineTop);
        lyricLineUpperView = (TextView) findViewById(R.id.lyricLineUpper);
        lyricLineCurrentView = (TextView) findViewById(R.id.lyricLineCurrent);
        lyricLineLowerView = (TextView) findViewById(R.id.lyricLineLower);
        lyricLineBottomView = (TextView) findViewById(R.id.lyricLineBottom);
    }

    private void beginConnectionFlow() {
        try {
            Log.d(TAG, "beginConnectionFlow()");
            if (!companionManager.hasBluetoothAdapter()) {
                showConnectionMessage(getString(R.string.bluetooth_not_available));
                Toast.makeText(this, R.string.bluetooth_not_available, Toast.LENGTH_SHORT).show();
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && !hasAllBluetoothRuntimePermissions()) {
                showConnectionMessage(getString(R.string.bluetooth_permission_required));
                awaitingReconnectAfterPermission = true;
                bluetoothPermissionLauncher.launch(new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                });
                return;
            }
            if (!companionManager.isBluetoothEnabled()) {
                showConnectionMessage(getString(R.string.bluetooth_enable_required));
                enableBluetoothLauncher.launch(
                        new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                );
                return;
            }
            openBondedDevicePicker();
        } catch (SecurityException permissionError) {
            Log.e(TAG, "Bluetooth permission error while starting connection", permissionError);
            showConnectionMessage(getString(R.string.bluetooth_permission_required));
            Toast.makeText(this, R.string.bluetooth_permission_required, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException unexpectedError) {
            Log.e(TAG, "Unexpected error while starting connection", unexpectedError);
            showConnectionMessage(getString(R.string.bluetooth_connect_failed));
            Toast.makeText(this, R.string.bluetooth_connect_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void handleDebugConnectIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String connectAddress = intent.getStringExtra(EXTRA_DEBUG_CONNECT_ADDRESS);
        if (TextUtils.isEmpty(connectAddress)) {
            return;
        }
        Log.d(TAG, "Debug connect request for address=" + connectAddress);
        companionManager.connect(connectAddress);
        intent.removeExtra(EXTRA_DEBUG_CONNECT_ADDRESS);
    }

    private void maybeReconnectLastDevice() {
        Log.i(TAG, "maybeReconnectLastDevice()");
        if (currentSession != null && currentSession.connectionState != ConnectionState.DISCONNECTED) {
            Log.i(TAG, "Skip reconnect because currentSession state=" + currentSession.connectionState);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && !hasAllBluetoothRuntimePermissions()) {
            Log.i(TAG, "Skip reconnect because Bluetooth runtime permissions are missing");
            return;
        }
        if (!companionManager.isBluetoothEnabled()) {
            Log.i(TAG, "Skip reconnect because Bluetooth is disabled");
            return;
        }
        if (companionManager.hasPrimaryTrustedDevice()) {
            Log.i(TAG, "Reconnecting primary trusted device=" + companionManager.getPrimaryTrustedDeviceAddress());
            companionManager.reconnectLastDevice();
            return;
        }
        Log.i(TAG, "Skip auto-connect because no trusted phone companion is available");
    }

    private void openBondedDevicePicker() {
        try {
            List<BluetoothDevice> devices = companionManager.getBondedDevices();
            Log.d(TAG, "Paired device count=" + devices.size());
            if (devices.isEmpty()) {
                showConnectionMessage(getString(R.string.no_paired_devices));
                Toast.makeText(this, R.string.no_paired_devices, Toast.LENGTH_SHORT).show();
                return;
            }
            showBondedDevicePickerDialog(devices);
        } catch (SecurityException permissionError) {
            Log.e(TAG, "Bluetooth permission error while opening paired-device picker", permissionError);
            showConnectionMessage(getString(R.string.bluetooth_permission_required));
            Toast.makeText(this, R.string.bluetooth_permission_required, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException unexpectedError) {
            Log.e(TAG, "Unexpected error while opening paired-device picker", unexpectedError);
            showConnectionMessage(getString(R.string.bluetooth_connect_failed));
            Toast.makeText(this, R.string.bluetooth_connect_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void showBondedDevicePickerDialog(List<BluetoothDevice> devices) {
        final List<BluetoothDevice> orderedDevices = new ArrayList<BluetoothDevice>(devices);
        final String lastDeviceAddress = companionManager.getLastDeviceAddress();
        final String primaryTrustedAddress = companionManager.getPrimaryTrustedDeviceAddress();
        Collections.sort(orderedDevices, new Comparator<BluetoothDevice>() {
            @Override
            public int compare(BluetoothDevice first, BluetoothDevice second) {
                String firstAddress = safeDeviceAddress(first);
                String secondAddress = safeDeviceAddress(second);
                boolean firstIsPrimary = TextUtils.equals(firstAddress, primaryTrustedAddress);
                boolean secondIsPrimary = TextUtils.equals(secondAddress, primaryTrustedAddress);
                if (firstIsPrimary != secondIsPrimary) {
                    return firstIsPrimary ? -1 : 1;
                }
                boolean firstIsLast = TextUtils.equals(firstAddress, lastDeviceAddress);
                boolean secondIsLast = TextUtils.equals(secondAddress, lastDeviceAddress);
                if (firstIsLast != secondIsLast) {
                    return firstIsLast ? -1 : 1;
                }
                String firstName = emptyFallback(safeDeviceName(first), firstAddress);
                String secondName = emptyFallback(safeDeviceName(second), secondAddress);
                return firstName.compareToIgnoreCase(secondName);
            }
        });

        final String[] labels = new String[orderedDevices.size()];
        for (int i = 0; i < orderedDevices.size(); i++) {
            BluetoothDevice device = orderedDevices.get(i);
            String address = safeDeviceAddress(device);
            String name = emptyFallback(safeDeviceName(device), getString(R.string.unnamed_bluetooth_device));
            if (TextUtils.equals(address, primaryTrustedAddress)) {
                labels[i] = getString(R.string.paired_device_item_primary, name, address);
            } else if (TextUtils.equals(address, lastDeviceAddress)) {
                labels[i] = getString(R.string.paired_device_item_last_used, name, address);
            } else {
                labels[i] = getString(R.string.paired_device_item, name, address);
            }
        }
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(this)
                .setTitle(R.string.select_phone_companion)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which < 0 || which >= orderedDevices.size()) {
                            return;
                        }
                        BluetoothDevice selectedDevice = orderedDevices.get(which);
                        String address = safeDeviceAddress(selectedDevice);
                        String name = emptyFallback(safeDeviceName(selectedDevice), address);
                        if (TextUtils.isEmpty(address)) {
                            Toast.makeText(MainActivity.this, R.string.bluetooth_connect_failed, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        showConnectionMessage(getString(R.string.connecting_to_phone, name));
                        Log.d(TAG, "User selected paired device: " + address + " / " + name);
                        companionManager.connect(address);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (companionManager.hasPrimaryTrustedDevice()) {
            dialogBuilder.setNeutralButton(R.string.forget_trusted_phone, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    companionManager.forgetPrimaryTrustedDevice();
                    Toast.makeText(MainActivity.this, R.string.trusted_phone_forgotten, Toast.LENGTH_SHORT).show();
                }
            });
        }
        dialogBuilder.show();
    }

    private void renderSession(HeadUnitSessionSnapshot snapshot) {
        currentSession = snapshot;
        renderConnectionBanner(snapshot);

        String newTrackKey = getTrackKey(snapshot == null ? null : snapshot.playbackPayload);
        boolean trackChanged = !TextUtils.equals(currentTrackKey, newTrackKey);
        if (trackChanged) {
            cancelHeadUnitLyricsLookup();
            currentTrackKey = newTrackKey;
            currentLyricIndex = -1;
            currentLyricsLines = new ArrayList<LyricLine>();
            currentLyricsSynced = false;
            localLyricsPayload = null;
            headUnitLyricsStatus = null;
            attemptedLyricsTrackKey = "";
        }
        if (hasRemoteLyricsPayload(snapshot)) {
            cancelHeadUnitLyricsLookup();
            localLyricsPayload = null;
            headUnitLyricsStatus = null;
        }

        renderPlayback(snapshot);
        renderLyrics(snapshot);
        maybeScheduleHeadUnitLyricsLookup(snapshot);
        updateActionButtons(snapshot);
        renderDiagnostics();
    }

    private void renderConnectionBanner(HeadUnitSessionSnapshot snapshot) {
        boolean connected = snapshot != null && (snapshot.isConnected() || snapshot.hasTrackData());
        permissionPanel.setVisibility(connected ? View.GONE : View.VISIBLE);
        if (snapshot == null) {
            permissionDescriptionView.setText(R.string.connection_desc_idle);
            openPermissionButton.setText(R.string.connect_phone_companion);
            openPermissionButton.setEnabled(true);
            return;
        }

        if (snapshot.connectionState == ConnectionState.CONNECTING) {
            permissionDescriptionView.setText(snapshot.connectionLabel);
            openPermissionButton.setText(R.string.cancel_connecting_phone_companion);
            openPermissionButton.setEnabled(true);
        } else {
            permissionDescriptionView.setText(
                    TextUtils.isEmpty(snapshot.connectionLabel)
                            ? getString(R.string.connection_desc_idle)
                            : snapshot.connectionLabel
            );
            openPermissionButton.setText(R.string.connect_phone_companion);
            openPermissionButton.setEnabled(true);
        }
    }

    private void renderPlayback(HeadUnitSessionSnapshot snapshot) {
        if (snapshot == null || !snapshot.hasTrackData()) {
            titleView.setText(snapshot != null && snapshot.isConnected()
                    ? R.string.phone_status_connected_title
                    : R.string.default_title_companion);
            artistView.setText(snapshot != null && snapshot.isConnected()
                    ? buildRemoteStateHint(snapshot)
                    : getString(R.string.default_artist_companion));
            sourceView.setText(snapshot != null && snapshot.isConnected()
                    ? emptyFallback(snapshot.connectionLabel, getString(R.string.default_source_companion))
                    : getString(R.string.default_source_companion));
            currentTimeView.setText("00:00");
            totalTimeView.setText("00:00");
            progressBar.setMax(1000);
            progressBar.setProgress(0);
            playPauseButton.setImageResource(R.drawable.ic_play);
            playPauseButton.setContentDescription(getString(R.string.play));
            artworkView.setImageResource(android.R.drawable.ic_menu_gallery);
            clearBackdropArtwork();
            return;
        }

        RemotePlaybackPayload payload = snapshot.playbackPayload;
        titleView.setText(emptyFallback(payload.title, getString(R.string.default_title_companion)));
        artistView.setText(emptyFallback(payload.artist, getString(R.string.default_artist_companion)));
        sourceView.setText(buildSourceText(snapshot));
        playPauseButton.setImageResource(payload.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        playPauseButton.setContentDescription(getString(payload.playing ? R.string.pause : R.string.play));

        Bitmap artwork = snapshot.artworkBitmap;
        if (artwork != null) {
            artworkView.setImageBitmap(artwork);
        } else {
            artworkView.setImageResource(android.R.drawable.ic_menu_gallery);
        }
        updateBackdropArtwork(currentTrackKey, artwork);
        updateProgressAndLyrics();
    }

    private void renderLyrics(HeadUnitSessionSnapshot snapshot) {
        if (snapshot == null || !snapshot.hasTrackData()) {
            currentLyricsLines = new ArrayList<LyricLine>();
            currentLyricsSynced = false;
            currentLyricIndex = -1;
            lyricsStatusView.setText(snapshot != null && snapshot.isConnected()
                    ? buildRemoteLyricsHint(snapshot)
                    : getString(R.string.waiting_for_phone_companion));
            renderLyricStage();
            return;
        }

        RemoteLyricsPayload payload = getEffectiveLyricsPayload(snapshot);
        if (payload == null || !TextUtils.equals(payload.trackKey, currentTrackKey)) {
            lyricsStatusView.setText(buildLyricsStatusText(snapshot));
            renderLyricStage();
            return;
        }

        if (currentLyricsLines.isEmpty() || currentLyricsLines.size() != payload.lines.size()) {
            currentLyricsLines = toLyricLines(payload.lines);
            currentLyricsSynced = payload.synced;
            currentLyricIndex = -1;
        }
        lyricsStatusView.setText(
                getString(R.string.lyrics_status_prefix) + localizeLyricsSource(payload.sourceLabel)
        );
        renderLyricStage();
    }

    private String localizeLyricsSource(String sourceLabel) {
        String source = emptyFallback(sourceLabel, "");
        String cachePrefix = "Cache · ";
        if (source.startsWith(cachePrefix)) {
            return getString(
                    R.string.lyrics_source_cache,
                    localizeLyricsSource(source.substring(cachePrefix.length()))
            );
        }
        if ("LRCLIB exact".equals(source)) {
            return getString(R.string.lyrics_source_official_exact);
        }
        if ("LRCLIB search".equals(source)) {
            return getString(R.string.lyrics_source_official_search);
        }
        if ("LRCLIB mirror exact".equals(source)) {
            return getString(R.string.lyrics_source_mirror_exact);
        }
        if ("LRCLIB mirror search".equals(source)) {
            return getString(R.string.lyrics_source_mirror_search);
        }
        if ("LRCLIB head unit".equals(source)) {
            return getString(R.string.lyrics_source_head_unit_exact);
        }
        if ("LRCLIB head unit search".equals(source)) {
            return getString(R.string.lyrics_source_head_unit_search);
        }
        return source;
    }

    private void updateProgressAndLyrics() {
        if (currentSession == null || !currentSession.hasTrackData()) {
            currentTimeView.setText("00:00");
            totalTimeView.setText("00:00");
            progressBar.setMax(1000);
            progressBar.setProgress(0);
            renderLyricStage();
            return;
        }

        long positionMs = currentSession.getEstimatedPositionMs();
        long durationMs = currentSession.playbackPayload.durationMs;
        currentTimeView.setText(formatTime(positionMs));
        totalTimeView.setText(formatTime(durationMs));

        if (durationMs > 0L) {
            int progressMax = safeProgressValue(durationMs);
            int progressValue = safeProgressValue(Math.min(positionMs, durationMs));
            progressBar.setMax(Math.max(progressMax, 1));
            progressBar.setProgress(Math.min(progressValue, progressMax));
        } else {
            progressBar.setMax(1000);
            progressBar.setProgress(0);
        }

        if (!currentLyricsSynced || currentLyricsLines.isEmpty()) {
            renderLyricStage();
            return;
        }

        int newIndex = findActiveLyricIndex(positionMs, currentLyricsLines);
        if (newIndex != currentLyricIndex) {
            currentLyricIndex = newIndex;
            renderLyricStage();
        }
    }

    private void renderLyricStage() {
        if (currentLyricsLines.isEmpty()) {
            setLyricStageTexts(
                    "",
                    "",
                    hasCurrentLyricsPayload()
                            ? getString(R.string.lyrics_no_displayable_lines)
                            : getString(R.string.lyrics_waiting_remote),
                    "",
                    ""
            );
            return;
        }

        if (!currentLyricsSynced) {
            int firstIndex = findNextDisplayLyricIndex(0);
            int secondIndex = firstIndex >= 0 ? findNextDisplayLyricIndex(firstIndex + 1) : -1;
            int thirdIndex = secondIndex >= 0 ? findNextDisplayLyricIndex(secondIndex + 1) : -1;
            setLyricStageTexts(
                    "",
                    "",
                    firstIndex >= 0 ? getLineText(firstIndex) : getString(R.string.lyrics_no_displayable_lines),
                    secondIndex >= 0 ? getLineText(secondIndex) : "",
                    thirdIndex >= 0 ? getLineText(thirdIndex) : ""
            );
            return;
        }

        int anchorIndex = currentLyricIndex >= 0 ? currentLyricIndex : findNextDisplayLyricIndex(0);
        if (anchorIndex < 0) {
            setLyricStageTexts("", "", getString(R.string.lyrics_no_displayable_lines), "", "");
            return;
        }
        int nextIndex = findNextDisplayLyricIndex(anchorIndex + 1);
        int followingIndex = nextIndex >= 0 ? findNextDisplayLyricIndex(nextIndex + 1) : -1;
        setLyricStageTexts(
                "",
                "",
                getLineText(anchorIndex),
                nextIndex >= 0 ? getLineText(nextIndex) : "",
                followingIndex >= 0 ? getLineText(followingIndex) : ""
        );
    }

    private void setLyricStageTexts(String top, String upper, String current, String lower, String bottom) {
        lyricLineTopView.setText(emptyFallback(top, ""));
        lyricLineUpperView.setText(emptyFallback(upper, ""));
        lyricLineCurrentView.setText(emptyFallback(current, ""));
        lyricLineLowerView.setText(emptyFallback(lower, ""));
        lyricLineBottomView.setText(emptyFallback(bottom, ""));
    }

    private String getLineText(int index) {
        if (index < 0 || index >= currentLyricsLines.size()) {
            return "";
        }
        return currentLyricsLines.get(index).getText();
    }

    private boolean hasCurrentLyricsPayload() {
        return getEffectiveLyricsPayload(currentSession) != null;
    }

    private void updateActionButtons(HeadUnitSessionSnapshot snapshot) {
        boolean connected = snapshot != null && snapshot.isConnected();
        boolean canRefreshLyrics = connected
                && snapshot.hasTrackData()
                && !hasCurrentLyricsPayload();

        previousButton.setEnabled(connected);
        playPauseButton.setEnabled(connected);
        nextButton.setEnabled(connected);
        retryLyricsButton.setEnabled(canRefreshLyrics);
        retryLyricsButton.setVisibility(canRefreshLyrics ? View.VISIBLE : View.GONE);
        disconnectButton.setEnabled(connected);
        disconnectButton.setVisibility(connected ? View.VISIBLE : View.GONE);

        previousButton.setAlpha(connected ? 1f : 0.45f);
        playPauseButton.setAlpha(connected ? 1f : 0.45f);
        nextButton.setAlpha(connected ? 1f : 0.45f);
        retryLyricsButton.setAlpha(canRefreshLyrics ? 1f : 0.55f);
        disconnectButton.setAlpha(connected ? 1f : 0.55f);
    }

    private RemoteLyricsPayload getEffectiveLyricsPayload(HeadUnitSessionSnapshot snapshot) {
        if (hasRemoteLyricsPayload(snapshot)) {
            return snapshot.lyricsPayload;
        }
        if (localLyricsPayload != null
                && TextUtils.equals(localLyricsPayload.trackKey, currentTrackKey)) {
            return localLyricsPayload;
        }
        return null;
    }

    private boolean hasRemoteLyricsPayload(HeadUnitSessionSnapshot snapshot) {
        return snapshot != null
                && snapshot.lyricsPayload != null
                && TextUtils.equals(snapshot.lyricsPayload.trackKey, currentTrackKey);
    }

    private void maybeScheduleHeadUnitLyricsLookup(HeadUnitSessionSnapshot snapshot) {
        if (snapshot == null
                || !snapshot.isConnected()
                || !snapshot.hasTrackData()
                || hasCurrentLyricsPayload()
                || TextUtils.isEmpty(currentTrackKey)
                || TextUtils.equals(attemptedLyricsTrackKey, currentTrackKey)
                || TextUtils.equals(scheduledLyricsTrackKey, currentTrackKey)
                || headUnitLyricsSearching) {
            return;
        }
        scheduledLyricsTrackKey = currentTrackKey;
        uiHandler.removeCallbacks(headUnitLyricsLookupRunnable);
        uiHandler.postDelayed(headUnitLyricsLookupRunnable, AUTO_HEAD_UNIT_LYRICS_DELAY_MS);
    }

    private void requestHeadUnitLyrics(RemotePlaybackPayload playback, boolean forceRefresh) {
        if (playback == null
                || TextUtils.isEmpty(playback.trackKey)
                || !TextUtils.equals(playback.trackKey, currentTrackKey)) {
            return;
        }
        if (!isNetworkConnected()) {
            attemptedLyricsTrackKey = playback.trackKey;
            headUnitLyricsStatus = HeadUnitLyricsResult.Status.NETWORK_ERROR;
            renderLyrics(currentSession);
            return;
        }
        uiHandler.removeCallbacks(headUnitLyricsLookupRunnable);
        scheduledLyricsTrackKey = "";
        attemptedLyricsTrackKey = playback.trackKey;
        headUnitLyricsSearching = true;
        headUnitLyricsStatus = null;
        renderLyrics(currentSession);
        headUnitLyricsRepository.request(playback, forceRefresh, result -> {
            headUnitLyricsSearching = false;
            if (currentSession == null
                    || !TextUtils.equals(playback.trackKey, currentTrackKey)
                    || hasRemoteLyricsPayload(currentSession)) {
                return;
            }
            localLyricsPayload = result.payload;
            headUnitLyricsStatus = result.status;
            renderLyrics(currentSession);
            updateActionButtons(currentSession);
            renderDiagnostics();
        });
    }

    private void cancelHeadUnitLyricsLookup() {
        uiHandler.removeCallbacks(headUnitLyricsLookupRunnable);
        scheduledLyricsTrackKey = "";
        headUnitLyricsSearching = false;
        if (headUnitLyricsRepository != null) {
            headUnitLyricsRepository.cancel();
        }
    }

    private String buildLyricsStatusText(HeadUnitSessionSnapshot snapshot) {
        if (headUnitLyricsSearching) {
            return getString(R.string.head_unit_lyrics_searching);
        }
        if (headUnitLyricsStatus == HeadUnitLyricsResult.Status.NOT_FOUND) {
            return getString(R.string.head_unit_lyrics_not_found);
        }
        if (headUnitLyricsStatus == HeadUnitLyricsResult.Status.NETWORK_ERROR) {
            return getString(R.string.head_unit_lyrics_network_error);
        }
        if (headUnitLyricsStatus == HeadUnitLyricsResult.Status.TIMEOUT) {
            return getString(R.string.head_unit_lyrics_timeout);
        }
        if (headUnitLyricsStatus == HeadUnitLyricsResult.Status.RESPONSE_ERROR) {
            return getString(R.string.head_unit_lyrics_response_error);
        }
        return buildRemoteLyricsHint(snapshot);
    }

    @SuppressWarnings("deprecation")
    private boolean isNetworkConnected() {
        ConnectivityManager manager =
                (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = manager == null ? null : manager.getActiveNetworkInfo();
        return networkInfo != null && networkInfo.isConnected();
    }

    private void requestHomeShortcut() {
        Intent launchIntent = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ShortcutManager manager = getSystemService(ShortcutManager.class);
            if (manager != null && manager.isRequestPinShortcutSupported()) {
                ShortcutInfo shortcut = new ShortcutInfo.Builder(this, HOME_SHORTCUT_ID)
                        .setShortLabel(getString(R.string.app_name))
                        .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                        .setIntent(launchIntent)
                        .build();
                if (manager.requestPinShortcut(shortcut, null)) {
                    Toast.makeText(this, R.string.shortcut_requested, Toast.LENGTH_SHORT).show();
                    return;
                }
            }
        }
        Intent installIntent = new Intent("com.android.launcher.action.INSTALL_SHORTCUT");
        installIntent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, launchIntent);
        installIntent.putExtra(Intent.EXTRA_SHORTCUT_NAME, getString(R.string.app_name));
        installIntent.putExtra(
                Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                Intent.ShortcutIconResource.fromContext(this, R.mipmap.ic_launcher)
        );
        installIntent.putExtra("duplicate", false);
        try {
            sendBroadcast(installIntent);
            Toast.makeText(this, R.string.shortcut_requested, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException error) {
            Log.w(TAG, "Launcher rejected shortcut request", error);
            Toast.makeText(this, R.string.shortcut_unsupported, Toast.LENGTH_SHORT).show();
        }
    }

    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_software)
                .setMessage(
                        getString(R.string.about_version, BuildConfig.VERSION_NAME)
                                + "\n\n"
                                + getString(R.string.about_project)
                )
                .setNegativeButton(android.R.string.ok, null)
                .setPositiveButton(R.string.open_github, (dialog, which) -> openGitHub())
                .show();
    }

    private void openGitHub() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, GITHUB_URL, Toast.LENGTH_LONG).show();
        }
    }

    private void renderDiagnostics() {
        diagnosticsView.setVisibility(diagnosticsVisible ? View.VISIBLE : View.GONE);
        if (currentSession == null) {
            diagnosticsView.setText(R.string.default_diagnostics);
            return;
        }

        StringBuilder builder = new StringBuilder();
        builder.append(getString(R.string.diagnostics_connection_label))
                .append(currentSession.connectionLabel)
                .append('\n');
        if (currentSession.playbackPayload != null) {
            builder.append(getString(R.string.diagnostics_package_label))
                    .append(emptyFallback(currentSession.playbackPayload.packageName, "n/a"))
                    .append('\n');
            builder.append(getString(R.string.diagnostics_track_label))
                    .append(emptyFallback(currentSession.playbackPayload.trackKey, "n/a"))
                    .append('\n');
        }
        builder.append(getString(R.string.diagnostics_lyrics_label));
        if (currentSession.lyricsPayload == null) {
            builder.append(getString(R.string.diagnostics_waiting));
        } else {
            builder.append(currentSession.lyricsPayload.sourceLabel)
                    .append(currentSession.lyricsPayload.synced
                            ? getString(R.string.diagnostics_synced_suffix)
                            : getString(R.string.diagnostics_plain_suffix))
                    .append(getString(R.string.diagnostics_separator))
                    .append(currentSession.lyricsPayload.lines.size())
                    .append(' ')
                    .append(getString(R.string.diagnostics_lines_suffix));
        }
        if (currentSession.sessionStatusPayload != null) {
            builder.append('\n')
                    .append(getString(R.string.diagnostics_phone_state_label))
                    .append(currentSession.sessionStatusPayload.notificationAccessGranted
                            ? getString(R.string.diagnostics_notif_on)
                            : getString(R.string.diagnostics_notif_off))
                    .append(getString(R.string.diagnostics_separator))
                    .append(currentSession.sessionStatusPayload.notificationListenerActive
                            ? getString(R.string.diagnostics_listener_on)
                            : getString(R.string.diagnostics_listener_off))
                    .append(getString(R.string.diagnostics_separator))
                    .append(currentSession.sessionStatusPayload.mediaSessionReadable
                            ? getString(R.string.diagnostics_media_on)
                            : getString(R.string.diagnostics_media_off))
                    .append(getString(R.string.diagnostics_separator))
                    .append(currentSession.sessionStatusPayload.playbackAvailable
                            ? getString(R.string.diagnostics_playback_on)
                            : getString(R.string.diagnostics_playback_off))
                    .append(getString(R.string.diagnostics_separator))
                    .append(currentSession.sessionStatusPayload.lyricsAvailable
                            ? getString(R.string.diagnostics_remote_lyrics_on)
                            : getString(R.string.diagnostics_remote_lyrics_off));
        }
        diagnosticsView.setText(builder.toString());
    }

    private String buildSourceText(HeadUnitSessionSnapshot snapshot) {
        if (snapshot == null || snapshot.playbackPayload == null) {
            return getString(R.string.default_source_companion);
        }
        if (!TextUtils.isEmpty(snapshot.playbackPayload.album)) {
            return snapshot.playbackPayload.album;
        }
        return emptyFallback(snapshot.connectionLabel, getString(R.string.default_source_companion));
    }

    private String buildRemoteStateHint(HeadUnitSessionSnapshot snapshot) {
        if (snapshot == null) {
            return getString(R.string.default_artist_companion);
        }
        if (!snapshot.isConnected()) {
            return getString(R.string.default_artist_companion);
        }
        if (snapshot.sessionStatusPayload == null) {
            return getString(R.string.phone_status_syncing_state);
        }
        RemoteSessionStatusPayload statusPayload = snapshot.sessionStatusPayload;
        if (!statusPayload.notificationAccessGranted) {
            return getString(R.string.phone_status_notification_access_required);
        }
        if (!statusPayload.notificationListenerActive) {
            return getString(R.string.phone_status_notification_service_starting);
        }
        if (!statusPayload.mediaSessionReadable) {
            return getString(R.string.phone_status_media_session_unavailable);
        }
        if (!statusPayload.playbackAvailable) {
            return getString(R.string.phone_status_waiting_playback);
        }
        if (!statusPayload.lyricsAvailable) {
            return getString(R.string.phone_status_waiting_lyrics);
        }
        return getString(R.string.phone_status_ready);
    }

    private String buildRemoteLyricsHint(HeadUnitSessionSnapshot snapshot) {
        if (snapshot == null || !snapshot.isConnected()) {
            return getString(R.string.waiting_for_phone_companion);
        }
        if (snapshot.sessionStatusPayload == null) {
            return getString(R.string.phone_status_syncing_state);
        }
        RemoteSessionStatusPayload statusPayload = snapshot.sessionStatusPayload;
        if (!statusPayload.notificationAccessGranted) {
            return getString(R.string.phone_status_notification_access_required);
        }
        if (!statusPayload.notificationListenerActive) {
            return getString(R.string.phone_status_notification_service_starting);
        }
        if (!statusPayload.mediaSessionReadable) {
            return getString(R.string.phone_status_media_session_unavailable);
        }
        if (!statusPayload.playbackAvailable) {
            return getString(R.string.phone_status_waiting_playback);
        }
        if (!statusPayload.lyricsAvailable) {
            return getString(R.string.phone_status_waiting_lyrics);
        }
        return getString(R.string.lyrics_sync_waiting);
    }

    private List<LyricLine> toLyricLines(List<RemoteLyricLine> remoteLines) {
        List<LyricLine> localLines = new ArrayList<LyricLine>();
        for (RemoteLyricLine line : remoteLines) {
            localLines.add(new LyricLine(line.timeMs, line.text));
        }
        return localLines;
    }

    private int findActiveLyricIndex(long positionMs, List<LyricLine> lines) {
        int activeIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).getTimeMs() <= positionMs) {
                if (hasDisplayText(lines.get(i))) {
                    activeIndex = i;
                }
            } else {
                break;
            }
        }
        return activeIndex >= 0 ? activeIndex : findNextDisplayLyricIndex(0);
    }

    private int findNextDisplayLyricIndex(int startIndex) {
        int safeStartIndex = Math.max(0, startIndex);
        for (int i = safeStartIndex; i < currentLyricsLines.size(); i++) {
            if (hasDisplayText(currentLyricsLines.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private boolean hasDisplayText(LyricLine line) {
        return line != null && !TextUtils.isEmpty(line.getText().trim());
    }

    private String getTrackKey(RemotePlaybackPayload payload) {
        return payload == null ? "" : payload.trackKey;
    }

    private String emptyFallback(String value, String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value;
    }

    private String formatTime(long timeMs) {
        if (timeMs <= 0L) {
            return "00:00";
        }
        long totalSeconds = timeMs / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private int safeProgressValue(long value) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, value));
    }

    private void clearBackdropArtwork() {
        backdropTrackKey = "";
        backdropRequestVersion++;
        backgroundArtworkView.setImageDrawable(null);
    }

    private void updateBackdropArtwork(String trackKey, Bitmap artwork) {
        if (TextUtils.isEmpty(trackKey) || artwork == null) {
            clearBackdropArtwork();
            return;
        }
        if (TextUtils.equals(backdropTrackKey, trackKey) && backgroundArtworkView.getDrawable() != null) {
            return;
        }
        backdropTrackKey = trackKey;
        final Bitmap sourceArtwork = artwork;
        final int requestVersion = ++backdropRequestVersion;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap blurred = ArtworkBackdropFactory.createBlurredBackdrop(sourceArtwork);
                if (blurred == null) {
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (requestVersion != backdropRequestVersion) {
                            return;
                        }
                        backgroundArtworkView.setImageBitmap(blurred);
                    }
                });
            }
        }, "backdrop-render").start();
    }

    private void installPressFeedback(final View view, final float pressedScale) {
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.animate().scaleX(pressedScale).scaleY(pressedScale).setDuration(90L).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.animate().scaleX(1f).scaleY(1f).setDuration(140L).start();
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    private void showConnectionMessage(String message) {
        if (permissionDescriptionView != null && !TextUtils.isEmpty(message)) {
            permissionDescriptionView.setText(message);
        }
        if (lyricsStatusView != null && !TextUtils.isEmpty(message)) {
            lyricsStatusView.setText(message);
        }
    }

    private String safeDeviceName(BluetoothDevice device) {
        if (device == null) {
            return "";
        }
        try {
            return device.getName();
        } catch (SecurityException ignored) {
            return "";
        }
    }

    private String safeDeviceAddress(BluetoothDevice device) {
        if (device == null) {
            return "";
        }
        try {
            return device.getAddress();
        } catch (SecurityException ignored) {
            return "";
        }
    }

    private boolean hasAllBluetoothRuntimePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
    }
}
