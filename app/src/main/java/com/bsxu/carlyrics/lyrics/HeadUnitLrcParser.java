package com.bsxu.carlyrics.lyrics;

import com.bsxu.carlyrics.bridge.RemoteLyricLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HeadUnitLrcParser {

    private static final Pattern TIMESTAMP_PATTERN =
            Pattern.compile("\\[(\\d{1,2}):(\\d{1,2})(?:\\.(\\d{1,3}))?\\]");
    private static final int MAX_RAW_CHARS = 512 * 1024;
    private static final int MAX_LINES = 1500;
    private static final int MAX_LINE_CHARS = 500;

    private HeadUnitLrcParser() {
    }

    public static List<RemoteLyricLine> parseSynced(String rawText) {
        List<RemoteLyricLine> lines = new ArrayList<RemoteLyricLine>();
        if (isBlank(rawText)) {
            return lines;
        }
        String bounded = rawText.length() > MAX_RAW_CHARS
                ? rawText.substring(0, MAX_RAW_CHARS)
                : rawText;
        for (String rawLine : bounded.split("\\r?\\n")) {
            Matcher matcher = TIMESTAMP_PATTERN.matcher(rawLine);
            List<Long> timestamps = new ArrayList<Long>();
            int contentStart = 0;
            while (matcher.find()) {
                timestamps.add(parseTimeMs(matcher.group(1), matcher.group(2), matcher.group(3)));
                contentStart = matcher.end();
            }
            if (timestamps.isEmpty()) {
                continue;
            }
            String text = limitLine(rawLine.substring(contentStart).trim());
            for (Long timestamp : timestamps) {
                if (lines.size() >= MAX_LINES) {
                    break;
                }
                lines.add(new RemoteLyricLine(timestamp.longValue(), text));
            }
            if (lines.size() >= MAX_LINES) {
                break;
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

    public static List<RemoteLyricLine> parsePlain(String rawText) {
        List<RemoteLyricLine> lines = new ArrayList<RemoteLyricLine>();
        if (isBlank(rawText)) {
            return lines;
        }
        String bounded = rawText.length() > MAX_RAW_CHARS
                ? rawText.substring(0, MAX_RAW_CHARS)
                : rawText;
        for (String rawLine : bounded.split("\\r?\\n")) {
            String text = limitLine(rawLine.trim());
            if (!text.isEmpty()) {
                lines.add(new RemoteLyricLine(-1L, text));
            }
            if (lines.size() >= MAX_LINES) {
                break;
            }
        }
        return lines;
    }

    private static long parseTimeMs(String minute, String second, String fraction) {
        long millis = 0L;
        if (!isBlank(fraction)) {
            if (fraction.length() == 1) {
                millis = Long.parseLong(fraction) * 100L;
            } else if (fraction.length() == 2) {
                millis = Long.parseLong(fraction) * 10L;
            } else {
                millis = Long.parseLong(fraction.substring(0, 3));
            }
        }
        return Long.parseLong(minute) * 60_000L
                + Long.parseLong(second) * 1_000L
                + millis;
    }

    private static String limitLine(String value) {
        return value.length() > MAX_LINE_CHARS
                ? value.substring(0, MAX_LINE_CHARS)
                : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
