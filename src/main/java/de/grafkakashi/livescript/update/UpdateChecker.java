package de.grafkakashi.livescript.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.grafkakashi.livescript.LiveScriptMod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Polls cfwidget.com once on server start to check whether a newer version of
 * LiveScript is available on CurseForge. Result is cached in a static field
 * for {@link LiveScriptMod#onPlayerLoggedIn} to read at login time.
 *
 * <p>cfwidget.com is a long-running third-party JSON proxy in front of the
 * CurseForge API. We use it instead of the official CurseForge API because
 * the official one requires an API key per consumer, which would mean either
 * shipping a key in the JAR (bad — key gets revoked, mod stops working) or
 * asking every user to register one (bad UX).
 *
 * <p>The request runs on a background thread with a short timeout. On any
 * failure — network down, cfwidget down, JSON malformed, project not found —
 * we silently log and leave {@link #latestVersion} null. We will NOT spam
 * operators with "update check failed" messages; that's noise.
 *
 * <p>The version comparison is intentionally simple. CurseForge files are
 * named like {@code livescript-0.11.2-neoforge-1.21.1.jar}; we extract the
 * first dotted version after the slug. Any non-matching format = give up.
 */
public final class UpdateChecker {
    private UpdateChecker() {}

    /** Our CurseForge project ID — hard-coded; the mod isn't multi-tenant. */
    private static final int PROJECT_ID = 1552057;

    /** cfwidget JSON endpoint. */
    private static final String CFWIDGET_URL = "https://api.cfwidget.com/" + PROJECT_ID;

    /** Short HTTP timeout — we don't want server-start to hang on a slow API. */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(8);

    /** Extracts a semver-ish version from a CurseForge file name like
     *  {@code livescript-0.11.2-neoforge-1.21.1.jar}. Captures "0.11.2".
     *  Case-insensitive in case CurseForge ever normalises the case. */
    private static final Pattern VERSION_FROM_FILENAME =
            Pattern.compile("livescript-(\\d+\\.\\d+\\.\\d+)", Pattern.CASE_INSENSITIVE);

    /** The latest released version we found on CurseForge, or null if unknown. */
    private static volatile String latestVersion = null;

    /** True iff we've completed at least one check (success or failure). */
    private static volatile boolean checkCompleted = false;

    /**
     * Fire-and-forget background check. Safe to call multiple times — second
     * call replaces the previous future. Logged outcome is informational only.
     *
     * @param currentVersion the running mod's version (e.g. "0.11.2"). Compared
     *                       against the latest CurseForge version. If we can't
     *                       parse it, the comparison silently does nothing.
     */
    public static void checkAsync(String currentVersion) {
        CompletableFuture.runAsync(() -> {
            try {
                doCheck(currentVersion);
            } catch (Throwable t) {
                LiveScriptMod.LOGGER.debug("[update] check failed: {}", t.getMessage());
            } finally {
                checkCompleted = true;
            }
        });
    }

    private static void doCheck(String currentVersion) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(CFWIDGET_URL))
                .timeout(HTTP_TIMEOUT)
                .header("User-Agent", "LiveScript-Mod/" + currentVersion + " (NeoForge 1.21.1)")
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        // cfwidget conventions:
        //   200 = project data ready
        //   202 = first request, cached in the background, retry in a few seconds
        //   404 = project doesn't exist or is hidden
        if (resp.statusCode() == 202) {
            LiveScriptMod.LOGGER.debug("[update] cfwidget says 'try again later' (cold cache); skipping");
            return;
        }
        if (resp.statusCode() != 200) {
            LiveScriptMod.LOGGER.debug("[update] cfwidget returned HTTP {}", resp.statusCode());
            return;
        }

        String latest = parseLatestVersionFromJson(resp.body());
        if (latest == null) {
            LiveScriptMod.LOGGER.debug("[update] could not extract version from cfwidget response");
            return;
        }

        latestVersion = latest;
        if (isNewer(latest, currentVersion)) {
            LiveScriptMod.LOGGER.info("[update] update available: {} (you have {})", latest, currentVersion);
        } else {
            LiveScriptMod.LOGGER.info("[update] running latest version ({})", currentVersion);
        }
    }

    /**
     * Scan files[].name in the JSON for the highest version string we can
     * extract. cfwidget orders files newest-first but not always reliably, so
     * we compare all candidates rather than trusting position 0.
     */
    private static String parseLatestVersionFromJson(String json) {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return null;
            JsonObject obj = root.getAsJsonObject();
            JsonElement filesEl = obj.get("files");
            if (filesEl == null || !filesEl.isJsonArray()) return null;
            JsonArray files = filesEl.getAsJsonArray();

            String best = null;
            for (JsonElement e : files) {
                if (!e.isJsonObject()) continue;
                JsonObject f = e.getAsJsonObject();
                String name = f.has("name") ? f.get("name").getAsString() : null;
                if (name == null) continue;
                Matcher m = VERSION_FROM_FILENAME.matcher(name);
                if (!m.find()) continue;
                String candidate = m.group(1);
                if (best == null || compareVersions(candidate, best) > 0) {
                    best = candidate;
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Compare two dotted version strings ("0.11.2" vs "0.11.10"). Numeric per
     * component. Missing components count as zero ("1.0" < "1.0.1").
     * Returns negative/zero/positive like {@link Comparable#compareTo}.
     */
    static int compareVersions(String a, String b) {
        String[] aParts = a.split("\\.");
        String[] bParts = b.split("\\.");
        int n = Math.max(aParts.length, bParts.length);
        for (int i = 0; i < n; i++) {
            int ai = i < aParts.length ? parseSafe(aParts[i]) : 0;
            int bi = i < bParts.length ? parseSafe(bParts[i]) : 0;
            if (ai != bi) return Integer.compare(ai, bi);
        }
        return 0;
    }

    private static int parseSafe(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    /** True if {@code latest} is strictly newer than {@code current}. */
    public static boolean isNewer(String latest, String current) {
        if (latest == null || current == null) return false;
        return compareVersions(latest, current) > 0;
    }

    /**
     * @return the latest version string from CurseForge, or null if we haven't
     *         finished checking yet / the check failed.
     */
    public static String getLatestVersion() {
        return latestVersion;
    }

    /** @return true if our background check ran to completion (success or failure). */
    public static boolean isCheckCompleted() {
        return checkCompleted;
    }
}
