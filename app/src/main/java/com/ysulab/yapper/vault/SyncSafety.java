package com.ysulab.yapper.vault;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Keeps vault writes safe while a file-sync tool (Syncthing) also writes to the vault folder.
 * Pure Java.
 *
 * <p>Syncthing replaces a file by writing a temp file and renaming it over the old one. An append
 * that opened the old file just before the rename lands in a file nobody sees any more. So every
 * append is checked by reading the file again, and retried if the block is not there.
 *
 * <p>When the same file changed on two devices before they synced, Syncthing keeps both versions:
 * the losing one becomes {@code Name.sync-conflict-YYYYMMDD-HHMMSS-DEVICEID.md}. Nothing is lost,
 * but a Yapper block can end up only in that copy. Those copies are kept out of the index (so the
 * sorter never files into them) and listed in {@link #REPORT_PATH} for the owner.
 */
public final class SyncSafety {

    public static final String REPORT_PATH = VaultIndex.APP_DIR + "/sync-conflicts.md";
    static final int ATTEMPTS = 3;

    /** The plain append, without the check. */
    public interface RawAppend {
        void append(String path, String text) throws IOException;
    }

    private SyncSafety() {}

    /**
     * Appends {@code text} and confirms it by reading the file back. Our blocks are unique (they carry
     * a {@code %%yap:<id>%%} marker or a timed heading), so "the text is in the file" means "written".
     * If the append vanished (file replaced by sync), it is retried, up to {@link #ATTEMPTS} times in
     * all. A retry first re-reads, so it never adds a second copy. If the text was already in the file
     * before we started, the check can't tell copies apart: append once and trust it.
     */
    public static void appendVerified(VaultFs fs, RawAppend raw, String path, String text) throws IOException {
        String current = fs.read(path);
        if (current == null) {
            throw new IOException("Missing: " + path);
        }
        if (text.isEmpty() || current.contains(text)) {
            raw.append(path, text);
            return;
        }
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            if (attempt > 0) {
                current = fs.read(path); // re-read right before appending again
                if (current == null) {
                    throw new IOException("Missing: " + path);
                }
                if (current.contains(text)) {
                    return; // the earlier attempt did land, just late
                }
            }
            raw.append(path, text);
            String after = fs.read(path);
            if (after != null && after.contains(text)) {
                return;
            }
        }
        throw new IOException("Append not confirmed after " + ATTEMPTS + " tries: " + path);
    }

    /** True for Syncthing conflict copies and Syncthing temp files. */
    public static boolean isSyncArtifact(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.contains(".sync-conflict-") || name.startsWith("~syncthing~") || name.startsWith(".syncthing.");
    }

    /** Markdown for {@link #REPORT_PATH}. Paths are vault-relative. */
    public static String report(List<String> conflicts, String stamp) {
        StringBuilder sb = new StringBuilder("# Sync conflicts\n\n");
        sb.append("Checked ").append(stamp).append(" by Yapper. This file is rewritten on every check.\n\n");
        if (conflicts.isEmpty()) {
            sb.append("No conflict copies. ✅\n");
            return sb.toString();
        }
        sb.append("Syncthing kept two versions of these notes because they changed on two devices at once.\n")
                .append("Open each copy, move anything you want into the main note, then delete the copy.\n\n");
        for (String c : conflicts) {
            String link = c.endsWith(".md") ? c.substring(0, c.length() - 3) : c;
            sb.append("- [[").append(link).append("]] → main note: [[").append(mainNote(link)).append("]]\n");
        }
        return sb.toString();
    }

    /** True if {@code reportText} (an earlier {@link #report}) lists exactly these conflicts. */
    public static boolean sameConflicts(String reportText, List<String> conflicts) {
        String fresh = report(conflicts, "");
        String body = fresh.substring(fresh.indexOf('\n', fresh.indexOf("Checked")));
        int at = reportText.indexOf("Checked");
        return at >= 0 && reportText.substring(reportText.indexOf('\n', at)).equals(body);
    }

    /** {@code Uni/Algo.sync-conflict-20261111-130000-ABCDEFG} → {@code Uni/Algo}. */
    static String mainNote(String path) {
        int at = path.toLowerCase(Locale.ROOT).indexOf(".sync-conflict-");
        if (at < 0) {
            return path;
        }
        int end = path.indexOf('.', at + 1);
        return path.substring(0, at) + (end < 0 ? "" : path.substring(end));
    }
}
