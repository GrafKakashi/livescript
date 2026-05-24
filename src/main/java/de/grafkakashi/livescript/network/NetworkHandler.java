package de.grafkakashi.livescript.network;

import de.grafkakashi.livescript.LiveScriptMod;
import de.grafkakashi.livescript.storage.AsyncIO;
import de.grafkakashi.livescript.storage.ScriptStorage;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers all client↔server payloads. Naming convention:
 *   C2S = client to server (player asks server to do something)
 *   S2C = server to client (server pushes data to player's UI)
 */
public final class NetworkHandler {
    public static final String PROTOCOL_VERSION = "2";

    private NetworkHandler() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(LiveScriptMod.MOD_ID).versioned(PROTOCOL_VERSION);

        // C2S
        registrar.playToServer(OpenEditorC2S.TYPE,         OpenEditorC2S.CODEC,         OpenEditorC2S::handle);
        registrar.playToServer(LoadScriptC2S.TYPE,         LoadScriptC2S.CODEC,         LoadScriptC2S::handle);
        registrar.playToServer(SaveScriptC2S.TYPE,         SaveScriptC2S.CODEC,         SaveScriptC2S::handle);
        registrar.playToServer(ExecuteScriptC2S.TYPE,      ExecuteScriptC2S.CODEC,      ExecuteScriptC2S::handle);
        registrar.playToServer(DeleteScriptC2S.TYPE,       DeleteScriptC2S.CODEC,       DeleteScriptC2S::handle);
        registrar.playToServer(RenameScriptC2S.TYPE,       RenameScriptC2S.CODEC,       RenameScriptC2S::handle);
        registrar.playToServer(CreateFolderC2S.TYPE,       CreateFolderC2S.CODEC,       CreateFolderC2S::handle);
        registrar.playToServer(DeleteFolderC2S.TYPE,       DeleteFolderC2S.CODEC,       DeleteFolderC2S::handle);

        // S2C
        registrar.playToClient(ScriptListSyncS2C.TYPE,     ScriptListSyncS2C.CODEC,     ScriptListSyncS2C::handle);
        registrar.playToClient(ScriptContentS2C.TYPE,      ScriptContentS2C.CODEC,      ScriptContentS2C::handle);
        registrar.playToClient(ExecutionResultS2C.TYPE,    ExecutionResultS2C.CODEC,    ExecutionResultS2C::handle);
    }

    /**
     * Push a fresh list of scripts AND folders to the given player. Use this
     * instead of constructing {@link ScriptListSyncS2C} directly so the I/O
     * happens off the server thread.
     */
    public static void syncFileTreeTo(ServerPlayer sp) {
        AsyncIO.supply(() -> new ScriptListSyncS2C(ScriptStorage.list(), ScriptStorage.listFolders()))
                .thenAccept(pkt -> PacketDistributor.sendToPlayer(sp, pkt));
    }
}
