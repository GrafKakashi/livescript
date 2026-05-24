package de.grafkakashi.livescript;

import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Single source of truth for "where do user-editable LiveScript files live".
 *
 * <p>We deliberately put everything under {@code <gameDir>/data/livescript/}
 * instead of the legacy locations:
 * <ul>
 *   <li>Scripts used to live in {@code <world>/livescript/scripts/} — per-world,
 *       hidden deep in the saves tree. Painful to edit from an external editor;
 *       lost on world delete; not portable across worlds in the same instance.</li>
 *   <li>Custom items used to live in {@code config/livescript/} — fine in
 *       isolation but conceptually it isn't a "config" (mod settings); it's
 *       user content. Living next to the scripts is more discoverable.</li>
 * </ul>
 *
 * <p>The new layout:
 * <pre>
 *   &lt;gameDir&gt;/data/livescript/
 *     ├── scripts/
 *     │   ├── *.js, *.lua, subfolders/...
 *     │   └── .history/    (auto-snapshot before each save)
 *     ├── items.json
 *     └── textures/
 *         └── &lt;item_id&gt;.png
 * </pre>
 *
 * <p>Where {@code <gameDir>} resolves via {@link FMLPaths#GAMEDIR}:
 * <ul>
 *   <li>On a Minecraft client: {@code .minecraft/} (or whatever
 *       {@code --gameDir} was passed at launch)</li>
 *   <li>On a dedicated server: the server's root folder, i.e. wherever the
 *       server jar runs from</li>
 * </ul>
 *
 * <p>One root for both client and server means instance-wide scripts (all
 * worlds in the same client install share them, all worlds on the dedicated
 * server share them).
 *
 * <p><b>Note:</b> the mod's own configuration (timeouts, permission level,
 * sandbox toggles) still lives at {@code config/livescript-common.toml} —
 * that's the NeoForge convention and changing it would surprise admins.
 * Only user content moved.
 */
public final class LiveScriptPaths {
    private LiveScriptPaths() {}

    /** Root directory: {@code <gameDir>/data/livescript/} */
    public static Path root() {
        return FMLPaths.GAMEDIR.get().resolve("data").resolve("livescript");
    }

    /** Editable script files (and the .history subfolder). */
    public static Path scriptsDir() {
        return root().resolve("scripts");
    }

    /** Custom item definition file. */
    public static Path itemsJson() {
        return root().resolve("items.json");
    }

    /** PNG textures for custom items, named {@code <item_id>.png}. */
    public static Path texturesDir() {
        return root().resolve("textures");
    }

    /**
     * Create all directories up front so users (and the editor) don't see
     * "directory doesn't exist" errors. Safe to call repeatedly. Failures
     * are non-fatal: if the FS is read-only or permissions are wrong, the
     * subsequent IO calls will surface a more specific error.
     */
    public static void ensureExist() {
        try {
            Files.createDirectories(scriptsDir());
            Files.createDirectories(texturesDir());
        } catch (IOException ignored) {
            // Permissions or read-only FS: nothing we can do here. The first
            // real read/write will report the actual problem with a useful
            // message; we don't want mod construction to abort over this.
        }
    }

    /**
     * One-shot migration from the legacy layout to the current one. Idempotent:
     * if the new layout already has content, we leave it alone. The legacy
     * files are NOT deleted, so if anything goes wrong the user still has the
     * originals — they can delete the old paths manually once they've
     * confirmed the new ones look right.
     *
     * <p>Legacy paths covered:
     * <ul>
     *   <li>{@code config/livescript/items.json} → {@code data/livescript/items.json}</li>
     *   <li>{@code config/livescript/textures/*.png} → {@code data/livescript/textures/}</li>
     *   <li>{@code <server-or-world-dir>/livescript/scripts/} → {@code data/livescript/scripts/}
     *       — only the client-side instance scripts dir; per-world legacy data isn't
     *       reachable from mod-construction time, so we expose a separate
     *       {@link #migrateLegacyScripts(Path)} method that the server-start hook calls.</li>
     * </ul>
     *
     * <p>Logging is informational; nothing crashes if migration fails.
     */
    public static void migrateLegacyConfig() {
        Path legacyConfigDir = FMLPaths.CONFIGDIR.get().resolve("livescript");
        if (!Files.isDirectory(legacyConfigDir)) return;

        copyIfMissing(legacyConfigDir.resolve("items.json"), itemsJson(),
                      "legacy items.json");
        copyDirContents(legacyConfigDir.resolve("textures"), texturesDir(),
                        "legacy textures");
    }

    /**
     * Migrate scripts from a legacy per-world or per-server location into the
     * unified {@link #scriptsDir()}. Caller is expected to pass the OLD root
     * (e.g. {@code <world>/livescript/} or {@code <server>/livescript/}), since
     * those paths aren't reachable from mod-construction time.
     *
     * <p>Subdirectories (including .history) are copied recursively, so the
     * full editor state is preserved.
     */
    public static void migrateLegacyScripts(Path legacyRoot) {
        if (legacyRoot == null || !Files.isDirectory(legacyRoot)) return;
        Path legacyScripts = legacyRoot.resolve("scripts");
        if (!Files.isDirectory(legacyScripts)) return;
        copyDirContents(legacyScripts, scriptsDir(), "legacy scripts");
    }

    /** Copy single file iff destination doesn't yet exist. */
    private static void copyIfMissing(Path src, Path dst, String label) {
        if (!Files.isRegularFile(src)) return;
        if (Files.exists(dst)) return; // user already has new content, don't overwrite
        try {
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst);
            System.out.println("[livescript] migrated " + label + ": " + src + " -> " + dst);
        } catch (IOException e) {
            System.err.println("[livescript] could not migrate " + label + ": " + e.getMessage());
        }
    }

    /**
     * Recursively copy directory contents. Files that already exist at the
     * destination are skipped (legacy never overwrites new). Empty source
     * directories are silently ignored.
     */
    private static void copyDirContents(Path src, Path dst, String label) {
        if (!Files.isDirectory(src)) return;
        try {
            Files.createDirectories(dst);
            try (var walk = Files.walk(src)) {
                walk.forEach(srcPath -> {
                    if (srcPath.equals(src)) return;
                    Path relative = src.relativize(srcPath);
                    Path dstPath = dst.resolve(relative.toString());
                    try {
                        if (Files.isDirectory(srcPath)) {
                            Files.createDirectories(dstPath);
                        } else if (!Files.exists(dstPath)) {
                            Files.createDirectories(dstPath.getParent());
                            Files.copy(srcPath, dstPath);
                            System.out.println("[livescript] migrated " + label + ": " + relative);
                        }
                        // else: dest exists — don't touch, user may have edited
                    } catch (IOException e) {
                        System.err.println("[livescript] could not migrate " + relative + ": " + e.getMessage());
                    }
                });
            }
        } catch (IOException e) {
            System.err.println("[livescript] could not walk " + src + ": " + e.getMessage());
        }
    }
}
