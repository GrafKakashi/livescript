package de.grafkakashi.livescript.client.gui.highlight;

import java.util.List;

/**
 * Stateful syntax highlighter. Each line carries an opaque "state" — the
 * tokenizer is told what state the line begins in (e.g. INSIDE_BLOCK_COMMENT)
 * and returns both the tokens AND the state for the start of the NEXT line.
 *
 * This is how multi-line constructs (block comments, triple-quoted strings,
 * Lua's --[[ ... ]] long-bracket comments) get highlighted correctly without
 * re-parsing the entire file on every keystroke.
 *
 * The editor stores the state-per-line in a parallel array. When a line is
 * edited, retokenizing starts at that line and continues downward until the
 * computed end-state stops changing (or end-of-file).
 */
public interface Tokenizer {
    /** State at the start of a line. NORMAL is the default; sub-types add language-specifics. */
    enum LineState {
        NORMAL,
        IN_BLOCK_COMMENT,    // /* ... */ for JS; --[[ ... ]] for Lua
        IN_TEMPLATE_STRING   // `...` for JS; (unused for Lua)
    }

    record Result(List<Token> tokens, LineState endState) {}
    record Token(int start, int length, int colorRgb) {}

    /**
     * Tokenize one line, given the state inherited from the previous line's end.
     */
    Result tokenize(String line, LineState startState);

    /** Convenience for the common NORMAL-start case (kept for tests and simple callers). */
    default List<Token> tokenize(String line) {
        return tokenize(line, LineState.NORMAL).tokens();
    }

    /** Standard palette — matches a dark "Monokai-ish" theme that reads well over MC's dark backgrounds. */
    final class Palette {
        public static final int DEFAULT  = 0xFFE6E6E6;
        public static final int KEYWORD  = 0xFFFF6B9D;
        public static final int STRING   = 0xFFE8C97A;
        public static final int NUMBER   = 0xFFB4E197;
        public static final int COMMENT  = 0xFF7F848E;
        public static final int FUNCTION = 0xFF8AC4FF;
        public static final int OPERATOR = 0xFFC8C8C8;
        private Palette() {}
    }
}
