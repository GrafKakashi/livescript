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

public record DeleteScriptC2S(String path) implements CustomPacketPayload {
    public static final Type<DeleteScriptC2S> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(LiveScriptMod.MOD_ID, "delete_script"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DeleteScriptC2S> CODEC =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, DeleteScriptC2S::path, DeleteScriptC2S::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(DeleteScriptC2S pkt, IPayloadContext ctx) {
        if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
        if (!sp.hasPermissions(de.grafkakashi.livescript.Config.REQUIRED_PERMISSION_LEVEL.get())) return;

        // First stop listeners (server thread)
        sp.getServer().execute(() -> {
            ScriptManager.get().unregister(pkt.path);

            // Then drop file (IO thread)
            de.grafkakashi.livescript.storage.AsyncIO.run(() -> ScriptStorage.delete(pkt.path))
                    .whenComplete((u, err) -> {
                        if (err != null) {
                            LiveScriptMod.LOGGER.warn("delete failed for {}", pkt.path, err);
                            PacketDistributor.sendToPlayer(sp, new ExecutionResultS2C(
                                    pkt.path, false, "delete failed: " + err.getCause().getMessage(), 0));
                            return;
                        }
                        // Refresh the file list
                        NetworkHandler.syncFileTreeTo(sp);
                    });
        });
    }
}
