package de.grafkakashi.livescript.engine;

import de.grafkakashi.livescript.Config;
import de.grafkakashi.livescript.LiveScriptMod;
import de.grafkakashi.livescript.api.EventBindings;
import net.minecraft.server.MinecraftServer;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LuaJ-based Lua engine. Uses a debug hook for timeout enforcement, similar in
 * spirit to ComputerCraft's approach but lighter (no full LuaMachine abstraction).
 *
 * Like {@link JsEngine}, scripts run synchronously on whatever thread invoked them.
 * For server-side scripts, that's the server thread.
 */
public class LuaEngine implements ScriptEngine {
    private final MinecraftServer server;
    private final Map<String, ScriptContext> contexts = new ConcurrentHashMap<>();

    public LuaEngine(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public ScriptType type() { return ScriptType.LUA; }

    @Override
    public ScriptResult execute(String scriptId, String source) {
        unregister(scriptId);

        ScriptContext scriptCtx = new ScriptContext(scriptId, server);
        contexts.put(scriptId, scriptCtx);

        long start = System.currentTimeMillis();
        long deadline = start + Config.SCRIPT_TIMEOUT_MS.get();

        try {
            // JsePlatform.standardGlobals() includes os, io, package etc.
            // For sandboxed mode we'd use debugGlobals() and strip dangerous libs —
            // gated by the same security config.
            Globals globals = Config.ALLOW_FILESYSTEM_ACCESS.get()
                    ? JsePlatform.standardGlobals()
                    : JsePlatform.debugGlobals();

            if (!Config.ALLOW_FILESYSTEM_ACCESS.get()) {
                globals.set("io", LuaValue.NIL);
                globals.set("os", stripDangerousOs(globals.get("os")));
            }
            if (!Config.ALLOW_NETWORK_ACCESS.get()) {
                // LuaJ has no built-in net library, but we strip socket if it's been added.
                globals.set("socket", LuaValue.NIL);
            }

            EventBindings.installLua(globals, scriptCtx);

            // Install timeout hook: fires every N instructions and bails if deadline exceeded
            installTimeoutHook(globals, deadline);

            LuaValue chunk = globals.load(source, scriptId);
            LuaValue result = chunk.call();

            long duration = System.currentTimeMillis() - start;
            String output = scriptCtx.drainOutput();
            if (!result.isnil()) {
                output += "=> " + result.tojstring() + "\n";
            }
            return ScriptResult.ok(output, duration);
        } catch (LuaError e) {
            long duration = System.currentTimeMillis() - start;
            String output = scriptCtx.drainOutput();
            scriptCtx.tearDown();
            contexts.remove(scriptId);
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            return ScriptResult.fail(output + msg, duration);
        } catch (Throwable t) {
            long duration = System.currentTimeMillis() - start;
            LiveScriptMod.LOGGER.debug("Lua script {} failed", scriptId, t);
            String output = scriptCtx.drainOutput();
            scriptCtx.tearDown();
            contexts.remove(scriptId);
            return ScriptResult.fail(output + t.getClass().getSimpleName() + ": " + t.getMessage(),
                    duration);
        }
    }

    private void installTimeoutHook(Globals globals, long deadline) {
        // debug.sethook(hook, mask [, count])
        // Signature: first arg is the hook function (no thread), second arg is
        // the mask string, third optional arg is instruction count.
        //
        // We were previously passing 'globals' as the first arg — that's a
        // LuaTable, not a thread or function, so LuaJ errored with
        // "function expected, got table" BEFORE any user code ran. The user
        // saw a confusing "Line 59" reference even though step 1 of the
        // script never executed.
        LuaValue debug = globals.get("debug");
        if (debug.isnil()) return; // standardGlobals lacks debug; debugGlobals has it

        LuaValue hookFn = new OneArgFunction() {
            @Override public LuaValue call(LuaValue arg) {
                if (System.currentTimeMillis() > deadline) {
                    throw new LuaError("timeout after " + Config.SCRIPT_TIMEOUT_MS.get() + "ms");
                }
                return LuaValue.NIL;
            }
        };
        // mask="" (no per-line/call/return events), count=10000 (fire every
        // 10k bytecode instructions). That's frequent enough to enforce a
        // few-second timeout without measurable overhead on normal scripts.
        debug.get("sethook").invoke(new LuaValue[]{
                hookFn, LuaValue.valueOf(""), LuaValue.valueOf(10_000)
        });
    }

    private LuaValue stripDangerousOs(LuaValue os) {
        if (os.isnil()) return os;
        // Keep clock/time/date, drop execute/exit/remove/rename/getenv/tmpname
        os.set("execute", LuaValue.NIL);
        os.set("exit", LuaValue.NIL);
        os.set("remove", LuaValue.NIL);
        os.set("rename", LuaValue.NIL);
        os.set("getenv", LuaValue.NIL);
        os.set("tmpname", LuaValue.NIL);
        return os;
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
}
