package de.grafkakashi.livescript.api;

import de.grafkakashi.livescript.engine.ScriptContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Item registry lookups. Read-only — runtime item *creation* would require
 * hooking into the mod-init RegisterEvent, which can't happen post-startup.
 */
public class ItemApi {
    private final ScriptContext ctx;

    public ItemApi(ScriptContext ctx) {
        this.ctx = ctx;
    }

    /** Look up an item by registry id; returns Items.AIR if not found. */
    public Item find(String itemId) {
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        if (rl == null) { ctx.print("[item] bad id: " + itemId); return Items.AIR; }
        Item it = BuiltInRegistries.ITEM.get(rl);
        if (it == Items.AIR && !itemId.equals("minecraft:air")) {
            ctx.print("[item] not found: " + itemId);
        }
        return it;
    }

    /** Build an ItemStack from an id and count. */
    public ItemStack stack(String itemId, int count) {
        Item it = find(itemId);
        return it == Items.AIR ? ItemStack.EMPTY : new ItemStack(it, count);
    }

    /** Resolve an item's registry id. */
    public String idOf(Item item) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return key == null ? "minecraft:air" : key.toString();
    }

    /** List all item ids matching a prefix (e.g. "minecraft:diamond" → diamond, diamond_block, ...). */
    public List<String> findByPrefix(String prefix) {
        List<String> out = new ArrayList<>();
        for (ResourceLocation key : BuiltInRegistries.ITEM.keySet()) {
            if (key.toString().startsWith(prefix)) out.add(key.toString());
        }
        return out;
    }
}
