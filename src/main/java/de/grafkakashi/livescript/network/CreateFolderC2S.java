package de.grafkakashi.livescript.network;

import de.grafkakashi.livescript.LiveScriptMod;
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
 * Create an empty folder. After creation we re-sync the file tree so the new
 * folder shows up immediately.
 */
public record CreateFolderC2S(String path) implements CustomPacketPayload {
    public static final Type<CreateFolderC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(LiveScriptMod.MOD_ID, "create_folder"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CreateFolderC2S> CODEC =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, CreateFolderC2S::path, CreateFolderC2S::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(CreateFolderC2S pkt, IPayloadContext ctx) {
        if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
        if (!sp.hasPermissions(de.grafkakashi.livescript.Config.REQUIRED_PERMISSION_LEVEL.get())) return;

        sp.getServer().execute(() ->
                AsyncIO.run(() -> ScriptStorage.createFolder(pkt.path))
                        .whenComplete((u, err) -> {
                            if (err != null) {
                                Throwable cause = err.getCause() != null ? err.getCause() : err;
                                LiveScriptMod.LOGGER.warn("create folder failed: {}", pkt.path, cause);
                                PacketDistributor.sendToPlayer(sp, new ExecutionResultS2C(
                                        pkt.path, false,
                                        "create folder failed: " + cause.getMessage(), 0));
                                return;
                            }
                            NetworkHandler.syncFileTreeTo(sp);
                        }));
    }
}
