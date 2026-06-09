package com.bsxu.carlyrics.phone.lyrics;

import android.content.Context;

import com.bsxu.carlyrics.phone.companion.ObservedPlaybackSnapshot;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PhoneLyricsRepository {

    private static final long PROVIDER_TIMEOUT_MS = 4500L;

    public interface Callback {
        void onLoaded(PhoneLyricsResult result);
    }

    private final ExecutorService executorService;
    private final Map<String, PhoneLyricsResult> memoryCache;
    private final PhoneLyricsDiskCache diskCache;
    private final PhoneLyricsSettings settings;

    public PhoneLyricsRepository(Context context) {
        this.executorService = Executors.newSingleThreadExecutor();
        this.memoryCache = new ConcurrentHashMap<String, PhoneLyricsResult>();
        this.diskCache = new PhoneLyricsDiskCache(context);
        this.settings = new PhoneLyricsSettings(context);
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
                if (cached == null && !forceRefresh) {
                    cached = diskCache.get(trackKey);
                    if (cached != null) {
                        memoryCache.put(trackKey, cached);
                    }
                }
                if (cached == null) {
                    List<PhoneLyricsSettings.ProviderEndpoint> endpoints =
                            settings.getProviderEndpoints();
                    for (PhoneLyricsSettings.ProviderEndpoint endpoint : endpoints) {
                        PhoneLyricsProvider provider = new PhoneLrcLibClient(
                                endpoint.baseUrl,
                                endpoint.sourceLabel
                        );
                        cached = provider.fetch(
                                trackKey,
                                snapshot.title,
                                snapshot.artist,
                                snapshot.album,
                                snapshot.durationMs,
                                PROVIDER_TIMEOUT_MS
                        );
                        if (cached != null) {
                            break;
                        }
                    }
                    if (cached != null) {
                        memoryCache.put(trackKey, cached);
                        diskCache.put(cached);
                    }
                }
                if (callback != null) {
                    callback.onLoaded(cached);
                }
            }
        });
    }
}

