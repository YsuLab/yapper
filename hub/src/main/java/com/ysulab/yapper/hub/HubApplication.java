package com.ysulab.yapper.hub;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The hub: a small mailbox between Yapper (phone) and the Today board (tablet). It holds tasks, raw
 * captures and drawing pages in one SQLite file, and serves the board's static files on the same origin.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(HubProperties.class)
public class HubApplication {

    public static void main(String[] args) throws IOException {
        // SQLite creates the file but not its folder.
        String db = System.getenv().getOrDefault("HUB_DB", "./data/hub.db");
        Path parent = Path.of(db).toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
            String result = Restore.applyIfAsked(Path.of(db),
                    Path.of(System.getenv().getOrDefault("HUB_BACKUP_DIR", "./data/backups")));
            if (result != null) {
                System.out.println("[hub] " + result);
            }
        }
        SpringApplication.run(HubApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
