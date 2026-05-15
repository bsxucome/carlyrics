package com.bsxu.carlyrics.phone.lyrics;

import android.text.TextUtils;
import android.util.Log;

import com.bsxu.carlyrics.bridge.RemoteLyricLine;

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

public final class PhoneLrcLibClient {

    private static final String TAG = "PhoneLrcLib";
    private static final String GET_URL = "https://lrclib.net/api/get";
    private static final String SEARCH_URL = "https://lrclib.net/api/search";
    private static final String LRCLIB_HOST = "lrclib.net";
    private static final String USER_AGENT = "CarLyricsPhoneCompanion/0.1";
    private static final AtomicBoolean insecureTlsLogPrinted = new AtomicBoolean(false);
    private static final SSLSocketFactory INSECURE_SSL_SOCKET_FACTORY = buildInsecureSocketFactory();
    private static final HostnameVerifier INSECURE_HOSTNAME_VERIFIER = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return LRCLIB_HOST.equalsIgnoreCase(hostname) || ("www." + LRCLIB_HOST).equalsIgnoreCase(hostname);
        }
    };

    public PhoneLyricsResult fetch(String trackKey, String title, String artist, String album, long durationMs) {
        QueryVariant primary = new QueryVariant(title, artist, album);
        QueryVariant cleaned = primary.toCleanedVariant();
        QueryVariant primaryFirstArtist = primary.toFirstArtistVariant();
        QueryVariant cleanedFirstArtist = cleaned.toFirstArtistVariant();
        QueryVariant titleOnly = cleaned.toTitleOnlyVariant();

        QueryVariant[] variants = uniqueVariants(primary, cleaned, primaryFirstArtist, cleanedFirstArtist);
        CandidateMatch bestMatch = null;

        for (QueryVariant variant : variants) {
            bestMatch = chooseBetter(bestMatch, fetchExactMatch(trackKey, variant, durationMs, true, true));
            if (isStrongSyncedWinner(bestMatch)) {
                return bestMatch.result;
            }
        }
        for (QueryVariant variant : variants) {
            bestMatch = chooseBetter(bestMatch, fetchExactMatch(trackKey, variant, durationMs, false, false));
            if (isStrongSyncedWinner(bestMatch)) {
                return bestMatch.result;
            }
        }
        for (QueryVariant variant : variants) {
            bestMatch = chooseBetter(bestMatch, fetchBySearch(trackKey, variant, durationMs));
        }
        bestMatch = chooseBetter(bestMatch, fetchBySearch(trackKey, titleOnly, durationMs));
        return bestMatch == null ? null : bestMatch.result;
    }

    private CandidateMatch fetchExactMatch(
            String trackKey,
            QueryVariant queryVariant,
            long durationMs,
            boolean includeAlbum,
            boolean includeDuration
    ) {
        if (!queryVariant.canLookupExactly()) {
            return null;
        }

        HttpURLConnection connection = null;
        try {
            StringBuilder urlBuilder = new StringBuilder(GET_URL)
                    .append("?track_name=").append(encode(queryVariant.title))
                    .append("&artist_name=").append(encode(queryVariant.artist));
            if (includeAlbum && !isBlank(queryVariant.album)) {
                urlBuilder.append("&album_name=").append(encode(queryVariant.album));
            }
            if (includeDuration && durationMs > 0L) {
                urlBuilder.append("&duration=").append(durationMs / 1000L);
            }

            connection = openConnection(urlBuilder.toString());
            if (!isSuccess(connection.getResponseCode())) {
                return null;
            }
            JSONObject jsonObject = new JSONObject(readString(connection.getInputStream()));
            PhoneLyricsResult parsedResult = parseLyricsResult(trackKey, jsonObject, "LRCLIB exact");
            if (parsedResult == null) {
                return null;
            }
            CandidateScore candidateScore = scoreCandidate(jsonObject, parsedResult, queryVariant, durationMs);
            return passesConfidenceGate(candidateScore, queryVariant)
                    ? new CandidateMatch(parsedResult, candidateScore)
                    : null;
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

    private CandidateMatch fetchBySearch(String trackKey, QueryVariant queryVariant, long durationMs) {
        if (!queryVariant.canSearch()) {
            return null;
        }

        HttpURLConnection connection = null;
        try {
            StringBuilder urlBuilder = new StringBuilder(SEARCH_URL)
                    .append("?track_name=").append(encode(queryVariant.title));
            if (!isBlank(queryVariant.artist)) {
                urlBuilder.append("&artist_name=").append(encode(queryVariant.artist));
            }
            if (!isBlank(queryVariant.album)) {
                urlBuilder.append("&album_name=").append(encode(queryVariant.album));
            }

            connection = openConnection(urlBuilder.toString());
            if (!isSuccess(connection.getResponseCode())) {
                return null;
            }

            JSONArray results = new JSONArray(readString(connection.getInputStream()));
            CandidateMatch bestMatch = null;
            for (int i = 0; i < results.length(); i++) {
                JSONObject candidate = results.optJSONObject(i);
                if (candidate == null) {
                    continue;
                }
                PhoneLyricsResult parsedResult = parseLyricsResult(trackKey, candidate, "LRCLIB search");
                if (parsedResult == null) {
                    continue;
                }
                CandidateScore score = scoreCandidate(candidate, parsedResult, queryVariant, durationMs);
                if (!passesConfidenceGate(score, queryVariant)) {
                    continue;
                }
                bestMatch = chooseBetter(bestMatch, new CandidateMatch(parsedResult, score));
            }
            return bestMatch;
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

    private PhoneLyricsResult parseLyricsResult(String trackKey, JSONObject object, String sourceLabel) {
        String syncedLyrics = object.optString("syncedLyrics", "");
        if (!isBlank(syncedLyrics)) {
            List<RemoteLyricLine> lines = PhoneLrcParser.parseSyncedLyrics(syncedLyrics);
            if (!lines.isEmpty()) {
                return new PhoneLyricsResult(trackKey, sourceLabel, true, lines);
            }
        }

        String plainLyrics = object.optString("plainLyrics", "");
        if (!isBlank(plainLyrics)) {
            List<RemoteLyricLine> lines = PhoneLrcParser.parsePlainLyrics(plainLyrics);
            if (!lines.isEmpty()) {
                return new PhoneLyricsResult(trackKey, sourceLabel, false, lines);
            }
        }
        return null;
    }

    private CandidateScore scoreCandidate(JSONObject candidate, PhoneLyricsResult result, QueryVariant queryVariant, long durationMs) {
        int score = result.synced ? 30 : 8;
        int titleMatchLevel = matchLevel(queryVariant.title, candidate.optString("trackName", ""));
        int artistMatchLevel = matchLevel(queryVariant.artist, candidate.optString("artistName", ""));
        int albumMatchLevel = matchLevel(queryVariant.album, candidate.optString("albumName", ""));

        score += scoreForMatchLevel(titleMatchLevel, 10, 25);
        score += scoreForMatchLevel(artistMatchLevel, 8, 20);
        score += scoreForMatchLevel(albumMatchLevel, 4, 10);

        int durationMatchLevel = 0;
        double candidateDurationSeconds = candidate.optDouble("duration", -1.0d);
        if (durationMs > 0L && candidateDurationSeconds > 0d) {
            long deltaMs = Math.abs(durationMs - Math.round(candidateDurationSeconds * 1000d));
            if (deltaMs <= 3000L) {
                score += 15;
                durationMatchLevel = 2;
            } else if (deltaMs <= 10000L) {
                score += 8;
                durationMatchLevel = 1;
            }
        }
        return new CandidateScore(score, titleMatchLevel, artistMatchLevel, durationMatchLevel, result.synced);
    }

    private boolean passesConfidenceGate(CandidateScore candidateScore, QueryVariant queryVariant) {
        if (candidateScore == null) {
            return false;
        }
        if (candidateScore.titleMatchLevel == 0) {
            return false;
        }
        if (!queryVariant.hasArtist()) {
            if (candidateScore.titleMatchLevel != 2 || candidateScore.durationMatchLevel == 0) {
                return false;
            }
            if (!candidateScore.synced && candidateScore.durationMatchLevel < 2) {
                return false;
            }
            return candidateScore.totalScore >= (candidateScore.synced ? 48 : 56);
        }
        if (candidateScore.artistMatchLevel == 0) {
            return false;
        }
        if (candidateScore.titleMatchLevel == 1
                && candidateScore.artistMatchLevel != 2
                && candidateScore.durationMatchLevel == 0) {
            return false;
        }
        if (!candidateScore.synced
                && candidateScore.titleMatchLevel != 2
                && candidateScore.durationMatchLevel == 0) {
            return false;
        }
        return candidateScore.totalScore >= (candidateScore.synced ? 50 : 58);
    }

    private int matchLevel(String expected, String candidate) {
        String normalizedExpected = normalize(expected);
        String normalizedCandidate = normalize(candidate);
        if (normalizedExpected.isEmpty() || normalizedCandidate.isEmpty()) {
            return 0;
        }
        if (normalizedExpected.equals(normalizedCandidate)) {
            return 2;
        }
        if (normalizedCandidate.contains(normalizedExpected) || normalizedExpected.contains(normalizedCandidate)) {
            return 1;
        }
        return 0;
    }

    private int scoreForMatchLevel(int matchLevel, int containsScore, int exactScore) {
        if (matchLevel == 2) {
            return exactScore;
        }
        if (matchLevel == 1) {
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
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
        try {
            StringBuilder builder = new StringBuilder();
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

    private static final class CandidateMatch {
        private final PhoneLyricsResult result;
        private final CandidateScore score;
        private final int effectiveScore;

        private CandidateMatch(PhoneLyricsResult result, CandidateScore score) {
            this.result = result;
            this.score = score;
            this.effectiveScore = score.totalScore + (result.synced ? 18 : 0);
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

        private boolean canLookupExactly() {
            return !isBlank(title) && !isBlank(artist);
        }

        private boolean canSearch() {
            return !isBlank(title);
        }

        private boolean hasArtist() {
            return !isBlank(artist);
        }

        private QueryVariant toCleanedVariant() {
            return new QueryVariant(
                    cleanTitle(title),
                    cleanArtist(artist),
                    cleanAlbum(album)
            );
        }

        private QueryVariant toFirstArtistVariant() {
            return new QueryVariant(title, firstArtistOnly(artist), album);
        }

        private QueryVariant toTitleOnlyVariant() {
            return new QueryVariant(title, "", "");
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

    private CandidateMatch chooseBetter(CandidateMatch currentBest, CandidateMatch candidate) {
        if (candidate == null) {
            return currentBest;
        }
        if (currentBest == null) {
            return candidate;
        }
        if (candidate.effectiveScore != currentBest.effectiveScore) {
            return candidate.effectiveScore > currentBest.effectiveScore ? candidate : currentBest;
        }
        if (candidate.score.totalScore != currentBest.score.totalScore) {
            return candidate.score.totalScore > currentBest.score.totalScore ? candidate : currentBest;
        }
        if (candidate.result.synced != currentBest.result.synced) {
            return candidate.result.synced ? candidate : currentBest;
        }
        return currentBest;
    }

    private boolean isStrongSyncedWinner(CandidateMatch candidate) {
        return candidate != null
                && candidate.result.synced
                && candidate.score.titleMatchLevel == 2
                && candidate.score.artistMatchLevel == 2
                && candidate.score.totalScore >= 70;
    }

    private QueryVariant[] uniqueVariants(QueryVariant... variants) {
        List<QueryVariant> unique = new java.util.ArrayList<QueryVariant>();
        if (variants == null) {
            return new QueryVariant[0];
        }
        for (QueryVariant candidate : variants) {
            if (candidate == null) {
                continue;
            }
            boolean duplicate = false;
            for (QueryVariant existing : unique) {
                if (existing.isEquivalentTo(candidate)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                unique.add(candidate);
            }
        }
        return unique.toArray(new QueryVariant[0]);
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String cleanTitle(String title) {
        String cleaned = safeTrim(title);
        cleaned = cleaned.replaceAll("\\s*[\\(\\[\\u3010\\uFF08][^\\)\\]\\u3011\\uFF09]{0,40}(live|remaster|version|ver\\.?|mix|edit|cover|karaoke|theme|ost)[^\\)\\]\\u3011\\uFF09]*[\\)\\]\\u3011\\uFF09]\\s*$", "");
        cleaned = cleaned.replaceAll("\\s+-\\s+.*$", "");
        cleaned = cleaned.replaceAll("\\s+(feat\\.?|ft\\.?|with)\\s+.*$", "");
        return safeTrim(cleaned);
    }

    private static String cleanArtist(String artist) {
        String cleaned = safeTrim(artist);
        cleaned = cleaned.replaceAll("\\s+(feat\\.?|ft\\.?|with)\\s+.*$", "");
        cleaned = firstArtistOnly(cleaned);
        return safeTrim(cleaned);
    }

    private static String cleanAlbum(String album) {
        return safeTrim(album);
    }

    private static String firstArtistOnly(String artist) {
        String value = safeTrim(artist);
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        String[] separators = new String[]{
                ",",
                "\uFF0C",
                "\u3001",
                "/",
                "&",
                "\u2022",
                " feat",
                " ft",
                " x ",
                " X "
        };
        for (String separator : separators) {
            int index = value.indexOf(separator);
            if (index > 0) {
                return safeTrim(value.substring(0, index));
            }
        }
        return value;
    }
}
