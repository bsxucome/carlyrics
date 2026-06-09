package com.bsxu.carlyrics.phone.util;

import java.util.LinkedHashMap;
import java.util.Map;

public final class BoundedLruCache<K, V> {

    private final int maxEntries;
    private final LinkedHashMap<K, V> entries;

    public BoundedLruCache(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
        this.entries = new LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > BoundedLruCache.this.maxEntries;
            }
        };
    }

    public synchronized V get(K key) {
        return entries.get(key);
    }

    public synchronized void put(K key, V value) {
        if (key == null || value == null) {
            return;
        }
        entries.put(key, value);
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
    }
}
