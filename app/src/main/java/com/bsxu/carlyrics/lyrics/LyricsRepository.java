package com.bsxu.carlyrics.lyrics;

import android.content.Context;
import android.text.TextUtils;

import com.bsxu.carlyrics.model.LyricLine;
import com.bsxu.carlyrics.model.LyricsResult;
import com.bsxu.carlyrics.model.PlaybackSnapshot;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LyricsRepository {

    public interface Callback {
        void onLyricsLoaded(String trackKey, LyricsResult result);
    }

    private static volatile LyricsRepository instance;
    private static final String CACHE_VERSION_PREFIX = "v2_";

    private final Context appContext;
    private final File cacheDir;
    private final ExecutorService executor;
    private final Map<String, LyricsResult> memoryCache;
    private final LrcLibLyricsClient lyricsClient;

    private LyricsRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.cacheDir = new File(appContext.getCacheDir(), "lyrics_cache");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        this.executor = Executors.newSingleThreadExecutor();
        this.memoryCache = new ConcurrentHashMap<String, LyricsResult>();
        this.lyricsClient = new LrcLibLyricsClient();
    }

    public static LyricsRepository getInstance(Context context) {
        if (instance == null) {
            synchronized (LyricsRepository.class) {
                if (instance == null) {
                    instance = new LyricsRepository(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    public void requestLyrics(final PlaybackSnapshot snapshot, final boolean forceRefresh, final Callback callback) {
        if (snapshot == null || !snapshot.hasTrackData()) {
            if (callback != null) {
                callback.onLyricsLoaded("", null);
            }
            return;
        }

        final String trackKey = snapshot.getTrackKey();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                LyricsResult result = null;
                if (!forceRefresh) {
                    result = memoryCache.get(trackKey);
                    if (result == null) {
                        result = readRemoteCache(trackKey);
                    }
                }

                if (result == null) {
                    result = lyricsClient.fetch(
                            snapshot.getTitle(),
                            snapshot.getArtist(),
                            snapshot.getAlbum(),
                            snapshot.getDurationMs()
                    );
                    if (result != null) {
                        writeStoredLyrics(getRemoteCacheFile(trackKey), result);
                    }
                }

                if (result != null) {
                    memoryCache.put(trackKey, result);
                }

                if (callback != null) {
                    callback.onLyricsLoaded(trackKey, result);
                }
            }
        });
    }

    private LyricsResult readRemoteCache(String trackKey) {
        return readStoredLyrics(getRemoteCacheFile(trackKey));
    }

    private LyricsResult readStoredLyrics(File sourceFile) {
        if (!sourceFile.exists()) {
            return null;
        }

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(sourceFile), "UTF-8"));
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }

            JSONObject jsonObject = new JSONObject(builder.toString());
            boolean synced = jsonObject.optBoolean("synced", false);
            String rawText = jsonObject.optString("rawText", "");
            String source = jsonObject.optString("source", "unknown");
            List<LyricLine> lines;
            if (jsonObject.has("lines")) {
                lines = readLines(jsonObject.getJSONArray("lines"));
            } else {
                lines = synced ? LrcParser.parseSyncedLyrics(rawText) : LrcParser.parsePlainLyrics(rawText);
            }
            if (lines.isEmpty()) {
                return null;
            }
            return new LyricsResult(lines, source, synced, rawText);
        } catch (IOException ignored) {
            return null;
        } catch (JSONException ignored) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void writeStoredLyrics(File targetFile, LyricsResult result) {
        BufferedWriter writer = null;
        try {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("synced", result.isSynced());
            jsonObject.put("rawText", result.getRawText());
            jsonObject.put("source", result.getSourceLabel());

            JSONArray lineArray = new JSONArray();
            for (LyricLine line : result.getLines()) {
                JSONObject lineObject = new JSONObject();
                lineObject.put("timeMs", line.getTimeMs());
                lineObject.put("text", line.getText());
                lineArray.put(lineObject);
            }
            jsonObject.put("lines", lineArray);

            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(targetFile), "UTF-8"));
            writer.write(jsonObject.toString());
            writer.flush();
        } catch (IOException ignored) {
        } catch (JSONException ignored) {
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private List<LyricLine> readLines(JSONArray array) throws JSONException {
        List<LyricLine> lines = new ArrayList<LyricLine>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.getJSONObject(i);
            lines.add(new LyricLine(
                    object.optLong("timeMs", -1L),
                    object.optString("text", "")
            ));
        }
        return lines;
    }

    private File getRemoteCacheFile(String trackKey) {
        return new File(cacheDir, CACHE_VERSION_PREFIX + makeSafeName(trackKey) + ".json");
    }

    private String makeSafeName(String trackKey) {
        String safeName = trackKey;
        if (TextUtils.isEmpty(safeName)) {
            safeName = "unknown_track";
        }
        return safeName.replaceAll("[^a-z0-9_\\-\\.]", "_");
    }
}
