package com.bsxu.carlyrics.lyrics;

import android.os.Handler;
import android.os.Looper;

import com.bsxu.carlyrics.bridge.RemoteLyricsPayload;
import com.bsxu.carlyrics.bridge.RemotePlaybackPayload;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class HeadUnitLyricsRepository {

    private static final int MAX_CACHE_ENTRIES = 24;
    private static final long LOOKUP_TIMEOUT_MS = 5500L;

    public interface Callback {
        void onLoaded(RemoteLyricsPayload payload);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final HeadUnitLyricsClient client = new HeadUnitLyricsClient();
    private final AtomicInteger generation = new AtomicInteger();
    private final LinkedHashMap<String, RemoteLyricsPayload> cache =
            new LinkedHashMap<String, RemoteLyricsPayload>(
                    MAX_CACHE_ENTRIES,
                    0.75f,
                    true
            ) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, RemoteLyricsPayload> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            };

    public void request(
            final RemotePlaybackPayload playback,
            final boolean forceRefresh,
            final Callback callback
    ) {
        if (playback == null || playback.trackKey.isEmpty()) {
            if (callback != null) {
                callback.onLoaded(null);
            }
            return;
        }
        final int requestGeneration = generation.incrementAndGet();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                RemoteLyricsPayload result;
                synchronized (cache) {
                    result = forceRefresh ? null : cache.get(playback.trackKey);
                }
                if (result == null) {
                    result = client.fetch(playback, LOOKUP_TIMEOUT_MS);
                    if (result != null) {
                        synchronized (cache) {
                            cache.put(playback.trackKey, result);
                        }
                    }
                }
                final RemoteLyricsPayload delivered = result;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (callback != null && generation.get() == requestGeneration) {
                            callback.onLoaded(delivered);
                        }
                    }
                });
            }
        });
    }

    public void cancel() {
        generation.incrementAndGet();
    }

    public void close() {
        cancel();
        executor.shutdownNow();
    }
}
