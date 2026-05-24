package de.grafkakashi.livescript.engine;

import java.util.Locale;

/**
 * What kind of executable script a file holds. JS and Lua are real script
 * types — the engine knows how to run them. JSON config files (items.json,
 * future others) live alongside scripts in the editor but are NOT a
 * ScriptType because they aren't executed; see {@link #isEditableExtension}
 * for the broader "what can show up in the file tree" check.
 */
public enum ScriptType {
    JS("js", "JavaScript"),
    LUA("lua", "Lua");

    public final String extension;
    public final String displayName;

    ScriptType(String extension, String displayName) {
        this.extension = extension;
        this.displayName = displayName;
    }

    public static ScriptType fromExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".js")) return JS;
        if (lower.endsWith(".lua")) return LUA;
        return null;
    }

    /**
     * True for any file extension the editor can show and save: scripts and
     * config JSON. Used everywhere we'd otherwise have said "is this a script"
     * but now also need to admit JSON. Note that "editable" doesn't mean
     * "executable" — the Run button still only fires for JS/Lua.
     */
    public static boolean isEditableExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".js") || lower.endsWith(".lua") || lower.endsWith(".json");
    }
}
