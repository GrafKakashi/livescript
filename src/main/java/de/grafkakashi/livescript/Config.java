package de.grafkakashi.livescript;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Live-tunable settings. Critical for a multi-user server with 300+ mods:
 * if any of these is wrong, scripts can lock up the main thread.
 */
public final class Config {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue SCRIPT_TIMEOUT_MS;
    public static final ModConfigSpec.IntValue MAX_SCRIPT_SIZE_BYTES;
    public static final ModConfigSpec.IntValue MAX_HISTORY_PER_SCRIPT;
    public static final ModConfigSpec.IntValue REQUIRED_PERMISSION_LEVEL;
    public static final ModConfigSpec.BooleanValue ALLOW_FILESYSTEM_ACCESS;
    public static final ModConfigSpec.BooleanValue ALLOW_NETWORK_ACCESS;
    public static final ModConfigSpec.BooleanValue AUTO_RUN_ON_SAVE;
    public static final ModConfigSpec.IntValue CONSOLE_BUFFER_LINES;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("execution");
        SCRIPT_TIMEOUT_MS = b
                .comment("Maximum wall-clock time (ms) a script may run before it is force-killed.",
                        "Prevents infinite loops from freezing the server thread.")
                .defineInRange("scriptTimeoutMs", 5000, 100, 60_000);
        MAX_SCRIPT_SIZE_BYTES = b
                .comment("Maximum source size per script in bytes. Larger files are rejected on save.")
                .defineInRange("maxScriptSizeBytes", 512 * 1024, 1024, 8 * 1024 * 1024);
        AUTO_RUN_ON_SAVE = b
                .comment("If true, every successful save also hot-reloads the script. Convenient but risky.")
                .define("autoRunOnSave", false);
        b.pop();

        b.push("storage");
        MAX_HISTORY_PER_SCRIPT = b
                .comment("How many historical revisions to keep per script. 0 disables history.")
                .defineInRange("maxHistoryPerScript", 50, 0, 1000);
        CONSOLE_BUFFER_LINES = b
                .comment("How many console output lines to keep in memory per client session.")
                .defineInRange("consoleBufferLines", 2000, 100, 50_000);
        b.pop();

        b.push("security");
        REQUIRED_PERMISSION_LEVEL = b
                .comment("Permission level required to open the editor and execute scripts (2 = op).")
                .defineInRange("requiredPermissionLevel", 2, 0, 4);
        ALLOW_FILESYSTEM_ACCESS = b
                .comment("If true, scripts may read/write files inside the livescript/ folder.",
                        "If false, scripts are pure compute — safer default for shared servers.")
                .define("allowFilesystemAccess", false);
        ALLOW_NETWORK_ACCESS = b
                .comment("If true, scripts may make outbound HTTP requests. KEEP THIS OFF on public servers.")
                .define("allowNetworkAccess", false);
        b.pop();

        SPEC = b.build();
    }

    private Config() {}
}
