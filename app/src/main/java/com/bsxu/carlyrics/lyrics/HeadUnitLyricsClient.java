package com.bsxu.carlyrics.lyrics;

import com.bsxu.carlyrics.BuildConfig;
import com.bsxu.carlyrics.bridge.RemoteLyricLine;
import com.bsxu.carlyrics.bridge.RemoteLyricsPayload;
import com.bsxu.carlyrics.bridge.RemotePlaybackPayload;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.Locale;

final class HeadUnitLyricsClient {

    private static final String BASE_URL = "https://lrclib.net";
    private static final int MAX_RESPONSE_CHARS = 1024 * 1024;
    private static final int MAX_SEARCH_CANDIDATES = 20;
    private static final String USER_AGENT =
            "CarLyricsHeadUnit/" + BuildConfig.VERSION_NAME
                    + " (https://github.com/bsxucome/carlyrics)";

    HeadUnitLyricsResult fetch(RemotePlaybackPayload playback, long timeoutMs) {
        if (playback == null || isBlank(playback.title)) {
            return HeadUnitLyricsResult.failure(HeadUnitLyricsResult.Status.NOT_FOUND);
        }
        long deadlineNanos = System.nanoTime() + Math.max(1000L, timeoutMs) * 1_000_000L;
        try {
            RemoteLyricsPayload exact = fetchExact(playback, deadlineNanos);
            if (exact != null) {
                return HeadUnitLyricsResult.success(exact);
            }
            RemoteLyricsPayload search = fetchSearch(playback, deadlineNanos);
            return search == null
                    ? HeadUnitLyricsResult.failure(HeadUnitLyricsResult.Status.NOT_FOUND)
                    : HeadUnitLyricsResult.success(search);
        } catch (SocketTimeoutException timeout) {
            return HeadUnitLyricsResult.failure(HeadUnitLyricsResult.Status.TIMEOUT);
        } catch (ResponseException responseError) {
            return HeadUnitLyricsResult.failure(HeadUnitLyricsResult.Status.RESPONSE_ERROR);
        } catch (IOException networkError) {
            return HeadUnitLyricsResult.failure(HeadUnitLyricsResult.Status.NETWORK_ERROR);
        }
    }

    private RemoteLyricsPayload fetchExact(
            RemotePlaybackPayload playback,
            long deadlineNanos
    ) throws IOException {
        if (isBlank(playback.artist)) {
            return null;
        }
        StringBuilder url = new StringBuilder(BASE_URL)
                .append("/api/get?track_name=").append(encode(playback.title))
                .append("&artist_name=").append(encode(playback.artist));
        if (!isBlank(playback.album)) {
            url.append("&album_name=").append(encode(playback.album));
        }
        if (playback.durationMs > 0L) {
            url.append("&duration=").append(playback.durationMs / 1000L);
        }
        JSONObject object = requestObject(url.toString(), deadlineNanos);
        return parsePayload(playback.trackKey, object, "LRCLIB head unit");
    }

    private RemoteLyricsPayload fetchSearch(
            RemotePlaybackPayload playback,
            long deadlineNanos
    ) throws IOException {
        StringBuilder url = new StringBuilder(BASE_URL)
                .append("/api/search?track_name=").append(encode(playback.title));
        if (!isBlank(playback.artist)) {
            url.append("&artist_name=").append(encode(playback.artist));
        }
        JSONArray array = requestArray(url.toString(), deadlineNanos);
        if (array == null) {
            return null;
        }
        JSONObject best = null;
        int bestScore = Integer.MIN_VALUE;
        int count = Math.min(array.length(), MAX_SEARCH_CANDIDATES);
        for (int index = 0; index < count; index++) {
            JSONObject candidate = array.optJSONObject(index);
            if (candidate == null) {
                continue;
            }
            int score = scoreCandidate(candidate, playback);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        if (best == null || bestScore < 40) {
            return null;
        }
        return parsePayload(playback.trackKey, best, "LRCLIB head unit search");
    }

    private int scoreCandidate(JSONObject candidate, RemotePlaybackPayload playback) {
        String expectedTitle = normalize(playback.title);
        String actualTitle = normalize(candidate.optString("trackName", ""));
        if (expectedTitle.isEmpty() || actualTitle.isEmpty()) {
            return Integer.MIN_VALUE;
        }
        int score = expectedTitle.equals(actualTitle) ? 60
                : containsEither(expectedTitle, actualTitle) ? 30 : 0;
        String expectedArtist = normalize(playback.artist);
        String actualArtist = normalize(candidate.optString("artistName", ""));
        if (!expectedArtist.isEmpty() && !actualArtist.isEmpty()) {
            score += expectedArtist.equals(actualArtist) ? 35
                    : containsEither(expectedArtist, actualArtist) ? 15 : -20;
        }
        if (!isBlank(candidate.optString("syncedLyrics", ""))) {
            score += 15;
        }
        if (playback.durationMs > 0L) {
            double durationSeconds = candidate.optDouble("duration", 0D);
            if (durationSeconds > 0D
                    && Math.abs(durationSeconds * 1000D - playback.durationMs) <= 3500D) {
                score += 10;
            }
        }
        return score;
    }

    private JSONObject requestObject(String url, long deadlineNanos) throws IOException {
        String body = request(url, deadlineNanos);
        if (body == null) {
            return null;
        }
        try {
            return new JSONObject(body);
        } catch (JSONException invalidResponse) {
            throw new ResponseException();
        }
    }

    private JSONArray requestArray(String url, long deadlineNanos) throws IOException {
        String body = request(url, deadlineNanos);
        if (body == null) {
            return null;
        }
        try {
            return new JSONArray(body);
        } catch (JSONException invalidResponse) {
            throw new ResponseException();
        }
    }

    private String request(String url, long deadlineNanos) throws IOException {
        HttpURLConnection connection = null;
        try {
            int remainingMs = remainingMs(deadlineNanos);
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(Math.min(2500, remainingMs));
            connection.setReadTimeout(Math.min(2500, remainingMs));
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                return null;
            }
            if (responseCode < 200 || responseCode >= 300) {
                throw new ResponseException();
            }
            return readString(connection.getInputStream());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private RemoteLyricsPayload parsePayload(
            String trackKey,
            JSONObject object,
            String sourceLabel
    ) {
        if (object == null) {
            return null;
        }
        String synced = object.optString("syncedLyrics", "");
        if (!isBlank(synced)) {
            List<RemoteLyricLine> lines = HeadUnitLrcParser.parseSynced(synced);
            if (!lines.isEmpty()) {
                return new RemoteLyricsPayload(trackKey, sourceLabel, true, lines);
            }
        }
        String plain = object.optString("plainLyrics", "");
        if (!isBlank(plain)) {
            List<RemoteLyricLine> lines = HeadUnitLrcParser.parsePlain(plain);
            if (!lines.isEmpty()) {
                return new RemoteLyricsPayload(trackKey, sourceLabel, false, lines);
            }
        }
        return null;
    }

    private String readString(InputStream inputStream) throws IOException {
        InputStreamReader reader = new InputStreamReader(inputStream, "UTF-8");
        try {
            StringBuilder builder = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                if (builder.length() + count > MAX_RESPONSE_CHARS) {
                    throw new IOException("LRCLIB response too large");
                }
                builder.append(buffer, 0, count);
            }
            return builder.toString();
        } finally {
            reader.close();
        }
    }

    private static int remainingMs(long deadlineNanos) throws IOException {
        long remaining = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        if (remaining <= 0L) {
            throw new SocketTimeoutException("Lyrics lookup timed out");
        }
        return (int) Math.min(Integer.MAX_VALUE, remaining);
    }

    private static boolean containsEither(String first, String second) {
        return first.contains(second) || second.contains(first);
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT)
                .replace("&", "and")
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "");
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (IOException impossible) {
            return "";
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class ResponseException extends IOException {
    }
}
