package com.bsxu.carlyrics.phone.lyrics;

import com.bsxu.carlyrics.bridge.RemoteLyricLine;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PhoneLyricsDiskCacheTest {

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void storesAndLoadsLyrics() throws Exception {
        File directory = temporaryFolder.newFolder("cache");
        MutableTimeSource timeSource = new MutableTimeSource(System.currentTimeMillis());
        PhoneLyricsDiskCache cache = new PhoneLyricsDiskCache(directory, timeSource);

        cache.put(result("track", "LRCLIB exact"));
        PhoneLyricsResult loaded = cache.get("track");

        assertNotNull(loaded);
        assertEquals("track", loaded.trackKey);
        assertEquals("Cache \u00b7 LRCLIB exact", loaded.sourceLabel);
        assertEquals(1, loaded.lines.size());
        assertEquals("line", loaded.lines.get(0).text);
    }

    @Test
    public void deletesExpiredEntry() throws Exception {
        File directory = temporaryFolder.newFolder("expired");
        MutableTimeSource timeSource = new MutableTimeSource(System.currentTimeMillis());
        PhoneLyricsDiskCache cache = new PhoneLyricsDiskCache(directory, timeSource);
        cache.put(result("track", "LRCLIB"));
        assertEquals(1, jsonFileCount(directory));

        timeSource.now += 31L * DAY_MS;

        assertNull(cache.get("track"));
        assertEquals(0, jsonFileCount(directory));
    }

    @Test
    public void deletesCorruptEntry() throws Exception {
        File directory = temporaryFolder.newFolder("corrupt");
        MutableTimeSource timeSource = new MutableTimeSource(System.currentTimeMillis());
        PhoneLyricsDiskCache cache = new PhoneLyricsDiskCache(directory, timeSource);
        cache.put(result("track", "LRCLIB"));
        File cacheFile = firstJsonFile(directory);
        assertNotNull(cacheFile);
        writeBytes(cacheFile, "{broken".getBytes("UTF-8"));

        assertNull(cache.get("track"));
        assertFalse(cacheFile.exists());
    }

    @Test
    public void trimsOldEntriesAndTemporaryFiles() throws Exception {
        File directory = temporaryFolder.newFolder("trim");
        MutableTimeSource timeSource = new MutableTimeSource(System.currentTimeMillis());
        PhoneLyricsDiskCache cache = new PhoneLyricsDiskCache(directory, timeSource);
        File temporaryFile = new File(directory, "orphan.tmp");
        assertTrue(temporaryFile.createNewFile());

        for (int i = 0; i < 205; i++) {
            timeSource.now++;
            cache.put(result("track-" + i, "LRCLIB"));
        }

        assertTrue(jsonFileCount(directory) <= 200);
        assertFalse(temporaryFile.exists());
    }

    private static PhoneLyricsResult result(String trackKey, String sourceLabel) {
        return new PhoneLyricsResult(
                trackKey,
                sourceLabel,
                true,
                Collections.singletonList(new RemoteLyricLine(1000L, "line"))
        );
    }

    private static int jsonFileCount(File directory) {
        File[] files = directory.listFiles();
        if (files == null) {
            return 0;
        }
        int count = 0;
        for (File file : files) {
            if (file.getName().endsWith(".json")) {
                count++;
            }
        }
        return count;
    }

    private static File firstJsonFile(File directory) {
        File[] files = directory.listFiles();
        if (files == null) {
            return null;
        }
        for (File file : files) {
            if (file.getName().endsWith(".json")) {
                return file;
            }
        }
        return null;
    }

    private static void writeBytes(File file, byte[] bytes) throws Exception {
        FileOutputStream outputStream = new FileOutputStream(file, false);
        try {
            outputStream.write(bytes);
        } finally {
            outputStream.close();
        }
    }

    private static final class MutableTimeSource implements PhoneLyricsDiskCache.TimeSource {
        private long now;

        private MutableTimeSource(long now) {
            this.now = now;
        }

        @Override
        public long currentTimeMillis() {
            return now;
        }
    }
}
