package de.grafkakashi.livescript.client.gui;

import de.grafkakashi.livescript.client.gui.highlight.Tokenizer;
import de.grafkakashi.livescript.engine.ScriptType;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable per-script buffer. Holds lines, cursor, dirty flag, console output.
 * Also caches the tokenizer's line-start state per line so multi-line
 * highlighting (block comments, template strings) is incremental.
 */
public class EditorState {
    /** May change via rename. */
    public String path;
    /** May change if a rename crosses .js <-> .lua. */
    public ScriptType type;
    public final List<String> lines = new ArrayList<>();
    public final List<String> consoleLines = new ArrayList<>();

    /**
     * Parallel to {@link #lines}: lineStartStates.get(i) is the LineState at the
     * start of line i. lineStartStates.get(0) is always NORMAL.
     * Length is always lines.size() + 1; the last entry is the end-of-document state.
     */
    public final List<Tokenizer.LineState> lineStartStates = new ArrayList<>();

    public int cursorLine = 0;
    public int cursorCol = 0;
    /**
     * Selection anchor — when {@code anchor == cursor} there is no selection.
     * Otherwise the selection spans from anchor to cursor (in either direction;
     * use {@link #hasSelection()}/{@link #selectionStart}/{@link #selectionEnd}
     * for normalized access).
     */
    public int anchorLine = 0;
    public int anchorCol = 0;
    public int scrollLine = 0;       // first visible line in the viewport
    public boolean dirty = false;
    public long lastExecMs = 0;
    public final UndoStack undoStack = new UndoStack();

    // Folding — recomputed lazily after edits via foldRegionsDirty flag.
    // foldedByHeader maps header-line-number → folded? so it survives recompute
    // even when other lines were edited above (we re-map after recompute).
    public List<FoldRegion> foldRegions = java.util.List.of();
    public final java.util.Set<Integer> foldedHeaderLines = new java.util.HashSet<>();
    public boolean foldRegionsDirty = true;

    /**
     * Most recent linter issues. Re-populated on save and on a debounced
     * background re-lint after typing pauses. Empty list = code is clean.
     */
    public java.util.List<de.grafkakashi.livescript.engine.Linter.Issue> lintIssues = java.util.List.of();
    /**
     * Wall-clock time of the last edit. The screen uses this to schedule a
     * re-lint after the user has been idle for {@code LINT_DEBOUNCE_MS} ms.
     * Avoids running the parser on every single keystroke.
     */
    public long lastEditMs = 0;
    /** Set true when content changes; cleared by the lint scheduler after relinting. */
    public boolean lintDirty = false;

    public EditorState(String path, ScriptType type, String initialContent) {
        this.path = path;
        this.type = type;
        setContent(initialContent);
    }

    public void setContent(String content) {
        lines.clear();
        // splitting on \n only — \r is stripped to normalize CRLF
        for (String l : content.replace("\r", "").split("\n", -1)) {
            lines.add(l);
        }
        if (lines.isEmpty()) lines.add("");
        cursorLine = 0;
        cursorCol = 0;
        anchorLine = 0;
        anchorCol = 0;
        dirty = false;
        resetStates();
        foldedHeaderLines.clear();
        foldRegionsDirty = true;
    }

    /** Drop the state cache. Render recomputes lazily on next draw. */
    public void resetStates() {
        lineStartStates.clear();
        lineStartStates.add(Tokenizer.LineState.NORMAL);
        // Fill remaining slots with NORMAL — they'll be corrected when render touches them.
        // Conservative invariant: lineStartStates.size() >= lines.size() + 1.
        while (lineStartStates.size() < lines.size() + 1) {
            lineStartStates.add(Tokenizer.LineState.NORMAL);
        }
    }

    /**
     * Mark all states from {@code fromLine} downward as stale. The actual recompute
     * happens incrementally during render. We do this rather than retokenize eagerly
     * because most keystrokes don't change cross-line state (typing inside an existing
     * NORMAL line stays NORMAL → NORMAL).
     *
     * Implementation: we just truncate the cached states to fromLine+1 entries.
     * When render reads beyond that, it'll find no cached entry and tokenize
     * the missing prefix on the fly, updating the cache as it goes.
     */
    public void invalidateStatesFrom(int fromLine) {
        // Keep the entry AT fromLine (start state of that line is determined by line above)
        int keep = Math.min(fromLine + 1, lineStartStates.size());
        while (lineStartStates.size() > keep) {
            lineStartStates.remove(lineStartStates.size() - 1);
        }
    }

    public String fullContent() {
        return String.join("\n", lines);
    }

    public String currentLine() {
        return lines.get(cursorLine);
    }

    public void replaceLine(String newLine) {
        lines.set(cursorLine, newLine);
        dirty = true;
        lintDirty = true;
        lastEditMs = System.currentTimeMillis();
        invalidateStatesFrom(cursorLine);
        foldRegionsDirty = true;
    }

    public void insertChar(char c) {
        if (hasSelection()) deleteSelection();
        String line = currentLine();
        String updated = line.substring(0, cursorCol) + c + line.substring(cursorCol);
        replaceLine(updated);
        cursorCol++;
        // Keep anchor in sync with cursor — otherwise the just-inserted char
        // is left as a single-char "selection" (cursor moved, anchor didn't),
        // and the next keystroke deletes it via the hasSelection() check above.
        collapseSelection();
    }

    public void insertNewline() {
        if (hasSelection()) deleteSelection();
        String line = currentLine();
        String before = line.substring(0, cursorCol);
        String after = line.substring(cursorCol);
        lines.set(cursorLine, before);
        lines.add(cursorLine + 1, after);
        cursorLine++;
        cursorCol = 0;
        collapseSelection();
        dirty = true;
        lintDirty = true;
        lastEditMs = System.currentTimeMillis();
        invalidateStatesFrom(cursorLine - 1);
        foldRegionsDirty = true;
    }

    public void backspace() {
        if (hasSelection()) { deleteSelection(); return; }
        if (cursorCol > 0) {
            String line = currentLine();
            String updated = line.substring(0, cursorCol - 1) + line.substring(cursorCol);
            replaceLine(updated);
            cursorCol--;
        } else if (cursorLine > 0) {
            String prev = lines.get(cursorLine - 1);
            String curr = lines.get(cursorLine);
            lines.set(cursorLine - 1, prev + curr);
            lines.remove(cursorLine);
            cursorLine--;
            cursorCol = prev.length();
            dirty = true;
            lintDirty = true;
            lastEditMs = System.currentTimeMillis();
            invalidateStatesFrom(cursorLine);
            foldRegionsDirty = true;
        }
        collapseSelection();
    }

    public void delete() {
        if (hasSelection()) { deleteSelection(); return; }
        String line = currentLine();
        if (cursorCol < line.length()) {
            replaceLine(line.substring(0, cursorCol) + line.substring(cursorCol + 1));
        } else if (cursorLine < lines.size() - 1) {
            String next = lines.remove(cursorLine + 1);
            replaceLine(line + next);
        }
        collapseSelection();
    }

    /**
     * Recompute fold regions if marked dirty. Folded headers are tracked by
     * line number; after recompute, only headers that still correspond to a
     * fold region remain folded. Cheap if nothing changed (no-op when not dirty).
     */
    public void ensureFoldsFresh() {
        if (!foldRegionsDirty) return;
        foldRegions = FoldRegion.compute(lines);
        // Drop folded entries whose header line no longer exists or is no longer a fold header
        java.util.Set<Integer> validHeaders = new java.util.HashSet<>();
        for (FoldRegion r : foldRegions) validHeaders.add(r.headerLine);
        foldedHeaderLines.retainAll(validHeaders);
        foldRegionsDirty = false;
    }

    /** Toggle the folded state of the region whose header is at the given source line, if any. */
    public boolean toggleFoldAt(int sourceLine) {
        ensureFoldsFresh();
        for (FoldRegion r : foldRegions) {
            if (r.headerLine == sourceLine) {
                if (!foldedHeaderLines.add(sourceLine)) {
                    foldedHeaderLines.remove(sourceLine);
                }
                return true;
            }
        }
        return false;
    }

    /** Returns true if {@code sourceLine} is hidden by some currently-folded region. */
    public boolean isHidden(int sourceLine) {
        ensureFoldsFresh();
        for (FoldRegion r : foldRegions) {
            if (foldedHeaderLines.contains(r.headerLine)
                    && sourceLine >= r.firstFoldedLine
                    && sourceLine <= r.lastFoldedLine) return true;
        }
        return false;
    }

    public void moveCursor(int dLine, int dCol) {
        cursorLine = Math.max(0, Math.min(lines.size() - 1, cursorLine + dLine));
        // Clamp column to length of new line
        int maxCol = lines.get(cursorLine).length();
        cursorCol = Math.max(0, Math.min(maxCol, cursorCol + dCol));
        collapseSelection();
    }

    public void setCursor(int line, int col) {
        cursorLine = Math.max(0, Math.min(lines.size() - 1, line));
        cursorCol = Math.max(0, Math.min(lines.get(cursorLine).length(), col));
        collapseSelection();
    }

    // ============================================================
    //  Selection
    // ============================================================

    /** Move cursor without collapsing — used for Shift+arrow / mouse-drag. */
    public void extendCursor(int dLine, int dCol) {
        cursorLine = Math.max(0, Math.min(lines.size() - 1, cursorLine + dLine));
        int maxCol = lines.get(cursorLine).length();
        cursorCol = Math.max(0, Math.min(maxCol, cursorCol + dCol));
    }

    /** Set cursor without collapsing — used for shift-click and drag-select. */
    public void extendCursorTo(int line, int col) {
        cursorLine = Math.max(0, Math.min(lines.size() - 1, line));
        cursorCol = Math.max(0, Math.min(lines.get(cursorLine).length(), col));
    }

    /** Drop the anchor at the current cursor position (no selection). */
    public void collapseSelection() {
        anchorLine = cursorLine;
        anchorCol = cursorCol;
    }

    public boolean hasSelection() {
        return anchorLine != cursorLine || anchorCol != cursorCol;
    }

    /** Returns the earlier of anchor/cursor as a 2-element array [line, col]. */
    public int[] selectionStart() {
        if (anchorLine < cursorLine || (anchorLine == cursorLine && anchorCol <= cursorCol)) {
            return new int[]{anchorLine, anchorCol};
        }
        return new int[]{cursorLine, cursorCol};
    }

    public int[] selectionEnd() {
        if (anchorLine < cursorLine || (anchorLine == cursorLine && anchorCol <= cursorCol)) {
            return new int[]{cursorLine, cursorCol};
        }
        return new int[]{anchorLine, anchorCol};
    }

    /** Concatenated selected text, with '\n' between source lines. Empty if no selection. */
    public String getSelectedText() {
        if (!hasSelection()) return "";
        int[] s = selectionStart();
        int[] e = selectionEnd();
        if (s[0] == e[0]) {
            return lines.get(s[0]).substring(s[1], e[1]);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(lines.get(s[0]).substring(s[1])).append('\n');
        for (int i = s[0] + 1; i < e[0]; i++) {
            sb.append(lines.get(i)).append('\n');
        }
        sb.append(lines.get(e[0]), 0, e[1]);
        return sb.toString();
    }

    /**
     * Remove the selected range. Cursor lands at the selection start position
     * and selection is collapsed. No-op if there is no selection.
     */
    public void deleteSelection() {
        if (!hasSelection()) return;
        int[] s = selectionStart();
        int[] e = selectionEnd();
        String startLine = lines.get(s[0]);
        String endLine = lines.get(e[0]);
        String merged = startLine.substring(0, s[1]) + endLine.substring(e[1]);
        // Remove intermediate + end lines first (in reverse to preserve indices)
        for (int i = e[0]; i > s[0]; i--) {
            lines.remove(i);
        }
        lines.set(s[0], merged);
        cursorLine = s[0];
        cursorCol = s[1];
        collapseSelection();
        dirty = true;
        lintDirty = true;
        lastEditMs = System.currentTimeMillis();
        invalidateStatesFrom(cursorLine);
        foldRegionsDirty = true;
    }

    /**
     * Insert {@code text} at the cursor, replacing any active selection first.
     * Handles multi-line text by splitting on '\n'.
     */
    public void insertText(String text) {
        if (text == null || text.isEmpty()) return;
        if (hasSelection()) deleteSelection();
        // Normalize line endings; \r alone or \r\n both treated as line breaks
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        String[] parts = normalized.split("\n", -1);
        String currentLine = lines.get(cursorLine);
        String before = currentLine.substring(0, cursorCol);
        String after = currentLine.substring(cursorCol);

        if (parts.length == 1) {
            // Single-line paste — just insert inline
            lines.set(cursorLine, before + parts[0] + after);
            cursorCol += parts[0].length();
        } else {
            // Multi-line: first part appends to current line's prefix; last part
            // prepends to the suffix; the parts in between become whole new lines.
            lines.set(cursorLine, before + parts[0]);
            for (int i = 1; i < parts.length - 1; i++) {
                lines.add(cursorLine + i, parts[i]);
            }
            String lastPart = parts[parts.length - 1];
            lines.add(cursorLine + parts.length - 1, lastPart + after);
            cursorLine += parts.length - 1;
            cursorCol = lastPart.length();
        }
        collapseSelection();
        dirty = true;
        lintDirty = true;
        lastEditMs = System.currentTimeMillis();
        invalidateStatesFrom(cursorLine - parts.length + 1);
        foldRegionsDirty = true;
    }

    /** Select the entire document (Ctrl+A). */
    public void selectAll() {
        anchorLine = 0;
        anchorCol = 0;
        cursorLine = lines.size() - 1;
        cursorCol = lines.get(cursorLine).length();
    }

    public void appendConsole(String text) {
        if (text == null || text.isEmpty()) return;
        for (String l : text.split("\n", -1)) {
            consoleLines.add(l);
        }
        // Cap at config-bound size (rough)
        while (consoleLines.size() > 2000) consoleLines.remove(0);
    }

    public void clearConsole() {
        consoleLines.clear();
    }
}
