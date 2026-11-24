package com.ysulab.yapper.hub;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every night (03:30 Riyadh): a consistent copy of the database with SQLite's online backup, keeping
 * the newest {@code backupKeep} copies, then archive tasks done long ago. The hub holds the only copy of
 * the tasks, so the backup is not optional.
 */
@Component
public class Nightly {

    private static final Logger log = LoggerFactory.getLogger(Nightly.class);
    static final String PREFIX = "hub-";
    static final String SUFFIX = ".db";

    private final DataSource ds;
    private final HubStore store;
    private final HubProperties props;

    public Nightly(DataSource ds, HubStore store, HubProperties props) {
        this.ds = ds;
        this.store = store;
        this.props = props;
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "${hub.zone:Asia/Riyadh}")
    public void run() {
        try {
            Path p = backup();
            log.info("Backup written: {}", p);
        } catch (Exception e) {
            log.error("Backup FAILED", e);
        }
        int n = store.archiveDoneBefore(store.today().minusDays(props.getArchiveAfterDays()));
        if (n > 0) {
            log.info("Archived {} task(s) done more than {} days ago", n, props.getArchiveAfterDays());
        }
    }

    /** Writes {@code hub-YYYY-MM-DD.db} into the backup folder and prunes old copies. */
    public Path backup() throws IOException, SQLException {
        Path dir = Path.of(props.getBackupDir()).toAbsolutePath();
        Files.createDirectories(dir);
        Path out = dir.resolve(PREFIX + store.today() + SUFFIX);
        Path tmp = dir.resolve(out.getFileName() + ".part");
        Files.deleteIfExists(tmp);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            // sqlite-jdbc's online backup: same result as the sqlite3 CLI's ".backup".
            st.executeUpdate("backup to '" + tmp.toString().replace("'", "''") + "'");
        }
        Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        prune(dir, props.getBackupKeep());
        return out;
    }

    static List<Path> prune(Path dir, int keep) throws IOException {
        List<Path> all = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, PREFIX + "*" + SUFFIX)) {
            ds.forEach(all::add);
        }
        all.sort(null); // names sort by date
        List<Path> removed = new ArrayList<>();
        for (int i = 0; i < all.size() - Math.max(1, keep); i++) {
            Files.delete(all.get(i));
            removed.add(all.get(i));
        }
        return removed;
    }
}
