package de.grafkakashi.livescript.network;

import de.grafkakashi.livescript.LiveScriptMod;
import de.grafkakashi.livescript.client.ClientEntry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ExecutionResultS2C(String path, boolean success, String output, long durationMs) implements CustomPacketPayload {
    public static final Type<ExecutionResultS2C> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(LiveScriptMod.MOD_ID, "exec_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ExecutionResultS2C> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ExecutionResultS2C::path,
            ByteBufCodecs.BOOL,         ExecutionResultS2C::success,
            ByteBufCodecs.STRING_UTF8, ExecutionResultS2C::output,
            ByteBufCodecs.VAR_LONG,    ExecutionResultS2C::durationMs,
            ExecutionResultS2C::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(ExecutionResultS2C pkt, IPayloadContext ctx) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ctx.enqueueWork(() -> ClientEntry.onExecutionResult(pkt.path, pkt.success, pkt.output, pkt.durationMs));
        }
    }
}
