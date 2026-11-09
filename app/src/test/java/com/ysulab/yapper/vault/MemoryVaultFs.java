package com.ysulab.yapper.vault;

import java.io.IOException;
import java.util.TreeMap;

/** In-memory vault for tests. */
public final class MemoryVaultFs implements VaultFs {

    public final TreeMap<String, String> files = new TreeMap<>();

    @Override
    public String read(String path) {
        return files.get(path);
    }

    @Override
    public void create(String path, String text) throws IOException {
        if (files.containsKey(path)) {
            throw new IOException("exists " + path);
        }
        files.put(path, text);
    }

    @Override
    public void append(String path, String text) throws IOException {
        if (!files.containsKey(path)) {
            throw new IOException("missing " + path);
        }
        files.put(path, files.get(path) + text);
    }

    @Override
    public void replace(String path, String text) {
        files.put(path, text);
    }

    @Override
    public void delete(String path) {
        files.remove(path);
    }

    @Override
    public void move(String from, String to) throws IOException {
        String v = files.remove(from);
        if (v != null) {
            if (files.containsKey(to)) {
                throw new IOException("exists " + to);
            }
            files.put(to, v);
        }
    }
}
