package com.bsxu.carlyrics;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
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

import com.bsxu.carlyrics.bridge.BridgeContract;
import com.bsxu.carlyrics.bridge.RemoteLyricLine;
import com.bsxu.carlyrics.bridge.RemoteLyricsPayload;
import com.bsxu.carlyrics.bridge.RemotePlaybackPayload;
import com.bsxu.carlyrics.companion.ConnectionState;
import com.bsxu.carlyrics.companion.HeadUnitCompanionManager;
import com.bsxu.carlyrics.companion.HeadUnitSessionSnapshot;
import com.bsxu.carlyrics.model.LyricLine;
import com.bsxu.carlyrics.ui.ArtworkBackdropFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements HeadUnitCompanionManager.Listener {

    private static final String TAG = "HeadUnitMain";
    private static final int REQUEST_ENABLE_BLUETOOTH = 201;
    private static final int REQUEST_BLUETOOTH_CONNECT_PERMISSION = 202;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
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
    private TextView lyricsStatusView;
    private TextView diagnosticsView;
    private TextView lyricLineTopView;
    private TextView lyricLineUpperView;
    private TextView lyricLineCurrentView;
    private TextView lyricLineLowerView;
    private TextView lyricLineBottomView;

    private HeadUnitCompanionManager companionManager;

    private HeadUnitSessionSnapshot currentSession;
    private String currentTrackKey = "";
    private List<LyricLine> currentLyricsLines = new ArrayList<LyricLine>();
    private boolean currentLyricsSynced;
    private int currentLyricIndex = -1;
    private boolean diagnosticsVisible;
    private int backdropRequestVersion;
    private String backdropTrackKey = "";
    private boolean awaitingReconnectAfterPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();

        companionManager = HeadUnitCompanionManager.getInstance(this);

        openPermissionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
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
                if (currentSession != null && currentSession.isConnected()) {
                    companionManager.sendControl(BridgeContract.ACTION_RESEND_LYRICS);
                } else {
                    beginConnectionFlow();
                }
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
        renderSession(HeadUnitCompanionManager.getInstance(this).getSnapshot());
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
    public void onSessionUpdated(HeadUnitSessionSnapshot snapshot) {
        renderSession(snapshot);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            if (resultCode == RESULT_OK) {
                openBondedDevicePicker();
            } else {
                Toast.makeText(this, R.string.bluetooth_enable_required, Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH_CONNECT_PERMISSION) {
            boolean granted = grantResults.length > 0;
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
            if (granted) {
                if (awaitingReconnectAfterPermission) {
                    awaitingReconnectAfterPermission = false;
                    openBondedDevicePicker();
                }
            } else {
                awaitingReconnectAfterPermission = false;
                Toast.makeText(this, R.string.bluetooth_permission_required, Toast.LENGTH_SHORT).show();
            }
        }
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
                requestPermissions(
                        new String[]{
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_SCAN
                        },
                        REQUEST_BLUETOOTH_CONNECT_PERMISSION
                );
                return;
            }
            if (!companionManager.isBluetoothEnabled()) {
                showConnectionMessage(getString(R.string.bluetooth_enable_required));
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH);
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
        if (!TextUtils.isEmpty(companionManager.getLastDeviceAddress())) {
            Log.i(TAG, "Reconnecting last known device=" + companionManager.getLastDeviceAddress());
            companionManager.reconnectLastDevice();
            return;
        }
        List<BluetoothDevice> devices = companionManager.getBondedDevices();
        Log.i(TAG, "Bonded device count for auto-connect=" + devices.size());
        if (!devices.isEmpty()) {
            showConnectionMessage(getString(R.string.connecting_to_phone_candidates, devices.size()));
            companionManager.connectBondedDevicesInPriorityOrder(devices);
        }
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

            if (devices.size() == 1) {
                String address = safeDeviceAddress(devices.get(0));
                String name = safeDeviceName(devices.get(0));
                showConnectionMessage(getString(R.string.connecting_to_phone, emptyFallback(name, address)));
                Log.d(TAG, "Auto connecting to single paired device: " + address + " / " + name);
            } else {
                showConnectionMessage(getString(R.string.connecting_to_phone_candidates, devices.size()));
                Log.d(TAG, "Trying paired phone candidates count=" + devices.size());
            }
            companionManager.connectBondedDevicesInPriorityOrder(devices);
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

    private void renderSession(HeadUnitSessionSnapshot snapshot) {
        currentSession = snapshot;
        renderConnectionBanner(snapshot);
        updateActionButtons(snapshot);

        String newTrackKey = getTrackKey(snapshot == null ? null : snapshot.playbackPayload);
        boolean trackChanged = !TextUtils.equals(currentTrackKey, newTrackKey);
        if (trackChanged) {
            currentTrackKey = newTrackKey;
            currentLyricIndex = -1;
            currentLyricsLines = new ArrayList<LyricLine>();
            currentLyricsSynced = false;
        }

        renderPlayback(snapshot);
        renderLyrics(snapshot);
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
            openPermissionButton.setText(R.string.connecting_phone_companion);
            openPermissionButton.setEnabled(false);
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
            titleView.setText(R.string.default_title_companion);
            artistView.setText(R.string.default_artist_companion);
            sourceView.setText(R.string.default_source_companion);
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
            lyricsStatusView.setText(R.string.waiting_for_phone_companion);
            renderLyricStage();
            return;
        }

        RemoteLyricsPayload payload = snapshot.lyricsPayload;
        if (payload == null || !TextUtils.equals(payload.trackKey, currentTrackKey)) {
            lyricsStatusView.setText(R.string.lyrics_sync_waiting);
            renderLyricStage();
            return;
        }

        if (currentLyricsLines.isEmpty() || currentLyricsLines.size() != payload.lines.size()) {
            currentLyricsLines = toLyricLines(payload.lines);
            currentLyricsSynced = payload.synced;
            currentLyricIndex = -1;
        }
        lyricsStatusView.setText(getString(R.string.lyrics_status_prefix) + payload.sourceLabel);
        renderLyricStage();
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
            setLyricStageTexts("", "", getString(R.string.lyrics_waiting_remote), "", "");
            return;
        }

        if (!currentLyricsSynced) {
            setLyricStageTexts(
                    "",
                    "",
                    getLineText(0),
                    getLineText(1),
                    getLineText(2)
            );
            return;
        }

        int anchorIndex = currentLyricIndex >= 0 ? currentLyricIndex : 0;
        setLyricStageTexts(
                "",
                "",
                getLineText(anchorIndex),
                getLineText(anchorIndex + 1),
                getLineText(anchorIndex + 2)
        );
    }

    private void setLyricStageTexts(String top, String upper, String current, String lower, String bottom) {
        lyricLineTopView.setText(emptyFallback(top, ""));
        lyricLineUpperView.setText(emptyFallback(upper, ""));
        lyricLineCurrentView.setText(emptyFallback(current, getString(R.string.lyrics_waiting_remote)));
        lyricLineLowerView.setText(emptyFallback(lower, ""));
        lyricLineBottomView.setText(emptyFallback(bottom, ""));
    }

    private String getLineText(int index) {
        if (index < 0 || index >= currentLyricsLines.size()) {
            return "";
        }
        return currentLyricsLines.get(index).getText();
    }

    private void updateActionButtons(HeadUnitSessionSnapshot snapshot) {
        boolean connected = snapshot != null && snapshot.isConnected();
        boolean hasTrack = snapshot != null && snapshot.hasTrackData();

        previousButton.setEnabled(connected);
        playPauseButton.setEnabled(connected);
        nextButton.setEnabled(connected);
        retryLyricsButton.setEnabled(connected && hasTrack);

        previousButton.setAlpha(connected ? 1f : 0.45f);
        playPauseButton.setAlpha(connected ? 1f : 0.45f);
        nextButton.setAlpha(connected ? 1f : 0.45f);
        retryLyricsButton.setAlpha((connected && hasTrack) ? 1f : 0.55f);
    }

    private void renderDiagnostics() {
        diagnosticsView.setVisibility(diagnosticsVisible ? View.VISIBLE : View.GONE);
        if (currentSession == null) {
            diagnosticsView.setText(R.string.default_diagnostics);
            return;
        }

        StringBuilder builder = new StringBuilder();
        builder.append("Connection: ")
                .append(currentSession.connectionLabel)
                .append('\n');
        if (currentSession.playbackPayload != null) {
            builder.append("Package: ")
                    .append(emptyFallback(currentSession.playbackPayload.packageName, "n/a"))
                    .append('\n');
            builder.append("Track: ")
                    .append(emptyFallback(currentSession.playbackPayload.trackKey, "n/a"))
                    .append('\n');
        }
        builder.append("Lyrics: ");
        if (currentSession.lyricsPayload == null) {
            builder.append("waiting");
        } else {
            builder.append(currentSession.lyricsPayload.sourceLabel)
                    .append(currentSession.lyricsPayload.synced ? " | synced" : " | plain")
                    .append(" | ")
                    .append(currentSession.lyricsPayload.lines.size())
                    .append(" lines");
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
                activeIndex = i;
            } else {
                break;
            }
        }
        return activeIndex;
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
