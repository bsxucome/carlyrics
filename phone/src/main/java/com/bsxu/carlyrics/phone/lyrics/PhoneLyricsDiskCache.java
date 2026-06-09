package com.bsxu.carlyrics.phone.lyrics;

import android.content.Context;

import com.bsxu.carlyrics.bridge.RemoteLyricLine;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class PhoneLyricsDiskCache {

    private static final String CACHE_DIRECTORY = "lyrics-cache";
    private static final int MAX_CACHE_ENTRIES = 200;

    private final File cacheDirectory;

    PhoneLyricsDiskCache(Context context) {
        cacheDirectory = new File(context.getApplicationContext().getFilesDir(), CACHE_DIRECTORY);
    }

    PhoneLyricsResult get(String trackKey) {
        File cacheFile = getCacheFile(trackKey);
        if (!cacheFile.isFile()) {
            return null;
        }
        try {
            JSONObject object = new JSONObject(readFile(cacheFile));
            if (!trackKey.equals(object.optString("trackKey", ""))) {
                deleteQuietly(cacheFile);
                return null;
            }
            JSONArray lineArray = object.optJSONArray("lines");
            List<RemoteLyricLine> lines = new ArrayList<RemoteLyricLine>();
            if (lineArray != null) {
                for (int i = 0; i < lineArray.length(); i++) {
                    JSONObject lineObject = lineArray.optJSONObject(i);
                    if (lineObject != null) {
                        lines.add(new RemoteLyricLine(
                                lineObject.optLong("timeMs", -1L),
                                lineObject.optString("text", "")
                        ));
                    }
                }
            }
            if (lines.isEmpty()) {
                deleteQuietly(cacheFile);
                return null;
            }
            cacheFile.setLastModified(System.currentTimeMillis());
            return new PhoneLyricsResult(
                    trackKey,
                    "Cache · " + object.optString("sourceLabel", "lyrics"),
                    object.optBoolean("synced", false),
                    lines
            );
        } catch (IOException ignored) {
            deleteQuietly(cacheFile);
            return null;
        } catch (JSONException ignored) {
            deleteQuietly(cacheFile);
            return null;
        }
    }

    void put(PhoneLyricsResult result) {
        if (result == null || result.trackKey.isEmpty() || result.lines.isEmpty()) {
            return;
        }
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            return;
        }
        File cacheFile = getCacheFile(result.trackKey);
        File temporaryFile = new File(cacheFile.getAbsolutePath() + ".tmp");
        try {
            JSONObject object = new JSONObject();
            object.put("trackKey", result.trackKey);
            object.put("sourceLabel", stripCachePrefix(result.sourceLabel));
            object.put("synced", result.synced);
            JSONArray lines = new JSONArray();
            for (RemoteLyricLine line : result.lines) {
                JSONObject lineObject = new JSONObject();
                lineObject.put("timeMs", line.timeMs);
                lineObject.put("text", line.text);
                lines.put(lineObject);
            }
            object.put("lines", lines);
            writeFile(temporaryFile, object.toString());
            if (cacheFile.exists() && !cacheFile.delete()) {
                deleteQuietly(temporaryFile);
                return;
            }
            if (!temporaryFile.renameTo(cacheFile)) {
                deleteQuietly(temporaryFile);
                return;
            }
            trimCache();
        } catch (IOException ignored) {
            deleteQuietly(temporaryFile);
        } catch (JSONException ignored) {
            deleteQuietly(temporaryFile);
        }
    }

    private void trimCache() {
        File[] files = cacheDirectory.listFiles();
        if (files == null || files.length <= MAX_CACHE_ENTRIES) {
            return;
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File first, File second) {
                return Long.compare(first.lastModified(), second.lastModified());
            }
        });
        int deleteCount = files.length - MAX_CACHE_ENTRIES;
        for (int i = 0; i < deleteCount; i++) {
            deleteQuietly(files[i]);
        }
    }

    private File getCacheFile(String trackKey) {
        return new File(cacheDirectory, sha256(trackKey) + ".json");
    }

    private static String stripCachePrefix(String sourceLabel) {
        String value = sourceLabel == null ? "" : sourceLabel;
        String prefix = "Cache · ";
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value).getBytes("UTF-8"));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                builder.append(String.format("%02x", item & 0xff));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String readFile(File file) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8")
        );
        try {
            StringBuilder builder = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                builder.append(buffer, 0, count);
            }
            return builder.toString();
        } finally {
            reader.close();
        }
    }

    private static void writeFile(File file, String value) throws IOException {
        OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file),
                "UTF-8"
        );
        try {
            writer.write(value);
            writer.flush();
        } finally {
            writer.close();
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            file.delete();
        }
    }
}
