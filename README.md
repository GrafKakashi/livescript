# LiveScript

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Minecraft 1.21.1](https://img.shields.io/badge/Minecraft-1.21.1-green)]()
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1+-orange)]()
[![CurseForge](https://img.shields.io/badge/CurseForge-livescript-orange)](https://legacy.curseforge.com/minecraft/mc-mods/livescript)

In-game JavaScript + Lua scripting for NeoForge 1.21.1 — like KubeJS, but you edit
the scripts in a fullscreen GUI inside the game instead of in external files, and
like ComputerCraft, but with a real editor (syntax highlighting, multi-error
linter, undo/redo, hot-reload, console) instead of a 51×19 text terminal.

**Current state (v0.12.x):** All five recipe types (shaped, shapeless, smelting,
smoking, blasting, campfire, stonecutting), `@startup`-annotated scripts that
auto-run on server start, custom item registration via `items.json` **with
runtime resource-pack provider for textures + display names**, custom
result-stack appearance (name + lore + glow), 18-event listener catalogue with
hot-reload, Linter-Squigglies, folder tree, a usable text editor (selection,
clipboard, find/replace, code-folding), startup-output replay for operators,
and CurseForge update check.

## Security model

LiveScript embeds Mozilla Rhino (JavaScript) and LuaJ (Lua) as scripting
engines. **Both engines are sandboxed**:

- **No filesystem access.** `java.io.File` and `java.nio.file.*` are blocked
  via Rhino's `ClassShutter` (see [`JsEngine.java`](src/main/java/de/grafkakashi/livescript/engine/JsEngine.java)).
  Lua's `io` library is set to `nil`.
- **No subprocess execution.** `java.lang.Runtime` and `java.lang.ProcessBuilder`
  are blocked in `ClassShutter`. Lua's `os.execute`, `os.exit`, `os.remove`,
  `os.rename`, `os.getenv`, `os.tmpname` are stripped.
- **No reflection.** `java.lang.reflect.*` is blocked.
- **No network from scripts.** `java.net.*` is blocked. (LiveScript itself
  makes one outbound request to `api.cfwidget.com` for the CurseForge update
  check; that's mod code, not script code, and you can disable it by editing
  `UpdateChecker.java`.)
- **CPU timeout.** Every script has a configurable timeout (default 5 s).
  JS uses Rhino's instruction-count hook; Lua uses `debug.sethook`.
- **Operator-gated.** `/scripteditor` requires permission level 2 (`/op`).
  Non-ops cannot open the editor or execute scripts.

The same security model is used by KubeJS, GroovyScript, CraftTweaker, and
ComputerCraft. The relevant files to audit are:

- [`engine/JsEngine.java`](src/main/java/de/grafkakashi/livescript/engine/JsEngine.java) — Rhino setup, `ClassShutter`, instruction-count hook
- [`engine/LuaEngine.java`](src/main/java/de/grafkakashi/livescript/engine/LuaEngine.java) — LuaJ setup, `debugGlobals`, stripped `os`
- [`Config.java`](src/main/java/de/grafkakashi/livescript/Config.java) — toggles for the sandbox (default: max-restrictive)

## Build

```bash
./gradlew build
# JAR ends up in build/libs/livescript-<version>-neoforge-1.21.1.jar
```

Requires JDK 21. Gradle wrapper is included via the NeoForge moddev plugin.

Don't have Gradle installed? `sdk install gradle 8.8` via SDKMAN is the
fastest path on Linux/macOS. On Windows, grab the Gradle 8.8 binary distribution
from gradle.org/releases.

Drop the JAR in `mods/`, start the server, then in-game:

```
/scripteditor
```

You'll need OP (permission level 2 by default — configurable in
`config/livescript-common.toml`).

## Architecture

| Concern                  | Class                                          |
|--------------------------|------------------------------------------------|
| Mod entrypoint           | `LiveScriptMod`                                |
| Config                   | `Config`                                       |
| Script engines           | `engine/JsEngine` (Rhino), `engine/LuaEngine` (LuaJ) |
| Sandbox + listener cleanup | `engine/ScriptContext`                       |
| Pre-run syntax check     | `engine/Linter`                                |
| Script-exposed events    | `api/EventBindings`                            |
| Recipe/tag/item APIs     | `api/RecipeApi`, `api/TagApi`, `api/ItemApi`   |
| Recipe-spec normalization (JS/Lua/Map → uniform) | `api/SpecAdapter` |
| Storage + history        | `storage/ScriptStorage`, `storage/HistoryManager` |
| Async IO                 | `storage/AsyncIO`                              |
| Network payloads         | `network/*`                                    |
| Editor GUI               | `client/gui/ScriptEditorScreen`                |
| Editor buffer + undo     | `client/gui/EditorState`, `client/gui/UndoStack` |
| Stateful syntax highlighting | `client/gui/highlight/*`                   |
| Autocomplete             | `client/gui/autocomplete/CompletionProvider`   |
| Indent-based code folding | `client/gui/FoldRegion`                       |
| Find/Replace             | `client/gui/FindState`                         |

## Scripts API

Both languages expose the same surface. Extend `EventBindings` / `RecipeApi`
/ `TagApi` / `ItemApi` to add more.

### Top-level functions

- `print(msg)` — write to the editor console
- `broadcast(msg)` — chat message to all online players
- `server` — `MinecraftServer` instance (use carefully)
- `on(eventName, handler)` — subscribe to an event
- `cancel(event)` — cancel a cancellable event from inside a handler

### Event names

Ticks:
- `server.tick` — every server tick (fires 20×/sec; keep handlers tiny)

Player lifecycle:
- `player.join` / `player.leave` — receives the `ServerPlayer`
- `player.respawn` — after death respawn
- `player.dimension_change` — portal traversal

Chat:
- `player.chat` — receives `ServerChatEvent`; cancel to suppress, mutate to rewrite

Death and damage:
- `player.death` — `LivingDeathEvent` filtered to players
- `entity.death` — `LivingDeathEvent` for any mob
- `entity.damage` — `LivingIncomingDamageEvent`; cancel to absorb the hit

Blocks:
- `block.break` / `block.place` — cancel to deny

Items + interactions:
- `item.use_block` — right-click an item on a block
- `item.use` — right-click an item in air (food, throwables)
- `item.craft` — item taken from a crafting result slot (`PlayerEvent.ItemCraftedEvent`)
- `item.smelt` — item taken from a furnace result slot
- `item.pickup` — item picked up off the ground

Entities:
- `entity.spawn` — `EntityJoinLevelEvent`; cancel to deny spawn
- `mob.target` — `LivingChangeTargetEvent`; mob aggro switch

World:
- `explosion.start` — before the boom; cancel to prevent
- `explosion.detonate` — after; modify affected blocks/entities

### `recipe` — runtime recipe manipulation

Read + delete:

```javascript
recipe.removeByOutput('minecraft:torch');  // remove all recipes producing torches
recipe.removeById('minecraft:stick');       // remove a specific recipe
recipe.count();                              // total recipe count
```

Crafting:

```javascript
recipe.shapedAdd({
    id: 'livescript:my_torch',
    pattern: ['C', 'C', 'S'],
    key:     { C: 'minecraft:coal', S: 'minecraft:stick' },
    result:  { item: 'minecraft:torch', count: 8 }
});

recipe.shapelessAdd({
    id: 'livescript:bread_fast',
    ingredients: ['#c:crops', 'minecraft:water_bucket'],
    result:  'minecraft:bread'
});
```

Cooking — `smeltAdd` (furnace), `smokerAdd`, `blastAdd`, `campfireAdd`. Same
schema; default cook times are 200 (smelt), 100 (smoker, blast), 600 (campfire).

```javascript
recipe.smeltAdd({
    id:         'livescript:cobble_to_diamond',
    ingredient: 'minecraft:cobblestone',
    result:     'minecraft:diamond',
    experience: 1.0,
    cookTime:   100      // ticks; optional
    // category: 'MISC'  // optional: FOOD, BLOCKS, MISC
});
```

Stonecutter — no experience, no cook time:

```javascript
recipe.stoneCutAdd({
    id:         'livescript:diamond_block_to_diamonds',
    ingredient: 'minecraft:diamond_block',
    result:     { item: 'minecraft:diamond', count: 9 }
});
```

Ingredient syntax everywhere:
- Plain item:  `'minecraft:iron_ingot'`
- Tag:         `'#c:logs'` (NeoForge common tags use the `c:` namespace, not `minecraft:`)
- Object form: `{ item: 'minecraft:iron_ingot' }` or `{ tag: 'c:logs' }`

Custom result item appearance — works for any recipe type:

```javascript
recipe.shapedAdd({
    id: 'livescript:magic_diamond',
    pattern: ['DDD', 'DND', 'DDD'],
    key: { D: 'minecraft:diamond', N: 'minecraft:nether_star' },
    result: {
        item:  'minecraft:diamond',     // underlying item — unchanged
        count: 1,
        name:  '§bMagic Diamond',        // sets DataComponents.CUSTOM_NAME
        lore:  ['§7Pulsates with arcane energy',  // each entry = one line
                '§8Artisan-crafted'],
        glow:  true                      // adds enchantment shimmer w/o real enchants
    }
});
```

Notes on cosmetic output:
- The output is still the underlying item (here, `minecraft:diamond`) — it stacks
  with other named-diamonds that have the same name+lore but **not** with vanilla
  diamonds. Minecraft treats stacks with different data components as distinct.
- Color codes use the section sign (`§`); copy from `/give`-style commands.
- Lore can be a single string or an array. Both render upright (not italic),
  matching what most users expect from Custom-NBT items.
- No restart, no resource pack, no client install required — the data components
  travel with the ItemStack like any vanilla enchanted item.

Caveats: changes are NOT persisted. Re-apply on every server start (auto-run on
save = on, plus a `@startup` script, is the recommended pattern). Client recipe
books may show stale data until next reconnect.

### `tag` — read-only tag lookup

```javascript
tag.itemsIn('minecraft:logs');               // [String] of item ids
tag.itemHas(itemStack, 'minecraft:logs');    // bool
tag.blocksIn('minecraft:mineable/pickaxe');  // [String] of block ids
```

### `item` — item registry

```javascript
item.find('minecraft:diamond');         // → Item
item.stack('minecraft:diamond', 64);    // → ItemStack
item.idOf(someItem);                    // → 'minecraft:diamond'
item.findByPrefix('minecraft:diamond'); // [String] of matching ids
```

### Lua note

Lua scripts use `:` for method calls on Java objects, not `.`:

```lua
recipe:removeByOutput('minecraft:torch')   -- correct
recipe.removeByOutput('minecraft:torch')   -- WRONG — would pass recipe as wrong arg
```

This is because the script gets CoerceJavaToLua wrappers, and LuaJ treats `:`
as "pass self as first arg" — which is what Java instance methods expect.

### Examples

On first server start, the following are seeded into
`livescript/scripts/examples/` (never overwritten if you've edited them):
`welcome.js`, `welcome.lua`, `no_creeper_grief.js`, `recipes.js`, `recipes.lua`,
`tag_query.js`.

## Keyboard shortcuts in the editor

| Shortcut | Action |
|----------|--------|
| Ctrl+S          | Save current script (lints first; refuses on syntax error) |
| Ctrl+Enter      | Run current buffer (without saving; also lints) |
| F2              | Rename current script or selected folder |
| + Folder        | Create a new folder (also for nested paths like `tools/utils`) |
| Click folder    | Toggle expand/collapse; also selects it for Rename/Delete |
| Ctrl+Z          | Undo |
| Ctrl+Y / Ctrl+Shift+Z | Redo |
| Ctrl+A          | Select all |
| Ctrl+C          | Copy selection (or current line if no selection) |
| Ctrl+X          | Cut selection (or current line if no selection) |
| Ctrl+V          | Paste — replaces selection if any |
| Shift+Arrow / Home / End | Extend selection |
| Mouse drag      | Drag-select text |
| Shift+Click     | Extend selection to click point |
| Ctrl+F          | Open find bar |
| Ctrl+H          | Toggle replace mode (only when find bar is open) |
| Enter (in bar)  | Jump to next match |
| Shift+Enter (in bar) | Jump to previous match |
| Tab (in bar, replace mode) | Switch between find and replace field |
| Esc             | Close find bar / cancel a confirm dialog / close editor |
| Click in gutter | Toggle fold for that region (header lines show `>` or `v`) |
| Drag splitter   | Resize file tree / console (cyan when hovered) |
| Tab (in editor) | Insert 2 spaces |
| Up/Down/Tab in popup | Navigate autocomplete |
| Enter in popup  | Accept completion |
| Y / N           | Confirm or cancel a delete prompt |

## Custom items

LiveScript can register entirely new items with their own IDs, display names,
and item properties. These work like vanilla items: craftable, stackable,
usable as recipe ingredients/outputs, food, fire-resistant, etc.

**Restart caveat:** Minecraft's item registry freezes after mod load and stays
frozen for the entire process. That means changes to `items.json` need a
**full Minecraft restart** to take effect — quitting to the title screen and
opening a new world is NOT enough, because the mod constructor (where items
get registered) only runs once per JVM. Server admins running a dedicated
server have it easier: a server restart is enough.

Items live in `data/livescript/items.json`. On first run, three demo items
(`magic_dust`, `phoenix_feather`, `soul_bread`) are seeded along with a paired
script `scripts/examples/items_demo.js` that registers crafting recipes for
them. Delete or replace as you like.

Minimal schema:

```json
{
  "items": {
    "magic_dust": {
      "display_name": "Magic Dust",
      "max_stack_size": 64,
      "rarity": "rare"
    },
    "phoenix_feather": {
      "display_name": "Phoenix Feather",
      "max_stack_size": 16,
      "fire_resistant": true
    },
    "soul_bread": {
      "display_name": "Soul Bread",
      "max_stack_size": 16,
      "food": {
        "nutrition": 6,
        "saturation": 0.8,
        "always_edible": true
      }
    }
  }
}
```

Schema reference for each entry:

| Field | Type | Default | Notes |
|---|---|---|---|
| `display_name` | string | titlecase of id | Shows in tooltips and inventories |
| `max_stack_size` | int 1..99 | 64 | Stack size in inventory |
| `rarity` | string | `common` | One of `common`, `uncommon`, `rare`, `epic` — colors the tooltip name |
| `fire_resistant` | bool | false | Survives lava / fire damage when dropped |
| `food.nutrition` | int | — | Hunger points restored. If `food` is set, item becomes edible |
| `food.saturation` | float | — | Saturation modifier (golden apple = 1.2) |
| `food.always_edible` | bool | false | Can eat at full hunger (golden apple style) |

Keys prefixed with `_` are skipped — use that to "comment out" an entry
(`"_old_item": { ... }`).

Use in recipes with the `livescript:` namespace:

```javascript
recipe.shapedAdd({
    id: 'livescript:dust_to_feather',
    pattern: ['DDD', 'DDD', 'DDD'],
    key: { D: 'livescript:magic_dust' },
    result: 'livescript:phoenix_feather'
});
```

### Textures

Custom item textures live in `data/livescript/textures/`. For an item with
id `magic_dust`, drop a `magic_dust.png` file in there:

```
data/livescript/
├── items.json
├── scripts/
└── textures/
    ├── magic_dust.png
    ├── phoenix_feather.png
    └── soul_bread.png
```

PNGs must be square, power-of-two size — 16x16 is the vanilla default, but
32x32, 64x64, and 128x128 all work. Minecraft scales them for inventory icons.

Items without a matching PNG show the magenta/black missing-texture pattern.
The item is still functional; it just has no icon.

**Live reload:** press F3+T in-game after editing or adding a texture to see
the change immediately. No restart needed for texture changes — only for
`items.json` changes (and those need a full Minecraft restart, see above).

## Hot-reload semantics

Every `execute()` call to a script with a previously-used `scriptId` first tears
down all listeners registered by the prior run. This works via `ScriptContext`,
which tracks every subscription and neutralizes them with an `active` flag on
unregister. Listeners themselves are NOT removed from the event bus — NeoForge's
`IEventBus` doesn't currently expose unsubscribe-by-lambda — but the guarded
wrapper makes them no-ops, and the JVM eventually GCs the closures when the
last script reference drops.

This is the same lesson as your perfmod debugging: leaked listeners are the
silent killer of "reloadable" systems. Worth watching with a memory profiler if
you start running thousands of reloads in a session.

## Linting

The editor re-lints the active buffer ~500ms after each typing pause. Syntax
errors are underlined with a red wavy line, and the status bar at the bottom
shows the first error and an overflow count if more exist.

- JS uses Rhino's parser with multiple-error recovery — up to 10 issues per file
- Lua uses LuaJ's compile path — single error per file (the first one bails)
- Lint failures block save (Ctrl+S surfaces the message), but you can still
  run (Ctrl+Enter) for testing — the script just won't run if it doesn't parse

## Startup scripts (`@startup`)

Add a `@startup` annotation in the first 20 lines of a script to make it run
automatically every time the server starts. Recipe edits, event listeners, and
anything else that needs to be re-applied across restarts goes here.

```js
// @startup priority=100
recipe.removeByOutput('minecraft:torch');
recipe.shapedAdd({ /* ... */ });
```

```lua
-- @startup priority=-5
print('low-priority startup; runs near the end')
```

Higher priority runs first; ties broken alphabetically by script id. Output
from startup scripts goes to the server log (no client is connected yet).
A failing startup script logs the error but doesn't block the others.

This runs in `ServerStartedEvent`, AFTER the recipe manager is fully populated.
Doing it during `ServerStartingEvent` would race with mod recipe loading.

## Security

Three configurable layers (`config/livescript-common.toml`):

1. **Permission level** — only ops can open the editor or save scripts.
2. **Class shutter (JS) / stripped libs (Lua)** — reflection, `Runtime`,
   `ProcessBuilder`, `File`, and network classes are blocked by default.
3. **Hard timeout** — Rhino's instruction observer and Lua's debug hook both
   kill scripts that exceed `scriptTimeoutMs` (5s default).

`allowFilesystemAccess` and `allowNetworkAccess` are both `false` by default.
Flip them only on single-player or fully-trusted servers.

## Shaded dependencies

Rhino and LuaJ are shaded into the final JAR to avoid classpath conflicts with
other mods that bundle their own copies (KubeJS ships Rhino, CC:Tweaked ships
its own Lua). If you want to *use* this mod alongside KubeJS, the shading
should make that safe; if it doesn't, we'd need to relocate the packages with
the Shadow plugin instead — `build.gradle` is set up so adding that is a
2-line change.

## Known gaps (intentional, for v0.3)

- **Item registration** isn't possible from scripts at all — Minecraft's
  RegisterEvent fires before scripts can ever run. KubeJS solves this via
  JSON file generation at JVM startup; we'd need a similar bootstrap layer.
- **Tag mutation** is not possible at runtime — datapack-only since 1.19.
  Lookups are available; modifications aren't.
- **Smelting / brewing / smithing recipes** aren't exposed yet — only crafting.
  Easy enough to add: each follows the same pattern as `shapelessAdd`, with a
  different RecipeType.
- **Code-folding is indent-based**, not language-aware. Mis-indented code folds
  weirdly; the upside is one algorithm covers both JS and Lua. Switch to
  brace/keyword matching later if it bites.
- **Find/Replace is plain text**, no regex. Adding regex would be a 5-line change
  in `FindState.recomputeMatches`, but the bigger work is escaping for
  user-typed `(`/`[`/`*` so they search literally by default.
- Autocomplete is keyword/binding-name only, not context-aware.
- Per-file undo history doesn't survive a save→close→reopen cycle —
  on-disk history (HistoryManager) does, but the in-memory UndoStack resets.

## License

MIT.
