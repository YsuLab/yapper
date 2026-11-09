package com.ysulab.yapper.vault;

import java.io.IOException;

/**
 * The vault as paths relative to its root ("Uni/Algorithms.md"). The SAF implementation is
 * {@link SafVaultFs}; tests use an in-memory map. Folders are created as needed.
 */
public interface VaultFs {

    /** File content, or null if the file does not exist. */
    String read(String path) throws IOException;

    default boolean exists(String path) throws IOException {
        return read(path) != null;
    }

    /** Creates a new file. Throws if it already exists. */
    void create(String path, String text) throws IOException;

    void append(String path, String text) throws IOException;

    /** Undo only: write a file we changed back without our block. */
    void replace(String path, String text) throws IOException;

    /** Undo only: delete a file we created and nobody changed since. */
    void delete(String path) throws IOException;

    /** Moves a file (our own capture notes and attachments only). No-op if {@code from} is missing. */
    void move(String from, String to) throws IOException;
}
