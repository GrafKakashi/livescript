package de.grafkakashi.livescript.api;

import de.grafkakashi.livescript.engine.ScriptContext;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

/**
 * APIs exposed to user scripts. Same surface for both languages —
 * a script written in JS and translated to Lua should behave the same.
 *
 * Bindings:
 *   - print(msg)              : write to the editor console
 *   - broadcast(msg)          : send a chat message to all players
 *   - server                  : MinecraftServer instance (advanced use)
 *   - on(eventName, handler)  : subscribe to a named event
 *   - cancel(event)           : cancel a cancellable event (call inside handler)
 *
 * Supported event names:
 *   ticks:
 *     - "server.tick"             : every server tick (warning: 20×/sec, hot path)
 *   player lifecycle:
 *     - "player.join"             : ServerPlayer joined (handler receives the player)
 *     - "player.leave"            : ServerPlayer left
 *     - "player.respawn"          : after death
 *     - "player.dimension_change" : nether/end portals
 *   chat:
 *     - "player.chat"             : ServerChatEvent — cancel/replace messages
 *   death + damage:
 *     - "player.death"            : LivingDeathEvent filtered to players
 *     - "entity.death"            : LivingDeathEvent for any mob
 *     - "entity.damage"           : LivingIncomingDamageEvent — cancel to absorb damage
 *   blocks:
 *     - "block.break"             : BlockEvent.BreakEvent — cancel to deny
 *     - "block.place"             : BlockEvent.EntityPlaceEvent — cancel to deny
 *   items + interactions:
 *     - "item.use_block"          : right-click an item on a block
 *     - "item.use"                : right-click an item in air (food, throwables)
 *     - "item.craft"              : item taken from a crafting result slot
 *     - "item.smelt"              : item taken from a furnace result slot
 *     - "item.pickup"             : item picked up off the ground
 *   entities:
 *     - "entity.spawn"            : EntityJoinLevelEvent — cancel to deny spawn
 *     - "mob.target"              : LivingChangeTargetEvent — mob aggro switch
 *   world:
 *     - "explosion.start"         : before the boom; cancel to prevent
 *     - "explosion.detonate"      : after the boom; modify affected blocks/entities
 */
public final class EventBindings {

    private EventBindings() {}

    /**
     * Map a script-level event name to the actual NeoForge event class and
     * subscribe with a normalized handler. Shared between JS and Lua so both
     * languages get the same surface — adding an event here lights it up for
     * both immediately.
     *
     * The handler receives a plain {@code Object} which is whatever payload
     * makes most sense for the event (usually the event itself; for
     * player.join/leave we unwrap to the Player since that's what scripts
     * actually want 99% of the time).
     */
    private static boolean subscribeByName(ScriptContext ctx, String eventName,
                                           java.util.function.Consumer<Object> call) {
        switch (eventName) {
            // ---- ticks ----
            case "server.tick" -> ctx.subscribe(ServerTickEvent.Post.class, EventPriority.NORMAL,
                    call::accept);

            // ---- player lifecycle ----
            case "player.join" -> ctx.subscribe(PlayerEvent.PlayerLoggedInEvent.class, EventPriority.NORMAL,
                    e -> call.accept(e.getEntity()));
            case "player.leave" -> ctx.subscribe(PlayerEvent.PlayerLoggedOutEvent.class, EventPriority.NORMAL,
                    e -> call.accept(e.getEntity()));
            case "player.respawn" -> ctx.subscribe(PlayerEvent.PlayerRespawnEvent.class, EventPriority.NORMAL,
                    call::accept);
            case "player.dimension_change" -> ctx.subscribe(
                    PlayerEvent.PlayerChangedDimensionEvent.class, EventPriority.NORMAL, call::accept);

            // ---- chat ----
            case "player.chat" -> ctx.subscribe(
                    net.neoforged.neoforge.event.ServerChatEvent.class, EventPriority.NORMAL,
                    call::accept);

            // ---- death + damage ----
            case "player.death" -> ctx.subscribe(
                    net.neoforged.neoforge.event.entity.living.LivingDeathEvent.class, EventPriority.NORMAL,
                    e -> { if (e.getEntity() instanceof net.minecraft.world.entity.player.Player) call.accept(e); });
            case "entity.death" -> ctx.subscribe(
                    net.neoforged.neoforge.event.entity.living.LivingDeathEvent.class, EventPriority.NORMAL,
                    call::accept);
            case "entity.damage" -> ctx.subscribe(
                    net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent.class, EventPriority.NORMAL,
                    call::accept);

            // ---- blocks ----
            case "block.break" -> ctx.subscribe(
                    net.neoforged.neoforge.event.level.BlockEvent.BreakEvent.class, EventPriority.NORMAL,
                    call::accept);
            case "block.place" -> ctx.subscribe(
                    net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent.class, EventPriority.NORMAL,
                    call::accept);

            // ---- items + interactions ----
            case "item.use_block" -> ctx.subscribe(
                    net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock.class,
                    EventPriority.NORMAL, call::accept);
            case "item.use" -> ctx.subscribe(
                    net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem.class,
                    EventPriority.NORMAL, call::accept);
            case "item.craft" -> ctx.subscribe(
                    PlayerEvent.ItemCraftedEvent.class, EventPriority.NORMAL, call::accept);
            case "item.smelt" -> ctx.subscribe(
                    PlayerEvent.ItemSmeltedEvent.class, EventPriority.NORMAL, call::accept);
            case "item.pickup" -> ctx.subscribe(
                    net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Post.class,
                    EventPriority.NORMAL, call::accept);

            // ---- entities ----
            case "entity.spawn" -> ctx.subscribe(
                    net.neoforged.neoforge.event.entity.EntityJoinLevelEvent.class, EventPriority.NORMAL,
                    call::accept);
            case "mob.target" -> ctx.subscribe(
                    net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent.class,
                    EventPriority.NORMAL, call::accept);

            // ---- world ----
            case "explosion.start" -> ctx.subscribe(
                    net.neoforged.neoforge.event.level.ExplosionEvent.Start.class, EventPriority.NORMAL,
                    call::accept);
            case "explosion.detonate" -> ctx.subscribe(
                    net.neoforged.neoforge.event.level.ExplosionEvent.Detonate.class, EventPriority.NORMAL,
                    call::accept);

            default -> {
                ctx.print("[warn] unknown event: " + eventName
                        + " (see README for supported names)");
                return false;
            }
        }
        return true;
    }

    // ============================================================
    //  JavaScript bindings (Rhino)
    // ============================================================

    public static void installJs(Context cx, Scriptable scope, ScriptContext ctx) {
        ScriptableObject.putProperty(scope, "print",
                new JsFunction1("print", args -> { ctx.print(args[0]); return null; }));

        ScriptableObject.putProperty(scope, "broadcast",
                new JsFunction1("broadcast", args -> {
                    broadcast(ctx, String.valueOf(args[0]));
                    return null;
                }));

        ScriptableObject.putProperty(scope, "server",
                Context.javaToJS(ctx.server(), scope));

        // KubeJS-style API objects
        ScriptableObject.putProperty(scope, "recipe",
                Context.javaToJS(new RecipeApi(ctx), scope));
        ScriptableObject.putProperty(scope, "tag",
                Context.javaToJS(new TagApi(ctx), scope));
        ScriptableObject.putProperty(scope, "item",
                Context.javaToJS(new ItemApi(ctx), scope));

        ScriptableObject.putProperty(scope, "on",
                new JsFunction2("on", (args) -> {
                    String eventName = String.valueOf(args[0]);
                    org.mozilla.javascript.Function handler = (org.mozilla.javascript.Function) args[1];
                    bindJsEvent(ctx, scope, eventName, handler);
                    return null;
                }));

        // cancel(event) — works on any ICancellableEvent. Scripts pass the event
        // object they received; we set canceled=true on it. Silently no-ops on
        // non-cancellable events, so handlers don't crash if the type changes.
        ScriptableObject.putProperty(scope, "cancel",
                new JsFunction1("cancel", args -> {
                    Object e = args[0];
                    if (e instanceof org.mozilla.javascript.NativeJavaObject njo) e = njo.unwrap();
                    if (e instanceof net.neoforged.bus.api.ICancellableEvent ce) ce.setCanceled(true);
                    return null;
                }));
    }

    private static void bindJsEvent(ScriptContext ctx, Scriptable scope,
                                    String eventName, org.mozilla.javascript.Function handler) {
        java.util.function.Consumer<Object> call = arg ->
                invokeJs(handler, scope, new Object[]{Context.javaToJS(arg, scope)});
        subscribeByName(ctx, eventName, call);
    }

    private static void invokeJs(org.mozilla.javascript.Function fn, Scriptable scope, Object[] args) {
        Context cx = Context.enter();
        try {
            fn.call(cx, scope, scope, args);
        } finally {
            Context.exit();
        }
    }

    // Tiny adapter classes — Rhino's BaseFunction is verbose; these keep callsites tidy.
    private static abstract class JsFunctionBase extends org.mozilla.javascript.BaseFunction {
        private final String name;
        JsFunctionBase(String name) { this.name = name; }
        @Override public String getFunctionName() { return name; }
    }
    private static class JsFunction1 extends JsFunctionBase {
        private final java.util.function.Function<Object[], Object> impl;
        JsFunction1(String name, java.util.function.Function<Object[], Object> impl) {
            super(name); this.impl = impl;
        }
        @Override public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
            return impl.apply(args);
        }
    }
    private static class JsFunction2 extends JsFunctionBase {
        private final java.util.function.Function<Object[], Object> impl;
        JsFunction2(String name, java.util.function.Function<Object[], Object> impl) {
            super(name); this.impl = impl;
        }
        @Override public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
            return impl.apply(args);
        }
    }

    // ============================================================
    //  Lua bindings (LuaJ)
    // ============================================================

    public static void installLua(Globals globals, ScriptContext ctx) {
        globals.set("print", new OneArgFunction() {
            @Override public LuaValue call(LuaValue arg) {
                ctx.print(arg.tojstring());
                return LuaValue.NIL;
            }
        });

        globals.set("broadcast", new OneArgFunction() {
            @Override public LuaValue call(LuaValue arg) {
                broadcast(ctx, arg.tojstring());
                return LuaValue.NIL;
            }
        });

        globals.set("server", org.luaj.vm2.lib.jse.CoerceJavaToLua.coerce(ctx.server()));
        globals.set("recipe", org.luaj.vm2.lib.jse.CoerceJavaToLua.coerce(new RecipeApi(ctx)));
        globals.set("tag",    org.luaj.vm2.lib.jse.CoerceJavaToLua.coerce(new TagApi(ctx)));
        globals.set("item",   org.luaj.vm2.lib.jse.CoerceJavaToLua.coerce(new ItemApi(ctx)));

        globals.set("on", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue eventName, LuaValue handler) {
                bindLuaEvent(ctx, eventName.tojstring(), handler);
                return LuaValue.NIL;
            }
        });

        globals.set("cancel", new OneArgFunction() {
            @Override public LuaValue call(LuaValue arg) {
                // CoerceJavaToLua wraps as JavaInstance; .touserdata() yields the original Java object
                Object e = arg.touserdata();
                if (e instanceof net.neoforged.bus.api.ICancellableEvent ce) ce.setCanceled(true);
                return LuaValue.NIL;
            }
        });
    }

    private static void bindLuaEvent(ScriptContext ctx, String eventName, LuaValue handler) {
        java.util.function.Consumer<Object> call = arg ->
                safeCallLua(handler, org.luaj.vm2.lib.jse.CoerceJavaToLua.coerce(arg));
        subscribeByName(ctx, eventName, call);
    }

    private static void safeCallLua(LuaValue fn, LuaValue arg) {
        try { fn.call(arg); } catch (Throwable ignored) { /* swallowed; ScriptContext guard logs */ }
    }

    // ============================================================
    //  Shared helpers
    // ============================================================

    private static void broadcast(ScriptContext ctx, String msg) {
        if (ctx.server() == null) return;
        Component component = Component.literal("[script] " + msg);
        for (ServerPlayer p : ctx.server().getPlayerList().getPlayers()) {
            p.sendSystemMessage(component);
        }
    }
}
