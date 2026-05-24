package de.grafkakashi.livescript.engine;

import de.grafkakashi.livescript.LiveScriptMod;
import de.grafkakashi.livescript.storage.ScriptStorage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans all stored scripts for a {@code @startup} annotation and runs them
 * after the server is fully started (ServerStartedEvent), in priority order.
 *
 * Annotation syntax (in a comment near the top of the file):
 *
 *   // @startup                  — runs at default priority 0
 *   // @startup priority=10      — higher priority runs first
 *   -- @startup priority=-5      — works in Lua too (lower runs later)
 *
 * The annotation must appear within the first 20 lines of the file. We don't
 * parse the full file just to look for it — that would defeat the purpose of
 * "cheap startup scan".
 *
 * Output from startup scripts goes to the server log only (no client is
 * connected yet at start). Any uncaught script error is logged but does NOT
 * prevent other startup scripts from running.
 */
public final class StartupRunner {
    private StartupRunner() {}

    /** Match "@startup" plus an optional "priority=NN" arg, anywhere on the line. */
    private static final Pattern ANNOTATION =
            Pattern.compile("@startup(?:\\s+priority\\s*=\\s*(-?\\d+))?");

    /** Cap scanning at this many lines per file to keep startup fast. */
    private static final int SCAN_LINE_LIMIT = 20;

    /** A discovered startup script with its parsed priority. */
    private record StartupEntry(String scriptId, ScriptType type, int priority) {}

    /**
     * Captured output from the most recent server startup, replayed to operators
     * who join. Reset every time {@link #runAll} fires (on each ServerStartedEvent).
     *
     * <p>Cap output size to keep this from growing unbounded if a script spams
     * print() — we keep ~100 lines max. Lines beyond that get dropped with a
     * summary marker. This is per-line, not per-byte, because chat-paging is
     * line-oriented.
     */
    private static final int MAX_REPLAY_LINES = 100;
    private static final List<String> startupReplay = new ArrayList<>();

    /** Snapshot of the captured output for player-join replay. */
    public static List<String> capturedOutput() {
        return List.copyOf(startupReplay);
    }

    public static void runAll() {
        // Fresh snapshot for this startup — replay is per-server-start, not cumulative.
        startupReplay.clear();

        List<StartupEntry> found = new ArrayList<>();
        for (String scriptId : ScriptStorage.list()) {
            ScriptType type = ScriptType.fromExtension(scriptId);
            if (type == null) continue;
            String source = readOrNull(scriptId);
            if (source == null) continue;
            Integer prio = findStartupPriority(source);
            if (prio != null) found.add(new StartupEntry(scriptId, type, prio));
        }

        if (found.isEmpty()) {
            LiveScriptMod.LOGGER.info("no @startup scripts found");
            return;
        }

        found.sort(Comparator
                .comparingInt(StartupEntry::priority).reversed()
                .thenComparing(StartupEntry::scriptId));

        LiveScriptMod.LOGGER.info("running {} @startup script(s)", found.size());
        for (StartupEntry e : found) {
            String source = readOrNull(e.scriptId);
            if (source == null) continue; // race with file deletion
            try {
                ScriptResult result = ScriptManager.get().execute(e.scriptId, e.type, source);
                if (result.success()) {
                    LiveScriptMod.LOGGER.info("[@startup p={}] {} OK ({}ms){}",
                            e.priority, e.scriptId, result.durationMs(),
                            result.output().isEmpty() ? "" : "\n" + result.output());
                    captureReplay(e.scriptId, result.output(), /*ok*/ true, null);
                } else {
                    LiveScriptMod.LOGGER.warn("[@startup p={}] {} FAILED: {}",
                            e.priority, e.scriptId, result.error());
                    captureReplay(e.scriptId, result.output(), /*ok*/ false, result.error());
                }
            } catch (Throwable t) {
                LiveScriptMod.LOGGER.warn("[@startup p={}] {} threw", e.priority, e.scriptId, t);
                captureReplay(e.scriptId, "", /*ok*/ false, t.getMessage());
            }
        }
    }

    /**
     * Append output lines from one startup script run to the replay buffer.
     * Stops appending if we've hit MAX_REPLAY_LINES, with a single tail marker
     * so the operator knows lines were dropped.
     */
    private static void captureReplay(String scriptId, String output, boolean ok, String error) {
        if (startupReplay.size() >= MAX_REPLAY_LINES) return;
        startupReplay.add(String.format("[@startup] %s %s",
                scriptId, ok ? "OK" : "FAILED"));
        if (!ok && error != null && !error.isEmpty()) {
            addLineCapped("  error: " + error);
        }
        if (output != null && !output.isEmpty()) {
            for (String line : output.split("\n", -1)) {
                if (line.isEmpty()) continue;
                if (!addLineCapped("  " + line)) {
                    addLineCapped("  ... (output truncated)");
                    return;
                }
            }
        }
    }

    /** Append a line if there's room. Returns false if the cap was hit. */
    private static boolean addLineCapped(String line) {
        if (startupReplay.size() >= MAX_REPLAY_LINES) return false;
        startupReplay.add(line);
        return true;
    }

    private static String readOrNull(String scriptId) {
        try {
            return ScriptStorage.read(scriptId);
        } catch (java.io.IOException e) {
            LiveScriptMod.LOGGER.warn("[@startup] failed to read {}: {}", scriptId, e.getMessage());
            return null;
        }
    }

    /**
     * Scan up to SCAN_LINE_LIMIT lines for the @startup annotation.
     * Returns the priority if found (default 0), null if not present.
     */
    private static Integer findStartupPriority(String source) {
        // Walk lines manually rather than split() — avoids allocating an array
        // for the full file when the annotation is almost always near the top.
        int lineCount = 0;
        int lineStart = 0;
        int len = source.length();
        for (int i = 0; i <= len; i++) {
            if (i == len || source.charAt(i) == '\n') {
                String line = source.substring(lineStart, i);
                Matcher m = ANNOTATION.matcher(line);
                if (m.find()) {
                    String prioStr = m.group(1);
                    return prioStr == null ? 0 : Integer.parseInt(prioStr);
                }
                if (++lineCount >= SCAN_LINE_LIMIT) return null;
                lineStart = i + 1;
            }
        }
        return null;
    }
}
