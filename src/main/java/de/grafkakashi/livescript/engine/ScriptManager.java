package de.grafkakashi.livescript.engine;

import net.minecraft.server.MinecraftServer;

import java.util.EnumMap;
import java.util.Map;

/**
 * Central script lifecycle manager. Holds the per-language engines and
 * routes execute() calls to the right one based on the script's extension.
 *
 * Singleton on the server side.
 */
public class ScriptManager {
    private static final ScriptManager INSTANCE = new ScriptManager();

    private MinecraftServer server;
    private final Map<ScriptType, ScriptEngine> engines = new EnumMap<>(ScriptType.class);

    public static ScriptManager get() { return INSTANCE; }

    private ScriptManager() {}

    public void onServerStarting(MinecraftServer server) {
        this.server = server;
        engines.put(ScriptType.JS, new JsEngine(server));
        engines.put(ScriptType.LUA, new LuaEngine(server));
    }

    public void shutdown() {
        for (ScriptEngine e : engines.values()) e.unregisterAll();
        engines.clear();
        this.server = null;
    }

    public boolean isReady() { return server != null && !engines.isEmpty(); }

    public ScriptResult execute(String scriptId, ScriptType type, String source) {
        if (!isReady()) return ScriptResult.fail("server not ready", 0);
        ScriptEngine engine = engines.get(type);
        if (engine == null) return ScriptResult.fail("no engine for " + type, 0);
        return engine.execute(scriptId, source);
    }

    public void unregister(String scriptId) {
        for (ScriptEngine e : engines.values()) e.unregister(scriptId);
    }

    public MinecraftServer server() { return server; }
}
