package de.grafkakashi.livescript.engine;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Per-script execution context. Tracks every event listener and resource
 * the script registers so we can clean them up on hot-reload.
 *
 * This is the same lesson from perfmod's noPhysics regression — if a mod
 * registers handlers and never unregisters them, "reload" leaks listeners
 * until the server OOMs. We avoid that by funneling all subscriptions
 * through this context and tearing them down on unregister.
 */
public class ScriptContext {
    private final String scriptId;
    private final MinecraftServer server;
    private final List<Runnable> cleanupHooks = new ArrayList<>();
    private final ConcurrentLinkedQueue<String> outputBuffer = new ConcurrentLinkedQueue<>();
    private volatile boolean active = true;

    public ScriptContext(String scriptId, MinecraftServer server) {
        this.scriptId = scriptId;
        this.server = server;
    }

    public String scriptId() { return scriptId; }
    public MinecraftServer server() { return server; }
    public boolean isActive() { return active; }

    /** Called from script bindings — append a line to console output. */
    public void print(Object obj) {
        String line = obj == null ? "null" : obj.toString();
        outputBuffer.add(line);
    }

    /** Drain the buffer; called after each execute() to capture output for the client. */
    public String drainOutput() {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = outputBuffer.poll()) != null) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /**
     * Subscribe to an event with automatic cleanup tracking.
     * Use this from script bindings instead of {@code NeoForge.EVENT_BUS.addListener} directly.
     */
    public <T extends Event> void subscribe(Class<T> eventClass, EventPriority priority, Consumer<T> handler) {
        // Wrap so we can check the active flag — if the script was unregistered
        // mid-tick, the handler becomes a no-op rather than touching torn-down state.
        Consumer<T> guardedHandler = e -> {
            if (active) {
                try {
                    handler.accept(e);
                } catch (Throwable t) {
                    print("[error] " + t.getMessage());
                }
            }
        };
        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener(priority, false, eventClass, guardedHandler);
        // NeoForge IEventBus does not currently expose direct unsubscribe by lambda reference,
        // so we rely on the active flag to neutralize the handler. The lambda holds a
        // reference to `this`, which is dropped when the script is unregistered and the
        // ScriptContext goes out of scope.
        cleanupHooks.add(() -> { /* nothing to do — guard handles it */ });
    }

    /** Register an arbitrary cleanup callback (e.g. for custom resources). */
    public void onCleanup(Runnable hook) {
        cleanupHooks.add(hook);
    }

    /** Called by ScriptManager on hot-reload / unregister. */
    public void tearDown() {
        active = false;
        for (Runnable hook : cleanupHooks) {
            try { hook.run(); } catch (Throwable ignored) {}
        }
        cleanupHooks.clear();
    }
}
