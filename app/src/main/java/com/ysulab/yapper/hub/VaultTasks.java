package com.ysulab.yapper.hub;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Decision 6: the open "- [ ]" tasks already in the vault are sent to the hub once. The vault is only
 * read, never changed. Each task's id comes from its note and its line, so importing twice adds nothing.
 * Reminder lines (⏰) and Yapper's questions (❓) are not tasks and stay where they are. Pure Java.
 */
public final class VaultTasks {

    static final Pattern OPEN = Pattern.compile("^\\s*[-*] \\[ \\]\\s+(.+)$");
    static final int PER_CAPTURE = 150;

    public static final class Found {
        public final String id;
        public final String text;
        public final String path;

        Found(String id, String text, String path) {
            this.id = id;
            this.text = text;
            this.path = path;
        }
    }

    private VaultTasks() {}

    /** Notes Yapper keeps for itself are skipped. */
    static boolean skipped(String path) {
        return path.startsWith("_yapper/") || path.startsWith(".") || path.contains("/.");
    }

    public static List<Found> find(Map<String, String> notes) {
        List<Found> out = new ArrayList<>();
        for (Map.Entry<String, String> n : new java.util.TreeMap<>(notes).entrySet()) {
            if (skipped(n.getKey()) || n.getValue() == null) {
                continue;
            }
            boolean fence = false;
            for (String line : n.getValue().split("\n")) {
                if (line.trim().startsWith("```")) {
                    fence = !fence;
                    continue;
                }
                Matcher m = OPEN.matcher(line);
                if (fence || !m.matches()) {
                    continue;
                }
                String text = clean(m.group(1));
                if (text.isEmpty() || text.startsWith("⏰") || text.startsWith("❓")) {
                    continue;
                }
                out.add(new Found(id(n.getKey(), line.trim()), text, n.getKey()));
            }
        }
        return out;
    }

    /** "[[Projects/Sadeem|Sadeem]] demo %%yap:x/1%%" → "Sadeem demo". */
    static String clean(String s) {
        return s.replaceAll("%%[^%]*%%", "")
                .replaceAll("\\[\\[([^\\]|]*\\|)?([^\\]]*)\\]\\]", "$2")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static String id(String path, String line) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest((path + "\n" + line).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("v-");
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One or more capture bodies (at most {@link #PER_CAPTURE} tasks each, the hub's limit is 200). */
    public static List<JSONObject> payloads(List<Found> found, String capturedAtIso) throws JSONException {
        List<JSONObject> out = new ArrayList<>();
        for (int start = 0, part = 1; start < found.size(); start += PER_CAPTURE, part++) {
            JSONArray items = new JSONArray();
            StringBuilder list = new StringBuilder("Open tasks imported once from the Obsidian vault.\n");
            for (Found f : found.subList(start, Math.min(found.size(), start + PER_CAPTURE))) {
                items.put(new JSONObject().put("id", f.id).put("kind", "task").put("text", f.text)
                        .put("quote", f.path));
                list.append("- ").append(f.path).append(": ").append(f.text).append('\n');
            }
            out.add(new JSONObject().put("id", "vault-import-" + part).put("capturedAt", capturedAtIso)
                    .put("transcript", list.toString()).put("src", "vault").put("items", items));
        }
        return out;
    }
}
