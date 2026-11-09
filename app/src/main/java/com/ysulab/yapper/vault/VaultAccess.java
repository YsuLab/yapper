package com.ysulab.yapper.vault;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import androidx.documentfile.provider.DocumentFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The Obsidian vault folder, through the Storage Access Framework (persisted tree URI).
 *
 * <p>Write rules: only create new files or append. Never delete or rewrite owner text. Our own
 * attachment copies are the only files we ever replace, and only if a previous copy was partial.
 */
public final class VaultAccess {

    /** Generic type so SAF keeps our exact file name and doesn't add ".txt". */
    private static final String MIME = "application/octet-stream";

    private final Context context;
    private final DocumentFile root;

    private VaultAccess(Context context, DocumentFile root) {
        this.context = context;
        this.root = root;
    }

    /** Null if no folder is chosen or the permission was revoked. */
    public static VaultAccess open(Context context, Uri treeUri) {
        if (treeUri == null) {
            return null;
        }
        boolean granted = false;
        for (android.content.UriPermission p : context.getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(treeUri) && p.isWritePermission()) {
                granted = true;
                break;
            }
        }
        DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
        if (!granted || root == null || !root.canWrite()) {
            return null;
        }
        return new VaultAccess(context.getApplicationContext(), root);
    }

    public String name() {
        String n = root.getName();
        return n == null ? "vault" : n;
    }

    public DocumentFile dir(String... path) throws IOException {
        DocumentFile cur = root;
        for (String part : path) {
            DocumentFile next = cur.findFile(part);
            if (next == null) {
                next = cur.createDirectory(part);
            }
            if (next == null || !next.isDirectory()) {
                throw new IOException("Cannot open folder " + part);
            }
            cur = next;
        }
        return cur;
    }

    /** Copies a local file in. Skips if an identical-size copy already exists (retry after crash). */
    public void copyIn(DocumentFile dir, String name, File source) throws IOException {
        DocumentFile existing = dir.findFile(name);
        if (existing != null) {
            if (existing.length() == source.length()) {
                return;
            }
            existing.delete(); // our own partial attachment from an interrupted attempt
        }
        DocumentFile f = dir.createFile(MIME, name);
        if (f == null) {
            throw new IOException("Cannot create " + name);
        }
        try (InputStream in = new FileInputStream(source);
                OutputStream out = context.getContentResolver().openOutputStream(f.getUri(), "w")) {
            if (out == null) {
                throw new IOException("No output stream for " + name);
            }
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    public DocumentFile root() {
        return root;
    }

    public Context context() {
        return context;
    }

    /** The file or folder at a vault-relative path, or null. Never creates anything. */
    public DocumentFile findPath(String path) {
        DocumentFile cur = root;
        for (String part : path.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            cur = cur.findFile(part);
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    /** Overwrites a file's content. Used for our own files (index, Undo of our own blocks). */
    public void write(DocumentFile f, String text) throws IOException {
        writeNew(f, text);
    }

    /** Creates an empty file with our exact name (no extension added by the provider). */
    public DocumentFile createFile(DocumentFile dir, String name) throws IOException {
        DocumentFile f = dir.createFile(MIME, name);
        if (f == null) {
            throw new IOException("Cannot create " + name);
        }
        return f;
    }

    public DocumentFile find(DocumentFile dir, String name) {
        return dir.findFile(name);
    }

    /** Creates a new note. Fails if the name is taken by a non-empty file we did not write. */
    public DocumentFile createNote(DocumentFile dir, String name, String markdown, String marker)
            throws IOException {
        DocumentFile existing = dir.findFile(name);
        if (existing != null) {
            String current = read(existing);
            if (current.contains(marker)) {
                return existing; // written by an earlier attempt
            }
            if (!current.isEmpty()) {
                // Someone else's note with the same name: append instead of touching their text.
                append(existing, "\n" + markdown);
                return existing;
            }
            writeNew(existing, markdown);
            return existing;
        }
        DocumentFile f = dir.createFile(MIME, name);
        if (f == null) {
            throw new IOException("Cannot create " + name);
        }
        writeNew(f, markdown);
        return f;
    }

    /** Appends text. Re-reads nothing it doesn't need; uses "wa" when the provider supports it. */
    public void append(DocumentFile file, String text) throws IOException {
        ContentResolver cr = context.getContentResolver();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(file.getUri(), "wa")) {
            if (pfd != null) {
                try (FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
                    // Some providers ignore the "a"; seek to the end explicitly.
                    out.getChannel().position(out.getChannel().size());
                    out.write(bytes);
                    out.getFD().sync();
                }
                return;
            }
        } catch (FileNotFoundException | IllegalArgumentException | UnsupportedOperationException e) {
            // Provider has no append mode: fall through.
        }
        // Fallback: re-read right before writing, then write old + new.
        String current = read(file);
        writeNew(file, current + text);
    }

    public String read(DocumentFile file) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(file.getUri())) {
            if (in == null) {
                return "";
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        }
    }

    private void writeNew(DocumentFile f, String text) throws IOException {
        try (OutputStream out = context.getContentResolver().openOutputStream(f.getUri(), "wt")) {
            if (out == null) {
                throw new IOException("No output stream");
            }
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
