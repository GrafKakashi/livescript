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
    /**
     * Root directory the editor tree spans. Equal to {@code data/livescript/}.
     * The file tree shows everything under this except the excluded paths
     * (history sidecar, textures, hidden dirs).
     */
    private static Path root;
    /**
     * Scripts subdirectory: {@code data/livescript/scripts/}. This is where
     * the seed examples land and where @startup runner looks for executable
     * files. Outside scripts/, only JSON config files are recognised.
     */
    private static Path scriptsDir;
    /** History snapshots, sidecar to scripts/. Excluded from the file tree. */
    private static Path historyDir;
    /**
     * Textures directory: {@code data/livescript/textures/}. PNG-only content
     * for custom items. Excluded from the file tree because PNGs aren't
     * text-editable from within the in-game editor — users edit them in their
     * external image tool of choice.
     */
    private static Path texturesDir;
    private static HistoryManager history;

    private ScriptStorage() {}

    public static void init(Path rootDir) {
        root = rootDir.toAbsolutePath().normalize();
        scriptsDir = root.resolve("scripts");
        historyDir = scriptsDir.resolve(".history");
        texturesDir = root.resolve("textures");
        try {
            Files.createDirectories(scriptsDir);
            Files.createDirectories(historyDir);
            Files.createDirectories(texturesDir);
            seedExamples();
        } catch (IOException e) {
            LiveScriptMod.LOGGER.error("failed to create script directories under {}", root, e);
        }
        history = new HistoryManager(historyDir);
    }

    /**
     * Drop curated example scripts on first run so people have something to
     * read and copy from. Each example covers ONE concept; together they
     * tour the whole API surface without overlap (e.g. recipes live in
     * exactly one file, events in exactly one file).
     *
     * <p>Idempotent: skips any file that already exists. Never overwrites
     * user edits — if a previous version of LiveScript shipped a similarly
     * named example and the user customised it, they keep their version
     * even if our seed text changed.
     *
     * <p>Distribution: 4 JS + 4 Lua + 1 items.json. Both engines get
     * representative coverage so users can see they're truly equivalent
     * and pick whichever fits their head better.
     */
    private static void seedExamples() throws IOException {
        // === 1. welcome.js — the first thing a new user opens ===
        // Keep this very short. Covers print, broadcast, and a single event.
        // Anything else would dilute the "what's the minimum to know" goal.
        writeIfMissing("scripts/examples/welcome.js", """
                // welcome.js — your first LiveScript file.
                // Hit Ctrl+Enter to run, Ctrl+S to save.
                //
                // This file has no auto-run directive (so it runs only when
                // you press Run). Most of the other examples DO have one
                // and run automatically on server start — that's why their
                // recipes and handlers are live after every restart.

                print('hello from livescript');
                broadcast('LiveScript is live!');

                // React to players joining the server. This handler is lost
                // when the server stops; for persistence add a startup
                // directive at the top of the file (see events.lua).
                on('player.join', function(e) {
                    var name = e.player.getName().getString();
                    print(name + ' joined');
                    broadcast('welcome, ' + name);
                });
                """);

        // === 2. events.lua — broader event catalogue + cancel ===
        // Shows that handlers can cancel events, and demonstrates Lua's
        // method-call colon syntax for Java objects.
        writeIfMissing("scripts/examples/events.lua", """
                -- @startup priority=20
                -- events.lua — listening to gameplay events and cancelling them.
                -- The @startup line above re-registers these handlers on every
                -- server start; event listeners get cleared on shutdown so
                -- without it you'd lose them after every restart.
                --
                -- Java methods use `:` (method-call syntax) since the objects
                -- are CoerceJavaToLua wrappers: player:getName() not player.getName().
                -- LuaJ does NOT auto-translate Bean getters — so you have to
                -- call e:getPlayer() rather than just e.player. (JS does the
                -- auto-translation; that's why the JS examples look different.)

                -- Log every chat message before it's delivered.
                -- e:getRawText() returns the raw String. The sibling method
                -- e:getMessage() returns a Component (formatted-text object) —
                -- using that with Lua's `..` would either silently mis-format
                -- or fail outright. When in doubt for chat-event handlers,
                -- reach for getRawText() and getUsername().
                on('player.chat', function(e)
                    local name = e:getPlayer():getName():getString()
                    print('[chat] ' .. name .. ': ' .. e:getRawText())
                end)

                -- Cancel block breaks for a specific player by name.
                -- Replace 'TestPlayer' with whoever you want to protect from
                -- griefing — or comment out if you don't need it.
                on('block.break', function(e)
                    local name = e:getPlayer():getName():getString()
                    if name == 'TestPlayer' then
                        cancel(e)
                        broadcast('§c' .. name .. ' tried to break a block — denied')
                    end
                end)

                print('events.lua loaded')
                """);

        // === 3. recipes.js — all 7 recipe types in one place ===
        // The biggest seed file; intentionally — recipes are the most
        // common LiveScript use case and having one canonical reference
        // beats scattering them across files.
        writeIfMissing("scripts/examples/recipes.js", """
                // @startup priority=10
                // recipes.js — add and remove recipes at runtime.
                // The @startup line above re-applies these recipes on every
                // server start; without it your recipes would only exist
                // until the next restart.
                // All recipe types are demonstrated here. For custom-styled
                // outputs (name, lore, glow) see custom_results.lua.

                print('recipes before: ' + recipe.count());

                // --- Remove vanilla recipes ---
                recipe.removeByOutput('minecraft:torch');

                // --- Crafting table: shaped ---
                recipe.shapedAdd({
                    id:      'livescript:torch_v2',
                    pattern: ['C', 'S'],
                    key:     { C: 'minecraft:coal', S: 'minecraft:stick' },
                    result:  { item: 'minecraft:torch', count: 8 }
                });

                // --- Crafting table: shapeless ---
                recipe.shapelessAdd({
                    id:     'livescript:bread_fast',
                    ingredients: ['minecraft:wheat', 'minecraft:wheat'],
                    result: 'minecraft:bread'
                });

                // --- Furnace: smelting ---
                recipe.smeltAdd({
                    id:           'livescript:cobble_to_diamond',
                    ingredient:   'minecraft:cobblestone',
                    result:       'minecraft:diamond',
                    experience:   1.0,
                    cookingTime:  200
                });

                // --- Smoker (food only) ---
                recipe.smokerAdd({
                    id:           'livescript:kelp_to_emerald',
                    ingredient:   'minecraft:kelp',
                    result:       'minecraft:emerald',
                    cookingTime:  100
                });

                // --- Blast furnace ---
                recipe.blastAdd({
                    id:           'livescript:gold_to_iron',
                    ingredient:   'minecraft:gold_ingot',
                    result:       'minecraft:iron_ingot',
                    cookingTime:  100
                });

                // --- Campfire ---
                recipe.campfireAdd({
                    id:           'livescript:chicken_to_beef',
                    ingredient:   'minecraft:chicken',
                    result:       'minecraft:beef',
                    cookingTime:  600
                });

                // --- Stonecutter (one input → one output, no count multipliers) ---
                recipe.stoneCutAdd({
                    id:         'livescript:diamond_block_to_diamonds',
                    ingredient: 'minecraft:diamond_block',
                    result:     'minecraft:diamond',
                    count:      9
                });

                print('recipes after:  ' + recipe.count());
                """);

        // === 4. custom_results.lua — the ONE place that demonstrates name/lore/glow ===
        // Keep this off recipes.js so the recipe-types reference stays compact.
        writeIfMissing("scripts/examples/custom_results.lua", """
                -- @startup priority=10
                -- custom_results.lua — recipes that produce custom-styled items.
                -- @startup keeps the recipe registered across restarts.
                -- Adds name, lore, and the enchantment glow to a vanilla item
                -- via DataComponents — no resource pack needed.

                recipe:shapedAdd({
                    id      = 'livescript:magic_diamond',
                    pattern = {'DDD', 'DND', 'DDD'},
                    key     = {
                        D = 'minecraft:diamond',
                        N = 'minecraft:nether_star'
                    },
                    result  = {
                        item  = 'minecraft:diamond',
                        count = 1,
                        name  = '§bMagic Diamond',
                        lore  = {
                            '§7Pulsates with arcane energy',
                            '§8(crafted via LiveScript)'
                        },
                        glow  = true
                    }
                })

                print('crafted livescript:magic_diamond recipe registered')
                """);

        // === 5. tags.js — tag lookups, queried-by-id ===
        // Useful before you write event handlers that need to ask "is the
        // block I just broke a log? an ore?" instead of comparing item IDs.
        writeIfMissing("scripts/examples/tags.js", """
                // tags.js — tag membership lookups.

                // Enumerate everything in a tag.
                var logs = tag.itemsIn('minecraft:logs');
                print('items in minecraft:logs: ' + logs.length);
                logs.forEach(function(id) { print('  ' + id); });

                // Check a specific item against a tag.
                var stack = item.stack('minecraft:oak_log', 1);
                if (tag.itemHas(stack, 'minecraft:logs')) {
                    print('oak_log IS in minecraft:logs (as expected)');
                }
                """);

        // === 6. startup.lua — @startup annotation pattern ===
        // Magic comment that auto-runs the script on server start, in
        // priority order. Without an example most users would never find it.
        writeIfMissing("scripts/examples/startup.lua", """
                -- @startup priority=10
                -- This script auto-runs every time the server starts.
                -- The priority directive controls ordering (higher = earlier).
                -- Without `@startup`, scripts only run when triggered manually.

                -- Recipe-clean-up patterns go here: remove what your modpack
                -- includes that you don't want.
                -- recipe:removeByOutput('minecraft:rotten_flesh')

                print('startup.lua ran on server start')
                """);

        // === 7. advanced.lua — multi-event state sharing ===
        // Shows a complete pattern: keep state in a closure, update it from
        // several handlers, query it from another. The kind of thing people
        // ask "is this even possible in LiveScript?" — yes.
        writeIfMissing("scripts/examples/advanced.lua", """
                -- @startup priority=5
                -- advanced.lua — coordinating state across events.
                -- @startup re-registers the handlers and resets the counter
                -- on each server start. The counter lives in this script's
                -- closure, so reloading the script clears all counts.
                -- Tracks block breaks per player and broadcasts at milestones.
                --
                -- Lua note: e:getPlayer() instead of e.player — LuaJ doesn't
                -- auto-translate Bean getters the way JS does.

                local breakCount = {}  -- player UUID → count

                local function key(player)
                    return player:getUUID():toString()
                end

                on('block.break', function(e)
                    local p = e:getPlayer()
                    local k = key(p)
                    breakCount[k] = (breakCount[k] or 0) + 1
                    local n = breakCount[k]

                    -- Announce at every 10 breaks (small for easier demo).
                    if n % 10 == 0 then
                        broadcast(p:getName():getString() ..
                            ' has broken ' .. n .. ' blocks this session!')
                    end
                end)

                -- Reset on quit so the counter is per-session, not per-life.
                -- For player.leave the binding unwraps to the Player directly,
                -- so we can call methods on it without a getPlayer() detour.
                on('player.leave', function(player)
                    breakCount[key(player)] = nil
                end)

                print('advanced.lua loaded — tracking block breaks')
                """);

        // === 8. items_demo.js — recipes USING the seeded custom items ===
        // Pairs with the items.json seed below. Shows the round trip:
        // declare items in JSON, then craft them in JS. Without this, users
        // would see items.json examples but not know how to actually MAKE them.
        writeIfMissing("scripts/examples/items_demo.js", """
                // @startup priority=15
                // items_demo.js — recipes for the custom items defined in
                // items.json. To use these, the items must be declared first
                // (they are, by default — see items.json one level up).
                // @startup keeps the recipes active across restarts.

                // Magic Dust: nether star + 4 redstone → 4 dust
                recipe.shapelessAdd({
                    id: 'livescript:craft_magic_dust',
                    ingredients: [
                        'minecraft:nether_star',
                        'minecraft:redstone',
                        'minecraft:redstone',
                        'minecraft:redstone',
                        'minecraft:redstone'
                    ],
                    result: { item: 'livescript:magic_dust', count: 4 }
                });

                // Phoenix Feather: 8 feathers around a blaze rod → 1 feather
                recipe.shapedAdd({
                    id:      'livescript:craft_phoenix_feather',
                    pattern: ['FFF', 'FBF', 'FFF'],
                    key:     {
                        F: 'minecraft:feather',
                        B: 'minecraft:blaze_rod'
                    },
                    result:  'livescript:phoenix_feather'
                });

                // Soul Bread: 2 bread + 1 soul sand → 1 soul bread
                recipe.shapelessAdd({
                    id: 'livescript:craft_soul_bread',
                    ingredients: [
                        'minecraft:bread',
                        'minecraft:bread',
                        'minecraft:soul_sand'
                    ],
                    result: 'livescript:soul_bread'
                });

                print('items_demo.js — 3 custom-item recipes registered');
                """);
    }

    /** Seed-only helper for scripts/ — relative path is under scriptsDir. */
    private static void writeIfMissing(String relative, String content) throws IOException {
        Path p = root.resolve(relative);
        if (Files.exists(p)) return;
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    /**
     * Seed-only helper for files that live directly at the livescript root
     * (items.json, future entities.json, etc.) rather than under scripts/.
     * Same idempotent semantics as {@link #writeIfMissing(String, String)}.
     */
    private static void writeIfMissingRoot(String relative, String content) throws IOException {
        Path p = root.resolve(relative);
        if (Files.exists(p)) return;
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }


    public static HistoryManager history() { return history; }
    public static Path scriptsDir() { return scriptsDir; }

    /**
     * List all editable files as paths relative to {@link #root}: scripts
     * ({@code scripts/welcome.js}), config JSON ({@code items.json}), etc.
     *
     * <p>Excluded:
     * <ul>
     *   <li>Anything under {@code scripts/.history/} (snapshot sidecar)</li>
     *   <li>Anything under {@code textures/} (PNGs aren't editable in-game)</li>
     *   <li>Hidden directories: any path whose components start with '.'</li>
     *   <li>Files whose extension isn't .js, .lua, or .json</li>
     * </ul>
     *
     * <p>The result is sorted alphabetically so the tree is deterministic.
     */
    public static List<String> list() {
        List<String> result = new ArrayList<>();
        if (!Files.isDirectory(root)) return result;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(ScriptStorage::isVisible)
                .filter(p -> ScriptType.isEditableExtension(p.getFileName().toString()))
                .map(p -> root.relativize(p).toString().replace('\\', '/'))
                .sorted()
                .forEach(result::add);
        } catch (IOException e) {
            LiveScriptMod.LOGGER.warn("list() failed", e);
        }
        return result;
    }

    /**
     * List sub-directories (relative paths with trailing slash) so the editor
     * can show empty folders too. Same exclusion rules as {@link #list()}:
     * .history/, textures/, and any other hidden directory is hidden from
     * the tree.
     */
    public static List<String> listFolders() {
        List<String> result = new ArrayList<>();
        if (!Files.isDirectory(root)) return result;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isDirectory)
                .filter(p -> !p.equals(root))
                .filter(ScriptStorage::isVisible)
                .map(p -> root.relativize(p).toString().replace('\\', '/') + "/")
                .sorted()
                .forEach(result::add);
        } catch (IOException e) {
            LiveScriptMod.LOGGER.warn("listFolders() failed", e);
        }
        return result;
    }

    /**
     * True if the path is visible in the editor tree: not inside .history,
     * not inside textures, no component starts with '.'. Applied to both
     * files and folders.
     */
    private static boolean isVisible(Path p) {
        if (p.startsWith(historyDir)) return false;
        if (p.startsWith(texturesDir)) return false;
        // Hidden component anywhere in the relative path
        Path rel = root.relativize(p);
        for (Path component : rel) {
            String name = component.getFileName().toString();
            if (name.startsWith(".")) return false;
        }
        return true;
    }

    /**
     * Create a folder (and any missing parents). Resolved root-relative now,
     * not scripts-relative — so you can create folders directly under
     * {@code data/livescript/} as well as deep nested. The standard exclusions
     * apply: cannot create a folder inside .history/ or textures/, and
     * folder names cannot themselves be an editable file extension.
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
        if (ScriptType.isEditableExtension(clean))
            throw new IOException("folder names cannot end in .js/.lua/.json");

        Path resolved = root.resolve(clean).toAbsolutePath().normalize();
        if (!resolved.startsWith(root))
            throw new IOException("path escapes livescript directory: " + relativePath);
        if (resolved.startsWith(historyDir) || resolved.startsWith(texturesDir))
            throw new IOException("cannot create folder in reserved location: " + relativePath);
        Files.createDirectories(resolved);
    }

    /**
     * Recursively delete a folder and everything inside it. Used by "Delete
     * Folder". The path safety check resolves against the root and refuses
     * to touch the root itself, the history sidecar, the textures dir, or
     * the scripts dir itself (deleting all scripts at once would be too
     * easy to do by accident).
     */
    public static void deleteFolder(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) throw new IOException("empty path");
        String clean = relativePath.replace('\\', '/');
        if (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        if (clean.isEmpty()) throw new IOException("empty path");
        if (clean.contains("..")) throw new IOException("no '..' in path");
        if (clean.startsWith("/") || clean.contains(":"))
            throw new IOException("absolute paths forbidden");

        Path resolved = root.resolve(clean).toAbsolutePath().normalize();
        if (!resolved.startsWith(root) || resolved.equals(root))
            throw new IOException("invalid folder: " + relativePath);
        if (resolved.equals(scriptsDir) || resolved.equals(texturesDir) || resolved.equals(historyDir))
            throw new IOException("refuse to delete reserved folder: " + relativePath);
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
        // JSON files must parse before we write them. We don't want a half-typed
        // brace to brick custom items on next server start. Scripts (.js/.lua)
        // can be saved freely — they're only executed on demand, and an in-progress
        // save is something users commonly do mid-thought.
        if (relativePath.toLowerCase(java.util.Locale.ROOT).endsWith(".json")) {
            validateJsonOrThrow(content);
        }
        Path p = resolveSafe(relativePath);
        Files.createDirectories(p.getParent());

        // Snapshot history BEFORE overwriting
        if (Files.exists(p) && Config.MAX_HISTORY_PER_SCRIPT.get() > 0) {
            history.snapshot(relativePath, Files.readString(p, StandardCharsets.UTF_8));
        }

        Files.write(p, bytes);
    }

    /**
     * Parse the content as JSON and throw a descriptive IOException if it
     * doesn't parse. We use Gson because it's already on the classpath
     * (Minecraft ships it) and its error messages include line+column.
     */
    private static void validateJsonOrThrow(String content) throws IOException {
        try {
            com.google.gson.JsonParser.parseString(content);
        } catch (com.google.gson.JsonSyntaxException e) {
            // Gson wraps the underlying parse exception; the message usually
            // looks like "Expected EOF at line 5 column 3 path $.items" which
            // is exactly what the user needs to find the typo.
            String msg = e.getMessage();
            if (msg == null) msg = "syntax error";
            throw new IOException("invalid JSON: " + msg);
        }
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
     * Resolve a root-relative path for read/write operations. The path must:
     * <ul>
     *   <li>Be non-empty and not absolute</li>
     *   <li>Contain no {@code ..} segments</li>
     *   <li>End in .js, .lua, or .json (one of the editable extensions)</li>
     *   <li>Stay inside {@link #root} after normalisation</li>
     *   <li>Not fall under .history/ or textures/</li>
     * </ul>
     * The last two checks defeat traversal attempts even if the earlier
     * string checks missed something — defence in depth.
     */
    private static Path resolveSafe(String relative) throws IOException {
        if (relative == null || relative.isBlank()) throw new IOException("empty path");
        if (relative.startsWith("/") || relative.startsWith("\\") || relative.contains(":"))
            throw new IOException("absolute paths forbidden");
        if (relative.contains("..")) throw new IOException("no '..' in path");
        if (!ScriptType.isEditableExtension(relative))
            throw new IOException("only .js / .lua / .json files allowed");

        Path resolved = root.resolve(relative).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("path escapes livescript directory: " + relative);
        }
        if (resolved.startsWith(historyDir) || resolved.startsWith(texturesDir)) {
            throw new IOException("path falls under reserved location: " + relative);
        }
        return resolved;
    }
}
