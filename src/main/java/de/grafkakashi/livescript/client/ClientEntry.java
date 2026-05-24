package de.grafkakashi.livescript.client;

import de.grafkakashi.livescript.client.gui.ScriptEditorScreen;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * Client-side entrypoint called from S2C packet handlers via DistExecutor pattern.
 *
 * Splitting this into its own class avoids ClassNotFoundException on dedicated servers —
 * the network handler classes are loaded on both sides, but they only *reference*
 * ClientEntry inside a {@code if (FMLEnvironment.dist == Dist.CLIENT)} guard, so
 * the dedicated-server JVM never actually links this class.
 */
public final class ClientEntry {
    private ClientEntry() {}

    public static void onScriptList(List<String> scripts, List<String> folders) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ScriptEditorScreen editor) {
            editor.updateScriptList(scripts, folders);
        } else {
            // First time — open the editor
            mc.setScreen(new ScriptEditorScreen(scripts, folders));
        }
    }

    public static void onScriptContent(String path, String content) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ScriptEditorScreen editor) {
            editor.onContentReceived(path, content);
        }
    }

    public static void onExecutionResult(String path, boolean success, String output, long durationMs) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ScriptEditorScreen editor) {
            editor.onExecutionResult(path, success, output, durationMs);
        }
    }
}
