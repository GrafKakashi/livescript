package de.grafkakashi.livescript.network;

import de.grafkakashi.livescript.Config;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Optional;

/** Shared permission gate for every C2S payload — fail fast if a non-op tries to use the editor. */
public final class PermissionGate {
    private PermissionGate() {}

    /**
     * @return the player if allowed, empty otherwise. On denial, sends a chat message.
     */
    public static Optional<ServerPlayer> allow(IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return Optional.empty();
        int required = Config.REQUIRED_PERMISSION_LEVEL.get();
        if (!sp.hasPermissions(required)) {
            sp.sendSystemMessage(Component.literal("§cLiveScript: permission denied (need level " + required + ")"));
            return Optional.empty();
        }
        return Optional.of(sp);
    }
}
