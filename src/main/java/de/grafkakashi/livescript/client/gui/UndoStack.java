package de.grafkakashi.livescript.client.gui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * In-memory undo/redo stack for the editor. Stores full snapshots of the
 * buffer because scripts are small (sub-512KB) and reasoning about diffs
 * adds complexity we don't need for v0.2.
 *
 * Coalescing: consecutive single-char inserts within {@link #COALESCE_MS}
 * collapse into one undo entry. This means undo undoes "words", not letters,
 * which matches typical editor UX (notepad++, VS Code, …).
 *
 * Memory: with {@link #MAX_DEPTH}=200 and a 512KB max file, worst case is
 * ~100MB per open script. In practice files are far smaller, so this is fine.
 */
public class UndoStack {
    private static final int MAX_DEPTH = 200;
    private static final long COALESCE_MS = 600;

    public record Snapshot(List<String> lines, int cursorLine, int cursorCol) {}

    private final Deque<Snapshot> undo = new ArrayDeque<>();
    private final Deque<Snapshot> redo = new ArrayDeque<>();
    private long lastPushMs = 0;

    /**
     * Push the current state onto the undo stack.
     * @param coalescable if true and last push was < COALESCE_MS ago, this push is dropped
     *                    (coalesces typing bursts). Pass false for "structural" edits like
     *                    paste, delete-line, format.
     */
    public void push(EditorState st, boolean coalescable) {
        long now = System.currentTimeMillis();
        if (coalescable && !undo.isEmpty() && (now - lastPushMs) < COALESCE_MS) {
            lastPushMs = now;
            return;
        }
        undo.push(snapshot(st));
        redo.clear();
        while (undo.size() > MAX_DEPTH) undo.pollLast();
        lastPushMs = now;
    }

    public boolean undo(EditorState st) {
        if (undo.isEmpty()) return false;
        redo.push(snapshot(st));
        apply(st, undo.pop());
        return true;
    }

    public boolean redo(EditorState st) {
        if (redo.isEmpty()) return false;
        undo.push(snapshot(st));
        apply(st, redo.pop());
        return true;
    }

    public void clear() {
        undo.clear();
        redo.clear();
        lastPushMs = 0;
    }

    private static Snapshot snapshot(EditorState st) {
        return new Snapshot(List.copyOf(st.lines), st.cursorLine, st.cursorCol);
    }

    private static void apply(EditorState st, Snapshot s) {
        st.lines.clear();
        st.lines.addAll(s.lines());
        st.cursorLine = Math.min(s.cursorLine(), st.lines.size() - 1);
        st.cursorCol = Math.min(s.cursorCol(),
                st.lines.isEmpty() ? 0 : st.lines.get(st.cursorLine).length());
        st.dirty = true;
        st.resetStates();
    }
}
