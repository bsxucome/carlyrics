package com.bsxu.carlyrics.lyrics;

import android.content.Context;
import android.net.Uri;
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

    public interface ImportCallback {
        void onLyricsImported(String trackKey, LyricsResult result, String errorMessage);
    }

    private static volatile LyricsRepository instance;

    private final Context appContext;
    private final File cacheDir;
    private final File importedDir;
    private final ExecutorService executor;
    private final Map<String, LyricsResult> memoryCache;
    private final LrcLibLyricsClient lyricsClient;

    private LyricsRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.cacheDir = new File(appContext.getCacheDir(), "lyrics_cache");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        this.importedDir = new File(appContext.getFilesDir(), "imported_lyrics");
        if (!importedDir.exists()) {
            importedDir.mkdirs();
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
                LyricsResult importedLyrics = readImportedLyrics(trackKey);
                if (importedLyrics != null) {
                    memoryCache.put(trackKey, importedLyrics);
                    if (callback != null) {
                        callback.onLyricsLoaded(trackKey, importedLyrics);
                    }
                    return;
                }

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

    public boolean hasImportedLyrics(PlaybackSnapshot snapshot) {
        if (snapshot == null || !snapshot.hasTrackData()) {
            return false;
        }
        return getImportedFile(snapshot.getTrackKey()).exists();
    }

    public void clearImportedLyrics(PlaybackSnapshot snapshot) {
        if (snapshot == null || !snapshot.hasTrackData()) {
            return;
        }

        String trackKey = snapshot.getTrackKey();
        memoryCache.remove(trackKey);
        File importedFile = getImportedFile(trackKey);
        if (importedFile.exists()) {
            importedFile.delete();
        }
    }

    public void importLyrics(final PlaybackSnapshot snapshot, final Uri uri, final ImportCallback callback) {
        if (snapshot == null || !snapshot.hasTrackData() || uri == null) {
            if (callback != null) {
                callback.onLyricsImported("", null, "Unable to import lyrics for this track.");
            }
            return;
        }

        final String trackKey = snapshot.getTrackKey();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    LyricsResult result = buildImportedLyrics(readTextFromUri(uri));
                    if (result == null) {
                        if (callback != null) {
                            callback.onLyricsImported(trackKey, null, "Selected file does not contain readable lyrics.");
                        }
                        return;
                    }

                    writeStoredLyrics(getImportedFile(trackKey), result);
                    memoryCache.put(trackKey, result);
                    if (callback != null) {
                        callback.onLyricsImported(trackKey, result, null);
                    }
                } catch (IOException exception) {
                    if (callback != null) {
                        callback.onLyricsImported(trackKey, null, exception.getMessage());
                    }
                }
            }
        });
    }

    private LyricsResult readImportedLyrics(String trackKey) {
        return readStoredLyrics(getImportedFile(trackKey));
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

    private LyricsResult buildImportedLyrics(String rawText) {
        String normalizedText = normalizeText(rawText);
        if (TextUtils.isEmpty(normalizedText)) {
            return null;
        }

        List<LyricLine> syncedLines = LrcParser.parseSyncedLyrics(normalizedText);
        if (!syncedLines.isEmpty()) {
            return new LyricsResult(syncedLines, "Imported LRC", true, normalizedText);
        }

        List<LyricLine> plainLines = LrcParser.parsePlainLyrics(normalizedText);
        if (!plainLines.isEmpty()) {
            return new LyricsResult(plainLines, "Imported text", false, normalizedText);
        }

        return null;
    }

    private String readTextFromUri(Uri uri) throws IOException {
        InputStream inputStream = appContext.getContentResolver().openInputStream(uri);
        if (inputStream == null) {
            throw new IOException("Unable to open selected file.");
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
        try {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return builder.toString();
        } finally {
            reader.close();
        }
    }

    private String normalizeText(String rawText) {
        if (rawText == null) {
            return "";
        }
        return rawText.replace("\r\n", "\n").replace('\r', '\n').trim();
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
        return new File(cacheDir, makeSafeName(trackKey) + ".json");
    }

    private File getImportedFile(String trackKey) {
        return new File(importedDir, makeSafeName(trackKey) + ".json");
    }

    private String makeSafeName(String trackKey) {
        String safeName = trackKey;
        if (TextUtils.isEmpty(safeName)) {
            safeName = "unknown_track";
        }
        return safeName.replaceAll("[^a-z0-9_\\-\\.]", "_");
    }
}
