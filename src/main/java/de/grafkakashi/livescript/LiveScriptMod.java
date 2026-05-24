package de.grafkakashi.livescript;

import com.mojang.logging.LogUtils;
import de.grafkakashi.livescript.command.ScriptEditorCommand;
import de.grafkakashi.livescript.engine.ScriptManager;
import de.grafkakashi.livescript.network.NetworkHandler;
import de.grafkakashi.livescript.storage.ScriptStorage;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;

@Mod(LiveScriptMod.MOD_ID)
public class LiveScriptMod {
    public static final String MOD_ID = "livescript";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** The version string from the mod metadata, e.g. "0.11.2". Captured at
     *  mod-construction time so the update checker can compare against it. */
    private static String runningVersion = "0.0.0";

    public LiveScriptMod(IEventBus modBus, ModContainer container) {
        // Config
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // Snapshot the running version. The container's mod info exposes it
        // as an artifact version (from neoforge.mods.toml), already parsed and
        // ready. Use as plain string for the update-check comparison.
        runningVersion = container.getModInfo().getVersion().toString();

        // Custom items: read items.json at mod-construction time so the items
        // are registered before the item registry freezes. Server-restart is
        // required to add or modify items (Minecraft constraint, not ours).
        java.nio.file.Path configDir = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                .resolve(MOD_ID);
        java.nio.file.Path texturesDir = configDir.resolve("textures");
        try {
            java.nio.file.Files.createDirectories(texturesDir);  // also creates configDir
        } catch (java.io.IOException ignored) {
            /* directories may exist or be unwritable; nothing we can do here */
        }
        de.grafkakashi.livescript.items.CustomItemRegistry.bootstrap(modBus, configDir);

        // Custom item resource pack: synthesises models, textures, lang for the
        // registered items at pack-load time. Texture PNGs come from
        // config/livescript/textures/<id>.png; everything else is auto-generated.
        modBus.addListener(de.grafkakashi.livescript.items.CustomItemResourcePack::onAddPackFinders);

        // Mod bus events (setup)
        modBus.addListener(this::onRegisterPayloads);

        // Game bus events (runtime)
        NeoForge.EVENT_BUS.register(this);

        LOGGER.info("LiveScript loaded — JS + Lua scripting with in-game editor");
    }

    private void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        NetworkHandler.register(event);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ScriptEditorCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Initialize storage relative to the server/world directory
        var serverDir = event.getServer().getServerDirectory();
        ScriptStorage.init(serverDir.resolve("livescript"));
        ScriptManager.get().onServerStarting(event.getServer());
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        // Run @startup-annotated scripts AFTER the server is fully ready.
        // Doing this in ServerStartingEvent would race with the RecipeManager —
        // a startup script that touches recipes would find an empty list.
        de.grafkakashi.livescript.engine.StartupRunner.runAll();

        // Kick off a background CurseForge update check. Result is cached and
        // shown to operators on their first login this session. Failures are
        // silent — we don't want a spinning HTTP request to delay anything,
        // and we don't want noise if the user is offline.
        de.grafkakashi.livescript.update.UpdateChecker.checkAsync(runningVersion);
    }

    /**
     * UUIDs of operators who have already seen the @startup replay this session.
     * Cleared on every server start so a fresh @startup run gets shown again.
     *
     * <p>Why a Set instead of a flag-per-player? Because we don't want to store
     * state on the player entity — that would survive world reloads, which
     * we don't want (different startup outputs across server restarts).
     */
    private static final java.util.Set<java.util.UUID> replayShownThisSession =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        ScriptManager.get().shutdown();
        de.grafkakashi.livescript.storage.AsyncIO.shutdown();
        // Drop the seen-set so the next server start is a clean slate.
        // (Also belt-and-suspenders against this set growing unbounded if
        // someone keeps the server running for months.)
        replayShownThisSession.clear();
    }

    /**
     * When an operator joins, replay the captured @startup output as system
     * messages so they can see what happened on this server start. Non-ops
     * don't see anything — startup output is debug info, not gameplay text.
     *
     * <p>Each operator only gets the replay once per server session, tracked
     * by UUID. Re-logging in won't re-show it.
     *
     * <p>Permission level 2 = command-block / op tier. Anyone with /op gets
     * the replay. Single-player worlds always show it (player is implicitly op).
     */
    @SubscribeEvent
    public void onPlayerLoggedIn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
        // Server is the source of truth for op status — checking permissions
        // directly catches both vanilla op and command-block-level permissions.
        if (!sp.hasPermissions(2)) return;
        // Don't replay if this op already saw it this session.
        if (!replayShownThisSession.add(sp.getUUID())) return;

        java.util.List<String> lines = de.grafkakashi.livescript.engine.StartupRunner.capturedOutput();
        if (!lines.isEmpty()) {
            // Header + footer so the operator knows what they're looking at.
            sp.sendSystemMessage(net.minecraft.network.chat.Component.literal("§7--- LiveScript @startup output ---"));
            for (String line : lines) {
                sp.sendSystemMessage(net.minecraft.network.chat.Component.literal("§7" + line));
            }
            sp.sendSystemMessage(net.minecraft.network.chat.Component.literal("§7--- end ---"));
        }

        // Update notification: shown right after the startup replay so the
        // operator sees both pieces of LiveScript-related info together.
        // We only notify if a newer version is known — silent on parity,
        // failure, or check-not-yet-complete (operator joined fast).
        String latest = de.grafkakashi.livescript.update.UpdateChecker.getLatestVersion();
        if (latest != null &&
            de.grafkakashi.livescript.update.UpdateChecker.isNewer(latest, runningVersion)) {
            // §6 gold for visibility, §7 grey for the details — matches the
            // muted style of the rest of our system messages.
            sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§6[LiveScript] §eUpdate available: §a" + latest +
                    " §7(you have " + runningVersion + ")"));
            sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§7Get it at: legacy.curseforge.com/minecraft/mc-mods/livescript"));
        }
    }
}
