package de.grafkakashi.livescript.items;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.grafkakashi.livescript.LiveScriptMod;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads custom item definitions from {@code config/livescript/items.json} and
 * registers them with the {@code livescript} namespace at mod-construction time.
 *
 * The file is loaded ONCE during mod init — items are baked into the registry
 * before the world loads, so server-restart is required to add or modify items.
 * This is a hard Minecraft constraint (the item registry is frozen post-load).
 *
 * Schema:
 * <pre>
 * {
 *   "items": {
 *     "magic_dust": {
 *       "display_name": "Magic Dust",
 *       "max_stack_size": 64,
 *       "rarity": "rare",
 *       "fire_resistant": false,
 *       "food": { "nutrition": 4, "saturation": 0.6 }
 *     },
 *     "phoenix_feather": {
 *       "max_stack_size": 16,
 *       "fire_resistant": true
 *     }
 *   }
 * }
 * </pre>
 *
 * All fields except the item key are optional. Missing file or empty {@code items}
 * map = no custom items, no error. Bad JSON = error logged, no items registered,
 * mod continues to load (so a malformed config doesn't break the entire server).
 */
public final class CustomItemRegistry {
    private CustomItemRegistry() {}

    /** Mod-id namespace for custom items: their full id is {@code livescript:<key>}. */
    public static final String NAMESPACE = LiveScriptMod.MOD_ID;

    /** Deferred register for items. Created lazily at mod-construction time. */
    private static DeferredRegister.Items ITEMS;

    /** Captured definitions, in registration order, keyed by registry path. */
    private static final Map<String, ItemDef> DEFINITIONS = new LinkedHashMap<>();

    /** DeferredItem suppliers per id — populated as we register, queried later. */
    private static final Map<String, DeferredItem<Item>> ITEM_HOLDERS = new LinkedHashMap<>();

    /**
     * One item's parsed JSON config. Held as a record so the rest of the system
     * (resource-pack generator, recipe resolver) can read it without re-parsing.
     */
    public record ItemDef(
            String id,                  // path part — e.g. "magic_dust"
            String displayName,         // null = fall back to "Magic Dust" (titlecase of id)
            int maxStackSize,           // 1..99
            String rarity,              // common/uncommon/rare/epic
            boolean fireResistant,
            Integer foodNutrition,      // null if not a food item
            Float foodSaturation,
            boolean foodAlwaysEdible    // can eat at full hunger (golden apple)
    ) {
        public Rarity rarityEnum() {
            return switch (rarity == null ? "common" : rarity.toLowerCase(Locale.ROOT)) {
                case "uncommon" -> Rarity.UNCOMMON;
                case "rare"     -> Rarity.RARE;
                case "epic"     -> Rarity.EPIC;
                default         -> Rarity.COMMON;
            };
        }
        public boolean isFood() { return foodNutrition != null && foodSaturation != null; }
        /** Auto-titlecase id if no display_name was provided ("magic_dust" -> "Magic Dust"). */
        public String resolvedDisplayName() {
            if (displayName != null && !displayName.isEmpty()) return displayName;
            String[] parts = id.split("_");
            StringBuilder sb = new StringBuilder();
            for (String p : parts) {
                if (p.isEmpty()) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
            }
            return sb.toString();
        }
    }

    /**
     * Call from the mod constructor BEFORE any other registries fire. Reads the
     * items.json (if any), creates a DeferredRegister, and schedules registration.
     * Safe to call even if no items.json exists — DeferredRegister still attaches
     * to the bus but with zero entries.
     */
    public static void bootstrap(IEventBus modBus, Path configDir) {
        ITEMS = DeferredRegister.createItems(NAMESPACE);

        // Seed a README in textures/ so the user knows where to drop PNGs.
        // We do this unconditionally each launch — costs nothing and helps
        // users who delete the file by accident.
        Path texturesReadme = configDir.resolve("textures").resolve("README.txt");
        if (!Files.exists(texturesReadme)) {
            try {
                Files.writeString(texturesReadme, DEFAULT_TEXTURES_README);
            } catch (IOException ignored) { /* harmless if it fails */ }
        }

        Path itemsJson = configDir.resolve("items.json");
        if (!Files.exists(itemsJson)) {
            // Seed a commented example so the user sees the schema. Items are
            // marked _disabled_ by being inside an "_examples" key (which we
            // ignore) — that way the first run doesn't surprise people with
            // registered items they didn't ask for.
            try {
                Files.writeString(itemsJson, DEFAULT_ITEMS_JSON);
                LiveScriptMod.LOGGER.info("[items] seeded default items.json at {}", itemsJson);
            } catch (IOException e) {
                LiveScriptMod.LOGGER.warn("[items] could not seed items.json", e);
            }
            ITEMS.register(modBus);
            return;
        }

        try {
            String content = Files.readString(itemsJson);
            JsonElement root = JsonParser.parseString(content);
            if (!root.isJsonObject()) {
                LiveScriptMod.LOGGER.error("[items] items.json: root must be an object");
                ITEMS.register(modBus);
                return;
            }
            JsonElement itemsNode = root.getAsJsonObject().get("items");
            if (itemsNode == null || !itemsNode.isJsonObject()) {
                LiveScriptMod.LOGGER.info("[items] items.json has no 'items' object, no custom items registered");
                ITEMS.register(modBus);
                return;
            }
            for (Map.Entry<String, JsonElement> e : itemsNode.getAsJsonObject().entrySet()) {
                String key = e.getKey();
                // Leading underscore = user disabled this entry, skip silently
                if (key.startsWith("_")) continue;
                if (!isValidItemKey(key)) {
                    LiveScriptMod.LOGGER.warn("[items] skipping invalid id '{}' (must be [a-z0-9_])", key);
                    continue;
                }
                if (!e.getValue().isJsonObject()) {
                    LiveScriptMod.LOGGER.warn("[items] skipping '{}' — definition must be an object", key);
                    continue;
                }
                ItemDef def = parseDef(key, e.getValue().getAsJsonObject());
                DEFINITIONS.put(key, def);
                registerOne(def);
            }
            LiveScriptMod.LOGGER.info("[items] registered {} custom item(s) from items.json", DEFINITIONS.size());
        } catch (IOException ex) {
            LiveScriptMod.LOGGER.error("[items] could not read items.json", ex);
        } catch (Exception ex) {
            // Malformed JSON, type mismatches in fields, etc — log and continue.
            LiveScriptMod.LOGGER.error("[items] failed to parse items.json — no items registered", ex);
        }

        ITEMS.register(modBus);
    }

    private static boolean isValidItemKey(String key) {
        if (key == null || key.isEmpty()) return false;
        // Leading underscore = user-disabled. We treat it as invalid and skip
        // silently — that's a common pattern for "comment out this item".
        if (key.charAt(0) == '_') return false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    private static ItemDef parseDef(String id, JsonObject obj) {
        String displayName = obj.has("display_name") ? obj.get("display_name").getAsString() : null;
        int stackSize = obj.has("max_stack_size") ? obj.get("max_stack_size").getAsInt() : 64;
        stackSize = Math.max(1, Math.min(99, stackSize));
        String rarity = obj.has("rarity") ? obj.get("rarity").getAsString() : "common";
        boolean fireRes = obj.has("fire_resistant") && obj.get("fire_resistant").getAsBoolean();

        Integer foodNut = null;
        Float foodSat = null;
        boolean alwaysEdible = false;
        if (obj.has("food") && obj.get("food").isJsonObject()) {
            JsonObject f = obj.getAsJsonObject("food");
            foodNut = f.has("nutrition") ? f.get("nutrition").getAsInt() : 0;
            foodSat = f.has("saturation") ? f.get("saturation").getAsFloat() : 0f;
            alwaysEdible = f.has("always_edible") && f.get("always_edible").getAsBoolean();
        }
        return new ItemDef(id, displayName, stackSize, rarity, fireRes,
                foodNut, foodSat, alwaysEdible);
    }

    /**
     * Build the Item.Properties chain from a def and call register on the
     * DeferredRegister. We assemble the property object inside the supplier
     * because some properties (foodProperties builder, etc.) can't be reused.
     */
    private static void registerOne(ItemDef def) {
        DeferredItem<Item> holder = ITEMS.registerItem(def.id, props -> {
            Item.Properties p = props
                    .stacksTo(def.maxStackSize)
                    .rarity(def.rarityEnum());
            if (def.fireResistant) p = p.fireResistant();
            if (def.isFood()) {
                FoodProperties.Builder fb = new FoodProperties.Builder()
                        .nutrition(def.foodNutrition)
                        .saturationModifier(def.foodSaturation);
                if (def.foodAlwaysEdible) fb = fb.alwaysEdible();
                p = p.food(fb.build());
            }
            return new Item(p);
        });
        ITEM_HOLDERS.put(def.id, holder);
    }

    /** Read-only snapshot of all loaded item definitions. */
    public static List<ItemDef> definitions() {
        return Collections.unmodifiableList(new ArrayList<>(DEFINITIONS.values()));
    }

    /** Lookup an item by its path-part id; null if unknown. */
    public static Item byId(String id) {
        DeferredItem<Item> h = ITEM_HOLDERS.get(id);
        return h == null ? null : h.get();
    }

    /**
     * Default sample items.json written on first run. Includes a commented
     * "_examples" block that's ignored by the loader (only "items" is read)
     * so the user can see the schema without surprise items appearing.
     *
     * Note: GSON parses this fine because comments aren't actually JSON-comments,
     * they're just keys with "_" prefix that we ignore. Real JSON doesn't allow
     * // comments.
     */
    private static final String DEFAULT_ITEMS_JSON = """
            {
              "_about": [
                "LiveScript custom items. Edit this file then restart the server.",
                "Each entry under 'items' becomes a 'livescript:<key>' item.",
                "Textures: drop a PNG named <key>.png in the textures/ folder."
              ],
              "_examples": {
                "magic_dust": {
                  "display_name": "Magic Dust",
                  "max_stack_size": 64,
                  "rarity": "rare"
                },
                "phoenix_feather": {
                  "max_stack_size": 16,
                  "fire_resistant": true
                },
                "soul_bread": {
                  "max_stack_size": 16,
                  "food": { "nutrition": 6, "saturation": 0.8, "always_edible": true }
                }
              },
              "items": {
              }
            }
            """;

    private static final String DEFAULT_TEXTURES_README = """
            LiveScript custom item textures
            ================================

            Drop 16x16 PNG files in this folder, named the same as your item id
            in items.json. For an item with id "magic_dust", the texture file
            must be:

                magic_dust.png

            Larger sizes (32x32, 64x64, 128x128) also work — Minecraft scales them
            for inventory icons. Power-of-two sizes only.

            Items without a matching PNG show the magenta/black missing-texture
            pattern. The item is still usable; it just has no icon.

            Live reload: press F3+T in-game after editing or adding a texture to
            see the change immediately. No server restart needed for textures
            (only for items.json changes).
            """;
}
