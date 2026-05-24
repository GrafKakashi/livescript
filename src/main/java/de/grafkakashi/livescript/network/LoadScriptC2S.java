package de.grafkakashi.livescript.network;

import de.grafkakashi.livescript.LiveScriptMod;
import de.grafkakashi.livescript.storage.ScriptStorage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record LoadScriptC2S(String path) implements CustomPacketPayload {
    public static final Type<LoadScriptC2S> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(LiveScriptMod.MOD_ID, "load_script"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LoadScriptC2S> CODEC =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, LoadScriptC2S::path, LoadScriptC2S::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(LoadScriptC2S pkt, IPayloadContext ctx) {
        if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
        if (!sp.hasPermissions(de.grafkakashi.livescript.Config.REQUIRED_PERMISSION_LEVEL.get())) return;

        de.grafkakashi.livescript.storage.AsyncIO.supply(() -> ScriptStorage.read(pkt.path))
                .whenComplete((content, err) -> {
                    if (err != null) {
                        PacketDistributor.sendToPlayer(sp, new ExecutionResultS2C(
                                pkt.path, false, "load failed: " + err.getCause().getMessage(), 0));
                    } else {
                        PacketDistributor.sendToPlayer(sp, new ScriptContentS2C(pkt.path, content));
                    }
                });
    }
}
