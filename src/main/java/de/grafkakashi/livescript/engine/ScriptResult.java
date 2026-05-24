package de.grafkakashi.livescript.engine;

/**
 * Result of running a script — never throws to the caller; failure is in-band.
 */
public record ScriptResult(boolean success, String output, String error, long durationMs) {
    public static ScriptResult ok(String output, long durationMs) {
        return new ScriptResult(true, output, "", durationMs);
    }
    public static ScriptResult fail(String error, long durationMs) {
        return new ScriptResult(false, "", error, durationMs);
    }
}
