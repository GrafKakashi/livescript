package de.grafkakashi.livescript.network;

import de.grafkakashi.livescript.LiveScriptMod;
import de.grafkakashi.livescript.engine.ScriptManager;
import de.grafkakashi.livescript.storage.ScriptStorage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Rename a script on disk. The new path goes through the same path-safety
 * checks as save/delete, so traversal attempts on either end are rejected.
 * Listener cleanup for the old path runs before the file move so we don't end
 * up with two registrations pointing at the same script.
 */
public record RenameScriptC2S(String fromPath, String toPath) implements CustomPacketPayload {
    public static final Type<RenameScriptC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(LiveScriptMod.MOD_ID, "rename_script"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RenameScriptC2S> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, RenameScriptC2S::fromPath,
                    ByteBufCodecs.STRING_UTF8, RenameScriptC2S::toPath,
                    RenameScriptC2S::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(RenameScriptC2S pkt, IPayloadContext ctx) {
        if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
        if (!sp.hasPermissions(de.grafkakashi.livescript.Config.REQUIRED_PERMISSION_LEVEL.get())) return;

        sp.getServer().execute(() -> {
            // Unregister listeners under the old id — the script must be re-run
            // under its new id to re-register. Saves us from having "ghost"
            // listeners keyed by a path that no longer exists.
            ScriptManager.get().unregister(pkt.fromPath);

            de.grafkakashi.livescript.storage.AsyncIO.run(
                    () -> ScriptStorage.move(pkt.fromPath, pkt.toPath))
                    .whenComplete((u, err) -> {
                        if (err != null) {
                            Throwable cause = err.getCause() != null ? err.getCause() : err;
                            LiveScriptMod.LOGGER.warn("rename failed {} -> {}",
                                    pkt.fromPath, pkt.toPath, cause);
                            PacketDistributor.sendToPlayer(sp, new ExecutionResultS2C(
                                    pkt.fromPath, false,
                                    "rename failed: " + cause.getMessage(), 0));
                            return;
                        }
                        // Refresh the file list so the new name shows up
                        NetworkHandler.syncFileTreeTo(sp);
                    });
        });
    }
}
