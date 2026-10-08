package com.culpen.nes;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/** Reads only public, complete Pocket NES releases, including numbered betas. */
public final class ReleaseFeed {
    private static final String REPOSITORY_URL = "https://github.com/culpen90/nes";
    private static final String API_URL = "https://api.github.com/repos/culpen90/nes/releases";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 20;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int REQUEST_TIMEOUT_MS = 10_000;
    private static final long FETCH_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60);

    public static final class Release {
        public final String tag;
        public final String version;
        public final String url;

        private Release(String tag, ReleaseVersion version, String url) {
            this.tag = tag;
            this.version = version.versionName();
            this.url = url;
        }
    }

    /** Allows the scheduler to respect GitHub's primary and secondary rate limits. */
    public static final class RateLimitedException extends IOException {
        public final long retryAtMillis;

        private RateLimitedException(long retryAtMillis) {
            super("Release API rate limited");
            this.retryAtMillis = retryAtMillis;
        }
    }

    /**
     * Returns every newer published version in ascending version order.
     * Partial feeds, HTTP errors, and pagination limits fail the whole check.
     */
    public List<Release> fetchNewer(String installedVersion) throws IOException {
        ReleaseVersion installed = requireInstalledVersion(installedVersion);
        TreeMap<ReleaseVersion, Release> newer = new TreeMap<>();
        long deadline = System.nanoTime() + FETCH_TIMEOUT_NANOS;
        for (int page = 1; page <= MAX_PAGES; page++) {
            JSONArray releases = parseArray(fetchPage(page, deadline));
            if (releases.length() > PAGE_SIZE) throw new IOException("Unexpected release page size");
            addNewer(releases, installed, newer);
            if (releases.length() < PAGE_SIZE) return immutableList(newer);
        }
        // GitHub does not promise semantic-version ordering. Reading a prefix
        // cannot establish that all newer versions have been collected.
        throw new IOException("Release pagination limit reached");
    }

    /** Parses a complete fixture or saved feed with the same publication checks. */
    public static List<Release> parse(String json, String installedVersion) throws IOException {
        ReleaseVersion installed = requireInstalledVersion(installedVersion);
        TreeMap<ReleaseVersion, Release> newer = new TreeMap<>();
        addNewer(parseArray(json), installed, newer);
        return immutableList(newer);
    }

    private static ReleaseVersion requireInstalledVersion(String value) throws IOException {
        ReleaseVersion installed = ReleaseVersion.parse(value);
        if (installed == null) throw new IOException("Invalid installed release version");
        return installed;
    }

    private static List<Release> immutableList(TreeMap<ReleaseVersion, Release> releases) {
        return Collections.unmodifiableList(new ArrayList<>(releases.values()));
    }

    private static JSONArray parseArray(String json) throws IOException {
        if (json == null || json.length() > MAX_RESPONSE_BYTES
                || json.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
            throw new IOException("Release response is too large or missing");
        }
        try {
            JSONTokener tokens = new JSONTokener(json);
            Object value = tokens.nextValue();
            if (!(value instanceof JSONArray) || tokens.nextClean() != 0) {
                throw new IOException("Expected a release array");
            }
            return (JSONArray) value;
        } catch (JSONException invalid) {
            throw new IOException("Invalid release response", invalid);
        }
    }

    private static void addNewer(JSONArray releases, ReleaseVersion installed,
                                 TreeMap<ReleaseVersion, Release> newer) {
        for (int i = 0; i < releases.length(); i++) {
            JSONObject entry = releases.optJSONObject(i);
            if (entry == null || !Boolean.FALSE.equals(entry.opt("draft"))) continue;
            Object tagValue = entry.opt("tag_name");
            if (!(tagValue instanceof String)) continue;
            String tag = (String) tagValue;
            ReleaseVersion version = ReleaseVersion.parse(tag);
            if (version == null || version.compareTo(installed) <= 0) continue;
            if (!Boolean.valueOf(version.isBeta()).equals(entry.opt("prerelease"))) continue;
            String url = REPOSITORY_URL + "/releases/tag/" + tag;
            if (!url.equals(entry.opt("html_url")) || !isPublished(entry.opt("published_at"))) continue;
            if (!hasUploadedApk(entry.optJSONArray("assets"), tag, version.versionName())) continue;
            newer.putIfAbsent(version, new Release(tag, version, url));
        }
    }

    private static boolean isPublished(Object value) {
        if (!(value instanceof String) || ((String) value).length() > 64) return false;
        try {
            Instant.parse((String) value);
            return true;
        } catch (DateTimeParseException invalid) {
            return false;
        }
    }

    private static boolean hasUploadedApk(JSONArray assets, String tag, String version) {
        if (assets == null) return false;
        String name = "pocket-nes-" + version + ".apk";
        String download = REPOSITORY_URL + "/releases/download/" + tag + "/" + name;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null || !name.equals(asset.opt("name"))
                    || !"uploaded".equals(asset.opt("state"))
                    || !download.equals(asset.opt("browser_download_url"))) continue;
            Object size = asset.opt("size");
            if ((size instanceof Integer || size instanceof Long) && ((Number) size).longValue() > 0) {
                return true;
            }
        }
        return false;
    }

    private static String fetchPage(int page, long deadline) throws IOException {
        HttpURLConnection connection = (HttpURLConnection)
                new URL(API_URL + "?per_page=" + PAGE_SIZE + "&page=" + page).openConnection();
        try {
            connection.setConnectTimeout(remainingTimeout(deadline));
            connection.setReadTimeout(remainingTimeout(deadline));
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setRequestProperty("User-Agent", "Pocket-NES-release-notifications");
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_FORBIDDEN || status == 429) {
                throw new RateLimitedException(retryAtMillis(connection.getHeaderField("Retry-After"),
                        connection.getHeaderField("X-RateLimit-Remaining"),
                        connection.getHeaderField("X-RateLimit-Reset"), System.currentTimeMillis()));
            }
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("Release HTTP status " + status);
            if (connection.getContentLengthLong() > MAX_RESPONSE_BYTES) {
                throw new IOException("Release response is too large");
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                while (true) {
                    connection.setReadTimeout(remainingTimeout(deadline));
                    int count = input.read(buffer);
                    if (count == -1) break;
                    if (output.size() + count > MAX_RESPONSE_BYTES) {
                        throw new IOException("Release response is too large");
                    }
                    output.write(buffer, 0, count);
                }
                remainingTimeout(deadline);
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(output.toByteArray())).toString();
            }
        } finally {
            connection.disconnect();
        }
    }

    static long retryAtMillis(String retryAfter, String remaining, String resetAt, long now) {
        long retryAt = now + TimeUnit.MINUTES.toMillis(1);
        if (retryAfter != null) {
            try {
                long seconds = Long.parseLong(retryAfter);
                if (seconds >= 0) {
                    retryAt = Math.max(retryAt, Math.addExact(now, Math.multiplyExact(seconds, 1000)));
                }
            } catch (NumberFormatException | ArithmeticException invalid) {
                try {
                    retryAt = Math.max(retryAt, ZonedDateTime.parse(retryAfter,
                            DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli());
                } catch (DateTimeParseException | ArithmeticException ignored) {
                    // Missing or malformed headers still require a delay.
                }
            }
        }
        if ("0".equals(remaining)) {
            try {
                long reset = Long.parseLong(resetAt);
                if (reset >= 0) retryAt = Math.max(retryAt, Math.multiplyExact(reset, 1000));
            } catch (NumberFormatException | ArithmeticException ignored) {
                // A secondary limit may have only Retry-After, or neither header.
            }
        }
        return retryAt;
    }

    private static int remainingTimeout(long deadline) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
            throw new IOException("Release check timed out or stopped");
        }
        return (int) Math.min(REQUEST_TIMEOUT_MS,
                Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
    }
}
