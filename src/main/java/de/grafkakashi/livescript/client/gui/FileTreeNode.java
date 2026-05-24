package de.grafkakashi.livescript.client.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One node in the file-tree displayed in the left panel. Built from the two
 * flat lists the server sends (scripts + folders) by splitting on "/" and
 * merging into a directory hierarchy.
 *
 * Folders sort before files, both alphabetically within their group, matching
 * what every other code editor does.
 */
public final class FileTreeNode {
    /** Full path from root (e.g. "tools/utils/foo.js"). Empty string for root. */
    public final String fullPath;
    /** Just the last segment ("foo.js" or "utils"). */
    public final String displayName;
    public final boolean isFolder;
    public final List<FileTreeNode> children;

    private FileTreeNode(String fullPath, String displayName, boolean isFolder) {
        this.fullPath = fullPath;
        this.displayName = displayName;
        this.isFolder = isFolder;
        this.children = isFolder ? new ArrayList<>() : List.of();
    }

    /**
     * Build a tree from the server's two lists.
     * @param scripts Flat list of script paths like "hello.js", "tools/foo.lua"
     * @param folders Flat list of folder paths like "tools/" (always trailing slash)
     */
    public static FileTreeNode build(List<String> scripts, List<String> folders) {
        FileTreeNode root = new FileTreeNode("", "", true);
        Map<String, FileTreeNode> byPath = new HashMap<>();
        byPath.put("", root);

        // Folders first so a script in tools/foo.js can find its parent "tools"
        // even if no other script lives in that folder (the listFolders endpoint
        // catches empty directories the script walk wouldn't see).
        for (String fpath : folders) {
            String clean = fpath.endsWith("/") ? fpath.substring(0, fpath.length() - 1) : fpath;
            ensureFolder(clean, byPath);
        }
        for (String script : scripts) {
            insertScript(script, byPath);
        }
        sortRecursive(root);
        return root;
    }

    /**
     * Walk up the segments of {@code folderPath}, creating any folder node
     * that's missing along the way. Idempotent.
     */
    private static FileTreeNode ensureFolder(String folderPath, Map<String, FileTreeNode> byPath) {
        if (byPath.containsKey(folderPath)) return byPath.get(folderPath);
        int slash = folderPath.lastIndexOf('/');
        String parentPath = slash < 0 ? "" : folderPath.substring(0, slash);
        String name = slash < 0 ? folderPath : folderPath.substring(slash + 1);
        FileTreeNode parent = ensureFolder(parentPath, byPath);
        FileTreeNode node = new FileTreeNode(folderPath, name, true);
        parent.children.add(node);
        byPath.put(folderPath, node);
        return node;
    }

    private static void insertScript(String scriptPath, Map<String, FileTreeNode> byPath) {
        int slash = scriptPath.lastIndexOf('/');
        String parentPath = slash < 0 ? "" : scriptPath.substring(0, slash);
        String name = slash < 0 ? scriptPath : scriptPath.substring(slash + 1);
        FileTreeNode parent = ensureFolder(parentPath, byPath);
        parent.children.add(new FileTreeNode(scriptPath, name, false));
    }

    /**
     * Sort children so folders come first, then files. Within each group,
     * alphabetical. Applied recursively to every folder in the tree.
     */
    private static void sortRecursive(FileTreeNode node) {
        if (!node.isFolder) return;
        node.children.sort(Comparator
                .comparing((FileTreeNode n) -> !n.isFolder)  // folder=false sorts before file=true
                .thenComparing(n -> n.displayName.toLowerCase(java.util.Locale.ROOT)));
        for (FileTreeNode c : node.children) sortRecursive(c);
    }

    // ============================================================
    //  Flattening — turn the tree into a render-order list of visible rows
    // ============================================================

    /** One visible row in the rendered tree. */
    public record Row(FileTreeNode node, int depth) {}

    /**
     * Walk the tree and produce the flat list of rows that should be drawn,
     * respecting which folders are expanded. The root itself is not included.
     */
    public List<Row> flatten(Set<String> expandedFolders) {
        List<Row> out = new ArrayList<>();
        for (FileTreeNode c : children) {
            flattenInto(c, 0, expandedFolders, out);
        }
        return out;
    }

    private static void flattenInto(FileTreeNode node, int depth,
                                    Set<String> expanded, List<Row> out) {
        out.add(new Row(node, depth));
        if (node.isFolder && expanded.contains(node.fullPath)) {
            for (FileTreeNode c : node.children) {
                flattenInto(c, depth + 1, expanded, out);
            }
        }
    }
}
