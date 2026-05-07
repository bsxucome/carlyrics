package com.bsxu.carlyrics.phone.lyrics;

import com.bsxu.carlyrics.bridge.RemoteLyricLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PhoneLrcParser {

    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("\\[(\\d{1,2}):(\\d{1,2})(?:\\.(\\d{1,3}))?\\]");

    private PhoneLrcParser() {
    }

    public static List<RemoteLyricLine> parseSyncedLyrics(String rawText) {
        List<RemoteLyricLine> lines = new ArrayList<RemoteLyricLine>();
        if (rawText == null || rawText.trim().isEmpty()) {
            return lines;
        }

        String[] rawLines = rawText.split("\\r?\\n");
        for (String rawLine : rawLines) {
            Matcher matcher = TIMESTAMP_PATTERN.matcher(rawLine);
            List<Long> times = new ArrayList<Long>();
            int contentStart = 0;
            while (matcher.find()) {
                times.add(parseTimeMs(matcher.group(1), matcher.group(2), matcher.group(3)));
                contentStart = matcher.end();
            }
            if (times.isEmpty()) {
                continue;
            }
            String content = rawLine.substring(contentStart).trim();
            for (Long time : times) {
                lines.add(new RemoteLyricLine(time.longValue(), content));
            }
        }

        Collections.sort(lines, new Comparator<RemoteLyricLine>() {
            @Override
            public int compare(RemoteLyricLine first, RemoteLyricLine second) {
                return Long.compare(first.timeMs, second.timeMs);
            }
        });
        return lines;
    }

    public static List<RemoteLyricLine> parsePlainLyrics(String rawText) {
        List<RemoteLyricLine> lines = new ArrayList<RemoteLyricLine>();
        if (rawText == null || rawText.trim().isEmpty()) {
            return lines;
        }

        String[] rawLines = rawText.split("\\r?\\n");
        for (String rawLine : rawLines) {
            String trimmed = rawLine.trim();
            if (!trimmed.isEmpty()) {
                lines.add(new RemoteLyricLine(-1L, trimmed));
            }
        }
        return lines;
    }

    private static long parseTimeMs(String minute, String second, String fraction) {
        long minutes = Long.parseLong(minute);
        long seconds = Long.parseLong(second);
        long millis = 0L;
        if (fraction != null && !fraction.isEmpty()) {
            if (fraction.length() == 1) {
                millis = Long.parseLong(fraction) * 100L;
            } else if (fraction.length() == 2) {
                millis = Long.parseLong(fraction) * 10L;
            } else {
                millis = Long.parseLong(fraction.substring(0, 3));
            }
        }
        return minutes * 60_000L + seconds * 1_000L + millis;
    }
}

