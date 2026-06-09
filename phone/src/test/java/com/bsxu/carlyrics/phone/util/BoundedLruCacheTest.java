package com.bsxu.carlyrics.phone.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class BoundedLruCacheTest {

    @Test
    public void evictsLeastRecentlyUsedEntry() {
        BoundedLruCache<String, String> cache = new BoundedLruCache<String, String>(2);
        cache.put("first", "1");
        cache.put("second", "2");

        assertEquals("1", cache.get("first"));
        cache.put("third", "3");

        assertEquals("1", cache.get("first"));
        assertNull(cache.get("second"));
        assertEquals("3", cache.get("third"));
        assertEquals(2, cache.size());
    }

    @Test
    public void ignoresNullEntriesAndCanClear() {
        BoundedLruCache<String, String> cache = new BoundedLruCache<String, String>(2);
        cache.put(null, "value");
        cache.put("key", null);
        assertEquals(0, cache.size());

        cache.put("key", "value");
        cache.clear();

        assertEquals(0, cache.size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidCapacity() {
        new BoundedLruCache<String, String>(0);
    }
}
