package com.ysulab.yapper.hub;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Restore without shell scripts (works the same under Dokploy): put a backup's file name into
 * {@code RESTORE} next to the database, then restart the hub. At start, before the database is opened,
 * the hub checks the backup, keeps the current database as {@code hub.db.before-restore-<time>}, copies
 * the backup in and removes {@code RESTORE}. A bad request is renamed to {@code RESTORE.failed}.
 */
final class Restore {

    static final String MARKER = "RESTORE";

    private Restore() {}

    /** Returns what happened, or null when no restore was asked for. */
    static String applyIfAsked(Path db, Path backups) throws IOException {
        Path marker = db.toAbsolutePath().getParent().resolve(MARKER);
        if (!Files.isRegularFile(marker)) {
            return null;
        }
        String name = Path.of(Files.readString(marker, StandardCharsets.UTF_8).trim()).getFileName().toString();
        Path backup = backups.resolve(name);
        String why = check(backup);
        if (why != null) {
            Files.move(marker, marker.resolveSibling(MARKER + ".failed"), StandardCopyOption.REPLACE_EXISTING);
            return "Restore NOT done: " + why;
        }
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        for (String suffix : new String[] {"", "-wal", "-shm"}) {
            Path f = db.resolveSibling(db.getFileName() + suffix);
            if (Files.exists(f)) {
                Files.move(f, f.resolveSibling(f.getFileName() + ".before-restore-" + stamp));
            }
        }
        Files.copy(backup, db);
        Files.delete(marker);
        return "Restored the database from " + name;
    }

    /** Null when the file is a sound SQLite database with the task table. */
    static String check(Path backup) {
        if (!Files.isRegularFile(backup)) {
            return "no backup named " + backup.getFileName();
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + backup);
                ResultSet ok = c.createStatement().executeQuery("PRAGMA integrity_check");
                ResultSet n = c.createStatement().executeQuery("SELECT count(*) FROM task")) {
            return ok.next() && "ok".equals(ok.getString(1)) && n.next() ? null : "integrity check failed";
        } catch (Exception e) {
            return "not a hub database (" + e.getMessage() + ")";
        }
    }
}
