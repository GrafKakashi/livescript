package de.grafkakashi.livescript.items;

import com.google.gson.JsonObject;
import de.grafkakashi.livescript.LiveScriptMod;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.neoforged.neoforge.event.AddPackFindersEvent;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * Synthesises a virtual resource pack at runtime that supplies the assets for
 * {@link CustomItemRegistry custom items}: per-item model JSONs, the user's
 * texture PNGs (read from {@code config/livescript/textures/<id>.png}), and a
 * lang file mapping translation keys to display names.
 *
 * <p>Nothing is materialised on disk. Mojang's {@link PackResources} interface
 * is "give me a resource at this path"; we just synthesise JSON on demand and
 * read PNGs straight from the config folder. If the user changes a texture and
 * presses F3+T, they see it immediately.
 *
 * <p>Registered via {@link AddPackFindersEvent} on
 * {@link PackType#CLIENT_RESOURCES}, with {@code Pack.Position.TOP} so it
 * overrides anything the user might have in a lower pack, and {@code defaultEnabled=true}
 * so users don't have to enable it manually.
 *
 * <p><b>Status:</b> first cut. Mojang's pack API has many subtle method
 * signatures; expect compile errors on first build. Iterate from those.
 */
public final class CustomItemResourcePack {
    private CustomItemResourcePack() {}

    private static final String PACK_ID = "livescript_custom_items";
    private static final String NS = CustomItemRegistry.NAMESPACE;
    /** pack_format for MC 1.21 / 1.21.1 — see https://minecraft.wiki/w/Pack_format */
    private static final int PACK_FORMAT = 34;

    public static void onAddPackFinders(AddPackFindersEvent event) {
        // Client assets only. Custom items don't need a data pack — they're
        // registered in code, not via JSON recipes.
        if (event.getPackType() != PackType.CLIENT_RESOURCES) return;

        Path texturesDir = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                .resolve(LiveScriptMod.MOD_ID).resolve("textures");

        PackLocationInfo location = new PackLocationInfo(
                PACK_ID,
                Component.literal("LiveScript Custom Items"),
                PackSource.BUILT_IN,
                Optional.empty());

        PackSelectionConfig selection = new PackSelectionConfig(
                /*defaultEnabled*/ true,
                Pack.Position.TOP,
                /*fixedPosition*/ false);

        event.addRepositorySource(consumer -> {
            Pack pack = Pack.readMetaAndCreate(
                    location,
                    new PackFactory(texturesDir),
                    PackType.CLIENT_RESOURCES,
                    selection);
            if (pack != null) consumer.accept(pack);
        });
    }

    /**
     * Factory that produces a fresh {@link DynamicPackResources} every time the
     * client (re)loads resources. F3+T re-invokes this so texture changes appear
     * without restart.
     */
    private static final class PackFactory implements Pack.ResourcesSupplier {
        private final Path texturesDir;
        PackFactory(Path texturesDir) { this.texturesDir = texturesDir; }

        @Override public PackResources openPrimary(PackLocationInfo info) {
            return new DynamicPackResources(info, texturesDir);
        }
        @Override public PackResources openFull(PackLocationInfo info, Pack.Metadata metadata) {
            return new DynamicPackResources(info, texturesDir);
        }
    }

    /**
     * The actual virtual filesystem. Vanilla calls {@link #getResource} with
     * paths like {@code assets/livescript/models/item/magic_dust.json}; we
     * recognise the patterns and synthesise the file content.
     *
     * <p>Resource types served:
     * <ul>
     *   <li>{@code pack.mcmeta} — pack metadata</li>
     *   <li>{@code models/item/<id>.json} — generated item model</li>
     *   <li>{@code textures/item/<id>.png} — PNG read from config dir</li>
     *   <li>{@code lang/en_us.json} — all display names in one file</li>
     * </ul>
     */
    private static final class DynamicPackResources implements PackResources {
        private final PackLocationInfo info;
        private final Path texturesDir;

        DynamicPackResources(PackLocationInfo info, Path texturesDir) {
            this.info = info;
            this.texturesDir = texturesDir;
        }

        @Override public PackLocationInfo location() { return info; }
        @Override public String packId()             { return info.id(); }

        @Override
        public IoSupplier<InputStream> getRootResource(String... pathParts) {
            if (pathParts.length == 1 && "pack.mcmeta".equals(pathParts[0])) {
                return () -> bytes(buildPackMcmeta());
            }
            return null;
        }

        @Override
        public IoSupplier<InputStream> getResource(PackType type, ResourceLocation loc) {
            if (type != PackType.CLIENT_RESOURCES) return null;
            if (!NS.equals(loc.getNamespace())) return null;
            String path = loc.getPath();

            // Item model: models/item/<id>.json
            if (path.startsWith("models/item/") && path.endsWith(".json")) {
                String id = path.substring("models/item/".length(), path.length() - ".json".length());
                if (CustomItemRegistry.byId(id) == null) return null;
                return () -> bytes(buildItemModel(id));
            }

            // Texture: textures/item/<id>.png  →  config/livescript/textures/<id>.png
            if (path.startsWith("textures/item/") && path.endsWith(".png")) {
                String id = path.substring("textures/item/".length(), path.length() - ".png".length());
                Path pngFile = texturesDir.resolve(id + ".png");
                if (!Files.isRegularFile(pngFile)) return null;
                return () -> Files.newInputStream(pngFile);
            }

            // Lang file
            if ("lang/en_us.json".equals(path)) {
                return () -> bytes(buildLangFile());
            }

            return null;
        }

        @Override
        public void listResources(PackType type, String namespace, String prefix, ResourceOutput output) {
            if (type != PackType.CLIENT_RESOURCES) return;
            if (!NS.equals(namespace)) return;

            for (CustomItemRegistry.ItemDef def : CustomItemRegistry.definitions()) {
                emitIfMatching(prefix, "models/item/" + def.id() + ".json", output);
                emitIfMatching(prefix, "lang/en_us.json", output);
                Path png = texturesDir.resolve(def.id() + ".png");
                if (Files.isRegularFile(png)) {
                    emitIfMatching(prefix, "textures/item/" + def.id() + ".png", output);
                }
            }
        }

        private void emitIfMatching(String prefix, String path, ResourceOutput output) {
            if (!path.startsWith(prefix)) return;
            ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(NS, path);
            IoSupplier<InputStream> s = getResource(PackType.CLIENT_RESOURCES, rl);
            if (s != null) output.accept(rl, s);
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            if (type != PackType.CLIENT_RESOURCES) return Set.of();
            return Set.of(NS);
        }

        @Override
        public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) {
            // For the "pack" section, build a fresh JsonObject and deserialise.
            // For anything else (overlay, language, etc.) we return null.
            if ("pack".equals(serializer.getMetadataSectionName())) {
                JsonObject pack = new JsonObject();
                pack.addProperty("pack_format", PACK_FORMAT);
                pack.addProperty("description", "Auto-generated for LiveScript items");
                try {
                    return serializer.fromJson(pack);
                } catch (Exception e) {
                    LiveScriptMod.LOGGER.warn("[items-pack] failed to build pack metadata", e);
                    return null;
                }
            }
            return null;
        }

        @Override public void close() { /* no resources to release */ }

        // ----- content builders -----

        private String buildPackMcmeta() {
            JsonObject root = new JsonObject();
            JsonObject pack = new JsonObject();
            pack.addProperty("pack_format", PACK_FORMAT);
            pack.addProperty("description", "Auto-generated for LiveScript items");
            root.add("pack", pack);
            return root.toString();
        }

        /** Standard "flat 2D item" model — the same template every vanilla item uses. */
        private String buildItemModel(String id) {
            JsonObject root = new JsonObject();
            root.addProperty("parent", "minecraft:item/generated");
            JsonObject textures = new JsonObject();
            textures.addProperty("layer0", NS + ":item/" + id);
            root.add("textures", textures);
            return root.toString();
        }

        /** Single en_us.json with all display names. */
        private String buildLangFile() {
            JsonObject root = new JsonObject();
            for (CustomItemRegistry.ItemDef def : CustomItemRegistry.definitions()) {
                String key = "item." + NS + "." + def.id();
                root.addProperty(key, def.resolvedDisplayName());
            }
            return root.toString();
        }

        private static InputStream bytes(String s) {
            return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
