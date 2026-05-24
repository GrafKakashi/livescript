package de.grafkakashi.livescript.client.gui.highlight;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Lua tokenizer with multi-line block comment support (--[[ ... ]]).
 *
 * Limitation: only handles the level-0 long bracket form (==[[ would be
 * level 1). Most hand-written Lua sticks to --[[, so we don't bother.
 */
public class LuaTokenizer implements Tokenizer {
    private static final Set<String> KEYWORDS = Set.of(
            "and", "break", "do", "else", "elseif", "end", "false", "for",
            "function", "goto", "if", "in", "local", "nil", "not", "or",
            "repeat", "return", "then", "true", "until", "while");

    @Override
    public Result tokenize(String line, LineState startState) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = line.length();
        LineState state = startState;

        if (state == LineState.IN_BLOCK_COMMENT) {
            int end = line.indexOf("]]", i);
            if (end < 0) {
                tokens.add(new Token(i, n - i, Palette.COMMENT));
                return new Result(tokens, LineState.IN_BLOCK_COMMENT);
            }
            tokens.add(new Token(i, end + 2 - i, Palette.COMMENT));
            i = end + 2;
            state = LineState.NORMAL;
        }

        while (i < n) {
            char c = line.charAt(i);

            // Comment: -- or --[[
            if (c == '-' && i + 1 < n && line.charAt(i + 1) == '-') {
                // Block?
                if (i + 3 < n && line.charAt(i + 2) == '[' && line.charAt(i + 3) == '[') {
                    int end = line.indexOf("]]", i + 4);
                    if (end < 0) {
                        tokens.add(new Token(i, n - i, Palette.COMMENT));
                        return new Result(tokens, LineState.IN_BLOCK_COMMENT);
                    }
                    tokens.add(new Token(i, end + 2 - i, Palette.COMMENT));
                    i = end + 2;
                    continue;
                }
                // Line comment
                tokens.add(new Token(i, n - i, Palette.COMMENT));
                return new Result(tokens, LineState.NORMAL);
            }

            // String literal
            if (c == '"' || c == '\'') {
                int j = i + 1;
                char quote = c;
                while (j < n) {
                    char ch = line.charAt(j);
                    if (ch == '\\' && j + 1 < n) { j += 2; continue; }
                    if (ch == quote) { j++; break; }
                    j++;
                }
                tokens.add(new Token(i, j - i, Palette.STRING));
                i = j;
                continue;
            }

            // Number
            if (Character.isDigit(c)) {
                int j = i + 1;
                while (j < n && (Character.isDigit(line.charAt(j)) || line.charAt(j) == '.')) j++;
                tokens.add(new Token(i, j - i, Palette.NUMBER));
                i = j;
                continue;
            }

            // Identifier
            if (Character.isJavaIdentifierStart(c)) {
                int j = i + 1;
                while (j < n && Character.isJavaIdentifierPart(line.charAt(j))) j++;
                String word = line.substring(i, j);
                if (KEYWORDS.contains(word)) {
                    tokens.add(new Token(i, j - i, Palette.KEYWORD));
                } else if (j < n && line.charAt(j) == '(') {
                    tokens.add(new Token(i, j - i, Palette.FUNCTION));
                }
                i = j;
                continue;
            }

            i++;
        }
        return new Result(tokens, LineState.NORMAL);
    }
}
