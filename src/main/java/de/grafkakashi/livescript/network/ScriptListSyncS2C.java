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

import java.util.List;

/**
 * Sync the full file-tree to the client. Sends both scripts and folders so
 * empty directories can be shown in the tree view; the client builds its
 * tree from these two flat lists.
 *
 * Folder paths come with a trailing slash (e.g. "tools/").
 */
public record ScriptListSyncS2C(List<String> scripts, List<String> folders)
        implements CustomPacketPayload {

    public static final Type<ScriptListSyncS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(LiveScriptMod.MOD_ID, "list_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ScriptListSyncS2C> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ScriptListSyncS2C::scripts,
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ScriptListSyncS2C::folders,
                    ScriptListSyncS2C::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(ScriptListSyncS2C pkt, IPayloadContext ctx) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ctx.enqueueWork(() -> ClientEntry.onScriptList(pkt.scripts, pkt.folders));
        }
    }
}
