package de.grafkakashi.livescript.client.gui.highlight;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokenizer for JSON files in the editor. JSON has no multi-line constructs
 * — no block comments, no template strings — so we can ignore {@code startState}
 * entirely and always end in {@link LineState#NORMAL}. Standard six categories:
 *
 * <ul>
 *   <li>Strings (double-quoted, with backslash escapes) → {@link Palette#STRING}</li>
 *   <li>Numbers (integers, floats, scientific) → {@link Palette#NUMBER}</li>
 *   <li>Literals true/false/null → {@link Palette#KEYWORD}</li>
 *   <li>Structural punctuation {@code { } [ ] : ,} → {@link Palette#OPERATOR}</li>
 *   <li>Everything else → {@link Palette#DEFAULT} (will rarely happen in valid JSON)</li>
 * </ul>
 *
 * <p>Strict-JSON has no comments. We don't pretend to support {@code //} or
 * {@code /* *\/} — if the user adds them, they'll get a parse error on save.
 * The tokenizer doesn't reject anything; that's the linter's job.
 */
public class JsonTokenizer implements Tokenizer {

    @Override
    public Result tokenize(String line, LineState startState) {
        // JSON has no multi-line constructs to carry state through.
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);

            if (c == '"') {
                int start = i++;
                // Consume until closing quote, honoring backslash escapes.
                // If the line ends mid-string (invalid JSON), the token just
                // runs to end-of-line — we don't carry a state forward.
                while (i < n) {
                    char d = line.charAt(i);
                    if (d == '\\' && i + 1 < n) { i += 2; continue; }
                    if (d == '"') { i++; break; }
                    i++;
                }
                tokens.add(new Token(start, i - start, Palette.STRING));
                continue;
            }

            if (Character.isDigit(c) || (c == '-' && i + 1 < n && Character.isDigit(line.charAt(i + 1)))) {
                int start = i;
                if (c == '-') i++;
                while (i < n && (Character.isDigit(line.charAt(i)) || line.charAt(i) == '.'
                        || line.charAt(i) == 'e' || line.charAt(i) == 'E'
                        || line.charAt(i) == '+' || line.charAt(i) == '-')) {
                    i++;
                }
                tokens.add(new Token(start, i - start, Palette.NUMBER));
                continue;
            }

            if (Character.isLetter(c)) {
                int start = i;
                while (i < n && Character.isLetter(line.charAt(i))) i++;
                String word = line.substring(start, i);
                if ("true".equals(word) || "false".equals(word) || "null".equals(word)) {
                    tokens.add(new Token(start, i - start, Palette.KEYWORD));
                } else {
                    // Stray identifier — invalid JSON but render as default
                    tokens.add(new Token(start, i - start, Palette.DEFAULT));
                }
                continue;
            }

            if (c == '{' || c == '}' || c == '[' || c == ']' || c == ':' || c == ',') {
                tokens.add(new Token(i, 1, Palette.OPERATOR));
                i++;
                continue;
            }

            // Whitespace or anything else — skip without emitting a token. The
            // editor renders ungiven ranges in the default colour anyway.
            i++;
        }

        return new Result(tokens, LineState.NORMAL);
    }
}
