package de.grafkakashi.livescript.client.gui.autocomplete;

import de.grafkakashi.livescript.engine.ScriptType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Static completions for the editor. Knows the binding API and language keywords.
 *
 * Future: hook this into Rhino's parser for context-aware completion (object members etc.).
 */
public class CompletionProvider {
    private static final List<String> JS_BINDINGS = List.of(
            "print", "broadcast", "server", "on", "cancel",
            "recipe", "recipe.removeByOutput", "recipe.removeById", "recipe.count",
            "recipe.shapedAdd", "recipe.shapelessAdd",
            "tag", "tag.itemsIn", "tag.itemHas", "tag.blocksIn",
            "item", "item.find", "item.stack", "item.idOf", "item.findByPrefix",
            "on('server.tick', function(e) {})",
            "on('player.join', function(player) {})",
            "on('player.leave', function(player) {})",
            "on('player.chat', function(e) {})",
            "on('player.death', function(e) {})",
            "on('block.break', function(e) {})",
            "on('block.place', function(e) {})",
            "on('item.use_block', function(e) {})",
            "on('entity.spawn', function(e) {})"
    );
    private static final Set<String> JS_KEYWORDS = Set.of(
            "var", "let", "const", "function", "return", "if", "else", "for", "while",
            "true", "false", "null", "undefined", "new", "class", "import", "export"
    );

    private static final List<String> LUA_BINDINGS = List.of(
            "print", "broadcast", "server", "on", "cancel",
            "recipe", "recipe:removeByOutput", "recipe:removeById", "recipe:count",
            "recipe:shapedAdd", "recipe:shapelessAdd",
            "tag", "tag:itemsIn", "tag:itemHas", "tag:blocksIn",
            "item", "item:find", "item:stack", "item:idOf", "item:findByPrefix",
            "on('server.tick', function(e) end)",
            "on('player.join', function(player) end)",
            "on('player.leave', function(player) end)",
            "on('player.chat', function(e) end)",
            "on('player.death', function(e) end)",
            "on('block.break', function(e) end)",
            "on('block.place', function(e) end)",
            "on('item.use_block', function(e) end)",
            "on('entity.spawn', function(e) end)"
    );
    private static final Set<String> LUA_KEYWORDS = Set.of(
            "and", "break", "do", "else", "elseif", "end", "false", "for",
            "function", "if", "in", "local", "nil", "not", "or", "repeat",
            "return", "then", "true", "until", "while"
    );

    public List<String> suggest(ScriptType type, String prefix) {
        if (prefix == null || prefix.isEmpty()) return List.of();
        List<String> all = new ArrayList<>();
        if (type == ScriptType.JS) {
            all.addAll(JS_BINDINGS);
            all.addAll(JS_KEYWORDS);
        } else {
            all.addAll(LUA_BINDINGS);
            all.addAll(LUA_KEYWORDS);
        }
        String lp = prefix.toLowerCase();
        return all.stream()
                .filter(s -> s.toLowerCase().startsWith(lp))
                .sorted()
                .distinct()
                .limit(8)
                .toList();
    }
}
