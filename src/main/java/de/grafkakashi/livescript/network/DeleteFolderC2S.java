package de.grafkakashi.livescript.network;

import de.grafkakashi.livescript.LiveScriptMod;
import de.grafkakashi.livescript.engine.ScriptManager;
import de.grafkakashi.livescript.storage.AsyncIO;
import de.grafkakashi.livescript.storage.ScriptStorage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Recursively delete a folder and every script inside it. Unregister every
 * affected script's listeners first so we don't leak handlers.
 *
 * The client should have already shown a confirm dialog before sending this.
 */
public record DeleteFolderC2S(String path) implements CustomPacketPayload {
    public static final Type<DeleteFolderC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(LiveScriptMod.MOD_ID, "delete_folder"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DeleteFolderC2S> CODEC =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, DeleteFolderC2S::path, DeleteFolderC2S::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(DeleteFolderC2S pkt, IPayloadContext ctx) {
        if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
        if (!sp.hasPermissions(de.grafkakashi.livescript.Config.REQUIRED_PERMISSION_LEVEL.get())) return;

        // Normalize: ensure we have a trailing slash for the prefix-match below
        final String prefix = pkt.path.endsWith("/") ? pkt.path : pkt.path + "/";

        sp.getServer().execute(() -> {
            // Unregister listeners for every script that lived under this folder
            for (String scriptId : ScriptStorage.list()) {
                if (scriptId.startsWith(prefix)) {
                    ScriptManager.get().unregister(scriptId);
                }
            }
            AsyncIO.run(() -> ScriptStorage.deleteFolder(pkt.path))
                    .whenComplete((u, err) -> {
                        if (err != null) {
                            Throwable cause = err.getCause() != null ? err.getCause() : err;
                            LiveScriptMod.LOGGER.warn("delete folder failed: {}", pkt.path, cause);
                            PacketDistributor.sendToPlayer(sp, new ExecutionResultS2C(
                                    pkt.path, false,
                                    "delete folder failed: " + cause.getMessage(), 0));
                            return;
                        }
                        NetworkHandler.syncFileTreeTo(sp);
                    });
        });
    }
}
