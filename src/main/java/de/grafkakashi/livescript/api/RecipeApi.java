package de.grafkakashi.livescript.api;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import de.grafkakashi.livescript.LiveScriptMod;
import de.grafkakashi.livescript.engine.ScriptContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime recipe manipulation.
 *
 * The {@link RecipeManager} keeps two private collections (since 1.20.6):
 *   - {@code byName}: Map&lt;ResourceLocation, RecipeHolder&lt;?&gt;&gt; — all recipes by id
 *   - {@code byType}: Multimap&lt;RecipeType&lt;?&gt;, RecipeHolder&lt;?&gt;&gt; — recipes grouped by type
 * Both default to immutable builders. On first {@link #ensureMutable()} call we
 * reflectively swap them for mutable replacements; everything after that is
 * normal Map/Multimap mutation.
 *
 * After every batch of changes we push a {@link ClientboundUpdateRecipesPacket}
 * to all connected players so the client knows the recipes are gone/added and
 * the crafting UI works correctly. Without this, the server matches recipes
 * correctly but the client thinks no recipe applies.
 *
 * Persistence: changes are LIVE only. Re-apply on every server start (autoRunOnSave
 * helps here).
 */
public class RecipeApi {
    private final ScriptContext ctx;
    private RecipeManager cachedManager;
    private Map<ResourceLocation, RecipeHolder<?>> mutableById;
    private Multimap<RecipeType<?>, RecipeHolder<?>> mutableByType;

    public RecipeApi(ScriptContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Remove all recipes whose output item matches the given id (e.g. "minecraft:torch").
     * Returns the number of recipes removed.
     */
    public int removeByOutput(String itemId) {
        if (!ensureMutable()) return 0;
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        if (rl == null) {
            ctx.print("[recipe] bad item id: " + itemId);
            return 0;
        }
        Item target = BuiltInRegistries.ITEM.get(rl);
        if (target == null || target == net.minecraft.world.item.Items.AIR) {
            ctx.print("[recipe] item not found: " + itemId);
            return 0;
        }

        List<ResourceLocation> toRemove = new ArrayList<>();
        var registryAccess = ctx.server().registryAccess();
        for (var entry : mutableById.entrySet()) {
            try {
                var stack = entry.getValue().value().getResultItem(registryAccess);
                if (stack != null && stack.is(target)) {
                    toRemove.add(entry.getKey());
                }
            } catch (Throwable t) {
                // Some modded recipes throw on getResultItem without proper context — skip
            }
        }
        for (ResourceLocation id : toRemove) {
            RecipeHolder<?> removed = mutableById.remove(id);
            if (removed != null) mutableByType.remove(removed.value().getType(), removed);
        }
        if (!toRemove.isEmpty()) syncToAll();
        ctx.print("[recipe] removed " + toRemove.size() + " recipe(s) producing " + itemId);
        return toRemove.size();
    }

    /** Remove a single recipe by its registry id (e.g. "minecraft:torch"). */
    public boolean removeById(String recipeId) {
        if (!ensureMutable()) return false;
        ResourceLocation rl = ResourceLocation.tryParse(recipeId);
        if (rl == null) return false;
        RecipeHolder<?> removed = mutableById.remove(rl);
        if (removed != null) {
            mutableByType.remove(removed.value().getType(), removed);
            syncToAll();
            ctx.print("[recipe] removed " + recipeId);
            return true;
        }
        return false;
    }

    /** Total recipe count across all types. */
    public int count() {
        if (cachedManager == null) cachedManager = ctx.server().getRecipeManager();
        return cachedManager.getRecipes().size();
    }

    /**
     * Debug helper — how many recipes are registered for a given recipe type.
     * Pass the bare type name like "smelting", "smoking", "blasting",
     * "campfire_cooking", "stonecutting", "crafting".
     *
     * Usage in script:
     *   print('smelting: ' + recipe.debugCountForType('smelting'));
     *   print('smoking:  ' + recipe.debugCountForType('smoking'));
     *
     * If smelting shows count 0 even after smeltAdd, the recipe didn't land in
     * the byType multimap and the furnace will never find it.
     */
    public int debugCountForType(String typeName) {
        if (cachedManager == null) cachedManager = ctx.server().getRecipeManager();
        // Resolve the RecipeType via its registry id
        var typeId = ResourceLocation.tryParse(
                typeName.contains(":") ? typeName : "minecraft:" + typeName);
        if (typeId == null) return -1;
        net.minecraft.world.item.crafting.RecipeType<?> rt =
                BuiltInRegistries.RECIPE_TYPE.get(typeId);
        if (rt == null) return -1;
        int n = 0;
        for (var h : cachedManager.getRecipes()) {
            if (h.value().getType() == rt) n++;
        }
        return n;
    }

    // ============================================================
    //  Recipe addition
    // ============================================================

    /**
     * Add a shaped crafting recipe. Spec is a script-friendly map shape:
     *
     *   recipe.shapedAdd({
     *     id:      "livescript:my_torch",
     *     pattern: ["X", "Y"],
     *     key:     { X: "minecraft:coal", Y: "minecraft:stick" },
     *     result:  "minecraft:torch",
     *     group:   "torches",
     *     category:"BUILDING"
     *   })
     */
    public String shapedAdd(Object spec) {
        if (!ensureMutable()) return null;
        try {
            SpecAdapter s = SpecAdapter.wrap(spec);
            List<String> pattern = s.getStringList("pattern");
            Map<String, Object> keyMap = s.getMap("key");
            if (pattern == null || pattern.isEmpty() || keyMap == null) {
                ctx.print("[recipe] shapedAdd: needs pattern[] and key{}");
                return null;
            }

            Map<Character, net.minecraft.world.item.crafting.Ingredient> ingMap = new HashMap<>();
            for (var e : keyMap.entrySet()) {
                if (e.getKey().length() != 1) {
                    ctx.print("[recipe] shapedAdd: key '" + e.getKey() + "' must be a single char");
                    return null;
                }
                var ing = parseIngredient(e.getValue());
                if (ing == null) return null;
                ingMap.put(e.getKey().charAt(0), ing);
            }

            var resultStack = parseResultStack(s.get("result"));
            if (resultStack == null) return null;

            var shapedPattern = net.minecraft.world.item.crafting.ShapedRecipePattern.of(
                    ingMap, pattern.toArray(new String[0]));

            String group = s.getString("group", "");
            var category = parseCraftingCategory(s.getString("category", "MISC"));

            var recipe = new net.minecraft.world.item.crafting.ShapedRecipe(
                    group, category, shapedPattern, resultStack);

            String idStr = s.getString("id", "livescript:dyn_" + System.nanoTime());
            var rl = ResourceLocation.tryParse(idStr);
            if (rl == null) { ctx.print("[recipe] bad id: " + idStr); return null; }
            var holder = new RecipeHolder<>(rl, recipe);

            registerHolder(rl, holder);
            syncToAll();
            ctx.print("[recipe] added shaped " + idStr);
            return idStr;
        } catch (Throwable t) {
            LiveScriptMod.LOGGER.warn("shapedAdd failed", t);
            ctx.print("[recipe] shapedAdd failed: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
            return null;
        }
    }

    /**
     * Add a shapeless recipe.
     *
     *   recipe.shapelessAdd({
     *     id:        "livescript:my_dough",
     *     ingredients: ["minecraft:wheat", "minecraft:wheat", "minecraft:water_bucket"],
     *     result:    "minecraft:bread"
     *   })
     */
    public String shapelessAdd(Object spec) {
        if (!ensureMutable()) return null;
        try {
            SpecAdapter s = SpecAdapter.wrap(spec);
            List<Object> rawIngs = s.getList("ingredients");
            if (rawIngs == null || rawIngs.isEmpty()) {
                ctx.print("[recipe] shapelessAdd: needs ingredients[]");
                return null;
            }

            var ingList = net.minecraft.core.NonNullList
                    .<net.minecraft.world.item.crafting.Ingredient>create();
            for (Object o : rawIngs) {
                var ing = parseIngredient(o);
                if (ing == null) return null;
                ingList.add(ing);
            }

            var resultStack = parseResultStack(s.get("result"));
            if (resultStack == null) return null;

            String group = s.getString("group", "");
            var category = parseCraftingCategory(s.getString("category", "MISC"));

            var recipe = new net.minecraft.world.item.crafting.ShapelessRecipe(
                    group, category, resultStack, ingList);

            String idStr = s.getString("id", "livescript:dyn_" + System.nanoTime());
            var rl = ResourceLocation.tryParse(idStr);
            if (rl == null) { ctx.print("[recipe] bad id: " + idStr); return null; }
            var holder = new RecipeHolder<>(rl, recipe);

            registerHolder(rl, holder);
            syncToAll();
            ctx.print("[recipe] added shapeless " + idStr);
            return idStr;
        } catch (Throwable t) {
            LiveScriptMod.LOGGER.warn("shapelessAdd failed", t);
            ctx.print("[recipe] shapelessAdd failed: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
            return null;
        }
    }

    // ============================================================
    //  Cooking + Stonecutter recipes
    // ============================================================

    /**
     * Add a furnace (smelting) recipe.
     *
     *   recipe.smeltAdd({
     *     id:         "livescript:ingot_from_block",
     *     ingredient: "minecraft:diamond_block",
     *     result:     "minecraft:diamond",
     *     experience: 1.0,    // optional, default 0
     *     cookTime:   200,    // optional, default 200 (10s)
     *     category:   "MISC"  // optional: FOOD, BLOCKS, MISC
     *   })
     */
    public String smeltAdd(Object spec) {
        return cookingAdd(spec, CookingKind.SMELT);
    }

    /** Smoker recipe — same shape as smeltAdd; default cookTime is 100 (half of furnace). */
    public String smokerAdd(Object spec) {
        return cookingAdd(spec, CookingKind.SMOKE);
    }

    /** Blast furnace recipe — same shape; default cookTime is 100. */
    public String blastAdd(Object spec) {
        return cookingAdd(spec, CookingKind.BLAST);
    }

    /** Campfire recipe — same shape; default cookTime is 600 (30s, slower than furnace). */
    public String campfireAdd(Object spec) {
        return cookingAdd(spec, CookingKind.CAMPFIRE);
    }

    /**
     * Stonecutter recipe — no experience, no cook time, no category. The stonecutter
     * also accepts a {@code count} on the result so you can mint many slabs from one block.
     *
     *   recipe.stoneCutAdd({
     *     id:         "livescript:diamond_slab_from_block",
     *     ingredient: "minecraft:diamond_block",
     *     result:     { item: "minecraft:diamond", count: 9 }
     *   })
     */
    public String stoneCutAdd(Object spec) {
        if (!ensureMutable()) return null;
        try {
            SpecAdapter s = SpecAdapter.wrap(spec);
            var ing = parseIngredient(s.get("ingredient"));
            if (ing == null) {
                ctx.print("[recipe] stoneCutAdd: needs ingredient");
                return null;
            }
            var resultStack = parseResultStack(s.get("result"));
            if (resultStack == null) return null;

            String group = s.getString("group", "");
            var recipe = new net.minecraft.world.item.crafting.StonecutterRecipe(
                    group, ing, resultStack);

            String idStr = s.getString("id", "livescript:dyn_" + System.nanoTime());
            var rl = ResourceLocation.tryParse(idStr);
            if (rl == null) { ctx.print("[recipe] bad id: " + idStr); return null; }
            registerHolder(rl, new RecipeHolder<>(rl, recipe));
            syncToAll();
            ctx.print("[recipe] added stonecut " + idStr);
            return idStr;
        } catch (Throwable t) {
            LiveScriptMod.LOGGER.warn("stoneCutAdd failed", t);
            ctx.print("[recipe] stoneCutAdd failed: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
            return null;
        }
    }

    /** Discriminator for the four cooking recipes — they share a constructor shape. */
    private enum CookingKind {
        SMELT  (200, "smelt"),
        SMOKE  (100, "smoker"),
        BLAST  (100, "blast"),
        CAMPFIRE(600, "campfire");

        final int defaultCookTime;
        final String label; // used in console output
        CookingKind(int defaultCookTime, String label) {
            this.defaultCookTime = defaultCookTime;
            this.label = label;
        }
    }

    /**
     * Shared add path for smelt/smoke/blast/campfire — these all extend
     * {@link net.minecraft.world.item.crafting.AbstractCookingRecipe} with the
     * same constructor signature (group, category, ingredient, result, exp, time).
     * Only the concrete subclass and the default cook time differ.
     */
    private String cookingAdd(Object spec, CookingKind kind) {
        if (!ensureMutable()) return null;
        try {
            SpecAdapter s = SpecAdapter.wrap(spec);
            var ing = parseIngredient(s.get("ingredient"));
            if (ing == null) {
                ctx.print("[recipe] " + kind.label + "Add: needs ingredient");
                return null;
            }
            var resultStack = parseResultStack(s.get("result"));
            if (resultStack == null) return null;

            String group = s.getString("group", "");
            float experience = (float) s.getDouble("experience", 0.0);
            int cookTime = (int) s.getDouble("cookTime", kind.defaultCookTime);
            var category = parseCookingCategory(s.getString("category", "MISC"));

            net.minecraft.world.item.crafting.AbstractCookingRecipe recipe = switch (kind) {
                case SMELT   -> new net.minecraft.world.item.crafting.SmeltingRecipe(
                        group, category, ing, resultStack, experience, cookTime);
                case SMOKE   -> new net.minecraft.world.item.crafting.SmokingRecipe(
                        group, category, ing, resultStack, experience, cookTime);
                case BLAST   -> new net.minecraft.world.item.crafting.BlastingRecipe(
                        group, category, ing, resultStack, experience, cookTime);
                case CAMPFIRE-> new net.minecraft.world.item.crafting.CampfireCookingRecipe(
                        group, category, ing, resultStack, experience, cookTime);
            };

            String idStr = s.getString("id", "livescript:dyn_" + System.nanoTime());
            var rl = ResourceLocation.tryParse(idStr);
            if (rl == null) { ctx.print("[recipe] bad id: " + idStr); return null; }
            registerHolder(rl, new RecipeHolder<>(rl, recipe));
            syncToAll();
            ctx.print("[recipe] added " + kind.label + " " + idStr);
            return idStr;
        } catch (Throwable t) {
            LiveScriptMod.LOGGER.warn(kind.label + "Add failed", t);
            ctx.print("[recipe] " + kind.label + "Add failed: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
            return null;
        }
    }

    // ============================================================
    //  Reflection: swap immutable internal maps for mutable replacements
    // ============================================================

    /**
     * Reflectively obtain mutable views of the RecipeManager's internal collections.
     * Looks up fields by their generic type (Map vs Multimap) rather than name,
     * which survives Parchment mapping changes.
     */
    @SuppressWarnings("unchecked")
    private boolean ensureMutable() {
        if (mutableById != null) return true;
        MinecraftServer server = ctx.server();
        if (server == null) return false;
        cachedManager = server.getRecipeManager();

        try {
            Field byNameField = null;
            Field byTypeField = null;
            for (Field f : RecipeManager.class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                Class<?> ft = f.getType();
                if (Multimap.class.isAssignableFrom(ft)) {
                    byTypeField = f;
                } else if (Map.class.isAssignableFrom(ft)) {
                    byNameField = f;
                }
            }
            if (byNameField == null) byNameField = tryField("byName", "f_44008_", "recipes");
            if (byTypeField == null) byTypeField = tryField("byType", "f_44007_");

            if (byNameField == null) throw new NoSuchFieldException("no Map field on RecipeManager");
            if (byTypeField == null) throw new NoSuchFieldException("no Multimap field on RecipeManager");

            byNameField.setAccessible(true);
            byTypeField.setAccessible(true);

            mutableById = new HashMap<>(
                    (Map<ResourceLocation, RecipeHolder<?>>) byNameField.get(cachedManager));
            mutableByType = HashMultimap.create(
                    (Multimap<RecipeType<?>, RecipeHolder<?>>) byTypeField.get(cachedManager));

            byNameField.set(cachedManager, mutableById);
            byTypeField.set(cachedManager, mutableByType);
            return true;
        } catch (Throwable t) {
            LiveScriptMod.LOGGER.warn("recipe mutation unavailable", t);
            ctx.print("[recipe] mutation unavailable: " + t.getClass().getSimpleName()
                    + " — " + t.getMessage());
            return false;
        }
    }

    private static Field tryField(String... names) {
        for (String n : names) {
            try { return RecipeManager.class.getDeclaredField(n); }
            catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private void registerHolder(ResourceLocation rl, RecipeHolder<?> holder) {
        mutableById.put(rl, holder);
        mutableByType.put(holder.value().getType(), holder);
    }

    // ============================================================
    //  Client sync — push the updated recipe list to every connected player
    // ============================================================

    /**
     * Send the current full recipe set to every connected player. Without this,
     * crafting GUIs would show "no recipe" for our additions because the client
     * keeps its own cached copy synced only on login and /reload.
     */
    private void syncToAll() {
        if (cachedManager == null) return;
        var allRecipes = new ArrayList<>(mutableById.values());
        var pkt = new ClientboundUpdateRecipesPacket(allRecipes);
        for (ServerPlayer p : ctx.server().getPlayerList().getPlayers()) {
            p.connection.send(pkt);
        }
    }

    // ============================================================
    //  Spec helpers
    // ============================================================

    private net.minecraft.world.item.crafting.Ingredient parseIngredient(Object raw) {
        if (raw == null) return null;
        if (raw instanceof String str) {
            if (str.startsWith("#")) {
                var rl = ResourceLocation.tryParse(str.substring(1));
                if (rl == null) { ctx.print("[recipe] bad tag: " + str); return null; }
                return net.minecraft.world.item.crafting.Ingredient.of(
                        net.minecraft.tags.TagKey.create(
                                net.minecraft.core.registries.Registries.ITEM, rl));
            }
            var rl = ResourceLocation.tryParse(str);
            if (rl == null) { ctx.print("[recipe] bad item: " + str); return null; }
            var item = BuiltInRegistries.ITEM.get(rl);
            return net.minecraft.world.item.crafting.Ingredient.of(item);
        }
        SpecAdapter s = SpecAdapter.wrap(raw);
        if (s.has("tag")) return parseIngredient("#" + s.getString("tag", ""));
        if (s.has("item")) return parseIngredient(s.getString("item", ""));
        ctx.print("[recipe] ingredient must be string, {item:...}, or {tag:...}");
        return null;
    }

    private net.minecraft.world.item.ItemStack parseResultStack(Object raw) {
        if (raw == null) { ctx.print("[recipe] missing result"); return null; }
        if (raw instanceof String s) {
            var rl = ResourceLocation.tryParse(s);
            if (rl == null) { ctx.print("[recipe] bad result: " + s); return null; }
            var item = BuiltInRegistries.ITEM.get(rl);
            if (item == net.minecraft.world.item.Items.AIR && !s.equals("minecraft:air")) {
                ctx.print("[recipe] result item not found: " + s);
                return null;
            }
            return new net.minecraft.world.item.ItemStack(item, 1);
        }
        SpecAdapter s = SpecAdapter.wrap(raw);
        String itemId = s.getString("item", null);
        if (itemId == null) { ctx.print("[recipe] result needs item:..."); return null; }
        int count = (int) s.getDouble("count", 1);
        var rl = ResourceLocation.tryParse(itemId);
        var item = BuiltInRegistries.ITEM.get(rl);
        if (item == net.minecraft.world.item.Items.AIR && !itemId.equals("minecraft:air")) {
            ctx.print("[recipe] result item not found: " + itemId);
            return null;
        }
        var stack = new net.minecraft.world.item.ItemStack(item, count);
        applyDecorations(stack, s);
        return stack;
    }

    /**
     * Apply optional cosmetic data-component patches to {@code stack}:
     *
     *   name  → DataComponents.CUSTOM_NAME    (Component, rendered italic by default;
     *                                          we wrap in withItalic(false) so authored
     *                                          names render upright like vanilla
     *                                          renamed items)
     *   lore  → DataComponents.LORE           (List<Component>, one entry per line)
     *   glow  → DataComponents.ENCHANTMENT_GLINT_OVERRIDE (true = enchanted shimmer
     *                                                     without actual enchantments)
     *
     * All three are independent — pass any subset. Color codes (§a, §c …) and
     * legacy formatting work in name + lore via {@link Component#literal} since
     * vanilla parses them when rendering.
     */
    private void applyDecorations(net.minecraft.world.item.ItemStack stack, SpecAdapter s) {
        // --- custom name ---
        String name = s.getString("name", null);
        if (name != null && !name.isEmpty()) {
            net.minecraft.network.chat.MutableComponent nameComp =
                    net.minecraft.network.chat.Component.literal(name)
                            .withStyle(style -> style.withItalic(false));
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, nameComp);
        }

        // --- lore (array of strings, one per line) ---
        Object loreRaw = s.get("lore");
        if (loreRaw != null) {
            java.util.List<net.minecraft.network.chat.Component> lines = new java.util.ArrayList<>();
            if (loreRaw instanceof Iterable<?> it) {
                for (Object line : it) {
                    if (line == null) continue;
                    // Lore lines render italic + dark-purple by default in vanilla;
                    // strip italic to match what most KubeJS/CraftTweaker users expect
                    lines.add(net.minecraft.network.chat.Component.literal(String.valueOf(line))
                            .withStyle(style -> style.withItalic(false)));
                }
            } else if (loreRaw.getClass().isArray()) {
                int len = java.lang.reflect.Array.getLength(loreRaw);
                for (int i = 0; i < len; i++) {
                    Object line = java.lang.reflect.Array.get(loreRaw, i);
                    if (line == null) continue;
                    lines.add(net.minecraft.network.chat.Component.literal(String.valueOf(line))
                            .withStyle(style -> style.withItalic(false)));
                }
            } else if (loreRaw instanceof String singleLine) {
                lines.add(net.minecraft.network.chat.Component.literal(singleLine)
                        .withStyle(style -> style.withItalic(false)));
            }
            if (!lines.isEmpty()) {
                stack.set(net.minecraft.core.component.DataComponents.LORE,
                        new net.minecraft.world.item.component.ItemLore(lines));
            }
        }

        // --- enchantment glint override (without actual enchantments) ---
        if (s.getBoolean("glow", false)) {
            stack.set(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE, Boolean.TRUE);
        }
    }

    private net.minecraft.world.item.crafting.CraftingBookCategory parseCraftingCategory(String name) {
        try {
            return net.minecraft.world.item.crafting.CraftingBookCategory.valueOf(
                    name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return net.minecraft.world.item.crafting.CraftingBookCategory.MISC;
        }
    }

    private net.minecraft.world.item.crafting.CookingBookCategory parseCookingCategory(String name) {
        // CookingBookCategory has only FOOD, BLOCKS, MISC — anything else falls through to MISC
        try {
            return net.minecraft.world.item.crafting.CookingBookCategory.valueOf(
                    name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return net.minecraft.world.item.crafting.CookingBookCategory.MISC;
        }
    }
}
