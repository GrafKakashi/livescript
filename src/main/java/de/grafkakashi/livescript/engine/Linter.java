package de.grafkakashi.livescript.engine;

import de.grafkakashi.livescript.LiveScriptMod;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.Globals;
import org.luaj.vm2.lib.jse.JsePlatform;
import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.ErrorReporter;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Syntax-only validation using each engine's parser without running the code.
 *
 * Rhino has a {@link Parser} that produces an AST without execution. LuaJ does
 * not expose a parser-only path, but {@code globals.load(...)} compiles the
 * source — throwing if syntax is invalid — without executing it. We discard
 * the compiled chunk.
 *
 * Two entry points:
 *   - {@link #lint(ScriptType, String, String)} — returns a human-readable
 *     string (or null on success). Used by save flow for the console message.
 *   - {@link #lintDetailed(ScriptType, String, String)} — returns a list of
 *     {@link Issue} records with line/column for inline rendering.
 */
public final class Linter {
    private Linter() {}

    /**
     * One linter complaint with positional info so the editor can underline it.
     * line is 1-based to match Rhino; column is 0-based (0 = before first char).
     */
    public record Issue(int line, int column, int length, String message) {}

    public static String lint(ScriptType type, String scriptId, String source) {
        var issues = lintDetailed(type, scriptId, source);
        if (issues.isEmpty()) return null;
        Issue first = issues.get(0);
        return "line " + first.line() + ": " + first.message();
    }

    public static List<Issue> lintDetailed(ScriptType type, String scriptId, String source) {
        try {
            return switch (type) {
                case JS -> lintJsDetailed(scriptId, source);
                case LUA -> lintLuaDetailed(scriptId, source);
            };
        } catch (Throwable t) {
            // Defensive — never throw out to callers.
            LiveScriptMod.LOGGER.warn("linter crashed on {}", scriptId, t);
            return List.of();
        }
    }

    private static List<Issue> lintJsDetailed(String scriptId, String source) {
        CompilerEnvirons env = new CompilerEnvirons();
        env.setLanguageVersion(org.mozilla.javascript.Context.VERSION_ES6);
        // Tell Rhino to keep reporting errors instead of bailing on the first one,
        // so we can underline all of them — within reason. We cap to 10 in the
        // reporter to avoid worst-case slowdowns on heavily-broken files.
        env.setRecoverFromErrors(true);
        var reporter = new CollectingErrorReporter();
        env.setErrorReporter(reporter);
        Parser p = new Parser(env, reporter);
        try {
            p.parse(source, scriptId, 1);
        } catch (EvaluatorException e) {
            // Reporter already captured everything; ignore
        }
        return reporter.issues;
    }

    /** Lua compile errors look like "scriptId:LINE: message"; pull the line out. */
    private static final Pattern LUA_ERROR_LINE = Pattern.compile(":(\\d+):\\s*(.*)$");

    private static List<Issue> lintLuaDetailed(String scriptId, String source) {
        try {
            Globals globals = JsePlatform.standardGlobals();
            globals.load(source, scriptId);  // compiles; does not call
            return List.of();
        } catch (LuaError e) {
            String msg = e.getMessage();
            if (msg == null) return List.of();
            Matcher m = LUA_ERROR_LINE.matcher(msg);
            int line = 1;
            String cleanMsg = msg;
            if (m.find()) {
                try { line = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
                cleanMsg = m.group(2);
            }
            // LuaJ doesn't give us a column — underline the leading non-whitespace
            // on that line, or the whole line if it's empty.
            int column = 0;
            int length = lineWidthAtIndent(source, line);
            return List.of(new Issue(line, column, length, cleanMsg));
        }
    }

    /** Length from first non-whitespace to end of line, for a sensible underline span. */
    private static int lineWidthAtIndent(String source, int line1based) {
        int lineIdx = 1;
        int start = 0;
        for (int i = 0; i < source.length(); i++) {
            if (lineIdx == line1based) {
                start = i;
                int end = source.indexOf('\n', i);
                if (end < 0) end = source.length();
                String l = source.substring(start, end);
                int leading = 0;
                while (leading < l.length() && Character.isWhitespace(l.charAt(leading))) leading++;
                return Math.max(1, l.length() - leading);
            }
            if (source.charAt(i) == '\n') lineIdx++;
        }
        return 1;
    }

    /** Rhino reporter that collects up to 10 errors with line/column info. */
    private static class CollectingErrorReporter implements ErrorReporter {
        private static final int MAX_ISSUES = 10;
        final List<Issue> issues = new ArrayList<>();

        @Override public void warning(String message, String sourceName, int line, String lineSource, int lineOffset) {
            // Skip warnings — only fail on actual errors.
        }
        @Override public void error(String message, String sourceName, int line, String lineSource, int lineOffset) {
            if (issues.size() >= MAX_ISSUES) return;
            // Rhino's error recovery can attribute cascading errors back to the
            // very first source line if its parse stack collapses badly. If the
            // reported line is just a comment or whitespace, the error is almost
            // certainly a stale aftershock of a real error elsewhere — drop it.
            if (lineSource != null && isCommentOrBlankLine(lineSource)) {
                return;
            }
            issues.add(buildIssue(message, line, lineSource, lineOffset));
        }

        /** True for empty/whitespace-only lines and pure-comment lines (// or block comments). */
        private static boolean isCommentOrBlankLine(String line) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) return true;
            // Line-comment: everything from the first // is comment (we treat the
            // whole line as comment if the non-comment prefix is empty).
            if (trimmed.startsWith("//")) return true;
            if (trimmed.startsWith("/*") || trimmed.startsWith("*")) return true;
            // Lua-style line comment, since the JS reporter only sees JS; defensive.
            if (trimmed.startsWith("--")) return true;
            return false;
        }
        @Override public EvaluatorException runtimeError(String message, String sourceName, int line, String lineSource, int lineOffset) {
            error(message, sourceName, line, lineSource, lineOffset);
            return new EvaluatorException(message, sourceName, line, lineSource, lineOffset);
        }

        /**
         * Decide where to draw the squiggle. Rhino's lineOffset and lineSource
         * give us most of what we need, but some error types are reported with
         * the offset pinned at end-of-line (e.g. "missing ) after argument list"
         * gets caught at the newline, not where the open paren actually was).
         * Detect those and underline the whole non-whitespace span of the line
         * instead of a one-pixel sliver at column N.
         *
         *   pure-token errors           → just underline the token
         *   end-of-line / missing-X     → underline from first non-ws to EOL
         *   lineSource missing entirely → underline column-0 with width 1
         */
        private Issue buildIssue(String message, int line, String lineSource, int lineOffset) {
            if (lineSource == null || lineSource.isEmpty()) {
                return new Issue(line, 0, 1, message);
            }
            int col = Math.max(0, lineOffset);
            int lineLen = lineSource.length();

            // Heuristic 1: offset at or past end-of-line means Rhino bound the
            // error to EOL. Underline from leading-indent to end of line so the
            // user sees the whole problematic line marked, not just an empty bit.
            // Heuristic 2: messages that talk about "missing X" or just say
            // "syntax error" without pointing at a specific token get the same
            // line-wide treatment, since the column number isn't actionable.
            boolean eolBound = col >= lineLen;
            String lower = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
            boolean fuzzyError = lower.startsWith("missing ") || lower.equals("syntax error");

            if (eolBound || fuzzyError) {
                int indent = 0;
                while (indent < lineLen && Character.isWhitespace(lineSource.charAt(indent))) indent++;
                int width = Math.max(1, lineLen - indent);
                return new Issue(line, indent, width, message);
            }

            // Normal path: underline the offending token (walk forward to ws/EOL),
            // but if the token came out very short (1-2 chars on a punctuation
            // like ";" or "}"), pad to 3 so the squiggle is visible at our 6px
            // mono char width.
            int end = col;
            while (end < lineLen && !Character.isWhitespace(lineSource.charAt(end))) end++;
            int len = Math.max(1, end - col);
            if (len < 3 && col + 3 <= lineLen) len = 3;
            return new Issue(line, col, len, message);
        }
    }
}
