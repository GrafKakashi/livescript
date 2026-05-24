package de.grafkakashi.livescript.engine;

/**
 * Abstraction over a script runtime (Rhino for JS, LuaJ for Lua).
 *
 * Implementations MUST:
 *  - Respect the timeout in {@link de.grafkakashi.livescript.Config#SCRIPT_TIMEOUT_MS}
 *  - Capture stdout/stderr-equivalent output into the result
 *  - Never let exceptions escape — always return a ScriptResult
 *  - Clean up registered event listeners when {@link #unregister(String)} is called
 */
public interface ScriptEngine {
    ScriptType type();

    /**
     * Compile + run a script. The scriptId identifies it for hot-reload bookkeeping —
     * if a script with the same id was previously run, its listeners are removed first.
     */
    ScriptResult execute(String scriptId, String source);

    /**
     * Remove all listeners/state registered by a previously-run script.
     * Idempotent — safe to call on unknown ids.
     */
    void unregister(String scriptId);

    /** Drop all scripts. Called on /scripteditor unload-all or server stop. */
    void unregisterAll();
}
