package de.grafkakashi.livescript.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import de.grafkakashi.livescript.Config;
import de.grafkakashi.livescript.network.ScriptListSyncS2C;
import de.grafkakashi.livescript.storage.ScriptStorage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * /scripteditor — opens the in-game editor by pushing the current script list
 * to the requesting player. The client receives it and opens the GUI.
 *
 * We do NOT just open a Screen client-side via the command, because we want
 * the server to be the source of truth for the script list.
 */
public final class ScriptEditorCommand {
    private ScriptEditorCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                LiteralArgumentBuilder.<CommandSourceStack>literal("scripteditor")
                        .requires(src -> src.hasPermission(Config.REQUIRED_PERMISSION_LEVEL.get()))
                        .executes(ctx -> {
                            if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) {
                                ctx.getSource().sendFailure(Component.literal("Must be run by a player"));
                                return 0;
                            }
                            de.grafkakashi.livescript.network.NetworkHandler.syncFileTreeTo(sp);
                            sp.sendSystemMessage(Component.literal("§aLiveScript editor opened"));
                            return 1;
                        })
        );
    }
}
