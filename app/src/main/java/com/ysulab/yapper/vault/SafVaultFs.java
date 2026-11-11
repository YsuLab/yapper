package com.ysulab.yapper.vault;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.documentfile.provider.DocumentFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/** {@link VaultFs} over the Storage Access Framework, plus the index walk. */
public final class SafVaultFs implements VaultFs {

    static final int MAX_NOTES = 3000;
    static final int MAX_READ = 16 * 1024;

    private final VaultAccess vault;

    public SafVaultFs(VaultAccess vault) {
        this.vault = vault;
    }

    @Override
    public String read(String path) throws IOException {
        DocumentFile f = vault.findPath(path);
        if (f == null || f.isDirectory()) {
            return null;
        }
        return vault.read(f);
    }

    @Override
    public boolean exists(String path) {
        return vault.findPath(path) != null;
    }

    @Override
    public void create(String path, String text) throws IOException {
        DocumentFile dir = parentDir(path, true);
        String name = name(path);
        if (dir.findFile(name) != null) {
            throw new IOException("Already exists: " + path);
        }
        vault.write(vault.createFile(dir, name), text);
    }

    @Override
    public void append(String path, String text) throws IOException {
        DocumentFile f = vault.findPath(path);
        if (f == null) {
            throw new IOException("Missing: " + path);
        }
        vault.append(f, text);
    }

    @Override
    public void replace(String path, String text) throws IOException {
        DocumentFile f = vault.findPath(path);
        if (f == null) {
            throw new IOException("Missing: " + path);
        }
        vault.write(f, text);
    }

    @Override
    public void delete(String path) throws IOException {
        DocumentFile f = vault.findPath(path);
        if (f != null && !f.delete()) {
            throw new IOException("Cannot delete " + path);
        }
    }

    @Override
    public void move(String from, String to) throws IOException {
        DocumentFile src = vault.findPath(from);
        if (src == null) {
            return;
        }
        DocumentFile target = parentDir(to, true);
        if (target.findFile(name(to)) != null) {
            throw new IOException("Already exists: " + to);
        }
        DocumentFile srcParent = parentDir(from, false);
        ContentResolver cr = vault.context().getContentResolver();
        if (name(from).equals(name(to)) && srcParent != null) {
            try {
                Uri moved = DocumentsContract.moveDocument(cr, src.getUri(), srcParent.getUri(), target.getUri());
                if (moved != null) {
                    return;
                }
            } catch (Exception e) {
                // Provider can't move: copy, then delete.
            }
        }
        DocumentFile copy = vault.createFile(target, name(to));
        try (InputStream in = cr.openInputStream(src.getUri());
                OutputStream out = cr.openOutputStream(copy.getUri(), "w")) {
            if (in == null || out == null) {
                throw new IOException("Cannot copy " + from);
            }
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        if (copy.length() != src.length()) {
            copy.delete();
            throw new IOException("Copy incomplete: " + from);
        }
        src.delete();
    }

    private DocumentFile parentDir(String path, boolean create) throws IOException {
        int slash = path.lastIndexOf('/');
        if (slash < 0) {
            return vault.root();
        }
        String dir = path.substring(0, slash);
        return create ? vault.dir(dir.split("/")) : vault.findPath(dir);
    }

    private static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * Walks the vault and rebuilds {@code _yapper/index.md}. Only notes whose size or modified time
     * changed are read again (cache in app storage), so a big vault stays fast.
     */
    public String buildIndex(File cacheFile) throws IOException {
        JSONObject cache;
        try {
            cache = cacheFile.isFile()
                    ? new JSONObject(new String(Files.readAllBytes(cacheFile.toPath()), StandardCharsets.UTF_8))
                    : new JSONObject();
        } catch (Exception e) {
            cache = new JSONObject();
        }
        JSONObject next = new JSONObject();
        List<String> folders = new ArrayList<>();
        List<String> entries = new ArrayList<>();
        Uri tree = vault.root().getUri();
        String rootId = DocumentsContract.getDocumentId(tree);
        walk(tree, rootId, "", folders, entries, cache, next);
        try {
            File tmp = new File(cacheFile.getPath() + ".tmp");
            Files.write(tmp.toPath(), next.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(cacheFile)) {
                tmp.delete();
            }
        } catch (IOException ignored) {
            // cache is only an optimization
        }
        String text = VaultIndex.render(folders, entries);
        DocumentFile f = vault.findPath(VaultIndex.INDEX_PATH);
        if (f == null) {
            create(VaultIndex.INDEX_PATH, text);
        } else if (!text.equals(vault.read(f))) {
            vault.write(f, text);
        }
        return text;
    }

    private void walk(Uri tree, String docId, String prefix, List<String> folders, List<String> entries,
            JSONObject cache, JSONObject next) {
        ContentResolver cr = vault.context().getContentResolver();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] cols = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE
        };
        Map<String, String> dirs = new HashMap<>();
        try (Cursor c = cr.query(children, cols, null, null, null)) {
            if (c == null) {
                return;
            }
            while (c.moveToNext() && entries.size() < MAX_NOTES) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                long modified = c.isNull(3) ? 0 : c.getLong(3);
                long size = c.isNull(4) ? 0 : c.getLong(4);
                String path = prefix + name;
                if (name == null || VaultIndex.skip(path)) {
                    continue;
                }
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    dirs.put(path, id);
                    continue;
                }
                if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".md")) {
                    continue;
                }
                String stamp = modified + ":" + size;
                JSONObject cached = cache.optJSONObject(path);
                String entry;
                if (cached != null && stamp.equals(cached.optString("s"))) {
                    entry = cached.optString("e");
                } else {
                    entry = VaultIndex.entry(path, readHead(cr, DocumentsContract.buildDocumentUriUsingTree(tree, id)));
                }
                entries.add(entry);
                try {
                    next.put(path, new JSONObject().put("s", stamp).put("e", entry));
                } catch (Exception ignored) {
                    // JSONException: cannot happen for strings
                }
            }
        } catch (RuntimeException e) {
            return; // provider hiccup: index what we have
        }
        for (Map.Entry<String, String> d : dirs.entrySet()) {
            folders.add(d.getKey());
            walk(tree, d.getValue(), d.getKey() + "/", folders, entries, cache, next);
        }
    }

    /** Notes larger than this are cut for search (a 60-minute ramble is about 50 KB). */
    static final int MAX_SEARCH_READ = 96 * 1024;

    /** Folders "ask your vault" never searches: hidden ones and Yapper's own files. */
    public static boolean skipForSearch(String path) {
        String p = path.toLowerCase(java.util.Locale.ROOT);
        for (String seg : p.split("/")) {
            if (seg.startsWith(".")) {
                return true;
            }
        }
        return p.equals(VaultIndex.APP_DIR) || p.startsWith(VaultIndex.APP_DIR + "/");
    }

    /**
     * Every markdown note's text (path → text) for "ask your vault". Unchanged notes come from a
     * cache in app storage (keyed by modified time + size), so only new or edited notes are read.
     */
    public Map<String, String> allNotes(File cacheFile) {
        JSONObject cache;
        try {
            cache = cacheFile.isFile()
                    ? new JSONObject(new String(Files.readAllBytes(cacheFile.toPath()), StandardCharsets.UTF_8))
                    : new JSONObject();
        } catch (Exception e) {
            cache = new JSONObject();
        }
        JSONObject next = new JSONObject();
        Map<String, String> out = new java.util.TreeMap<>();
        Uri tree = vault.root().getUri();
        walkText(tree, DocumentsContract.getDocumentId(tree), "", out, cache, next);
        try {
            File tmp = new File(cacheFile.getPath() + ".tmp");
            Files.write(tmp.toPath(), next.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(cacheFile)) {
                tmp.delete();
            }
        } catch (IOException ignored) {
            // cache is only an optimization
        }
        return out;
    }

    private void walkText(Uri tree, String docId, String prefix, Map<String, String> out, JSONObject cache,
            JSONObject next) {
        ContentResolver cr = vault.context().getContentResolver();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] cols = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE
        };
        Map<String, String> dirs = new HashMap<>();
        try (Cursor c = cr.query(children, cols, null, null, null)) {
            if (c == null) {
                return;
            }
            while (c.moveToNext() && out.size() < MAX_NOTES) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                long modified = c.isNull(3) ? 0 : c.getLong(3);
                long size = c.isNull(4) ? 0 : c.getLong(4);
                String path = prefix + name;
                if (name == null || skipForSearch(path)) {
                    continue;
                }
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    dirs.put(path, id);
                    continue;
                }
                if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".md")) {
                    continue;
                }
                String stamp = modified + ":" + size;
                JSONObject cached = cache.optJSONObject(path);
                String text;
                if (cached != null && stamp.equals(cached.optString("s"))) {
                    text = cached.optString("t");
                } else {
                    text = readUpTo(cr, DocumentsContract.buildDocumentUriUsingTree(tree, id), MAX_SEARCH_READ);
                }
                out.put(path, text);
                try {
                    next.put(path, new JSONObject().put("s", stamp).put("t", text));
                } catch (Exception ignored) {
                    // JSONException: cannot happen for strings
                }
            }
        } catch (RuntimeException e) {
            return; // provider hiccup: search what we have
        }
        for (Map.Entry<String, String> d : dirs.entrySet()) {
            walkText(tree, d.getValue(), d.getKey() + "/", out, cache, next);
        }
    }

    private static String readHead(ContentResolver cr, Uri uri) {
        return readUpTo(cr, uri, MAX_READ);
    }

    private static String readUpTo(ContentResolver cr, Uri uri, int max) {
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) {
                return "";
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (bos.size() < max && (n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
