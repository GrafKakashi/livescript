package de.grafkakashi.livescript.api;

import de.grafkakashi.livescript.engine.ScriptContext;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only tag/item lookup. Mutating tags at runtime is not possible in
 * 1.21.1 — tags are baked at datapack-load time. These helpers cover the
 * 90%-case: "which items are in this tag", "does this stack carry this tag".
 */
public class TagApi {
    private final ScriptContext ctx;

    public TagApi(ScriptContext ctx) {
        this.ctx = ctx;
    }

    /** List item ids in an item tag (e.g. "minecraft:logs" or "c:ores"). */
    public List<String> itemsIn(String tagId) {
        ResourceLocation rl = ResourceLocation.tryParse(tagId);
        if (rl == null) { ctx.print("[tag] bad id: " + tagId); return List.of(); }
        TagKey<Item> key = TagKey.create(net.minecraft.core.registries.Registries.ITEM, rl);
        List<String> out = new ArrayList<>();
        for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(key)) {
            h.unwrapKey().ifPresent(k -> out.add(k.location().toString()));
        }
        return out;
    }

    /** True if {@code stack} carries the given item tag. */
    public boolean itemHas(ItemStack stack, String tagId) {
        if (stack == null) return false;
        ResourceLocation rl = ResourceLocation.tryParse(tagId);
        if (rl == null) return false;
        return stack.is(TagKey.create(net.minecraft.core.registries.Registries.ITEM, rl));
    }

    /** List block ids in a block tag. */
    public List<String> blocksIn(String tagId) {
        ResourceLocation rl = ResourceLocation.tryParse(tagId);
        if (rl == null) { ctx.print("[tag] bad id: " + tagId); return List.of(); }
        TagKey<Block> key = TagKey.create(net.minecraft.core.registries.Registries.BLOCK, rl);
        List<String> out = new ArrayList<>();
        for (Holder<Block> h : BuiltInRegistries.BLOCK.getTagOrEmpty(key)) {
            h.unwrapKey().ifPresent(k -> out.add(k.location().toString()));
        }
        return out;
    }
}
