package com.bsxu.carlyrics.phone.lyrics;

import com.bsxu.carlyrics.phone.companion.ObservedPlaybackSnapshot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PhoneLyricsRepository {

    public interface Callback {
        void onLoaded(PhoneLyricsResult result);
    }

    private final ExecutorService executorService;
    private final Map<String, PhoneLyricsResult> memoryCache;
    private final PhoneLrcLibClient lrcLibClient;

    public PhoneLyricsRepository() {
        this.executorService = Executors.newSingleThreadExecutor();
        this.memoryCache = new ConcurrentHashMap<String, PhoneLyricsResult>();
        this.lrcLibClient = new PhoneLrcLibClient();
    }

    public void requestLyrics(final ObservedPlaybackSnapshot snapshot, final boolean forceRefresh, final Callback callback) {
        if (snapshot == null || !snapshot.hasTrackData()) {
            if (callback != null) {
                callback.onLoaded(null);
            }
            return;
        }

        executorService.execute(new Runnable() {
            @Override
            public void run() {
                String trackKey = snapshot.getTrackKey();
                PhoneLyricsResult cached = forceRefresh ? null : memoryCache.get(trackKey);
                if (cached == null) {
                    cached = lrcLibClient.fetch(
                            trackKey,
                            snapshot.title,
                            snapshot.artist,
                            snapshot.album,
                            snapshot.durationMs
                    );
                    if (cached != null) {
                        memoryCache.put(trackKey, cached);
                    }
                }
                if (callback != null) {
                    callback.onLoaded(cached);
                }
            }
        });
    }
}

