package de.grafkakashi.livescript.storage;

import de.grafkakashi.livescript.Config;
import de.grafkakashi.livescript.LiveScriptMod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Per-script revision history. Each script "scripts/foo/bar.js" gets a directory
 * "scripts/.history/foo/bar.js/" containing snapshots named by epoch-millis timestamp.
 *
 * Old snapshots beyond {@link Config#MAX_HISTORY_PER_SCRIPT} are pruned on each save.
 */
public class HistoryManager {
    private final Path historyRoot;

    public HistoryManager(Path historyRoot) {
        this.historyRoot = historyRoot;
    }

    public void snapshot(String scriptRelPath, String previousContent) {
        try {
            Path bucket = historyRoot.resolve(scriptRelPath);
            Files.createDirectories(bucket);
            Path snap = bucket.resolve(System.currentTimeMillis() + ".snap");
            Files.write(snap, previousContent.getBytes(StandardCharsets.UTF_8));
            prune(bucket);
        } catch (IOException e) {
            LiveScriptMod.LOGGER.warn("history snapshot failed for {}", scriptRelPath, e);
        }
    }

    public List<HistoryEntry> list(String scriptRelPath) {
        Path bucket = historyRoot.resolve(scriptRelPath);
        List<HistoryEntry> out = new ArrayList<>();
        if (!Files.isDirectory(bucket)) return out;
        try (Stream<Path> walk = Files.list(bucket)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString();
                if (!name.endsWith(".snap")) return;
                try {
                    long ts = Long.parseLong(name.substring(0, name.length() - ".snap".length()));
                    out.add(new HistoryEntry(ts, Files.size(p)));
                } catch (NumberFormatException | IOException ignored) {}
            });
        } catch (IOException ignored) {}
        out.sort(Comparator.comparingLong(HistoryEntry::timestamp).reversed());
        return out;
    }

    public String read(String scriptRelPath, long timestamp) throws IOException {
        Path p = historyRoot.resolve(scriptRelPath).resolve(timestamp + ".snap");
        if (!Files.isRegularFile(p)) throw new IOException("snapshot not found");
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private void prune(Path bucket) {
        int keep = Config.MAX_HISTORY_PER_SCRIPT.get();
        if (keep <= 0) return;
        try (Stream<Path> walk = Files.list(bucket)) {
            List<Path> snaps = new ArrayList<>(walk.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList());
            while (snaps.size() > keep) {
                Files.deleteIfExists(snaps.remove(0));
            }
        } catch (IOException ignored) {}
    }

    /**
     * Migrate snapshots from {@code from} to {@code to} when a script is renamed.
     * No-op if the source bucket doesn't exist. Failures are logged but never
     * propagate — losing history shouldn't block a rename.
     */
    public void rename(String from, String to) {
        Path srcBucket = historyRoot.resolve(from);
        if (!Files.isDirectory(srcBucket)) return;
        Path dstBucket = historyRoot.resolve(to);
        try {
            Files.createDirectories(dstBucket.getParent());
            Files.move(srcBucket, dstBucket);
        } catch (IOException e) {
            LiveScriptMod.LOGGER.warn("history rename failed: {} -> {}", from, to, e);
        }
    }

    public record HistoryEntry(long timestamp, long sizeBytes) {}
}
