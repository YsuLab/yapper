package com.ysulab.yapper.sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONException;

/**
 * Makes a model-supplied note path safe for the vault: relative, no "..", no hidden or app folders,
 * no characters Obsidian or Android storage reject, always ending in ".md". Pure Java.
 */
public final class SafePath {

    static final int MAX_DEPTH = 5;
    static final int MAX_SEGMENT = 100;

    private SafePath() {}

    public static String normalize(String raw) throws JSONException {
        if (raw == null) {
            throw new JSONException("no path");
        }
        String p = raw.trim().replace('\\', '/');
        if (p.startsWith("[[") && p.endsWith("]]")) {
            p = p.substring(2, p.length() - 2); // the model sometimes writes a wikilink
        }
        List<String> parts = new ArrayList<>();
        for (String seg : p.split("/")) {
            String s = seg.trim();
            if (s.isEmpty() || s.equals(".")) {
                continue;
            }
            if (s.equals("..") || s.startsWith(".")) {
                throw new JSONException("unsafe path " + raw);
            }
            s = s.replaceAll("[\\x00-\\x1f:*?\"<>|#^\\[\\]]", " ").replaceAll("\\s+", " ").trim();
            if (s.isEmpty()) {
                continue;
            }
            if (s.length() > MAX_SEGMENT) {
                s = s.substring(0, MAX_SEGMENT).trim();
            }
            parts.add(s);
        }
        if (parts.isEmpty()) {
            throw new JSONException("empty path");
        }
        if (parts.size() > MAX_DEPTH) {
            throw new JSONException("path too deep " + raw);
        }
        String first = parts.get(0).toLowerCase(Locale.ROOT);
        if (first.equals("_yapper")) {
            throw new JSONException("app folder " + raw);
        }
        int last = parts.size() - 1;
        if (!parts.get(last).toLowerCase(Locale.ROOT).endsWith(".md")) {
            parts.set(last, parts.get(last) + ".md");
        } else if (!parts.get(last).endsWith(".md")) {
            String n = parts.get(last);
            parts.set(last, n.substring(0, n.length() - 3) + ".md");
        }
        if (parts.get(last).equals(".md")) {
            throw new JSONException("no file name " + raw);
        }
        return String.join("/", parts);
    }
}
