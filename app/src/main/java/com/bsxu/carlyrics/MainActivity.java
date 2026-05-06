package com.bsxu.carlyrics;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.bsxu.carlyrics.lyrics.LyricsRepository;
import com.bsxu.carlyrics.model.LyricLine;
import com.bsxu.carlyrics.model.LyricsResult;
import com.bsxu.carlyrics.model.PlaybackSnapshot;
import com.bsxu.carlyrics.playback.PlaybackRepository;
import com.bsxu.carlyrics.ui.ArtworkBackdropFactory;
import com.bsxu.carlyrics.ui.LyricListAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements PlaybackRepository.PlaybackListener {

    private static final String NOTIFICATION_LISTENER_SETTINGS_ACTION =
            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS";
    private static final int REQUEST_IMPORT_LYRICS = 2001;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            updateProgressAndLyrics();
            uiHandler.postDelayed(this, 500L);
        }
    };

    private View permissionPanel;
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
    private Button importLyricsButton;
    private Button clearImportedLyricsButton;
    private TextView lyricsStatusView;
    private TextView diagnosticsView;
    private ListView lyricsListView;

    private PlaybackRepository playbackRepository;
    private LyricsRepository lyricsRepository;
    private LyricListAdapter lyricListAdapter;

    private PlaybackSnapshot currentSnapshot;
    private LyricsResult currentLyrics;
    private String currentLyricsTrackKey = "";
    private int currentLyricIndex = -1;
    private boolean diagnosticsVisible;
    private int backdropRequestVersion;
    private String backdropTrackKey = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();

        playbackRepository = PlaybackRepository.getInstance(this);
        lyricsRepository = LyricsRepository.getInstance(this);
        lyricListAdapter = new LyricListAdapter(this);
        lyricsListView.setAdapter(lyricListAdapter);
        showPlaceholderLyrics(getString(R.string.lyrics_placeholder));

        openPermissionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(NOTIFICATION_LISTENER_SETTINGS_ACTION));
            }
        });
        previousButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playbackRepository.skipPrevious();
            }
        });
        playPauseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playbackRepository.togglePlayPause();
            }
        });
        nextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playbackRepository.skipNext();
            }
        });
        retryLyricsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestLyrics(true);
            }
        });
        importLyricsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openLyricsPicker();
            }
        });
        clearImportedLyricsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearImportedLyricsForCurrentTrack();
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

        installPressFeedback(previousButton, 0.92f);
        installPressFeedback(playPauseButton, 0.94f);
        installPressFeedback(nextButton, 0.92f);
        installPressFeedback(retryLyricsButton, 0.98f);
        installPressFeedback(importLyricsButton, 0.98f);
        installPressFeedback(clearImportedLyricsButton, 0.98f);
        updateActionButtons();
        renderDiagnostics();
    }

    @Override
    protected void onStart() {
        super.onStart();
        playbackRepository.registerListener(this);
        uiHandler.post(progressTicker);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean permissionEnabled = PlaybackRepository.isNotificationAccessEnabled(this);
        renderPermissionState(permissionEnabled);
    }

    @Override
    protected void onStop() {
        uiHandler.removeCallbacks(progressTicker);
        playbackRepository.unregisterListener(this);
        super.onStop();
    }

    @Override
    public void onPlaybackUpdated(PlaybackSnapshot snapshot, boolean permissionEnabled) {
        renderPermissionState(permissionEnabled);

        boolean trackChanged = !isSameTrack(currentSnapshot, snapshot);
        currentSnapshot = snapshot;
        renderSnapshot(snapshot);
        renderDiagnostics();
        updateActionButtons();

        if (trackChanged) {
            currentLyricIndex = -1;
            requestLyrics(false);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT_LYRICS || resultCode != RESULT_OK || data == null) {
            return;
        }

        Uri uri = data.getData();
        if (uri == null) {
            lyricsStatusView.setText(R.string.status_import_failed);
            return;
        }
        importLyricsForCurrentTrack(uri);
    }

    private void bindViews() {
        permissionPanel = findViewById(R.id.permissionPanel);
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
        importLyricsButton = (Button) findViewById(R.id.importLyricsButton);
        clearImportedLyricsButton = (Button) findViewById(R.id.clearImportedLyricsButton);
        lyricsStatusView = (TextView) findViewById(R.id.lyricsStatusView);
        diagnosticsView = (TextView) findViewById(R.id.diagnosticsView);
        lyricsListView = (ListView) findViewById(R.id.lyricsListView);
    }

    private void renderPermissionState(boolean permissionEnabled) {
        permissionPanel.setVisibility(permissionEnabled ? View.GONE : View.VISIBLE);
        if (!permissionEnabled) {
            lyricsStatusView.setText(R.string.status_permission_missing);
        }
    }

    private void renderSnapshot(PlaybackSnapshot snapshot) {
        if (snapshot == null || !snapshot.hasTrackData()) {
            titleView.setText(R.string.default_title);
            artistView.setText(R.string.default_artist);
            sourceView.setText(R.string.status_waiting_media);
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

        titleView.setText(emptyFallback(snapshot.getTitle(), getString(R.string.default_title)));
        artistView.setText(emptyFallback(snapshot.getArtist(), getString(R.string.default_artist)));
        sourceView.setText(buildSourceText(snapshot));
        playPauseButton.setImageResource(snapshot.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        playPauseButton.setContentDescription(getString(snapshot.isPlaying() ? R.string.pause : R.string.play));

        Bitmap artwork = snapshot.getArtwork();
        if (artwork != null) {
            artworkView.setImageBitmap(artwork);
        } else {
            artworkView.setImageResource(android.R.drawable.ic_menu_gallery);
        }
        updateBackdropArtwork(snapshot);

        updateProgressAndLyrics();
    }

    private void updateProgressAndLyrics() {
        if (currentSnapshot == null || !currentSnapshot.hasTrackData()) {
            currentTimeView.setText("00:00");
            totalTimeView.setText("00:00");
            progressBar.setMax(1000);
            progressBar.setProgress(0);
            lyricListAdapter.setActiveIndex(-1);
            currentLyricIndex = -1;
            return;
        }

        long positionMs = currentSnapshot.getEstimatedPositionMs();
        long durationMs = currentSnapshot.getDurationMs();
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

        if (currentLyrics == null || !currentLyrics.isSynced()) {
            return;
        }

        int newIndex = currentLyrics.findActiveLineIndex(positionMs);
        if (newIndex == currentLyricIndex) {
            return;
        }

        currentLyricIndex = newIndex;
        lyricListAdapter.setActiveIndex(newIndex);
        if (newIndex >= 0) {
            final int targetIndex = newIndex;
            lyricsListView.post(new Runnable() {
                @Override
                public void run() {
                    lyricsListView.smoothScrollToPositionFromTop(
                            targetIndex,
                            Math.max(0, lyricsListView.getHeight() / 3),
                            220
                    );
                }
            });
        }
    }

    private void requestLyrics(boolean forceRefresh) {
        final PlaybackSnapshot snapshot = currentSnapshot;
        if (snapshot == null || !snapshot.hasTrackData()) {
            currentLyrics = null;
            currentLyricsTrackKey = "";
            showPlaceholderLyrics(getString(R.string.lyrics_placeholder));
            lyricsStatusView.setText(R.string.default_lyrics_status);
            renderDiagnostics();
            updateActionButtons();
            return;
        }

        currentLyricsTrackKey = snapshot.getTrackKey();
        lyricsStatusView.setText(R.string.lyrics_loading);
        lyricsRepository.requestLyrics(snapshot, forceRefresh, new LyricsRepository.Callback() {
            @Override
            public void onLyricsLoaded(final String trackKey, final LyricsResult result) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!TextUtils.equals(currentLyricsTrackKey, trackKey)) {
                            return;
                        }

                        currentLyrics = result;
                        currentLyricIndex = -1;
                        if (result == null || result.getLines().isEmpty()) {
                            showPlaceholderLyrics(getString(R.string.lyrics_not_found));
                            lyricsStatusView.setText(R.string.lyrics_not_found);
                            renderDiagnostics();
                            updateActionButtons();
                            return;
                        }

                        lyricListAdapter.setItems(result.getLines());
                        lyricListAdapter.setActiveIndex(-1);
                        lyricsStatusView.setText(getString(R.string.lyrics_status_prefix) + result.getSourceLabel());
                        renderDiagnostics();
                        updateActionButtons();
                        updateProgressAndLyrics();
                    }
                });
            }
        });
    }

    private void openLyricsPicker() {
        if (currentSnapshot == null || !currentSnapshot.hasTrackData()) {
            lyricsStatusView.setText(R.string.status_no_track_for_import);
            return;
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "text/plain",
                "application/octet-stream",
                "application/x-subrip"
        });

        try {
            startActivityForResult(Intent.createChooser(intent, getString(R.string.import_lyrics)), REQUEST_IMPORT_LYRICS);
        } catch (ActivityNotFoundException noDocumentPicker) {
            Intent fallbackIntent = new Intent(Intent.ACTION_GET_CONTENT);
            fallbackIntent.addCategory(Intent.CATEGORY_OPENABLE);
            fallbackIntent.setType("*/*");
            try {
                startActivityForResult(Intent.createChooser(fallbackIntent, getString(R.string.import_lyrics)), REQUEST_IMPORT_LYRICS);
            } catch (ActivityNotFoundException ignored) {
                lyricsStatusView.setText(R.string.status_import_picker_missing);
            }
        }
    }

    private void importLyricsForCurrentTrack(Uri uri) {
        final PlaybackSnapshot snapshot = currentSnapshot;
        if (snapshot == null || !snapshot.hasTrackData()) {
            lyricsStatusView.setText(R.string.status_no_track_for_import);
            return;
        }

        currentLyricsTrackKey = snapshot.getTrackKey();
        lyricsStatusView.setText(R.string.lyrics_importing);
        lyricsRepository.importLyrics(snapshot, uri, new LyricsRepository.ImportCallback() {
            @Override
            public void onLyricsImported(final String trackKey, final LyricsResult result, final String errorMessage) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!TextUtils.equals(currentLyricsTrackKey, trackKey)) {
                            return;
                        }

                        if (result == null) {
                            lyricsStatusView.setText(TextUtils.isEmpty(errorMessage)
                                    ? getString(R.string.status_import_failed)
                                    : errorMessage);
                            renderDiagnostics();
                            updateActionButtons();
                            return;
                        }

                        currentLyrics = result;
                        currentLyricIndex = -1;
                        lyricListAdapter.setItems(result.getLines());
                        lyricListAdapter.setActiveIndex(-1);
                        lyricsStatusView.setText(R.string.status_import_success);
                        renderDiagnostics();
                        updateActionButtons();
                        updateProgressAndLyrics();
                    }
                });
            }
        });
    }

    private void clearImportedLyricsForCurrentTrack() {
        PlaybackSnapshot snapshot = currentSnapshot;
        if (snapshot == null || !snapshot.hasTrackData()) {
            lyricsStatusView.setText(R.string.status_no_track_for_import);
            return;
        }

        lyricsRepository.clearImportedLyrics(snapshot);
        currentLyrics = null;
        currentLyricIndex = -1;
        lyricsStatusView.setText(R.string.status_import_cleared);
        renderDiagnostics();
        updateActionButtons();
        requestLyrics(true);
    }

    private void showPlaceholderLyrics(String message) {
        List<LyricLine> items = new ArrayList<LyricLine>();
        items.add(new LyricLine(-1L, message));
        lyricListAdapter.setItems(items);
        lyricListAdapter.setActiveIndex(-1);
    }

    private void updateActionButtons() {
        boolean hasTrack = currentSnapshot != null && currentSnapshot.hasTrackData();
        retryLyricsButton.setEnabled(hasTrack);
        importLyricsButton.setEnabled(hasTrack);

        boolean hasImportedLyrics = hasTrack && lyricsRepository.hasImportedLyrics(currentSnapshot);
        clearImportedLyricsButton.setEnabled(hasImportedLyrics);

        retryLyricsButton.setAlpha(hasTrack ? 1f : 0.55f);
        importLyricsButton.setAlpha(hasTrack ? 1f : 0.55f);
        clearImportedLyricsButton.setAlpha(hasImportedLyrics ? 1f : 0.55f);
    }

    private void renderDiagnostics() {
        diagnosticsView.setVisibility(diagnosticsVisible ? View.VISIBLE : View.GONE);
        if (currentSnapshot == null || !currentSnapshot.hasTrackData()) {
            diagnosticsView.setText(R.string.default_diagnostics);
            return;
        }

        StringBuilder builder = new StringBuilder();
        builder.append("Package: ")
                .append(emptyFallback(currentSnapshot.getPackageName(), "n/a"))
                .append('\n');
        builder.append("Metadata: ")
                .append(sourceTypeLabel(currentSnapshot.getSourceType()))
                .append(" | ")
                .append(currentSnapshot.isPlaying() ? "playing" : "paused")
                .append('\n');
        builder.append("Timeline: ")
                .append(currentSnapshot.getDurationMs() > 0L ? "session position available" : "position fallback only")
                .append('\n');
        builder.append("Lyrics: ");
        if (currentLyrics == null) {
            builder.append("not loaded");
        } else {
            builder.append(currentLyrics.getSourceLabel())
                    .append(currentLyrics.isSynced() ? " | synced" : " | plain")
                    .append(" | ")
                    .append(currentLyrics.getLines().size())
                    .append(" lines");
        }
        diagnosticsView.setText(builder.toString());
    }

    private String sourceTypeLabel(int sourceType) {
        if (sourceType == PlaybackSnapshot.SOURCE_MEDIA_SESSION) {
            return "media session";
        }
        if (sourceType == PlaybackSnapshot.SOURCE_NOTIFICATION) {
            return "notification";
        }
        return "unknown";
    }

    private String buildSourceText(PlaybackSnapshot snapshot) {
        if (!TextUtils.isEmpty(snapshot.getAlbum())) {
            return snapshot.getAlbum();
        }
        if (snapshot.getSourceType() == PlaybackSnapshot.SOURCE_MEDIA_SESSION) {
            return getString(R.string.status_source_session);
        } else if (snapshot.getSourceType() == PlaybackSnapshot.SOURCE_NOTIFICATION) {
            return getString(R.string.status_source_notification);
        } else {
            return getString(R.string.status_source_unknown);
        }
    }

    private boolean isSameTrack(PlaybackSnapshot first, PlaybackSnapshot second) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        return TextUtils.equals(first.getTrackKey(), second.getTrackKey());
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

    private void updateBackdropArtwork(PlaybackSnapshot snapshot) {
        if (snapshot == null || !snapshot.hasTrackData() || snapshot.getArtwork() == null) {
            clearBackdropArtwork();
            return;
        }

        final String trackKey = snapshot.getTrackKey();
        if (TextUtils.equals(backdropTrackKey, trackKey) && backgroundArtworkView.getDrawable() != null) {
            return;
        }

        backdropTrackKey = trackKey;
        final Bitmap sourceArtwork = snapshot.getArtwork();
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
        }).start();
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
}
