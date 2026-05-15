package com.bsxu.carlyrics.lyrics;

import android.util.Log;

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
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

public final class LrcLibLyricsClient {

    private static final String TAG = "LrcLibClient";
    private static final String GET_URL = "https://lrclib.net/api/get";
    private static final String SEARCH_URL = "https://lrclib.net/api/search";
    private static final String LRCLIB_HOST = "lrclib.net";
    private static final String USER_AGENT = "CarLyricsDisplay/0.2";
    private static final int MATCH_NONE = 0;
    private static final int MATCH_CONTAINS = 1;
    private static final int MATCH_EXACT = 2;
    private static final AtomicBoolean insecureTlsLogPrinted = new AtomicBoolean(false);
    private static final SSLSocketFactory INSECURE_SSL_SOCKET_FACTORY = buildInsecureSocketFactory();
    private static final HostnameVerifier INSECURE_HOSTNAME_VERIFIER = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return LRCLIB_HOST.equalsIgnoreCase(hostname) || ("www." + LRCLIB_HOST).equalsIgnoreCase(hostname);
        }
    };

    public LyricsResult fetch(String title, String artist, String album, long durationMs) {
        QueryVariant primary = new QueryVariant(title, artist, album);
        QueryVariant cleaned = primary.toCleanedVariant();

        LyricsResult exactMatch = fetchExactMatch(primary, durationMs);
        if (exactMatch != null) {
            return exactMatch;
        }

        if (!primary.isEquivalentTo(cleaned)) {
            exactMatch = fetchExactMatch(cleaned, durationMs);
            if (exactMatch != null) {
                return exactMatch;
            }
        }

        LyricsResult searchMatch = fetchBySearch(primary, durationMs);
        if (searchMatch != null) {
            return searchMatch;
        }

        if (!primary.isEquivalentTo(cleaned)) {
            return fetchBySearch(cleaned, durationMs);
        }
        return null;
    }

    private LyricsResult fetchExactMatch(QueryVariant queryVariant, long durationMs) {
        if (!queryVariant.canLookup()) {
            return null;
        }

        HttpURLConnection connection = null;
        try {
            StringBuilder urlBuilder = new StringBuilder(GET_URL)
                    .append("?track_name=").append(encode(queryVariant.title))
                    .append("&artist_name=").append(encode(queryVariant.artist));
            if (!isBlank(queryVariant.album)) {
                urlBuilder.append("&album_name=").append(encode(queryVariant.album));
            }
            if (durationMs > 0L) {
                urlBuilder.append("&duration=").append(durationMs / 1000L);
            }

            connection = openConnection(urlBuilder.toString());
            if (!isSuccess(connection.getResponseCode())) {
                return null;
            }
            JSONObject jsonObject = new JSONObject(readString(connection.getInputStream()));
            LyricsResult parsedResult = parseLyricsResult(jsonObject, "LRCLIB exact");
            if (parsedResult == null) {
                return null;
            }
            CandidateScore candidateScore = scoreCandidate(jsonObject, parsedResult, queryVariant, durationMs);
            return passesConfidenceGate(candidateScore) ? parsedResult : null;
        } catch (IOException ignored) {
            Log.w(TAG, "Exact lyrics lookup failed for " + queryVariant.title + " / " + queryVariant.artist, ignored);
            return null;
        } catch (JSONException ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private LyricsResult fetchBySearch(QueryVariant queryVariant, long durationMs) {
        if (!queryVariant.canUseSearch()) {
            return null;
        }

        HttpURLConnection connection = null;
        try {
            StringBuilder urlBuilder = new StringBuilder(SEARCH_URL)
                    .append("?track_name=").append(encode(queryVariant.title))
                    .append("&artist_name=").append(encode(queryVariant.artist));
            if (!isBlank(queryVariant.album)) {
                urlBuilder.append("&album_name=").append(encode(queryVariant.album));
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
                CandidateScore candidateScore = scoreCandidate(candidate, parsedResult, queryVariant, durationMs);
                if (!passesConfidenceGate(candidateScore)) {
                    continue;
                }
                if (candidateScore.totalScore > bestScore) {
                    bestScore = candidateScore.totalScore;
                    bestResult = parsedResult;
                }
            }
            return bestResult;
        } catch (IOException ignored) {
            Log.w(TAG, "Search lyrics lookup failed for " + queryVariant.title + " / " + queryVariant.artist, ignored);
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
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(4000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        maybeAllowInsecureTls(url, connection);
        return connection;
    }

    private void maybeAllowInsecureTls(URL url, HttpURLConnection connection) {
        if (!(connection instanceof HttpsURLConnection) || url == null) {
            return;
        }
        String host = url.getHost();
        if (!LRCLIB_HOST.equalsIgnoreCase(host) && !("www." + LRCLIB_HOST).equalsIgnoreCase(host)) {
            return;
        }
        HttpsURLConnection httpsConnection = (HttpsURLConnection) connection;
        httpsConnection.setSSLSocketFactory(INSECURE_SSL_SOCKET_FACTORY);
        httpsConnection.setHostnameVerifier(INSECURE_HOSTNAME_VERIFIER);
        if (insecureTlsLogPrinted.compareAndSet(false, true)) {
            Log.w(TAG, "Using insecure TLS fallback for lrclib.net because the upstream certificate is currently invalid");
        }
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

    private CandidateScore scoreCandidate(JSONObject candidate, LyricsResult result, QueryVariant queryVariant, long durationMs) {
        int score = result.isSynced() ? 30 : 8;
        int titleMatchLevel = matchLevel(queryVariant.title, candidate.optString("trackName", ""));
        int artistMatchLevel = matchLevel(queryVariant.artist, candidate.optString("artistName", ""));
        int albumMatchLevel = matchLevel(queryVariant.album, candidate.optString("albumName", ""));

        score += scoreForMatchLevel(titleMatchLevel, 10, 25);
        score += scoreForMatchLevel(artistMatchLevel, 8, 20);
        score += scoreForMatchLevel(albumMatchLevel, 4, 10);

        double candidateDurationSeconds = candidate.optDouble("duration", -1.0d);
        int durationMatchLevel = MATCH_NONE;
        if (durationMs > 0L && candidateDurationSeconds > 0d) {
            long deltaMs = Math.abs(durationMs - Math.round(candidateDurationSeconds * 1000d));
            if (deltaMs <= 3000L) {
                score += 15;
                durationMatchLevel = MATCH_EXACT;
            } else if (deltaMs <= 10000L) {
                score += 8;
                durationMatchLevel = MATCH_CONTAINS;
            }
        }
        return new CandidateScore(score, titleMatchLevel, artistMatchLevel, durationMatchLevel, result.isSynced());
    }

    private int matchLevel(String expected, String candidate) {
        String normalizedExpected = normalize(expected);
        String normalizedCandidate = normalize(candidate);
        if (normalizedExpected.isEmpty() || normalizedCandidate.isEmpty()) {
            return MATCH_NONE;
        }
        if (normalizedExpected.equals(normalizedCandidate)) {
            return MATCH_EXACT;
        }
        if (normalizedCandidate.contains(normalizedExpected) || normalizedExpected.contains(normalizedCandidate)) {
            return MATCH_CONTAINS;
        }
        return MATCH_NONE;
    }

    private int scoreForMatchLevel(int matchLevel, int containsScore, int exactScore) {
        if (matchLevel == MATCH_EXACT) {
            return exactScore;
        }
        if (matchLevel == MATCH_CONTAINS) {
            return containsScore;
        }
        return 0;
    }

    private boolean passesConfidenceGate(CandidateScore candidateScore) {
        if (candidateScore == null) {
            return false;
        }
        if (candidateScore.titleMatchLevel == MATCH_NONE) {
            return false;
        }
        if (candidateScore.artistMatchLevel == MATCH_NONE) {
            return false;
        }
        if (candidateScore.titleMatchLevel == MATCH_CONTAINS
                && candidateScore.artistMatchLevel != MATCH_EXACT
                && candidateScore.durationMatchLevel == MATCH_NONE) {
            return false;
        }
        if (!candidateScore.synced
                && candidateScore.titleMatchLevel != MATCH_EXACT
                && candidateScore.durationMatchLevel == MATCH_NONE) {
            return false;
        }
        return candidateScore.totalScore >= 58;
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

    private static SSLSocketFactory buildInsecureSocketFactory() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new X509TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            }, new SecureRandom());
            return sslContext.getSocketFactory();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to initialize insecure LRCLIB TLS fallback", exception);
        }
    }

    private static final class CandidateScore {
        private final int totalScore;
        private final int titleMatchLevel;
        private final int artistMatchLevel;
        private final int durationMatchLevel;
        private final boolean synced;

        private CandidateScore(int totalScore, int titleMatchLevel, int artistMatchLevel, int durationMatchLevel, boolean synced) {
            this.totalScore = totalScore;
            this.titleMatchLevel = titleMatchLevel;
            this.artistMatchLevel = artistMatchLevel;
            this.durationMatchLevel = durationMatchLevel;
            this.synced = synced;
        }
    }

    private static final class QueryVariant {
        private final String title;
        private final String artist;
        private final String album;

        private QueryVariant(String title, String artist, String album) {
            this.title = safeTrim(title);
            this.artist = safeTrim(artist);
            this.album = safeTrim(album);
        }

        private boolean canLookup() {
            return !isBlank(title) && !isBlank(artist);
        }

        private boolean canUseSearch() {
            return canLookup();
        }

        private QueryVariant toCleanedVariant() {
            return new QueryVariant(
                    cleanTitle(title),
                    cleanArtist(artist),
                    cleanAlbum(album)
            );
        }

        private boolean isEquivalentTo(QueryVariant other) {
            if (other == null) {
                return false;
            }
            return normalize(title).equals(normalize(other.title))
                    && normalize(artist).equals(normalize(other.artist))
                    && normalize(album).equals(normalize(other.album));
        }
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String cleanTitle(String title) {
        String cleaned = safeTrim(title);
        cleaned = cleaned.replaceAll("\\s*[\\(\\[【（][^\\)\\]】）]{0,24}(live|remaster|version|ver\\.?|mix|edit|cover|karaoke|伴奏|纯音乐)[^\\)\\]】）]*[\\)\\]】）]\\s*$", "");
        cleaned = cleaned.replaceAll("\\s+-\\s+(live|remaster(ed)?|version|ver\\.?|mix|edit|cover|karaoke|伴奏|纯音乐).*$", "");
        cleaned = cleaned.replaceAll("\\s+(feat\\.?|ft\\.?|with)\\s+.*$", "");
        return safeTrim(cleaned);
    }

    private static String cleanArtist(String artist) {
        String cleaned = safeTrim(artist);
        cleaned = cleaned.replaceAll("\\s+(feat\\.?|ft\\.?|with)\\s+.*$", "");
        return safeTrim(cleaned);
    }

    private static String cleanAlbum(String album) {
        return safeTrim(album);
    }
}
