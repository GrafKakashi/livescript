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

public record ScriptContentS2C(String path, String content) implements CustomPacketPayload {
    public static final Type<ScriptContentS2C> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(LiveScriptMod.MOD_ID, "content_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ScriptContentS2C> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ScriptContentS2C::path,
            ByteBufCodecs.STRING_UTF8, ScriptContentS2C::content,
            ScriptContentS2C::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(ScriptContentS2C pkt, IPayloadContext ctx) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ctx.enqueueWork(() -> ClientEntry.onScriptContent(pkt.path, pkt.content));
        }
    }
}
