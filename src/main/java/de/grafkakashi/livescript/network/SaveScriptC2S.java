package de.grafkakashi.livescript.network;

import de.grafkakashi.livescript.Config;
import de.grafkakashi.livescript.LiveScriptMod;
import de.grafkakashi.livescript.engine.ScriptManager;
import de.grafkakashi.livescript.engine.ScriptResult;
import de.grafkakashi.livescript.engine.ScriptType;
import de.grafkakashi.livescript.storage.ScriptStorage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SaveScriptC2S(String path, String content) implements CustomPacketPayload {
    public static final Type<SaveScriptC2S> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(LiveScriptMod.MOD_ID, "save_script"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SaveScriptC2S> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SaveScriptC2S::path,
            ByteBufCodecs.STRING_UTF8, SaveScriptC2S::content,
            SaveScriptC2S::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SaveScriptC2S pkt, IPayloadContext ctx) {
        // Permission check on network thread is fine — just reads player flags.
        if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
        if (!sp.hasPermissions(Config.REQUIRED_PERMISSION_LEVEL.get())) {
            sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§cLiveScript: permission denied"));
            return;
        }

        // Lint first — cheap parse-only check, blocks save on syntax errors.
        // Lint runs on the network thread; both Rhino's parser and LuaJ's loader
        // are pure compute, no IO, no game-state touch.
        ScriptType type = ScriptType.fromExtension(pkt.path);
        if (type != null) {
            String lintError = de.grafkakashi.livescript.engine.Linter.lint(type, pkt.path, pkt.content);
            if (lintError != null) {
                PacketDistributor.sendToPlayer(sp, new ExecutionResultS2C(
                        pkt.path, false, "syntax error — " + lintError, 0));
                return;
            }
        }

        // Disk write on the IO executor — keeps the server thread responsive
        // even when saving the 500KB max-size files.
        de.grafkakashi.livescript.storage.AsyncIO.run(() -> {
            ScriptStorage.write(pkt.path, pkt.content);
        }).whenComplete((unused, err) -> {
            if (err != null) {
                LiveScriptMod.LOGGER.warn("save failed for {}", pkt.path, err);
                PacketDistributor.sendToPlayer(sp, new ExecutionResultS2C(
                        pkt.path, false, "save failed: " + err.getCause().getMessage(), 0));
                return;
            }
            // Save succeeded — notify client, then optionally auto-run on the server thread
            PacketDistributor.sendToPlayer(sp, new ExecutionResultS2C(
                    pkt.path, true, "saved (" + pkt.content.length() + " chars)", 0));
            // List refresh: also IO-bound (walks the directory tree)
            NetworkHandler.syncFileTreeTo(sp);

            if (Config.AUTO_RUN_ON_SAVE.get() && type != null) {
                // execute() MUST be on the server thread — it touches the live world.
                sp.getServer().execute(() -> {
                    ScriptResult r = ScriptManager.get().execute(pkt.path, type, pkt.content);
                    PacketDistributor.sendToPlayer(sp, new ExecutionResultS2C(
                            pkt.path, r.success(),
                            r.success() ? r.output() : r.error(), r.durationMs()));
                });
            }
        });
    }
}
