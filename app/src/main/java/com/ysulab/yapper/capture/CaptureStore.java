package com.ysulab.yapper.capture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Capture bundles on the phone's private storage: {@code <root>/<id>/}.
 *
 * <ul>
 *   <li>{@code meta.properties} — {@link CaptureMeta}
 *   <li>{@code audio.ogg} (or {@code audio.m4a}) — the recording, source of truth
 *   <li>{@code screenshot.jpg} — only if it was not blank
 *   <li>{@code screen.txt} — on-screen text
 * </ul>
 *
 * Every write goes to a temp file, is fsynced, then renamed, so a crash never leaves a half file.
 * Bundles are never deleted in M1. Pure Java (java.io), so it is unit-testable.
 */
public final class CaptureStore {

    public static final String META = "meta.properties";
    public static final String SCREENSHOT = "screenshot.jpg";
    public static final String SCREEN_TEXT = "screen.txt";

    private static final Object LOCK = new Object();

    private final File root;

    public CaptureStore(File root) {
        this.root = root;
    }

    public File dir(String id) {
        return new File(root, id);
    }

    public File file(String id, String name) {
        return new File(dir(id), name);
    }

    /** Creates the bundle folder and its first meta file (state RECORDING). */
    public CaptureMeta create(String id, long nowMs, String zoneId) throws IOException {
        File d = dir(id);
        if (!d.isDirectory() && !d.mkdirs()) {
            throw new IOException("Cannot create " + d);
        }
        CaptureMeta m = new CaptureMeta();
        m.id = id;
        m.createdAtMs = nowMs;
        m.zoneId = zoneId;
        save(m);
        return m;
    }

    public void save(CaptureMeta meta) throws IOException {
        synchronized (LOCK) {
            writeAtomic(file(meta.id, META), meta.serialize().getBytes(StandardCharsets.UTF_8));
        }
    }

    public CaptureMeta load(String id) throws IOException {
        synchronized (LOCK) {
            File f = file(id, META);
            return CaptureMeta.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        }
    }

    /**
     * Load, change and save under one lock, so two workers can't overwrite each other's progress.
     */
    public CaptureMeta update(String id, Mutator mutator) throws IOException {
        synchronized (LOCK) {
            CaptureMeta m = load(id);
            mutator.apply(m);
            save(m);
            return m;
        }
    }

    public interface Mutator {
        void apply(CaptureMeta meta);
    }

    public void writeText(String id, String name, String text) throws IOException {
        writeAtomic(file(id, name), text.getBytes(StandardCharsets.UTF_8));
    }

    public String readText(String id, String name) throws IOException {
        File f = file(id, name);
        if (!f.isFile()) {
            return "";
        }
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    public void writeBytes(String id, String name, byte[] bytes) throws IOException {
        writeAtomic(file(id, name), bytes);
    }

    /** Every bundle id that has a meta file, oldest first (ids are time-sortable). */
    public List<String> ids() {
        String[] names = root.list();
        List<String> out = new ArrayList<>();
        if (names == null) {
            return out;
        }
        Arrays.sort(names);
        for (String n : names) {
            if (new File(new File(root, n), META).isFile()) {
                out.add(n);
            }
        }
        return out;
    }

    static void writeAtomic(File target, byte[] bytes) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.getFD().sync();
        }
        Files.move(tmp.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
