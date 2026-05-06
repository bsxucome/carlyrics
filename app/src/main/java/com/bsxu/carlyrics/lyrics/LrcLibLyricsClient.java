package com.bsxu.carlyrics.lyrics;

import com.bsxu.carlyrics.model.LyricLine;
import com.bsxu.carlyrics.model.LyricsResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;

public final class LrcLibLyricsClient {

    private static final String GET_URL = "https://lrclib.net/api/get";
    private static final String SEARCH_URL = "https://lrclib.net/api/search";
    private static final String USER_AGENT = "CarLyricsDisplay/0.2";

    public LyricsResult fetch(String title, String artist, String album, long durationMs) {
        LyricsResult exactMatch = fetchExactMatch(title, artist, album, durationMs);
        if (exactMatch != null) {
            return exactMatch;
        }
        return fetchBySearch(title, artist, album, durationMs);
    }

    private LyricsResult fetchExactMatch(String title, String artist, String album, long durationMs) {
        if (isBlank(title) && isBlank(artist)) {
            return null;
        }

        HttpURLConnection connection = null;
        try {
            StringBuilder urlBuilder = new StringBuilder(GET_URL)
                    .append("?track_name=").append(encode(title))
                    .append("&artist_name=").append(encode(artist));
            if (!isBlank(album)) {
                urlBuilder.append("&album_name=").append(encode(album));
            }
            if (durationMs > 0L) {
                urlBuilder.append("&duration=").append(durationMs / 1000L);
            }

            connection = openConnection(urlBuilder.toString());
            if (!isSuccess(connection.getResponseCode())) {
                return null;
            }
            return parseLyricsResult(new JSONObject(readString(connection.getInputStream())), "LRCLIB exact");
        } catch (IOException ignored) {
            return null;
        } catch (JSONException ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private LyricsResult fetchBySearch(String title, String artist, String album, long durationMs) {
        if (isBlank(title) && isBlank(artist)) {
            return null;
        }

        HttpURLConnection connection = null;
        try {
            StringBuilder urlBuilder = new StringBuilder(SEARCH_URL)
                    .append("?track_name=").append(encode(title))
                    .append("&artist_name=").append(encode(artist));
            if (!isBlank(album)) {
                urlBuilder.append("&album_name=").append(encode(album));
            }

            connection = openConnection(urlBuilder.toString());
            if (!isSuccess(connection.getResponseCode())) {
                return null;
            }

            JSONArray results = new JSONArray(readString(connection.getInputStream()));
            LyricsResult bestResult = null;
            int bestScore = Integer.MIN_VALUE;
            for (int i = 0; i < results.length(); i++) {
                JSONObject candidate = results.optJSONObject(i);
                if (candidate == null) {
                    continue;
                }
                LyricsResult parsedResult = parseLyricsResult(candidate, "LRCLIB search");
                if (parsedResult == null) {
                    continue;
                }
                int score = scoreCandidate(candidate, parsedResult, title, artist, album, durationMs);
                if (score > bestScore) {
                    bestScore = score;
                    bestResult = parsedResult;
                }
            }
            return bestResult;
        } catch (IOException ignored) {
            return null;
        } catch (JSONException ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private HttpURLConnection openConnection(String urlString) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(4000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        return connection;
    }

    private LyricsResult parseLyricsResult(JSONObject jsonObject, String sourceLabel) {
        String syncedLyrics = jsonObject.optString("syncedLyrics", "");
        if (!isBlank(syncedLyrics)) {
            List<LyricLine> lines = LrcParser.parseSyncedLyrics(syncedLyrics);
            if (!lines.isEmpty()) {
                return new LyricsResult(lines, sourceLabel, true, syncedLyrics);
            }
        }

        String plainLyrics = jsonObject.optString("plainLyrics", "");
        if (!isBlank(plainLyrics)) {
            List<LyricLine> lines = LrcParser.parsePlainLyrics(plainLyrics);
            if (!lines.isEmpty()) {
                return new LyricsResult(lines, sourceLabel, false, plainLyrics);
            }
        }
        return null;
    }

    private int scoreCandidate(JSONObject candidate, LyricsResult result, String title, String artist, String album, long durationMs) {
        int score = result.isSynced() ? 30 : 8;
        score += scoreTextMatch(title, candidate.optString("trackName", ""), 25, 10);
        score += scoreTextMatch(artist, candidate.optString("artistName", ""), 20, 8);
        score += scoreTextMatch(album, candidate.optString("albumName", ""), 10, 4);

        double candidateDurationSeconds = candidate.optDouble("duration", -1.0d);
        if (durationMs > 0L && candidateDurationSeconds > 0d) {
            long deltaMs = Math.abs(durationMs - Math.round(candidateDurationSeconds * 1000d));
            if (deltaMs <= 3000L) {
                score += 15;
            } else if (deltaMs <= 10000L) {
                score += 8;
            }
        }
        return score;
    }

    private int scoreTextMatch(String expected, String candidate, int exactScore, int containsScore) {
        String normalizedExpected = normalize(expected);
        String normalizedCandidate = normalize(candidate);
        if (normalizedExpected.isEmpty() || normalizedCandidate.isEmpty()) {
            return 0;
        }
        if (normalizedExpected.equals(normalizedCandidate)) {
            return exactScore;
        }
        if (normalizedCandidate.contains(normalizedExpected) || normalizedExpected.contains(normalizedCandidate)) {
            return containsScore;
        }
        return 0;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean isSuccess(int code) {
        return code >= 200 && code < 300;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase()
                .replace("&", "and")
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "");
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }

    private static String readString(InputStream inputStream) throws IOException {
        StringBuilder builder = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            return builder.toString();
        } finally {
            reader.close();
        }
    }
}
