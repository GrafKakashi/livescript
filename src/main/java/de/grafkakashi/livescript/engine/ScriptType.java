package de.grafkakashi.livescript.engine;

import java.util.Locale;

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
}
