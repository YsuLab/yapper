package com.ysulab.yapper.vault;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Name → note lookup for the second brain: every note's file name, title and aliases, read from
 * {@code _yapper/index.md}. Lets the executor reuse "Topics/Google Cloud.md" when the sorter says
 * "GCP", instead of creating a duplicate note. Matching ignores case and Arabic/Latin spacing noise.
 * Pure Java.
 */
public final class EntityIndex {

    private final Map<String, String> byKey = new HashMap<>();

    /** Parses index lines like {@code - Topics/Google Cloud.md · title: GCP · aliases: Google Cloud, جي سي بي · ## X}. */
    public static EntityIndex fromIndex(String indexText) {
        EntityIndex e = new EntityIndex();
        if (indexText == null) {
            return e;
        }
        for (String line : indexText.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("- ") || !t.contains(".md")) {
                continue;
            }
            String[] parts = t.substring(2).split(" · ");
            String path = parts[0].trim();
            if (!path.endsWith(".md")) {
                continue;
            }
            e.put(baseName(path), path);
            for (int i = 1; i < parts.length; i++) {
                String p = parts[i].trim();
                if (p.startsWith("title: ")) {
                    e.put(p.substring(7), path);
                } else if (p.startsWith("aliases: ")) {
                    for (String a : p.substring(9).split(",")) {
                        e.put(a, path);
                    }
                }
            }
        }
        return e;
    }

    /** Registers a note (e.g. one created a moment ago in this run). File names win over aliases. */
    public void add(String path, String name, Iterable<String> aliases) {
        byKey.put(key(baseName(path)), path);
        put(name, path);
        if (aliases != null) {
            for (String a : aliases) {
                put(a, path);
            }
        }
    }

    /** The note for a name or alias, or null. */
    public String find(String name) {
        String k = key(name);
        return k.isEmpty() ? null : byKey.get(k);
    }

    /**
     * A wikilink for a name: {@code [[Google Cloud|GCP]]} when "GCP" is an alias of an existing note,
     * {@code [[Tabby]]} when it is the note's name or nothing matches (Obsidian then shows an
     * unresolved link the owner can click to create).
     */
    public String link(String name) {
        String clean = clean(name);
        String path = find(clean);
        if (path == null) {
            return "[[" + clean + "]]";
        }
        String base = baseName(path);
        return base.equalsIgnoreCase(clean) ? "[[" + base + "]]" : "[[" + base + "|" + clean + "]]";
    }

    /** Every name and alias that points to {@code path} (lower-cased keys), for search expansion. */
    public java.util.List<String> aliasesOf(String path) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Map.Entry<String, String> e : byKey.entrySet()) {
            if (e.getValue().equals(path)) {
                out.add(e.getKey());
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    public int size() {
        return byKey.size();
    }

    private void put(String name, String path) {
        String k = key(name);
        if (!k.isEmpty() && !byKey.containsKey(k)) {
            byKey.put(k, path);
        }
    }

    /** File name without folder and ".md". */
    public static String baseName(String path) {
        String n = path.substring(path.lastIndexOf('/') + 1);
        return n.endsWith(".md") ? n.substring(0, n.length() - 3) : n;
    }

    /** A name as it may appear inside [[ ]]: no brackets, pipes, hashes or line breaks. */
    public static String clean(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("[\\[\\]|#^\\n\\r]", " ").replaceAll("\\s+", " ").trim();
    }

    static String key(String name) {
        return clean(name).toLowerCase(Locale.ROOT)
                .replaceAll("[\\u064B-\\u0652\\u0640]", "") // Arabic diacritics and tatweel
                .replaceAll("[\\s_\\-.]+", " ")
                .trim();
    }
}
