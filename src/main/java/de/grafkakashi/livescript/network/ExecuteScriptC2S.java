package de.grafkakashi.livescript.network;

import de.grafkakashi.livescript.LiveScriptMod;
import de.grafkakashi.livescript.engine.ScriptManager;
import de.grafkakashi.livescript.engine.ScriptResult;
import de.grafkakashi.livescript.engine.ScriptType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Send "run the in-memory buffer" — does NOT save first. Lets the user iterate
 * without polluting on-disk history with broken intermediate versions.
 */
public record ExecuteScriptC2S(String path, String content) implements CustomPacketPayload {
    public static final Type<ExecuteScriptC2S> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(LiveScriptMod.MOD_ID, "execute_script"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ExecuteScriptC2S> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ExecuteScriptC2S::path,
            ByteBufCodecs.STRING_UTF8, ExecuteScriptC2S::content,
            ExecuteScriptC2S::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(ExecuteScriptC2S pkt, IPayloadContext ctx) {
        if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
        if (!sp.hasPermissions(de.grafkakashi.livescript.Config.REQUIRED_PERMISSION_LEVEL.get())) return;

        ScriptType type = ScriptType.fromExtension(pkt.path);
        if (type == null) {
            // .json files are valid in the editor (you can save them) but
            // they aren't scripts, so Run is a no-op for them — be specific
            // about it instead of saying "unknown extension".
            String msg = pkt.path.toLowerCase(java.util.Locale.ROOT).endsWith(".json")
                    ? "JSON files aren't executable — only .js and .lua scripts can be run"
                    : "unknown extension — must be .js or .lua";
            PacketDistributor.sendToPlayer(sp, new ExecutionResultS2C(
                    pkt.path, false, msg, 0));
            return;
        }
        // Lint before run — same logic as save. Catches the obvious cases up front.
        String lintError = de.grafkakashi.livescript.engine.Linter.lint(type, pkt.path, pkt.content);
        if (lintError != null) {
            PacketDistributor.sendToPlayer(sp, new ExecutionResultS2C(
                    pkt.path, false, "syntax error — " + lintError, 0));
            return;
        }

        sp.getServer().execute(() -> {
            ScriptResult r = ScriptManager.get().execute(pkt.path, type, pkt.content);
            PacketDistributor.sendToPlayer(sp, new ExecutionResultS2C(
                    pkt.path, r.success(),
                    r.success() ? r.output() : r.error(), r.durationMs()));
        });
    }
}
