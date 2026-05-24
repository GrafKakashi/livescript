package de.grafkakashi.livescript.client.gui.highlight;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JS tokenizer as a left-to-right character scanner. Unlike the previous
 * regex-based version, this one can resume mid-block-comment or
 * mid-template-string from a non-NORMAL start state.
 */
public class JsTokenizer implements Tokenizer {
    private static final Set<String> KEYWORDS = Set.of(
            "var", "let", "const", "function", "return", "if", "else", "for", "while",
            "do", "break", "continue", "switch", "case", "default", "new", "this",
            "true", "false", "null", "undefined", "throw", "try", "catch", "finally",
            "typeof", "instanceof", "in", "of", "delete", "void", "class", "extends",
            "import", "export", "from", "yield", "async", "await");

    @Override
    public Result tokenize(String line, LineState startState) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = line.length();
        LineState state = startState;

        // Continue-mid-block: scan for terminator and color everything up to it
        if (state == LineState.IN_BLOCK_COMMENT) {
            int end = line.indexOf("*/", i);
            if (end < 0) {
                tokens.add(new Token(i, n - i, Palette.COMMENT));
                return new Result(tokens, LineState.IN_BLOCK_COMMENT);
            }
            tokens.add(new Token(i, end + 2 - i, Palette.COMMENT));
            i = end + 2;
            state = LineState.NORMAL;
        } else if (state == LineState.IN_TEMPLATE_STRING) {
            int j = i;
            while (j < n) {
                char c = line.charAt(j);
                if (c == '\\' && j + 1 < n) { j += 2; continue; }
                if (c == '`') { j++; tokens.add(new Token(i, j - i, Palette.STRING)); i = j; state = LineState.NORMAL; break; }
                j++;
            }
            if (state == LineState.IN_TEMPLATE_STRING) {
                tokens.add(new Token(i, n - i, Palette.STRING));
                return new Result(tokens, LineState.IN_TEMPLATE_STRING);
            }
        }

        while (i < n) {
            char c = line.charAt(i);

            // Line comment — rest of line
            if (c == '/' && i + 1 < n && line.charAt(i + 1) == '/') {
                tokens.add(new Token(i, n - i, Palette.COMMENT));
                return new Result(tokens, LineState.NORMAL);
            }

            // Block comment — may span lines
            if (c == '/' && i + 1 < n && line.charAt(i + 1) == '*') {
                int end = line.indexOf("*/", i + 2);
                if (end < 0) {
                    tokens.add(new Token(i, n - i, Palette.COMMENT));
                    return new Result(tokens, LineState.IN_BLOCK_COMMENT);
                }
                tokens.add(new Token(i, end + 2 - i, Palette.COMMENT));
                i = end + 2;
                continue;
            }

            // Template string — may span lines
            if (c == '`') {
                int j = i + 1;
                boolean closed = false;
                while (j < n) {
                    char ch = line.charAt(j);
                    if (ch == '\\' && j + 1 < n) { j += 2; continue; }
                    if (ch == '`') { j++; closed = true; break; }
                    j++;
                }
                if (!closed) {
                    tokens.add(new Token(i, n - i, Palette.STRING));
                    return new Result(tokens, LineState.IN_TEMPLATE_STRING);
                }
                tokens.add(new Token(i, j - i, Palette.STRING));
                i = j;
                continue;
            }

            // Regular string
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
