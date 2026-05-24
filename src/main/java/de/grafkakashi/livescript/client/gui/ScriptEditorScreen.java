package de.grafkakashi.livescript.client.gui;

import de.grafkakashi.livescript.client.gui.autocomplete.CompletionProvider;
import de.grafkakashi.livescript.client.gui.highlight.JsTokenizer;
import de.grafkakashi.livescript.client.gui.highlight.LuaTokenizer;
import de.grafkakashi.livescript.client.gui.highlight.Tokenizer;
import de.grafkakashi.livescript.engine.ScriptType;
import de.grafkakashi.livescript.network.CreateFolderC2S;
import de.grafkakashi.livescript.network.DeleteFolderC2S;
import de.grafkakashi.livescript.network.DeleteScriptC2S;
import de.grafkakashi.livescript.network.ExecuteScriptC2S;
import de.grafkakashi.livescript.network.LoadScriptC2S;
import de.grafkakashi.livescript.network.OpenEditorC2S;
import de.grafkakashi.livescript.network.RenameScriptC2S;
import de.grafkakashi.livescript.network.SaveScriptC2S;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fullscreen editor GUI. Layout:
 *
 *   ┌─────────────────────────────────────────────────────────────────┐
 *   │  Toolbar: [Save] [Run] [Delete] [+ New] [Lang: JS|Lua]          │ 24px
 *   ├──────────────┬──────────────────────────────────────────────────┤
 *   │  File tree   │  ▼ Tabs: [foo.js] [bar.lua]                       │
 *   │              ├──────────────────────────────────────────────────┤
 *   │  hello.js    │                                                   │
 *   │  utils.lua   │   editor area with syntax-highlighted text        │
 *   │  ...         │                                                   │
 *   │              ├──────────────────────────────────────────────────┤
 *   │              │  Console:                                          │
 *   │              │    > hello                                          │
 *   │              │    (executed in 4ms)                                │
 *   └──────────────┴──────────────────────────────────────────────────┘
 *
 * Sizes are computed in {@link #init()} from {@code width}/{@code height} so
 * the layout adapts to GUI scale and window size.
 */
public class ScriptEditorScreen extends Screen {

    // Tokenizers — cheap, instantiated once
    private final Tokenizer jsTok = new JsTokenizer();
    private final Tokenizer luaTok = new LuaTokenizer();
    /** Tokenizer for .json files. Falls back to plain rendering if a file has
     *  no script type AND isn't json — but right now those are the only three
     *  editable extensions, so this covers everything. */
    private final Tokenizer jsonTok = new de.grafkakashi.livescript.client.gui.highlight.JsonTokenizer();
    private final CompletionProvider completions = new CompletionProvider();

    // State
    private List<String> scriptList;
    /** Flat list of folder paths (with trailing slash) — needed so we can show empty dirs. */
    private List<String> folderList = new ArrayList<>();
    /** Cached file tree, rebuilt whenever scriptList/folderList changes. */
    private FileTreeNode fileTree;
    /** Folders the user has expanded. Persists per editor session. */
    private final java.util.Set<String> expandedFolders = new java.util.HashSet<>();
    /** Cached flattened rows for hit-testing in the file panel. */
    private List<FileTreeNode.Row> visibleRows = List.of();
    /** Last folder the user clicked — null if a file or nothing is selected. */
    private String selectedFolder = null;
    private final Map<String, EditorState> openTabs = new LinkedHashMap<>();
    private String activeTab = null;

    // Layout (set in init / mutable via drag handles)
    private int tbY, tbH = 24;
    private int leftW = 180;       // file tree width — drag the vertical splitter
    private int tabsY, tabsH = 18;
    private int editorX, editorY, editorW, editorH;
    private int consoleY, consoleH = 90;   // console height — drag the horizontal splitter
    private static final int SPLITTER_W = 3;   // visual + hit area for drag
    private static final int MIN_LEFT_W = 80;
    /** How long the user must idle before we re-lint the active buffer. */
    private static final int LINT_DEBOUNCE_MS = 500;
    private static final int MIN_EDITOR_W = 240;
    private static final int MIN_EDITOR_H = 80;
    private static final int MIN_CONSOLE_H = 30;

    // Splitter drag tracking
    private enum DragTarget { NONE, V_SPLITTER, H_SPLITTER }
    private DragTarget dragging = DragTarget.NONE;
    /** True while the user is drag-selecting text in the editor area. */
    private boolean draggingSelection = false;

    // Editor visuals
    private static final int LINE_H = 11;
    private static final int GUTTER_W = 32;
    /**
     * Width of every character in our monospace font (livescript:mono).
     * Hard-coded because that's the whole point of the bitmap font — every
     * glyph is exactly 6 pixels wide. Keep this in sync with the atlas
     * cell width in gen_font.py if it ever changes.
     */
    private static final int CHAR_W = 6;
    private static final net.minecraft.resources.ResourceLocation MONO_FONT_ID =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    de.grafkakashi.livescript.LiveScriptMod.MOD_ID, "mono");
    /**
     * Reusable style that selects the monospace font. Mojang's Font.width() and
     * GuiGraphics.drawString() respect Style.font, so we just pass a Component
     * with this style applied instead of a raw String.
     */
    private static final net.minecraft.network.chat.Style MONO_STYLE =
            net.minecraft.network.chat.Style.EMPTY.withFont(MONO_FONT_ID);

    // Autocomplete popup
    private List<String> completionList = List.of();
    private int completionSelected = 0;

    // Modal confirm dialog (null = no dialog open)
    private ConfirmDialog confirmDialog = null;

    private record ConfirmDialog(String message, Runnable onYes) {}

    /**
     * Modal text-input dialog. Used for New (asks for filename) and Rename.
     * onConfirm receives the entered text iff validation passes.
     * The validator returns null for "ok" or an error message for "bad input".
     */
    private PromptDialog promptDialog = null;

    private static class PromptDialog {
        final String title;
        final String hint;
        String text;
        /**
         * Caret position within {@code text} — 0 = before first char, text.length() = end.
         * Private + setter so we can log every write and find any phantom mutator.
         */
        private int cursorPos;
        final java.util.function.Function<String, String> validator;
        final java.util.function.Consumer<String> onConfirm;
        String currentError;

        PromptDialog(String title, String hint, String initial,
                     java.util.function.Function<String, String> validator,
                     java.util.function.Consumer<String> onConfirm) {
            this.title = title;
            this.hint = hint;
            this.text = initial;
            this.cursorPos = initial.length();
            this.validator = validator;
            this.onConfirm = onConfirm;
            this.currentError = validator.apply(initial);
        }

        int getCursorPos() { return cursorPos; }

        /** Set cursorPos with clamping — used by external callers (mouse click etc). */
        void setCursorPos(int pos) {
            cursorPos = Math.max(0, Math.min(text.length(), pos));
        }

        /** Clamp the caret to a valid range and re-run validation. */
        void afterEdit() {
            if (cursorPos < 0) cursorPos = 0;
            if (cursorPos > text.length()) cursorPos = text.length();
            currentError = validator.apply(text);
        }

        /** Insert a single char at the caret and advance the caret past it. */
        void insertChar(char c) {
            insertString(String.valueOf(c));
        }

        /** Insert a multi-character string at the caret. Advance caret by its length. */
        void insertString(String s) {
            if (s == null || s.isEmpty()) return;
            int at = cursorPos;
            if (at < 0) at = 0;
            if (at > text.length()) at = text.length();
            text = text.substring(0, at) + s + text.substring(at);
            cursorPos = at + s.length();
            afterEdit();
        }

        /** Delete the character before the caret. */
        void backspace() {
            if (cursorPos <= 0) return;
            text = text.substring(0, cursorPos - 1) + text.substring(cursorPos);
            cursorPos--;
            afterEdit();
        }

        /** Delete the character at the caret. */
        void deleteChar() {
            if (cursorPos >= text.length()) return;
            text = text.substring(0, cursorPos) + text.substring(cursorPos + 1);
            afterEdit();
        }

        void moveLeft()  { if (cursorPos > 0) cursorPos--; }
        void moveRight() { if (cursorPos < text.length()) cursorPos++; }
        void moveHome()  { cursorPos = 0; }
        void moveEnd()   { cursorPos = text.length(); }
    }

    // Find/Replace — null = bar closed
    private FindState findState = null;
    private static final int FIND_BAR_H = 22;

    public ScriptEditorScreen(List<String> initialScriptList, List<String> initialFolderList) {
        super(Component.literal("LiveScript Editor"));
        this.scriptList = new ArrayList<>(initialScriptList);
        this.folderList = new ArrayList<>(initialFolderList);
        rebuildTree();
        // Default: expand all top-level folders so the user sees their contents
        // without having to click each one. Deeper folders stay collapsed.
        for (FileTreeNode top : fileTree.children) {
            if (top.isFolder) expandedFolders.add(top.fullPath);
        }
    }

    @Override
    protected void init() {
        // Toolbar
        tbY = 0;
        int btnY = 3;
        addRenderableWidget(Button.builder(Component.literal("Save"),    b -> saveActive()).bounds(4,   btnY, 50, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Run"),     b -> runActive()).bounds(58,  btnY, 50, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Rename"),  b -> renameActive()).bounds(112, btnY, 56, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Delete"),  b -> deleteActive()).bounds(172, btnY, 56, 18).build());
        addRenderableWidget(Button.builder(Component.literal("+ JS"),    b -> createNew(ScriptType.JS)).bounds(232, btnY, 36, 18).build());
        addRenderableWidget(Button.builder(Component.literal("+ Lua"),   b -> createNew(ScriptType.LUA)).bounds(272, btnY, 40, 18).build());
        // + JSON makes a new empty config file. Use case: user wants a fresh
        // config alongside items.json (e.g. a future entities.json) or just
        // wants to test the editor's JSON support. Default template is
        // valid empty JSON ("{}") so the save validator doesn't reject the
        // first save attempt.
        addRenderableWidget(Button.builder(Component.literal("+ JSON"),  b -> createNewJson()).bounds(316, btnY, 50, 18).build());
        addRenderableWidget(Button.builder(Component.literal("+ Folder"),b -> createFolder()).bounds(370, btnY, 60, 18).build());
        // Refresh re-reads the script directory from disk so files placed there
        // by an external editor (VSCode, Notepad, file manager) show up without
        // closing+reopening the editor. Uses the existing OpenEditorC2S which
        // already triggers a syncFileTreeTo on the server.
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> requestRefresh()).bounds(434, btnY, 56, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Close"),   b -> onClose()).bounds(width - 54, btnY, 50, 18).build());

        recomputeLayout();
    }

    /**
     * Recompute geometry from {@code leftW}, {@code consoleH}, and the current
     * window size. Called once at init and after every splitter drag.
     */
    private void recomputeLayout() {
        // Clamp splitter values to current window bounds
        leftW = Math.max(MIN_LEFT_W, Math.min(width - MIN_EDITOR_W, leftW));
        consoleH = Math.max(MIN_CONSOLE_H, Math.min(height - tbH - tabsH - MIN_EDITOR_H, consoleH));

        tabsY = tbY + tbH;
        editorX = leftW;
        int findExtra = (findState == null) ? 0 : (findState.replaceMode ? FIND_BAR_H * 2 : FIND_BAR_H);
        editorY = tabsY + tabsH + findExtra;
        editorW = width - leftW;
        editorH = height - editorY - consoleH;
        consoleY = height - consoleH;
    }

    public void updateScriptList(List<String> scripts, List<String> folders) {
        this.scriptList = new ArrayList<>(scripts);
        this.folderList = new ArrayList<>(folders);
        rebuildTree();
        // Prune expanded set: folders that no longer exist shouldn't linger
        expandedFolders.removeIf(f -> !folders.contains(f + "/") && !folders.contains(f));
    }

    private void rebuildTree() {
        this.fileTree = FileTreeNode.build(scriptList, folderList);
    }

    public void onContentReceived(String path, String content) {
        // Scripts have a ScriptType; JSON files don't (null is intentional
        // and means "editable but not executable"). Other extensions are
        // refused — we shouldn't have asked for them in the first place.
        ScriptType type = ScriptType.fromExtension(path);
        boolean isJson = path.toLowerCase(java.util.Locale.ROOT).endsWith(".json");
        if (type == null && !isJson) return;
        openTabs.put(path, new EditorState(path, type, content));
        activeTab = path;
    }

    public void onExecutionResult(String path, boolean success, String output, long durationMs) {
        EditorState st = openTabs.get(path);
        if (st == null) return;
        st.lastExecMs = durationMs;
        String prefix = success ? "§a✓§r " : "§c✗§r ";
        st.appendConsole(prefix + "[" + path + "] " + durationMs + "ms");
        if (output != null && !output.isEmpty()) st.appendConsole(output);
    }

    // ============================================================
    //  Rendering
    // ============================================================

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Dark backdrop
        g.fill(0, 0, width, height, 0xFF1E1E1E);
        super.render(g, mouseX, mouseY, partialTick);

        // Debounced re-lint of the active buffer. We sit in render anyway so a
        // tick-based scheduler would be redundant; we just check the elapsed
        // idle time against LINT_DEBOUNCE_MS. This is cheap (Rhino parses
        // hundreds of KB/ms for small files) and the user sees squiggles
        // appear shortly after they stop typing.
        EditorState activeForLint = active();
        if (activeForLint != null && activeForLint.lintDirty
                && System.currentTimeMillis() - activeForLint.lastEditMs > LINT_DEBOUNCE_MS) {
            relintActive(activeForLint);
        }

        renderFileTree(g, mouseX, mouseY);
        renderTabs(g, mouseX, mouseY);
        if (findState != null) renderFindBar(g);
        renderEditor(g);
        renderConsole(g);

        // Status bar
        EditorState st = active();
        if (st != null) {
            String lintHint = "";
            if (!st.lintIssues.isEmpty()) {
                var first = st.lintIssues.get(0);
                String suffix = st.lintIssues.size() > 1
                        ? " (+" + (st.lintIssues.size() - 1) + " more)" : "";
                lintHint = "  §c" + st.lintIssues.size() + " error"
                        + (st.lintIssues.size() == 1 ? "" : "s") + ": "
                        + "line " + first.line() + " — " + first.message() + suffix;
            }
            String status = String.format("%s — Line %d, Col %d %s%s",
                    st.path, st.cursorLine + 1, st.cursorCol + 1,
                    st.dirty ? "[unsaved]" : "",
                    lintHint);
            g.drawString(font, status, leftW + 4, height - 10, 0xFFAAAAAA, false);
        }

        // Splitters render above the panels but below popups
        renderSplitters(g, mouseX, mouseY);

        // Autocomplete popup last so it overlays
        if (!completionList.isEmpty() && st != null) {
            renderCompletions(g, st);
        }

        // Modal dialogs overlay everything else
        if (confirmDialog != null) {
            renderConfirmDialog(g);
        }
        if (promptDialog != null) {
            renderPromptDialog(g);
        }
    }

    private void renderFindBar(GuiGraphics g) {
        int y0 = tabsY + tabsH;
        int rowH = FIND_BAR_H;
        int x = leftW + 4;

        // Query row
        g.fill(leftW, y0, width, y0 + rowH, 0xFF333337);
        renderFindField(g, "Find:", findState.query, findState.focusOnQuery,
                x, y0 + 4, 220);

        // Match counter
        String counter = findState.matches.isEmpty() ? "0 matches"
                : (findState.currentIndex + 1) + " / " + findState.matches.size();
        g.drawString(font, counter, x + 230, y0 + 6, 0xFFAAAAAA, false);

        // Case toggle [Aa]
        int caseBoxX = x + 310;
        g.fill(caseBoxX, y0 + 4, caseBoxX + 22, y0 + rowH - 4,
                findState.caseSensitive ? 0xFF094771 : 0xFF1E1E1E);
        g.drawString(font, "Aa", caseBoxX + 5, y0 + 6, 0xFFFFFFFF, false);

        // Replace-mode toggle [Replace]
        int replX = caseBoxX + 30;
        g.fill(replX, y0 + 4, replX + 60, y0 + rowH - 4,
                findState.replaceMode ? 0xFF094771 : 0xFF1E1E1E);
        g.drawString(font, "Replace", replX + 6, y0 + 6, 0xFFFFFFFF, false);

        // Close [x]
        g.drawString(font, "§7Esc to close", width - 80, y0 + 6, 0xFFAAAAAA, false);

        if (!findState.replaceMode) return;

        // Replace row
        int y1 = y0 + rowH;
        g.fill(leftW, y1, width, y1 + rowH, 0xFF2D2D30);
        renderFindField(g, "Repl:", findState.replacement, !findState.focusOnQuery,
                x, y1 + 4, 220);

        int rOneX = x + 240;
        g.fill(rOneX, y1 + 4, rOneX + 75, y1 + rowH - 4, 0xFF1E1E1E);
        g.drawString(font, "Replace 1", rOneX + 6, y1 + 6, 0xFFFFFFFF, false);

        int rAllX = rOneX + 80;
        g.fill(rAllX, y1 + 4, rAllX + 75, y1 + rowH - 4, 0xFF1E1E1E);
        g.drawString(font, "Repl. all", rAllX + 6, y1 + 6, 0xFFFFFFFF, false);
    }

    private void renderFindField(GuiGraphics g, String label, String text, boolean focused,
                                 int x, int y, int w) {
        g.drawString(font, label, x, y + 2, 0xFFAAAAAA, false);
        int boxX = x + font.width(label) + 4;
        g.fill(boxX, y, boxX + w, y + 14, 0xFF1E1E1E);
        g.fill(boxX, y, boxX + w, y + 1, focused ? 0xFF5BA0F5 : 0xFF555555);
        g.drawString(font, text, boxX + 3, y + 3, 0xFFE6E6E6, false);
        if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
            int cx = boxX + 3 + font.width(text);
            g.fill(cx, y + 2, cx + 1, y + 12, 0xFFFFFFFF);
        }
    }

    private void renderSplitters(GuiGraphics g, int mouseX, int mouseY) {
        // Vertical splitter between file tree and editor
        boolean vHover = hoveringVSplitter(mouseX, mouseY) || dragging == DragTarget.V_SPLITTER;
        int vCol = vHover ? 0xFF5BA0F5 : 0xFF3F3F46;
        g.fill(leftW - 1, tabsY, leftW + SPLITTER_W - 1, height, vCol);

        // Horizontal splitter between editor and console
        boolean hHover = hoveringHSplitter(mouseX, mouseY) || dragging == DragTarget.H_SPLITTER;
        int hCol = hHover ? 0xFF5BA0F5 : 0xFF3F3F46;
        g.fill(leftW, consoleY - SPLITTER_W + 1, width, consoleY + 1, hCol);
    }

    private boolean hoveringVSplitter(double mx, double my) {
        return mx >= leftW - 1 && mx < leftW + SPLITTER_W - 1
                && my >= tabsY && my < height - consoleH;
    }

    private boolean hoveringHSplitter(double mx, double my) {
        return mx >= leftW && my >= consoleY - SPLITTER_W + 1 && my < consoleY + 1;
    }

    private void renderConfirmDialog(GuiGraphics g) {
        // Dim the whole screen
        g.fill(0, 0, width, height, 0xC0000000);
        int boxW = 280, boxH = 80;
        int boxX = (width - boxW) / 2;
        int boxY = (height - boxH) / 2;
        g.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xFF2D2D30);
        g.fill(boxX, boxY, boxX + boxW, boxY + 1, 0xFF666666);
        g.fill(boxX, boxY + boxH - 1, boxX + boxW, boxY + boxH, 0xFF666666);
        g.fill(boxX, boxY, boxX + 1, boxY + boxH, 0xFF666666);
        g.fill(boxX + boxW - 1, boxY, boxX + boxW, boxY + boxH, 0xFF666666);

        g.drawCenteredString(font, confirmDialog.message(),
                boxX + boxW / 2, boxY + 16, 0xFFFFFFFF);
        g.drawCenteredString(font, "§7Y to confirm, N or Esc to cancel",
                boxX + boxW / 2, boxY + 38, 0xFFAAAAAA);
        g.drawCenteredString(font, "§a[ Y ]§r       §c[ N ]§r",
                boxX + boxW / 2, boxY + 56, 0xFFFFFFFF);
    }

    private void renderPromptDialog(GuiGraphics g) {
        // Dim the whole screen
        g.fill(0, 0, width, height, 0xC0000000);
        int boxW = 380, boxH = 110;
        int boxX = (width - boxW) / 2;
        int boxY = (height - boxH) / 2;
        // Frame
        g.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xFF2D2D30);
        g.fill(boxX, boxY, boxX + boxW, boxY + 1, 0xFF666666);
        g.fill(boxX, boxY + boxH - 1, boxX + boxW, boxY + boxH, 0xFF666666);
        g.fill(boxX, boxY, boxX + 1, boxY + boxH, 0xFF666666);
        g.fill(boxX + boxW - 1, boxY, boxX + boxW, boxY + boxH, 0xFF666666);

        g.drawString(font, "§l" + promptDialog.title, boxX + 12, boxY + 10, 0xFFFFFFFF, false);
        if (promptDialog.hint != null) {
            g.drawString(font, "§7" + promptDialog.hint, boxX + 12, boxY + 24, 0xFFAAAAAA, false);
        }

        // Text input box
        int inputY = boxY + 44;
        int inputW = boxW - 24;
        g.fill(boxX + 12, inputY, boxX + 12 + inputW, inputY + 16, 0xFF1E1E1E);
        // Focus indicator: thin colored top border, red if validation failed
        int borderColor = promptDialog.currentError == null ? 0xFF5BA0F5 : 0xFFE06C75;
        g.fill(boxX + 12, inputY, boxX + 12 + inputW, inputY + 1, borderColor);
        // Text + blinking cursor
        g.drawString(font, promptDialog.text, boxX + 16, inputY + 4, 0xFFE6E6E6, false);
        if ((System.currentTimeMillis() / 500) % 2 == 0) {
            // Width-up-to-cursor lets the caret sit between characters, not always at end
            String preCursor = promptDialog.text.substring(0, promptDialog.getCursorPos());
            int cx = boxX + 16 + font.width(preCursor);
            g.fill(cx, inputY + 3, cx + 1, inputY + 14, 0xFFFFFFFF);
        }

        // Error message (if validation failed)
        if (promptDialog.currentError != null) {
            g.drawString(font, "§c" + promptDialog.currentError,
                    boxX + 12, inputY + 22, 0xFFE06C75, false);
        }

        // Footer hint
        String footer = promptDialog.currentError == null
                ? "§7Enter to confirm   §7Esc to cancel"
                : "§7Fix the error to enable confirm   §7Esc to cancel";
        g.drawString(font, footer, boxX + 12, boxY + boxH - 14, 0xFFAAAAAA, false);
    }

    private void renderFileTree(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(0, tabsY, leftW, height, 0xFF252526);
        g.drawString(font, "§lSCRIPTS", 6, tabsY + 4, 0xFFAAAAAA, false);

        // Refresh the flat row list each frame — cheap (~ms for thousands of files)
        // and means we don't need to invalidate it when expandedFolders changes.
        visibleRows = fileTree.flatten(expandedFolders);

        int y = tabsY + 18;
        for (FileTreeNode.Row row : visibleRows) {
            FileTreeNode node = row.node();
            // Highlight backgrounds
            boolean hover = mouseX >= 0 && mouseX < leftW && mouseY >= y && mouseY < y + LINE_H + 2;
            boolean active = !node.isFolder && node.fullPath.equals(activeTab);
            boolean folderSel = node.isFolder && node.fullPath.equals(selectedFolder);
            int bg = active ? 0xFF373737 : (folderSel ? 0xFF2E3F4F : (hover ? 0xFF2D2D2D : 0));
            if (bg != 0) g.fill(0, y, leftW, y + LINE_H + 2, bg);

            int indent = 4 + row.depth() * 10;
            int textX = indent + 12;

            if (node.isFolder) {
                // Disclosure triangle: ▸ collapsed / ▾ expanded
                boolean expanded = expandedFolders.contains(node.fullPath);
                g.drawString(font, expanded ? "§7v" : "§7>", indent, y + 2, 0xFFAAAAAA, false);
                // Folder icon — a small yellow square
                g.fill(indent + 8, y + 4, indent + 12, y + 8, 0xFFD7BA7D);
                g.drawString(font, node.displayName, textX + 2, y + 2, 0xFFE8E8E8, false);
            } else {
                // Language hint dot — yellow for JS, blue for Lua
                int dot = node.fullPath.endsWith(".js") ? 0xFFE8C97A : 0xFF6BC1FF;
                g.fill(indent + 4, y + 4, indent + 8, y + 8, dot);
                int col = active ? 0xFFFFFFFF : 0xFFCCCCCC;
                g.drawString(font, node.displayName, textX, y + 2, col, false);
            }
            y += LINE_H + 2;
        }
    }

    private void renderTabs(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(leftW, tabsY, width, tabsY + tabsH, 0xFF2D2D2D);
        int x = leftW + 4;
        for (String p : openTabs.keySet()) {
            int w = font.width(p) + 16;
            boolean active = p.equals(activeTab);
            int bg = active ? 0xFF1E1E1E : 0xFF333333;
            g.fill(x, tabsY + 2, x + w, tabsY + tabsH, bg);
            int col = active ? 0xFFFFFFFF : 0xFFAAAAAA;
            g.drawString(font, p, x + 4, tabsY + 6, col, false);
            // close hint
            g.drawString(font, "x", x + w - 8, tabsY + 6, 0xFF888888, false);
            x += w + 2;
        }
    }

    private void renderEditor(GuiGraphics g) {
        EditorState st = active();
        if (st == null) {
            g.drawCenteredString(font, "Open a script from the left, or click + New",
                    editorX + editorW / 2, editorY + editorH / 2 - 4, 0xFF777777);
            return;
        }

        g.fill(editorX, editorY, editorX + editorW, editorY + editorH, 0xFF1E1E1E);
        // Type-driven tokenizer pick. JSON files have type == null (JSON isn't
        // an executable script type) and get the dedicated JSON tokenizer.
        // Anything else routes to JS/Lua. Future file kinds will fall through
        // to luaTok by default — fine since they're plain-text rendered.
        Tokenizer tok;
        if (st.type == ScriptType.JS) tok = jsTok;
        else if (st.type == ScriptType.LUA) tok = luaTok;
        else if (st.path.toLowerCase(java.util.Locale.ROOT).endsWith(".json")) tok = jsonTok;
        else tok = luaTok;
        st.ensureFoldsFresh();

        int visibleRows = editorH / LINE_H;

        // Walk source lines starting at scrollLine, skipping hidden ones,
        // and lay them out at consecutive y positions.
        int sourceLine = st.scrollLine;
        int displayedRows = 0;

        // Ensure tokenizer states are valid up to scrollLine (cheap if cached)
        while (st.lineStartStates.size() <= st.scrollLine && st.lineStartStates.size() <= st.lines.size()) {
            int idx = st.lineStartStates.size() - 1;
            if (idx < 0 || idx >= st.lines.size()) break;
            Tokenizer.LineState prev = st.lineStartStates.get(idx);
            Tokenizer.Result r = tok.tokenize(st.lines.get(idx), prev);
            st.lineStartStates.add(r.endState());
        }

        while (displayedRows < visibleRows && sourceLine < st.lines.size()) {
            if (st.isHidden(sourceLine)) {
                // Hidden lines still need their token state cached so the next
                // visible line gets the right start-state (e.g. a /* ... */ inside
                // a folded block).
                Tokenizer.LineState startState = sourceLine < st.lineStartStates.size()
                        ? st.lineStartStates.get(sourceLine)
                        : Tokenizer.LineState.NORMAL;
                Tokenizer.Result r = tok.tokenize(st.lines.get(sourceLine), startState);
                if (sourceLine + 1 >= st.lineStartStates.size()) {
                    st.lineStartStates.add(r.endState());
                } else {
                    st.lineStartStates.set(sourceLine + 1, r.endState());
                }
                sourceLine++;
                continue;
            }

            int y = editorY + displayedRows * LINE_H;
            renderOneLine(g, st, tok, sourceLine, y);
            sourceLine++;
            displayedRows++;
        }
    }

    /** Render one already-known-visible source line at row y. */
    private void renderOneLine(GuiGraphics g, EditorState st, Tokenizer tok, int i, int y) {
        // Gutter: line number, plus fold marker if this is a fold header
        g.fill(editorX, y, editorX + GUTTER_W, y + LINE_H, 0xFF252526);
        String lineNo = String.valueOf(i + 1);
        // Line numbers stay in vanilla font — they're tiny and a few pixels of width drift doesn't matter
        g.drawString(font, lineNo, editorX + GUTTER_W - font.width(lineNo) - 4, y + 1, 0xFF666666, false);

        // Fold marker (▶ when folded, ▼ when expanded) in the gutter's left edge
        FoldRegion fr = foldHeaderAt(st, i);
        if (fr != null) {
            boolean folded = st.foldedHeaderLines.contains(fr.headerLine);
            String marker = folded ? ">" : "v";
            g.drawString(font, marker, editorX + 2, y + 1,
                    folded ? 0xFFE8C97A : 0xFF666666, false);
        }

        // Current line highlight
        if (i == st.cursorLine) {
            g.fill(editorX + GUTTER_W, y, editorX + editorW, y + LINE_H, 0xFF2A2A2A);
        }

        // Selection highlight — blue band over the selected span on this line
        if (st.hasSelection()) {
            int[] selS = st.selectionStart();
            int[] selE = st.selectionEnd();
            if (i >= selS[0] && i <= selE[0]) {
                int textX0 = editorX + GUTTER_W + 4;
                int startCol = (i == selS[0]) ? selS[1] : 0;
                int endCol = (i == selE[0]) ? selE[1] : st.lines.get(i).length();
                int sx = textX0 + startCol * CHAR_W;
                int ex = textX0 + endCol * CHAR_W;
                // Pad full-line selections so the highlight is visible even on blank lines
                if (i < selE[0]) ex = Math.max(ex, sx + CHAR_W / 2);
                g.fill(sx, y, ex, y + LINE_H, 0xFF264F78);
            }
        }

        // Find matches on this line — drawn before text so the text overlays
        if (findState != null && !findState.matches.isEmpty()) {
            int textX0 = editorX + GUTTER_W + 4;
            for (int mi = 0; mi < findState.matches.size(); mi++) {
                FindState.Match m = findState.matches.get(mi);
                if (m.line() != i) continue;
                int sx = textX0 + m.startCol() * CHAR_W;
                int ex = textX0 + m.endCol() * CHAR_W;
                int color = (mi == findState.currentIndex) ? 0xFFB85C00 : 0x80FFCC00;
                g.fill(sx, y + 1, ex, y + LINE_H, color);
            }
        }

        // Tokenize this line with cached start-state, store the resulting end-state
        Tokenizer.LineState startState = i < st.lineStartStates.size()
                ? st.lineStartStates.get(i)
                : Tokenizer.LineState.NORMAL;
        String line = st.lines.get(i);
        Tokenizer.Result result = tok.tokenize(line, startState);

        if (i + 1 >= st.lineStartStates.size()) {
            st.lineStartStates.add(result.endState());
        } else {
            st.lineStartStates.set(i + 1, result.endState());
        }

        // Single-pass colored render: walk the line char by char, looking up
        // each position's color from the token list. No double-draw, no overlap.
        int textX = editorX + GUTTER_W + 4;
        renderTokenizedLine(g, line, result.tokens(), textX, y + 1);

        // "… N lines hidden" hint at end of a folded header line
        if (fr != null && st.foldedHeaderLines.contains(fr.headerLine)) {
            String hint = "  ... " + fr.hiddenLineCount() + " hidden";
            int hx = textX + line.length() * CHAR_W;
            drawMono(g, hint, hx, y + 1, 0xFF7F848E);
        }

        // Lint squigglies — drawn under text just above the cursor row baseline.
        // We iterate the issue list (capped to 10 in the linter) and only render
        // those whose line == i+1 (linter is 1-based, source-line index is 0-based).
        if (!st.lintIssues.isEmpty()) {
            for (var issue : st.lintIssues) {
                if (issue.line() - 1 != i) continue;
                int col = Math.max(0, Math.min(line.length(), issue.column()));
                int len = Math.max(1, Math.min(line.length() - col, issue.length()));
                int sx = textX + col * CHAR_W;
                int ex = textX + (col + len) * CHAR_W;
                drawSquiggle(g, sx, ex, y + LINE_H - 2);
            }
        }

        // Cursor
        if (i == st.cursorLine && (System.currentTimeMillis() / 500) % 2 == 0) {
            int cx = textX + st.cursorCol * CHAR_W;
            g.fill(cx, y + 1, cx + 1, y + LINE_H - 1, 0xFFFFFFFF);
        }
    }

    /**
     * Draw a sawtooth wavy underline from x0 to x1 at the given baseline y.
     * Two pixels tall: alternates between y and y+1 in 2-pixel steps. Cheap,
     * recognizable as "syntax error" in every other editor.
     */
    private void drawSquiggle(GuiGraphics g, int x0, int x1, int y) {
        int color = 0xFFE06C75; // soft red, same hue as our error border
        boolean high = false;
        for (int x = x0; x < x1; x += 2) {
            int dotY = high ? y : y + 1;
            g.fill(x, dotY, x + 1, dotY + 1, color);
            high = !high;
        }
    }

    /**
     * Build a color-per-position array from the token list, then group runs of
     * the same color and emit one drawString per run. This is the fix for the
     * v0.3 double-render bug: tokens never overlap with a default-color pass.
     */
    private void renderTokenizedLine(GuiGraphics g, String line,
                                     java.util.List<Tokenizer.Token> tokens,
                                     int x, int y) {
        int n = line.length();
        if (n == 0) return;
        int[] colors = new int[n];
        java.util.Arrays.fill(colors, Tokenizer.Palette.DEFAULT);
        for (Tokenizer.Token t : tokens) {
            int end = Math.min(n, t.start() + t.length());
            for (int k = t.start(); k < end; k++) colors[k] = t.colorRgb();
        }
        // Group consecutive same-color runs into single draws
        int runStart = 0;
        for (int k = 1; k <= n; k++) {
            if (k == n || colors[k] != colors[runStart]) {
                String segment = line.substring(runStart, k);
                drawMono(g, segment, x + runStart * CHAR_W, y, colors[runStart]);
                runStart = k;
            }
        }
    }

    /** Draw a string using our monospace font. */
    private void drawMono(GuiGraphics g, String text, int x, int y, int color) {
        g.drawString(font,
                net.minecraft.network.chat.Component.literal(text).withStyle(MONO_STYLE),
                x, y, color, false);
    }

    private FoldRegion foldHeaderAt(EditorState st, int sourceLine) {
        for (FoldRegion r : st.foldRegions) {
            if (r.headerLine == sourceLine) return r;
        }
        return null;
    }

    private void renderConsole(GuiGraphics g) {
        g.fill(leftW, consoleY, width, consoleY + consoleH, 0xFF101010);
        g.drawString(font, "§7CONSOLE", leftW + 4, consoleY + 2, 0xFFAAAAAA, false);
        EditorState st = active();
        if (st == null) return;
        int visible = (consoleH - 14) / LINE_H;
        int start = Math.max(0, st.consoleLines.size() - visible);
        int y = consoleY + 12;
        for (int i = start; i < st.consoleLines.size(); i++) {
            // Keep console in vanilla font so § color codes (e.g. §a✓§r) keep working.
            // Code alignment matters less here than legible status messages.
            g.drawString(font, st.consoleLines.get(i), leftW + 4, y, 0xFFCCCCCC, false);
            y += LINE_H;
        }
    }

    private void renderCompletions(GuiGraphics g, EditorState st) {
        int cursorPxX = editorX + GUTTER_W + 4 + st.cursorCol * CHAR_W;
        int cursorPxY = editorY + (st.cursorLine - st.scrollLine + 1) * LINE_H;
        int w = 180;
        int h = completionList.size() * LINE_H + 4;
        g.fill(cursorPxX, cursorPxY, cursorPxX + w, cursorPxY + h, 0xFF2D2D30);
        g.fill(cursorPxX, cursorPxY, cursorPxX + w, cursorPxY + 1, 0xFF666666);
        for (int i = 0; i < completionList.size(); i++) {
            if (i == completionSelected) {
                g.fill(cursorPxX, cursorPxY + 2 + i * LINE_H,
                        cursorPxX + w, cursorPxY + 2 + (i + 1) * LINE_H, 0xFF094771);
            }
            g.drawString(font, completionList.get(i), cursorPxX + 4, cursorPxY + 3 + i * LINE_H,
                    0xFFEEEEEE, false);
        }
    }

    // ============================================================
    //  Input
    // ============================================================

    /**
     * For a proportional font, find the caret index that corresponds to a click
     * at screen-x {@code clickX}, given the text starts rendering at {@code textX}.
     * Returns the index in {@code text} that places the caret closest to the click
     * (with rounding at character mid-points), so clicking just past a char's center
     * lands the caret on its right edge.
     *
     * Linear scan: for typical prompt-dialog text (well under 100 chars) this is
     * cheaper than building a prefix-width table.
     */
    private int caretPosForX(String text, int textX, int clickX) {
        if (clickX <= textX) return 0;
        int prevWidth = 0;
        for (int i = 1; i <= text.length(); i++) {
            int w = font.width(text.substring(0, i));
            int charMid = textX + (prevWidth + w) / 2;
            if (clickX < charMid) return i - 1;
            prevWidth = w;
        }
        return text.length();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (confirmDialog != null) return true; // consume; only keyboard dismisses
        if (promptDialog != null) {
            // Only left-clicks (button 0) can move the caret. Anything else is
            // consumed but ignored so we don't accidentally jump the caret on a
            // synthetic mouse event from the window system.
            if (button != 0) return true;
            int boxW = 380, boxH = 110;
            int boxX = (width - boxW) / 2;
            int boxY = (height - boxH) / 2;
            int inputY = boxY + 44;
            int inputW = boxW - 24;
            int inputXStart = boxX + 12;
            int inputXEnd = inputXStart + inputW;
            int textX = boxX + 16;
            if (mouseX >= inputXStart && mouseX < inputXEnd
                    && mouseY >= inputY && mouseY < inputY + 16) {
                promptDialog.setCursorPos(caretPosForX(promptDialog.text, textX, (int) mouseX));
            }
            return true;
        }

        // Find bar click handling (when open)
        if (findState != null) {
            int y0 = tabsY + tabsH;
            int x = leftW + 4;
            int caseBoxX = x + 310;
            int replX = caseBoxX + 30;
            // Row 1: Aa, Replace toggle, query field
            if (mouseY >= y0 && mouseY < y0 + FIND_BAR_H) {
                if (mouseX >= caseBoxX && mouseX < caseBoxX + 22) {
                    findState.caseSensitive = !findState.caseSensitive;
                    EditorState st = active();
                    if (st != null) findState.recomputeMatches(st);
                    return true;
                }
                if (mouseX >= replX && mouseX < replX + 60) {
                    findState.replaceMode = !findState.replaceMode;
                    recomputeLayout();
                    return true;
                }
                // Click on query field → focus there
                findState.focusOnQuery = true;
                return true;
            }
            // Row 2 (replace mode only): replacement field + buttons
            if (findState.replaceMode && mouseY >= y0 + FIND_BAR_H && mouseY < y0 + FIND_BAR_H * 2) {
                int rOneX = x + 240;
                int rAllX = rOneX + 80;
                if (mouseX >= rOneX && mouseX < rOneX + 75) { replaceCurrent(); return true; }
                if (mouseX >= rAllX && mouseX < rAllX + 75) { replaceAll(); return true; }
                findState.focusOnQuery = false;
                return true;
            }
        }

        // Splitter takes precedence over other clicks — both occupy the same area
        // as the panels behind them, so we MUST check this before delegating.
        if (hoveringVSplitter(mouseX, mouseY)) {
            dragging = DragTarget.V_SPLITTER;
            return true;
        }
        if (hoveringHSplitter(mouseX, mouseY)) {
            dragging = DragTarget.H_SPLITTER;
            return true;
        }

        if (super.mouseClicked(mouseX, mouseY, button)) return true;

        // File tree click
        if (mouseX < leftW && mouseY >= tabsY + 18) {
            int idx = (int) ((mouseY - (tabsY + 18)) / (LINE_H + 2));
            if (idx >= 0 && idx < visibleRows.size()) {
                FileTreeNode node = visibleRows.get(idx).node();
                if (node.isFolder) {
                    // Toggle expansion. Also remember the selected folder so
                    // F2/Delete/Rename can operate on it (no active tab needed).
                    if (expandedFolders.contains(node.fullPath)) {
                        expandedFolders.remove(node.fullPath);
                    } else {
                        expandedFolders.add(node.fullPath);
                    }
                    selectedFolder = node.fullPath;
                } else {
                    selectedFolder = null;
                    openScript(node.fullPath);
                }
                return true;
            }
        }

        // Tab click
        if (mouseY >= tabsY && mouseY < tabsY + tabsH && mouseX >= leftW) {
            int x = leftW + 4;
            for (String p : openTabs.keySet()) {
                int w = font.width(p) + 16;
                if (mouseX >= x && mouseX < x + w) {
                    // close-button area is the last 10px
                    if (mouseX >= x + w - 10) {
                        openTabs.remove(p);
                        if (p.equals(activeTab)) {
                            activeTab = openTabs.isEmpty() ? null : openTabs.keySet().iterator().next();
                        }
                    } else {
                        activeTab = p;
                        selectedFolder = null;
                    }
                    return true;
                }
                x += w + 2;
            }
        }

        // Editor click → toggle fold marker or move cursor
        EditorState st = active();
        if (st != null && mouseX >= editorX && mouseY >= editorY && mouseY < editorY + editorH) {
            int displayedRows = (int) ((mouseY - editorY) / LINE_H);
            // Walk source lines from scrollLine, skipping hidden, to find which source line was clicked
            int src = st.scrollLine;
            int rowsLeft = displayedRows;
            st.ensureFoldsFresh();
            while (rowsLeft > 0 && src < st.lines.size() - 1) {
                src++;
                if (!st.isHidden(src)) rowsLeft--;
            }
            if (src >= st.lines.size()) return true;

            // Click in fold-marker gutter region (first ~10px of gutter)?
            if (mouseX >= editorX && mouseX < editorX + 12) {
                if (st.toggleFoldAt(src)) return true;
            }

            int col = (int) ((mouseX - editorX - GUTTER_W - 4) / CHAR_W);
            // Shift-click extends an existing selection; plain click starts a new one.
            // hasShiftDown() reads modifier state from GLFW since mouseClicked doesn't
            // pass us the modifiers like keyPressed does.
            if (hasShiftDown()) {
                st.extendCursorTo(src, col);
            } else {
                st.setCursor(src, col);
            }
            // Begin a potential drag-select. mouseDragged will read this flag and
            // call extendCursorTo from there.
            draggingSelection = true;
            completionList = List.of();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        EditorState st = active();
        if (st != null && mouseX >= editorX && mouseY >= editorY && mouseY < editorY + editorH) {
            st.scrollLine = Math.max(0, Math.min(st.lines.size() - 1,
                    st.scrollLine - (int) Math.signum(scrollY) * 3));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (dragging == DragTarget.V_SPLITTER) {
            leftW = (int) mouseX;
            recomputeLayout();
            return true;
        }
        if (dragging == DragTarget.H_SPLITTER) {
            consoleH = height - (int) mouseY;
            recomputeLayout();
            return true;
        }
        if (draggingSelection) {
            EditorState st = active();
            if (st == null) return true;
            // Map mouse position to a (line, col) in source-line space, skipping folds.
            // Allow mouseY outside the editor band so dragging "off the bottom" still extends.
            int displayedRows = (int) ((mouseY - editorY) / LINE_H);
            displayedRows = Math.max(0, displayedRows);
            int src = st.scrollLine;
            int rowsLeft = displayedRows;
            st.ensureFoldsFresh();
            while (rowsLeft > 0 && src < st.lines.size() - 1) {
                src++;
                if (!st.isHidden(src)) rowsLeft--;
            }
            src = Math.min(src, st.lines.size() - 1);
            int col = (int) ((mouseX - editorX - GUTTER_W - 4) / CHAR_W);
            if (col < 0) col = 0;
            st.extendCursorTo(src, col);
            ensureCursorVisible(st);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging != DragTarget.NONE) {
            dragging = DragTarget.NONE;
            return true;
        }
        if (draggingSelection) {
            draggingSelection = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        // Prompt dialog takes absolute precedence (modal)
        if (promptDialog != null) {
            if (c >= 32 && c != 127) {
                promptDialog.insertChar(c);
            }
            return true;
        }
        // Find bar takes precedence
        if (findState != null && c >= 32 && c != 127) {
            if (findState.focusOnQuery) {
                findState.query += c;
                EditorState st = active();
                if (st != null) findState.recomputeMatches(st);
            } else {
                findState.replacement += c;
            }
            return true;
        }
        EditorState st = active();
        if (st == null) return false;
        if (c < 32 || c == 127) return false;
        st.undoStack.push(st, true);  // coalesce typing bursts
        st.insertChar(c);
        if (findState != null) findState.recomputeMatches(st);
        updateCompletions(st);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        EditorState st = active();
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;

        // Modal confirm dialog short-circuits everything
        if (confirmDialog != null) {
            if (keyCode == GLFW.GLFW_KEY_Y) {
                Runnable yes = confirmDialog.onYes();
                confirmDialog = null;
                yes.run();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_N || keyCode == GLFW.GLFW_KEY_ESCAPE) {
                confirmDialog = null;
                return true;
            }
            return true; // consume all other input while dialog is open
        }

        // Modal prompt dialog
        if (promptDialog != null) {
            PromptDialog pd = promptDialog;
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                promptDialog = null;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER) {
                if (pd.currentError == null) {
                    var cb = pd.onConfirm;
                    String txt = pd.text;
                    promptDialog = null;
                    cb.accept(txt);
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) { pd.backspace();  return true; }
            if (keyCode == GLFW.GLFW_KEY_DELETE)    { pd.deleteChar(); return true; }
            if (keyCode == GLFW.GLFW_KEY_LEFT)      { pd.moveLeft();   return true; }
            if (keyCode == GLFW.GLFW_KEY_RIGHT)     { pd.moveRight();  return true; }
            if (keyCode == GLFW.GLFW_KEY_HOME)      { pd.moveHome();   return true; }
            if (keyCode == GLFW.GLFW_KEY_END)       { pd.moveEnd();    return true; }
            // Paste at caret
            if (ctrl && keyCode == GLFW.GLFW_KEY_V) {
                String pasted = net.minecraft.client.Minecraft.getInstance().keyboardHandler.getClipboard();
                if (pasted != null && !pasted.isEmpty()) {
                    pd.insertString(pasted.replace("\n", "").replace("\r", ""));
                }
                return true;
            }
            // Copy whole text
            if (ctrl && keyCode == GLFW.GLFW_KEY_C) {
                if (!pd.text.isEmpty()) {
                    net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(pd.text);
                }
                return true;
            }
            return true; // consume all other input (printable chars handled in charTyped)
        }

        // Find-bar shortcuts (always active when bar is open)
        if (findState != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                findState = null;
                recomputeLayout();
                return true;
            }
            if (ctrl && keyCode == GLFW.GLFW_KEY_H) {
                findState.replaceMode = !findState.replaceMode;
                recomputeLayout();
                return true;
            }
            if (ctrl && keyCode == GLFW.GLFW_KEY_F) {
                // Re-focus the query box and clear focus from cursor edits
                findState.focusOnQuery = true;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_TAB && findState.replaceMode) {
                findState.focusOnQuery = !findState.focusOnQuery;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER) {
                if (shift) findState.previous(); else findState.next();
                if (st != null) jumpToCurrentMatch(st);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (findState.focusOnQuery && !findState.query.isEmpty()) {
                    findState.query = findState.query.substring(0, findState.query.length() - 1);
                    if (st != null) findState.recomputeMatches(st);
                } else if (!findState.focusOnQuery && !findState.replacement.isEmpty()) {
                    findState.replacement = findState.replacement.substring(0, findState.replacement.length() - 1);
                }
                return true;
            }
            // Let printable chars fall through to charTyped below
        }

        // Ctrl+F opens the find bar
        if (ctrl && keyCode == GLFW.GLFW_KEY_F) {
            if (findState == null) {
                findState = new FindState();
                if (st != null) findState.recomputeMatches(st);
                recomputeLayout();
            }
            return true;
        }

        // Global shortcuts
        if (ctrl && keyCode == GLFW.GLFW_KEY_S) { saveActive(); return true; }
        if (ctrl && keyCode == GLFW.GLFW_KEY_ENTER) { runActive(); return true; }
        if (keyCode == GLFW.GLFW_KEY_F2) { renameActive(); return true; }

        if (st == null) return super.keyPressed(keyCode, scanCode, modifiers);

        // Undo / Redo — Ctrl+Z = undo, Ctrl+Y or Ctrl+Shift+Z = redo
        if (ctrl && keyCode == GLFW.GLFW_KEY_Z && !shift) {
            if (st.undoStack.undo(st)) ensureCursorVisible(st);
            completionList = List.of();
            return true;
        }
        if (ctrl && (keyCode == GLFW.GLFW_KEY_Y || (keyCode == GLFW.GLFW_KEY_Z && shift))) {
            if (st.undoStack.redo(st)) ensureCursorVisible(st);
            completionList = List.of();
            return true;
        }

        // Completion popup navigation
        if (!completionList.isEmpty()) {
            if (keyCode == GLFW.GLFW_KEY_DOWN) { completionSelected = (completionSelected + 1) % completionList.size(); return true; }
            if (keyCode == GLFW.GLFW_KEY_UP) { completionSelected = (completionSelected - 1 + completionList.size()) % completionList.size(); return true; }
            if (keyCode == GLFW.GLFW_KEY_TAB || keyCode == GLFW.GLFW_KEY_ENTER) {
                acceptCompletion(st);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) { completionList = List.of(); return true; }
        }

        // Clipboard shortcuts — Ctrl+C / Ctrl+X / Ctrl+V / Ctrl+A
        // We use Mojang's keyboardHandler.getClipboard()/setClipboard() so the
        // system clipboard is shared with other apps (paste from a browser etc).
        if (ctrl && keyCode == GLFW.GLFW_KEY_A) {
            st.selectAll();
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_C) {
            String sel = st.hasSelection() ? st.getSelectedText() : st.currentLine() + "\n";
            if (!sel.isEmpty()) {
                net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(sel);
            }
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_X) {
            // Cut: if no selection, take the whole current line (like most editors)
            if (st.hasSelection()) {
                String sel = st.getSelectedText();
                net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(sel);
                st.undoStack.push(st, false);
                st.deleteSelection();
            } else {
                String line = st.currentLine();
                net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(line + "\n");
                st.undoStack.push(st, false);
                // Delete the whole line including its trailing newline
                if (st.lines.size() == 1) {
                    st.lines.set(0, "");
                    st.cursorCol = 0;
                } else {
                    st.lines.remove(st.cursorLine);
                    if (st.cursorLine >= st.lines.size()) st.cursorLine = st.lines.size() - 1;
                    st.cursorCol = Math.min(st.cursorCol, st.lines.get(st.cursorLine).length());
                }
                st.collapseSelection();
                st.dirty = true;
                st.resetStates();
                st.foldRegionsDirty = true;
            }
            updateCompletions(st);
            ensureCursorVisible(st);
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_V) {
            String pasted = net.minecraft.client.Minecraft.getInstance().keyboardHandler.getClipboard();
            if (pasted != null && !pasted.isEmpty()) {
                st.undoStack.push(st, false);
                st.insertText(pasted);
                ensureCursorVisible(st);
                updateCompletions(st);
            }
            return true;
        }

        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT     -> { moveOrExtend(st, shift, 0, -1); ensureCursorVisible(st); }
            case GLFW.GLFW_KEY_RIGHT    -> { moveOrExtend(st, shift, 0, +1); ensureCursorVisible(st); }
            case GLFW.GLFW_KEY_UP       -> { moveVertical(st, shift, -1); ensureCursorVisible(st); }
            case GLFW.GLFW_KEY_DOWN     -> { moveVertical(st, shift, +1); ensureCursorVisible(st); }
            case GLFW.GLFW_KEY_HOME     -> { setOrExtend(st, shift, st.cursorLine, 0); }
            case GLFW.GLFW_KEY_END      -> { setOrExtend(st, shift, st.cursorLine, st.currentLine().length()); }
            case GLFW.GLFW_KEY_BACKSPACE-> { st.undoStack.push(st, false); st.backspace(); updateCompletions(st); }
            case GLFW.GLFW_KEY_DELETE   -> { st.undoStack.push(st, false); st.delete(); updateCompletions(st); }
            case GLFW.GLFW_KEY_ENTER    -> { st.undoStack.push(st, false); st.insertNewline(); ensureCursorVisible(st); completionList = List.of(); }
            case GLFW.GLFW_KEY_TAB      -> {
                // Insert two spaces — 4 feels too much in MC's narrow font
                st.undoStack.push(st, false);
                st.insertChar(' '); st.insertChar(' ');
            }
            case GLFW.GLFW_KEY_ESCAPE   -> onClose();
            default -> { return super.keyPressed(keyCode, scanCode, modifiers); }
        }
        return true;
    }

    /** Move cursor by delta cols (and skip-fold rows handled separately for vertical). */
    private void moveOrExtend(EditorState st, boolean shift, int dLine, int dCol) {
        if (shift) st.extendCursor(dLine, dCol); else st.moveCursor(dLine, dCol);
    }

    private void setOrExtend(EditorState st, boolean shift, int line, int col) {
        if (shift) st.extendCursorTo(line, col); else st.setCursor(line, col);
    }

    /**
     * Vertical cursor move that skips folded rows.
     * Honors shift for selection extension.
     */
    private void moveVertical(EditorState st, boolean shift, int delta) {
        st.ensureFoldsFresh();
        int target = st.cursorLine;
        int steps = Math.abs(delta);
        int dir = (int) Math.signum(delta);
        while (steps > 0) {
            int next = target + dir;
            if (next < 0 || next >= st.lines.size()) break;
            if (!st.isHidden(next)) steps--;
            target = next;
        }
        setOrExtend(st, shift, target, st.cursorCol);
    }

    private void jumpToCurrentMatch(EditorState st) {
        FindState.Match m = findState.current();
        if (m == null) return;
        st.setCursor(m.line(), m.startCol());
        // Make sure the match is visible: scroll to its display row
        ensureCursorVisible(st);
    }

    private void replaceCurrent() {
        EditorState st = active();
        if (st == null || findState == null) return;
        FindState.Match m = findState.current();
        if (m == null) return;
        st.undoStack.push(st, false);
        String line = st.lines.get(m.line());
        st.lines.set(m.line(), line.substring(0, m.startCol())
                + findState.replacement
                + line.substring(m.endCol()));
        st.dirty = true;
        st.invalidateStatesFrom(m.line());
        st.foldRegionsDirty = true;
        // Move cursor past the replacement and recompute matches
        st.setCursor(m.line(), m.startCol() + findState.replacement.length());
        findState.recomputeMatches(st);
    }

    private void replaceAll() {
        EditorState st = active();
        if (st == null || findState == null || findState.matches.isEmpty()) return;
        st.undoStack.push(st, false);
        // Walk matches in reverse so per-line offsets stay valid as we replace.
        int count = findState.matches.size();
        for (int i = count - 1; i >= 0; i--) {
            FindState.Match m = findState.matches.get(i);
            String line = st.lines.get(m.line());
            st.lines.set(m.line(), line.substring(0, m.startCol())
                    + findState.replacement
                    + line.substring(m.endCol()));
        }
        st.dirty = true;
        st.resetStates();
        st.foldRegionsDirty = true;
        findState.recomputeMatches(st);
        st.appendConsole("§a✓§r replaced " + count + " occurrence(s)");
    }

    private void ensureCursorVisible(EditorState st) {
        int visible = editorH / LINE_H;
        if (st.cursorLine < st.scrollLine) st.scrollLine = st.cursorLine;
        else if (st.cursorLine >= st.scrollLine + visible) st.scrollLine = st.cursorLine - visible + 1;
    }

    private void updateCompletions(EditorState st) {
        // Find word under cursor (the chars to the left)
        String line = st.currentLine();
        int start = st.cursorCol;
        while (start > 0 && Character.isJavaIdentifierPart(line.charAt(start - 1))) start--;
        String prefix = line.substring(start, st.cursorCol);
        if (prefix.length() < 2) {
            completionList = List.of();
            return;
        }
        completionList = completions.suggest(st.type, prefix);
        completionSelected = 0;
    }

    private void acceptCompletion(EditorState st) {
        if (completionList.isEmpty()) return;
        String pick = completionList.get(completionSelected);
        st.undoStack.push(st, false);
        // Replace the prefix with the completion
        String line = st.currentLine();
        int start = st.cursorCol;
        while (start > 0 && Character.isJavaIdentifierPart(line.charAt(start - 1))) start--;
        String updated = line.substring(0, start) + pick + line.substring(st.cursorCol);
        st.lines.set(st.cursorLine, updated);
        st.cursorCol = start + pick.length();
        st.dirty = true;
        st.invalidateStatesFrom(st.cursorLine);
        completionList = List.of();
    }

    // ============================================================
    //  Actions
    // ============================================================

    private EditorState active() {
        return activeTab == null ? null : openTabs.get(activeTab);
    }

    private void openScript(String path) {
        selectedFolder = null;
        if (openTabs.containsKey(path)) {
            activeTab = path;
        } else {
            PacketDistributor.sendToServer(new LoadScriptC2S(path));
        }
    }

    private void saveActive() {
        EditorState st = active();
        if (st == null) return;
        PacketDistributor.sendToServer(new SaveScriptC2S(st.path, st.fullContent()));
        st.dirty = false;
    }

    /**
     * Run the linter against the active buffer and update its issue list. Called
     * from the render loop after the user has been idle for LINT_DEBOUNCE_MS.
     * Cheap enough for the render thread on small/medium scripts (Rhino's parser
     * is fast; LuaJ's compile is on the order of microseconds for a few hundred
     * lines). If we ever see scripts large enough to lag, this would be the
     * place to push to a worker thread.
     */
    private void relintActive(EditorState st) {
        // Scripts get the proper linter (Rhino parser / LuaJ compile).
        // JSON files get no live linter — the JSON-parse validation runs
        // server-side on save and the result comes back via the console.
        // Showing inline squigglies while typing in JSON would mean wiring
        // Gson into the editor render loop and we'd rather not.
        if (st.type == null) {
            st.lintIssues = java.util.List.of();
            st.lintDirty = false;
            return;
        }
        try {
            st.lintIssues = de.grafkakashi.livescript.engine.Linter.lintDetailed(
                    st.type, st.path, st.fullContent());
        } catch (Throwable t) {
            st.lintIssues = java.util.List.of();
        }
        st.lintDirty = false;
    }

    private void runActive() {
        EditorState st = active();
        if (st == null) return;
        // Skip the server round-trip for non-executable file kinds — show
        // the verdict in the editor console immediately. The server would
        // reject these anyway (see ExecuteScriptC2S.handle), but a local
        // hint is friendlier than the network-delayed error.
        if (st.type == null) {
            st.appendConsole("§7Run does nothing for non-script files (.json etc.)");
            return;
        }
        PacketDistributor.sendToServer(new ExecuteScriptC2S(st.path, st.fullContent()));
    }

    /**
     * Re-request the file tree from the server so external file additions
     * (someone dropped a .js in the scripts folder via Explorer/VSCode) show
     * up without closing+reopening the editor. The active tab's in-memory
     * content is NOT replaced — if the user has unsaved edits to an open
     * file, those stay safe even if the on-disk version has changed; they'll
     * notice on next reload via clicking the tree entry.
     */
    private void requestRefresh() {
        PacketDistributor.sendToServer(new OpenEditorC2S());
    }

    private void deleteActive() {
        // Folder selected? Delete the folder (after confirm).
        if (selectedFolder != null) {
            final String folderPath = selectedFolder;
            // Count scripts that will go with it for the confirm dialog
            String prefix = folderPath.endsWith("/") ? folderPath : folderPath + "/";
            long scriptsInside = scriptList.stream().filter(s -> s.startsWith(prefix)).count();
            String msg = scriptsInside == 0
                    ? "Delete empty folder " + folderPath + "?"
                    : "Delete " + folderPath + " and " + scriptsInside + " script(s) inside?";
            confirmDialog = new ConfirmDialog(msg, () -> {
                PacketDistributor.sendToServer(new DeleteFolderC2S(folderPath));
                // Close any open tabs that lived in the deleted folder
                openTabs.keySet().removeIf(p -> p.startsWith(prefix));
                if (activeTab != null && activeTab.startsWith(prefix)) {
                    activeTab = openTabs.isEmpty() ? null : openTabs.keySet().iterator().next();
                }
                selectedFolder = null;
            });
            return;
        }
        // Otherwise delete the active script tab
        EditorState st = active();
        if (st == null) return;
        final String pathToDelete = st.path;
        confirmDialog = new ConfirmDialog("Delete " + pathToDelete + "?", () -> {
            PacketDistributor.sendToServer(new DeleteScriptC2S(pathToDelete));
            openTabs.remove(pathToDelete);
            activeTab = openTabs.isEmpty() ? null : openTabs.keySet().iterator().next();
        });
    }

    /**
     * Rename the active script or selected folder. Prompts for the new name,
     * validates it, then sends the rename to the server.
     */
    private void renameActive() {
        // Folder selected? Rename the folder (same RenameScriptC2S handler works —
        // ScriptStorage.move just calls Files.move which handles directories fine).
        if (selectedFolder != null) {
            final String oldPath = selectedFolder;
            promptDialog = new PromptDialog(
                    "Rename folder",
                    "Current path: " + oldPath,
                    oldPath,
                    name -> validateFolderName(name, oldPath),
                    newName -> {
                        if (newName.equals(oldPath)) return;
                        PacketDistributor.sendToServer(new RenameScriptC2S(oldPath, newName));
                        // Re-key any open tabs that were inside the renamed folder
                        String oldPrefix = oldPath + "/";
                        String newPrefix = newName.endsWith("/") ? newName : newName + "/";
                        Map<String, EditorState> rekeyed = new java.util.LinkedHashMap<>();
                        for (var e : openTabs.entrySet()) {
                            String p = e.getKey();
                            if (p.startsWith(oldPrefix)) {
                                String np = newPrefix + p.substring(oldPrefix.length());
                                e.getValue().path = np;
                                rekeyed.put(np, e.getValue());
                            } else {
                                rekeyed.put(p, e.getValue());
                            }
                        }
                        openTabs.clear();
                        openTabs.putAll(rekeyed);
                        if (activeTab != null && activeTab.startsWith(oldPrefix)) {
                            activeTab = newPrefix + activeTab.substring(oldPrefix.length());
                        }
                        // Also re-key the expanded set
                        if (expandedFolders.remove(oldPath)) expandedFolders.add(newName);
                        selectedFolder = newName;
                    }
            );
            return;
        }
        // Otherwise rename the active script
        EditorState st = active();
        if (st == null) return;
        final String oldPath = st.path;
        promptDialog = new PromptDialog(
                "Rename script",
                "Current name: " + oldPath,
                oldPath,
                name -> validateScriptName(name, oldPath),
                newName -> {
                    if (newName.equals(oldPath)) return;
                    PacketDistributor.sendToServer(new RenameScriptC2S(oldPath, newName));
                    EditorState moved = openTabs.remove(oldPath);
                    if (moved != null) {
                        moved.path = newName;
                        // Cross-extension rename: foo.js → foo.lua changes the
                        // ScriptType; foo.js → foo.json changes it to null
                        // (JSON is editable but not a script). Always update
                        // the field rather than only on non-null, so JS→JSON
                        // doesn't leave a stale type that the renderer would
                        // act on.
                        moved.type = ScriptType.fromExtension(newName);
                        openTabs.put(newName, moved);
                        activeTab = newName;
                    }
                }
        );
    }

    /**
     * Prompt for a new folder path and create it server-side.
     * Default-suggests a name under the currently selected folder if any,
     * else at the root.
     */
    private void createFolder() {
        String parent = selectedFolder != null ? selectedFolder + "/" : "";
        // Suggest a unique default like "newfolder", "newfolder2", ...
        String base = "newfolder";
        int n = 1;
        String suggestion;
        do {
            suggestion = parent + base + (n == 1 ? "" : String.valueOf(n));
            n++;
        } while (folderList.contains(suggestion + "/"));

        promptDialog = new PromptDialog(
                "New folder",
                "Path — nested folders allowed (e.g. tools/utils)",
                suggestion,
                name -> validateFolderName(name, null),
                name -> PacketDistributor.sendToServer(
                        new CreateFolderC2S(name))
        );
    }

    /**
     * Same rules as validateScriptName but for folder paths: no .js/.lua suffix,
     * and conflict check against folderList (not scriptList).
     */
    private String validateFolderName(String name, String currentName) {
        if (name == null || name.isBlank()) return "name cannot be empty";
        String clean = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        if (clean.isEmpty()) return "name cannot be empty";
        if (clean.contains("..")) return "no '..' in path";
        if (clean.startsWith("/") || clean.startsWith("\\")) return "no leading slash";
        for (char c : new char[]{':', '*', '?', '"', '<', '>', '|'}) {
            if (clean.indexOf(c) >= 0) return "invalid character: " + c;
        }
        if (clean.endsWith(".js") || clean.endsWith(".lua")) {
            return "folder names cannot end in .js or .lua";
        }
        if (!clean.equals(currentName) && folderList.contains(clean + "/")) {
            return "a folder named '" + clean + "' already exists";
        }
        return null;
    }

    /**
     * Validate a proposed script filename. Returns null if valid, or an error
     * message describing what's wrong. Used by both New and Rename prompts.
     *
     * Rules:
     *   - Must end in .js or .lua
     *   - Must contain at least one non-extension character
     *   - No path-traversal segments (../, ..\)
     *   - No reserved characters (: * ? " < > |)
     *   - Subdirectories via / are allowed (e.g. "examples/foo.js")
     *   - Trailing/leading slashes rejected
     *   - Optional currentName parameter skips the "already exists" check for that name
     *     (so renaming "foo.js" to "foo.js" doesn't error, even though the file exists)
     */
    private String validateScriptName(String name, String currentName) {
        if (name == null || name.isBlank()) return "name cannot be empty";
        if (name.contains("..")) return "no '..' in path";
        if (name.startsWith("/") || name.startsWith("\\")) return "no leading slash";
        if (name.endsWith("/") || name.endsWith("\\")) return "no trailing slash";
        for (char c : new char[]{':', '*', '?', '"', '<', '>', '|'}) {
            if (name.indexOf(c) >= 0) return "invalid character: " + c;
        }
        // .js / .lua are executable scripts; .json is editable config (e.g.
        // items.json). All three are accepted in the file tree.
        if (!ScriptType.isEditableExtension(name))
            return "must end in .js, .lua, or .json";
        // Reject "just an extension" like ".js" or ".json"
        int dotIdx = name.lastIndexOf('.');
        String stem = name.substring(0, dotIdx);
        if (stem.isEmpty() || stem.endsWith("/") || stem.endsWith("\\")) {
            return "filename cannot be just an extension";
        }
        // Conflict with existing file (other than the one being renamed)
        if (!name.equals(currentName)
                && (scriptList.contains(name) || openTabs.containsKey(name))) {
            return "a script named '" + name + "' already exists";
        }
        return null;
    }

    private void createNew(ScriptType type) {
        // Suggest a unique default name as the prompt's initial value
        String base = "untitled";
        int n = 1;
        String suggestion;
        do { suggestion = base + n + "." + type.extension; n++; }
        while (scriptList.contains(suggestion) || openTabs.containsKey(suggestion));

        final String defaultExt = "." + type.extension;
        promptDialog = new PromptDialog(
                "New " + type.name() + " script",
                "Filename — subdirectories with / are allowed (e.g. tools/foo" + defaultExt + ")",
                suggestion,
                name -> validateScriptName(name, null),
                name -> {
                    String template = type == ScriptType.JS
                            ? "// new JS script\nprint('hello from " + name + "');\n"
                            : "-- new Lua script\nprint('hello from " + name + "')\n";
                    EditorState st = new EditorState(name, type, template);
                    st.dirty = true;
                    openTabs.put(name, st);
                    activeTab = name;
                    PacketDistributor.sendToServer(new SaveScriptC2S(name, template));
                }
        );
    }

    /**
     * Create a new .json config file. Same flow as {@link #createNew(ScriptType)}
     * but with type=null (JSON isn't a ScriptType) and a minimal valid-JSON
     * template ("{}") so the server-side validator doesn't reject the first
     * autosave. The user picks a name; if they want it directly under the
     * livescript root (sibling of items.json) they just type "myconfig.json";
     * if they want it in a subfolder, "subfolder/myconfig.json".
     */
    private void createNewJson() {
        String base = "untitled";
        int n = 1;
        String suggestion;
        do { suggestion = base + n + ".json"; n++; }
        while (scriptList.contains(suggestion) || openTabs.containsKey(suggestion));

        promptDialog = new PromptDialog(
                "New JSON file",
                "Filename — subdirectories with / are allowed (e.g. configs/foo.json)",
                suggestion,
                name -> validateScriptName(name, null),
                name -> {
                    // Empty object: trivially valid JSON; user fills it in
                    String template = "{}\n";
                    EditorState st = new EditorState(name, null, template);
                    st.dirty = true;
                    openTabs.put(name, st);
                    activeTab = name;
                    PacketDistributor.sendToServer(new SaveScriptC2S(name, template));
                }
        );
    }

    @Override
    public boolean isPauseScreen() {
        return false;  // server is real-time; pausing the editor would freeze us out
    }
}
