package de.grafkakashi.livescript.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Find/Replace state for one editor session. Lives on the screen, not on
 * EditorState — search shouldn't persist when you switch tabs (closing the
 * bar is the user's choice via Esc).
 *
 * Matches are stored as a list of (sourceLine, startCol, endCol). Recomputed
 * on every keystroke in the query box; that's cheap because every file is
 * sub-512KB.
 */
public class FindState {
    public String query = "";
    public String replacement = "";
    public boolean caseSensitive = false;
    public boolean replaceMode = false;
    public boolean focusOnQuery = true; // false → focus on the replacement box
    public int currentIndex = -1;        // which match is "current" for nav

    public record Match(int line, int startCol, int endCol) {}
    public final List<Match> matches = new ArrayList<>();

    public void recomputeMatches(EditorState st) {
        matches.clear();
        currentIndex = -1;
        if (query.isEmpty()) return;

        String needle = caseSensitive ? query : query.toLowerCase(Locale.ROOT);
        int needleLen = query.length();

        for (int i = 0; i < st.lines.size(); i++) {
            String haystack = caseSensitive ? st.lines.get(i) : st.lines.get(i).toLowerCase(Locale.ROOT);
            int from = 0;
            while (true) {
                int idx = haystack.indexOf(needle, from);
                if (idx < 0) break;
                matches.add(new Match(i, idx, idx + needleLen));
                from = idx + Math.max(1, needleLen);  // guard zero-length
            }
        }

        // Pick the first match at or after the cursor as "current"
        for (int i = 0; i < matches.size(); i++) {
            Match m = matches.get(i);
            if (m.line > st.cursorLine
                    || (m.line == st.cursorLine && m.startCol >= st.cursorCol)) {
                currentIndex = i;
                break;
            }
        }
        if (currentIndex == -1 && !matches.isEmpty()) currentIndex = 0;
    }

    public Match current() {
        if (currentIndex < 0 || currentIndex >= matches.size()) return null;
        return matches.get(currentIndex);
    }

    public void next() {
        if (matches.isEmpty()) return;
        currentIndex = (currentIndex + 1) % matches.size();
    }

    public void previous() {
        if (matches.isEmpty()) return;
        currentIndex = (currentIndex - 1 + matches.size()) % matches.size();
    }
}
