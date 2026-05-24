package de.grafkakashi.livescript.storage;

import de.grafkakashi.livescript.Config;
import de.grafkakashi.livescript.LiveScriptMod;
import de.grafkakashi.livescript.engine.ScriptType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Stores scripts under {@code <serverDir>/livescript/scripts/}, history under
 * {@code <serverDir>/livescript/scripts/.history/}.
 *
 * All paths are validated against the root to prevent traversal — a script
 * named "../../config/something.toml" must be rejected.
 */
public final class ScriptStorage {
    private static Path root;
    private static Path scriptsDir;
    private static Path historyDir;
    private static HistoryManager history;

    private ScriptStorage() {}

    public static void init(Path rootDir) {
        root = rootDir.toAbsolutePath().normalize();
        scriptsDir = root.resolve("scripts");
        historyDir = scriptsDir.resolve(".history");
        try {
            Files.createDirectories(scriptsDir);
            Files.createDirectories(historyDir);
            seedExamples();
        } catch (IOException e) {
            LiveScriptMod.LOGGER.error("failed to create script directories under {}", root, e);
        }
        history = new HistoryManager(historyDir);
    }

    /**
     * Drop a few example scripts on first run so people have something to read.
     * Idempotent: skips any file that already exists. Never overwrites user edits.
     */
    private static void seedExamples() throws IOException {
        writeIfMissing("examples/welcome.js", """
                // welcome.js - basic example
                // Hit Ctrl+Enter to run, Ctrl+S to save.

                print('hello from livescript');
                broadcast('LiveScript is live!');

                on('player.join', function(player) {
                    print(player.getName().getString() + ' joined');
                    broadcast('welcome ' + player.getName().getString());
                });
                """);
        writeIfMissing("examples/no_creeper_grief.js", """
                // @startup
                //
                // Block break-event cancel example.
                // Anyone named "TestPlayer" can't break blocks; everyone else can.
                //
                // The @startup line above re-registers this listener on every
                // server start (event listeners get cleared on shutdown).

                on('block.break', function(e) {
                    var p = e.getPlayer();
                    if (p && p.getName().getString() === 'TestPlayer') {
                        cancel(e);
                        print('blocked break by ' + p.getName().getString());
                    }
                });
                """);
        writeIfMissing("examples/recipes.js", """
                // @startup priority=100
                //
                // Recipe manipulation. Recipe edits are LIVE - they vanish on
                // restart. The @startup annotation above makes this script run
                // automatically every time the server starts, so your tweaks
                // persist across reloads. Priority 100 = runs before most
                // other startup scripts (recipes first, listeners later).
                //
                // To disable auto-run, remove or rename the @startup line.

                print('recipes before: ' + recipe.count());

                // Remove the vanilla torch recipe
                recipe.removeByOutput('minecraft:torch');

                // Then add a shaped one that wants two coal stacked on a stick
                recipe.shapedAdd({
                    id: 'livescript:torch_v2',
                    pattern: ['C', 'C', 'S'],
                    key:     { C: 'minecraft:coal', S: 'minecraft:stick' },
                    result:  { item: 'minecraft:torch', count: 8 }
                });

                // And a shapeless dough-from-wheat recipe.
                // NOTE: Item tags use the 'c:' namespace (NeoForge common tags),
                // not 'minecraft:'. The vanilla minecraft namespace has BLOCK tags
                // like minecraft:crops but no matching item tag.
                // See https://docs.neoforged.net/docs/resources/server/tags/ for the
                // full list of common item tags.
                recipe.shapelessAdd({
                    id: 'livescript:bread_fast',
                    ingredients: ['#c:crops', 'minecraft:water_bucket'],
                    result:  'minecraft:bread'
                });

                print('recipes after:  ' + recipe.count());
                """);
        writeIfMissing("examples/cooking.js", """
                // Cooking + stonecutter recipes - these are not auto-startup;
                // remove the @startup line below if you want them only on demand.
                // @startup priority=99

                // Furnace: cobblestone -> diamond, gives a lot of XP, fast cook time
                recipe.smeltAdd({
                    id:         'livescript:cobble_to_diamond',
                    ingredient: 'minecraft:cobblestone',
                    result:     'minecraft:diamond',
                    experience: 1.0,
                    cookTime:   100   // ticks; 100 = 5s (half of vanilla furnace)
                });

                // Smoker: any kelp -> an emerald
                recipe.smokerAdd({
                    id:         'livescript:kelp_to_emerald',
                    ingredient: 'minecraft:dried_kelp',
                    result:     'minecraft:emerald'
                });

                // Blast furnace: gold ingot -> iron ingot (cursed, but illustrative)
                recipe.blastAdd({
                    id:         'livescript:gold_to_iron',
                    ingredient: 'minecraft:gold_ingot',
                    result:     'minecraft:iron_ingot',
                    experience: 0.1
                });

                // Campfire: raw chicken -> cooked beef (because why not)
                recipe.campfireAdd({
                    id:         'livescript:chicken_to_beef',
                    ingredient: 'minecraft:chicken',
                    result:     'minecraft:cooked_beef',
                    cookTime:   400  // 20 seconds
                });

                // Stonecutter: diamond block -> 9 diamonds (no XP, no cook time)
                recipe.stoneCutAdd({
                    id:         'livescript:diamond_block_to_diamonds',
                    ingredient: 'minecraft:diamond_block',
                    result:     { item: 'minecraft:diamond', count: 9 }
                });

                // Custom-named & glowing crafted item. Under the hood it's still a
                // vanilla diamond, but display name, lore, and the enchantment shimmer
                // are stamped onto the result stack. No restart needed; no resource pack.
                // Color codes in name + lore use the section sign: §b (aqua), §7 (gray),
                // §c (red), §o (italic), etc. — same as /give does.
                recipe.shapedAdd({
                    id: 'livescript:magic_diamond',
                    pattern: ['DDD', 'DND', 'DDD'],
                    key: { D: 'minecraft:diamond', N: 'minecraft:nether_star' },
                    result: {
                        item:  'minecraft:diamond',
                        count: 1,
                        name:  '§bMagic Diamond',
                        lore:  ['§7Pulsates with arcane energy', '§8Artisan-crafted'],
                        glow:  true
                    }
                });

                print('cooking + stonecut recipes added');
                """);
        writeIfMissing("examples/tag_query.js", """
                // Tag lookup - useful for "does this stack count as a log/ore/etc"
                // checks inside other event handlers.

                var logs = tag.itemsIn('minecraft:logs');
                print('items in minecraft:logs: ' + logs.length);
                logs.forEach(function(id) { print('  ' + id); });

                on('block.break', function(e) {
                    var stack = item.stack('minecraft:oak_log', 1);
                    if (tag.itemHas(stack, 'minecraft:logs')) {
                        // (just a demo; doesn't actually inspect the broken block)
                    }
                });
                """);
        writeIfMissing("examples/welcome.lua", """
                -- welcome.lua - Lua flavor of the same example
                print('hello from lua livescript')
                broadcast('LiveScript (Lua) is live!')

                on('player.join', function(player)
                    print(player:getName():getString() .. ' joined')
                    broadcast('welcome ' .. player:getName():getString())
                end)
                """);
        writeIfMissing("examples/recipes.lua", """
                -- Same recipe example in Lua. Note that Java methods use `:`
                -- (method-call syntax) since these are CoerceJavaToLua wrappers.

                print('recipes before: ' .. recipe:count())
                recipe:removeByOutput('minecraft:torch')
                print('recipes after:  ' .. recipe:count())
                """);
    }

    private static void writeIfMissing(String relative, String content) throws IOException {
        Path p = scriptsDir.resolve(relative);
        if (Files.exists(p)) return;
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    public static HistoryManager history() { return history; }
    public static Path scriptsDir() { return scriptsDir; }

    /** List all scripts as relative paths (e.g. "hello.js", "subdir/foo.lua"). */
    public static List<String> list() {
        List<String> result = new ArrayList<>();
        if (!Files.isDirectory(scriptsDir)) return result;
        try (Stream<Path> walk = Files.walk(scriptsDir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !p.startsWith(historyDir))
                .filter(p -> ScriptType.fromExtension(p.getFileName().toString()) != null)
                .map(p -> scriptsDir.relativize(p).toString().replace('\\', '/'))
                .sorted()
                .forEach(result::add);
        } catch (IOException e) {
            LiveScriptMod.LOGGER.warn("list() failed", e);
        }
        return result;
    }

    /**
     * List all sub-directories (relative paths with trailing slash), so the
     * client can show empty folders too. The root itself is excluded.
     */
    public static List<String> listFolders() {
        List<String> result = new ArrayList<>();
        if (!Files.isDirectory(scriptsDir)) return result;
        try (Stream<Path> walk = Files.walk(scriptsDir)) {
            walk.filter(Files::isDirectory)
                .filter(p -> !p.equals(scriptsDir))
                .filter(p -> !p.startsWith(historyDir))
                .map(p -> scriptsDir.relativize(p).toString().replace('\\', '/') + "/")
                .sorted()
                .forEach(result::add);
        } catch (IOException e) {
            LiveScriptMod.LOGGER.warn("listFolders() failed", e);
        }
        return result;
    }

    /**
     * Create a folder (and any missing parents). Path must NOT end in .js/.lua.
     * Resolved safely against scriptsDir like everything else.
     */
    public static void createFolder(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) throw new IOException("empty path");
        String clean = relativePath.replace('\\', '/');
        // Strip a trailing slash if present — Path doesn't want it
        if (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        if (clean.isEmpty()) throw new IOException("empty path");
        if (clean.contains("..")) throw new IOException("no '..' in path");
        if (clean.startsWith("/") || clean.contains(":"))
            throw new IOException("absolute paths forbidden");
        if (ScriptType.fromExtension(clean) != null)
            throw new IOException("folder names cannot end in .js or .lua");

        Path resolved = scriptsDir.resolve(clean).toAbsolutePath().normalize();
        if (!resolved.startsWith(scriptsDir))
            throw new IOException("path escapes scripts directory: " + relativePath);
        Files.createDirectories(resolved);
    }

    /**
     * Recursively delete a folder and everything inside it. Used by "Delete
     * Folder". The path safety check is the same as scripts — must resolve
     * inside scriptsDir. The history sidecar bucket is also wiped.
     */
    public static void deleteFolder(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) throw new IOException("empty path");
        String clean = relativePath.replace('\\', '/');
        if (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        if (clean.isEmpty()) throw new IOException("empty path");
        if (clean.contains("..")) throw new IOException("no '..' in path");
        if (clean.startsWith("/") || clean.contains(":"))
            throw new IOException("absolute paths forbidden");

        Path resolved = scriptsDir.resolve(clean).toAbsolutePath().normalize();
        if (!resolved.startsWith(scriptsDir) || resolved.equals(scriptsDir))
            throw new IOException("invalid folder: " + relativePath);
        if (!Files.exists(resolved)) return;
        if (!Files.isDirectory(resolved))
            throw new IOException("not a directory: " + relativePath);

        // Walk in reverse so children get removed before their parent
        try (Stream<Path> walk = Files.walk(resolved)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
        // Drop the matching history bucket too
        Path histBucket = historyDir.resolve(clean);
        if (Files.exists(histBucket)) {
            try (Stream<Path> walk = Files.walk(histBucket)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        }
    }

    public static String read(String relativePath) throws IOException {
        Path p = resolveSafe(relativePath);
        if (!Files.isRegularFile(p)) return "";
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    public static void write(String relativePath, String content) throws IOException {
        int max = Config.MAX_SCRIPT_SIZE_BYTES.get();
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > max) {
            throw new IOException("script too large: " + bytes.length + " > " + max);
        }
        Path p = resolveSafe(relativePath);
        Files.createDirectories(p.getParent());

        // Snapshot history BEFORE overwriting
        if (Files.exists(p) && Config.MAX_HISTORY_PER_SCRIPT.get() > 0) {
            history.snapshot(relativePath, Files.readString(p, StandardCharsets.UTF_8));
        }

        Files.write(p, bytes);
    }

    public static void delete(String relativePath) throws IOException {
        Path p = resolveSafe(relativePath);
        Files.deleteIfExists(p);
    }

    /**
     * Atomically rename {@code from} to {@code to}. Both paths go through the
     * same {@link #resolveSafe} sanity check, so traversal attempts on either
     * side are rejected. Throws if the target already exists, to avoid silent
     * overwrites.
     */
    public static void move(String from, String to) throws IOException {
        Path src = resolveSafe(from);
        Path dst = resolveSafe(to);
        if (!Files.exists(src)) throw new IOException("source does not exist: " + from);
        if (Files.exists(dst)) throw new IOException("target already exists: " + to);
        Files.createDirectories(dst.getParent());
        Files.move(src, dst);
        // Also migrate the history sidecar if any — see HistoryManager
        history.rename(from, to);
    }

    /**
     * Resolve a relative path and verify it stays under scriptsDir.
     * Throws IOException on traversal attempts ("../..", absolute paths, etc.).
     */
    private static Path resolveSafe(String relative) throws IOException {
        if (relative == null || relative.isBlank()) throw new IOException("empty path");
        if (relative.startsWith("/") || relative.startsWith("\\") || relative.contains(":"))
            throw new IOException("absolute paths forbidden");
        if (ScriptType.fromExtension(relative) == null)
            throw new IOException("only .js / .lua files allowed");

        Path resolved = scriptsDir.resolve(relative).toAbsolutePath().normalize();
        if (!resolved.startsWith(scriptsDir)) {
            throw new IOException("path escapes scripts directory: " + relative);
        }
        return resolved;
    }
}
