package de.grafkakashi.livescript.network;

import de.grafkakashi.livescript.LiveScriptMod;
import de.grafkakashi.livescript.storage.ScriptStorage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record OpenEditorC2S() implements CustomPacketPayload {
    public static final Type<OpenEditorC2S> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(LiveScriptMod.MOD_ID, "open_editor"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenEditorC2S> CODEC = StreamCodec.unit(new OpenEditorC2S());

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(OpenEditorC2S pkt, IPayloadContext ctx) {
        if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
        if (!sp.hasPermissions(de.grafkakashi.livescript.Config.REQUIRED_PERMISSION_LEVEL.get())) {
            sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§cLiveScript: permission denied"));
            return;
        }
        NetworkHandler.syncFileTreeTo(sp);
    }
}
