package com.ysulab.yapper.hub;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings from application.properties / environment. */
@ConfigurationProperties("hub")
public class HubProperties {

    private String db = "./data/hub.db";
    private String tokens = "";
    private String zone = "Asia/Riyadh";
    private String backupDir = "./data/backups";
    private int backupKeep = 14;
    private int archiveAfterDays = 30;
    private String corsOrigins = "https://appassets.androidplatform.net";

    public String getDb() { return db; }
    public void setDb(String db) { this.db = db; }
    public String getTokens() { return tokens; }
    public void setTokens(String tokens) { this.tokens = tokens; }
    public String getZone() { return zone; }
    public void setZone(String zone) { this.zone = zone; }
    public String getBackupDir() { return backupDir; }
    public void setBackupDir(String backupDir) { this.backupDir = backupDir; }
    public int getBackupKeep() { return backupKeep; }
    public void setBackupKeep(int backupKeep) { this.backupKeep = backupKeep; }
    public int getArchiveAfterDays() { return archiveAfterDays; }
    public void setArchiveAfterDays(int archiveAfterDays) { this.archiveAfterDays = archiveAfterDays; }

    public String getCorsOrigins() { return corsOrigins; }
    public void setCorsOrigins(String corsOrigins) { this.corsOrigins = corsOrigins; }

    public ZoneId zoneId() {
        return ZoneId.of(zone);
    }

    /** {@code phone=abc,tablet=def} → {abc: phone, def: tablet}. Blank or short tokens are ignored. */
    public Map<String, String> deviceByToken() {
        Map<String, String> out = new LinkedHashMap<>();
        if (tokens == null) {
            return out;
        }
        for (String pair : tokens.split(",")) {
            String p = pair.trim();
            int eq = p.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = p.substring(0, eq).trim();
            String token = p.substring(eq + 1).trim();
            if (token.length() >= TokenFilter.MIN_TOKEN_LENGTH) {
                out.put(token, name);
            }
        }
        return out;
    }
}
