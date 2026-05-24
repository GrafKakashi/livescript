package de.grafkakashi.livescript.engine;

import de.grafkakashi.livescript.Config;
import de.grafkakashi.livescript.LiveScriptMod;
import de.grafkakashi.livescript.api.EventBindings;
import net.minecraft.server.MinecraftServer;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Scriptable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rhino-based JS engine. Key design points:
 *
 *  - Runs scripts on the SERVER THREAD (synchronously when invoked from a tick),
 *    so script state can touch the world safely. Long-running scripts are killed
 *    via Rhino's instruction observer.
 *  - Uses a {@link ClassShutter} to block reflection-based escape from the sandbox.
 *  - Each script gets its own scope, so globals don't leak between scripts.
 */
public class JsEngine implements ScriptEngine {
    private final MinecraftServer server;
    private final Map<String, ScriptContext> contexts = new ConcurrentHashMap<>();
    private final ContextFactory contextFactory;

    public JsEngine(MinecraftServer server) {
        this.server = server;
        this.contextFactory = new InterruptibleContextFactory();
    }

    @Override
    public ScriptType type() { return ScriptType.JS; }

    @Override
    public ScriptResult execute(String scriptId, String source) {
        // Hot-reload: drop any prior context for this id before re-running
        unregister(scriptId);

        ScriptContext scriptCtx = new ScriptContext(scriptId, server);
        contexts.put(scriptId, scriptCtx);

        long start = System.currentTimeMillis();
        Context cx = contextFactory.enterContext();
        try {
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setOptimizationLevel(-1);  // interpreted mode — allows instruction observer
            cx.setInstructionObserverThreshold(10_000);
            cx.setClassShutter(SANDBOX_SHUTTER);

            // Set the deadline as a thread-local so InterruptibleContextFactory.observeInstructionCount can see it
            DEADLINE.set(start + Config.SCRIPT_TIMEOUT_MS.get());

            Scriptable scope = cx.initStandardObjects();
            EventBindings.installJs(cx, scope, scriptCtx);

            Object result = cx.evaluateString(scope, source, scriptId, 1, null);

            long duration = System.currentTimeMillis() - start;
            String output = scriptCtx.drainOutput();
            if (result != null && !(result instanceof org.mozilla.javascript.Undefined)) {
                output += "=> " + Context.toString(result) + "\n";
            }
            return ScriptResult.ok(output, duration);
        } catch (ScriptTimeoutException e) {
            long duration = System.currentTimeMillis() - start;
            scriptCtx.tearDown();
            contexts.remove(scriptId);
            return ScriptResult.fail("Timeout after " + duration + "ms: " + e.getMessage(),
                    duration);
        } catch (Throwable t) {
            long duration = System.currentTimeMillis() - start;
            LiveScriptMod.LOGGER.debug("Script {} failed", scriptId, t);
            String output = scriptCtx.drainOutput();
            scriptCtx.tearDown();
            contexts.remove(scriptId);
            return ScriptResult.fail(output + t.getClass().getSimpleName() + ": " + t.getMessage(),
                    duration);
        } finally {
            DEADLINE.remove();
            Context.exit();
        }
    }

    @Override
    public void unregister(String scriptId) {
        ScriptContext old = contexts.remove(scriptId);
        if (old != null) old.tearDown();
    }

    @Override
    public void unregisterAll() {
        for (ScriptContext c : contexts.values()) c.tearDown();
        contexts.clear();
    }

    // ---- Timeout machinery ----
    static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();

    static class ScriptTimeoutException extends RuntimeException {
        ScriptTimeoutException(String msg) { super(msg); }
    }

    private static class InterruptibleContextFactory extends ContextFactory {
        @Override
        protected void observeInstructionCount(Context cx, int instructionCount) {
            Long deadline = DEADLINE.get();
            if (deadline != null && System.currentTimeMillis() > deadline) {
                throw new ScriptTimeoutException(
                        "exceeded " + Config.SCRIPT_TIMEOUT_MS.get() + "ms");
            }
        }
    }

    // ---- Sandbox ----
    private static final ClassShutter SANDBOX_SHUTTER = fullClassName -> {
        // Allow our own bindings + a curated subset of MC classes.
        // Block reflection and file/process access by default.
        if (fullClassName.startsWith("java.lang.reflect.")) return false;
        if (fullClassName.equals("java.lang.Runtime")) return false;
        if (fullClassName.equals("java.lang.ProcessBuilder")) return false;
        if (fullClassName.equals("java.lang.System")) return false;
        if (fullClassName.startsWith("java.io.File") && !Config.ALLOW_FILESYSTEM_ACCESS.get()) return false;
        if (fullClassName.startsWith("java.net.") && !Config.ALLOW_NETWORK_ACCESS.get()) return false;
        if (fullClassName.startsWith("java.nio.file.") && !Config.ALLOW_FILESYSTEM_ACCESS.get()) return false;
        return true;
    };
}
