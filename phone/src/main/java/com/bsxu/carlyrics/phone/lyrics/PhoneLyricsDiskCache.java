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
    private static final long MAX_CACHE_BYTES = 8L * 1024L * 1024L;
    private static final long MAX_CACHE_FILE_BYTES = 512L * 1024L;
    private static final long MAX_CACHE_AGE_MS = 30L * 24L * 60L * 60L * 1000L;

    private final File cacheDirectory;
    private final TimeSource timeSource;

    PhoneLyricsDiskCache(Context context) {
        this(
                new File(context.getApplicationContext().getFilesDir(), CACHE_DIRECTORY),
                new SystemTimeSource()
        );
    }

    PhoneLyricsDiskCache(File cacheDirectory, TimeSource timeSource) {
        this.cacheDirectory = cacheDirectory;
        this.timeSource = timeSource;
    }

    PhoneLyricsResult get(String trackKey) {
        File cacheFile = getCacheFile(trackKey);
        if (!cacheFile.isFile()) {
            return null;
        }
        long now = timeSource.currentTimeMillis();
        if (cacheFile.length() <= 0L
                || cacheFile.length() > MAX_CACHE_FILE_BYTES
                || isExpired(cacheFile, now)) {
            deleteQuietly(cacheFile);
            return null;
        }
        try {
            JSONObject object = new JSONObject(readFile(cacheFile));
            if (!trackKey.equals(object.optString("trackKey", ""))) {
                deleteQuietly(cacheFile);
                return null;
            }
            long cachedAtMs = object.optLong("cachedAtMs", cacheFile.lastModified());
            if (cachedAtMs <= 0L || now - cachedAtMs > MAX_CACHE_AGE_MS) {
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
            cacheFile.setLastModified(now);
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
            object.put("cachedAtMs", timeSource.currentTimeMillis());
            JSONArray lines = new JSONArray();
            for (RemoteLyricLine line : result.lines) {
                JSONObject lineObject = new JSONObject();
                lineObject.put("timeMs", line.timeMs);
                lineObject.put("text", line.text);
                lines.put(lineObject);
            }
            object.put("lines", lines);
            String encoded = object.toString();
            if (encoded.getBytes("UTF-8").length > MAX_CACHE_FILE_BYTES) {
                deleteQuietly(temporaryFile);
                return;
            }
            writeFile(temporaryFile, encoded);
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
        if (files == null) {
            return;
        }
        long now = timeSource.currentTimeMillis();
        ArrayList<File> validFiles = new ArrayList<File>();
        long totalBytes = 0L;
        for (File file : files) {
            if (file == null) {
                continue;
            }
            if (!file.getName().endsWith(".json")
                    || file.length() <= 0L
                    || file.length() > MAX_CACHE_FILE_BYTES
                    || isExpired(file, now)) {
                deleteQuietly(file);
                continue;
            }
            validFiles.add(file);
            totalBytes += file.length();
        }
        File[] orderedFiles = validFiles.toArray(new File[0]);
        Arrays.sort(orderedFiles, new Comparator<File>() {
            @Override
            public int compare(File first, File second) {
                return Long.compare(first.lastModified(), second.lastModified());
            }
        });
        int remainingEntries = orderedFiles.length;
        for (File file : orderedFiles) {
            if (remainingEntries <= MAX_CACHE_ENTRIES && totalBytes <= MAX_CACHE_BYTES) {
                break;
            }
            long fileBytes = file.length();
            deleteQuietly(file);
            remainingEntries--;
            totalBytes = Math.max(0L, totalBytes - fileBytes);
        }
    }

    private boolean isExpired(File file, long now) {
        long lastModified = file.lastModified();
        return lastModified <= 0L || now - lastModified > MAX_CACHE_AGE_MS;
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
        if (file.length() > MAX_CACHE_FILE_BYTES) {
            throw new IOException("Lyrics cache file exceeds size limit");
        }
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8")
        );
        try {
            StringBuilder builder = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                if (builder.length() + count > MAX_CACHE_FILE_BYTES) {
                    throw new IOException("Lyrics cache file exceeds size limit");
                }
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

    interface TimeSource {
        long currentTimeMillis();
    }

    private static final class SystemTimeSource implements TimeSource {
        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }
    }
}
