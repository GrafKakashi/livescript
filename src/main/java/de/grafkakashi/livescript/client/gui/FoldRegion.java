package de.grafkakashi.livescript.client.gui;

import java.util.ArrayList;
import java.util.List;

/**
 * Indent-based fold regions. Computed once per content change and cached on
 * {@link EditorState}; folded state (which regions are collapsed) is separate
 * mutable state.
 *
 * Algorithm (simple, language-agnostic):
 *   For each line L, find the smallest indent of any non-blank line below L
 *   whose indent is strictly less than or equal to L's indent. The region for
 *   L spans from L+1 up to that boundary line - 1. If no such boundary exists,
 *   the region runs to end-of-file.
 *
 * Only regions of length >= 2 are kept — folding a one-line block is silly.
 *
 * Limitations:
 *   - Indent-based, so misindented code folds weirdly. That's a feature: it
 *     also flags formatting issues.
 *   - Blank lines are ignored when computing boundaries (so a fold doesn't
 *     end at a blank line inside the block).
 *   - No language awareness — doesn't recognize {/} or function/end explicitly.
 *     For typical mod scripts (where you indent your bodies) this is fine.
 */
public final class FoldRegion {
    public final int headerLine;       // 0-based, the "if (x) {" line — STAYS VISIBLE
    public final int firstFoldedLine;  // = headerLine + 1
    public final int lastFoldedLine;   // inclusive

    public FoldRegion(int headerLine, int firstFoldedLine, int lastFoldedLine) {
        this.headerLine = headerLine;
        this.firstFoldedLine = firstFoldedLine;
        this.lastFoldedLine = lastFoldedLine;
    }

    public int hiddenLineCount() { return lastFoldedLine - firstFoldedLine + 1; }

    public static List<FoldRegion> compute(List<String> lines) {
        List<FoldRegion> out = new ArrayList<>();
        int n = lines.size();
        int[] indent = new int[n];
        boolean[] blank = new boolean[n];
        for (int i = 0; i < n; i++) {
            String l = lines.get(i);
            blank[i] = l.trim().isEmpty();
            indent[i] = blank[i] ? -1 : leadingIndent(l);
        }

        for (int i = 0; i < n; i++) {
            if (blank[i]) continue;
            int myIndent = indent[i];

            // Find the next non-blank line with strictly greater indent
            int j = i + 1;
            while (j < n && (blank[j] || indent[j] > myIndent)) j++;
            // Skip blanks immediately following j to refine the boundary
            int boundary = j;
            // Body is lines i+1 .. (boundary - 1), but trim trailing blanks
            int lastBody = boundary - 1;
            while (lastBody > i && blank[lastBody]) lastBody--;

            if (lastBody >= i + 1 && (lastBody - i) >= 1) {
                // Only fold if there's at least one indented body line, i.e. lastBody > i
                // AND it's worth folding (>= 2 hidden lines)
                int hidden = lastBody - i;
                if (hidden >= 2) {
                    out.add(new FoldRegion(i, i + 1, lastBody));
                }
            }
        }
        return out;
    }

    private static int leadingIndent(String l) {
        int n = 0;
        while (n < l.length() && (l.charAt(n) == ' ' || l.charAt(n) == '\t')) {
            n += (l.charAt(n) == '\t') ? 4 : 1;
        }
        return n;
    }
}
