package com.ysulab.yapper.sort;

import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Split, then sort. Pass 1 lists every item in a ramble with his exact words ({@link #request},
 * {@link #parse}). Pass 2 is the normal sorter, told to file each numbered item. {@link #complete} then
 * makes sure nothing is lost: an item no call filed becomes a task (if pass 1 called it a task) or an
 * Unsorted item, and a sentence no item covers becomes an Unsorted item. Pure Java.
 */
public final class Splitter {

    public static final String TOOL = "list_items";
    /** Below this many words a capture is one item; a second model call would only add delay. */
    static final int MIN_WORDS = 12;

    /** One item from pass 1. {@code n} is 1-based. */
    public static final class Item {
        public int n;
        public String kind = "";
        public String quote = "";
        public String summary = "";
    }

    public static final class Split {
        public final List<Item> items = new ArrayList<>();
        public final List<String> noAction = new ArrayList<>();
        public long latencyMs;
    }

    private Splitter() {}

    /** Split only spoken captures with some length; shares and weekly reviews stay one-pass. */
    public static boolean worthSplitting(SorterInput in) {
        if ("share".equals(in.mode) || "review".equals(in.mode)) {
            return false;
        }
        return Coverage.words(in.transcript).size() >= MIN_WORDS;
    }

    public static JSONObject request(SorterConfig cfg, String template, SorterInput in) throws JSONException {
        String system = template
                .replace("{{NOW}}", in.now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .replace("{{ZONE}}", in.now.getZone().getId())
                .replace("{{WEEKDAY}}", in.now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
        String user = "## Transcript\n" + SorterPrompt.cap(in.transcript.trim(), SorterPrompt.MAX_TRANSCRIPT);
        JSONObject item = new JSONObject().put("type", "object")
                .put("properties", new JSONObject()
                        .put("quote", new JSONObject().put("type", "string")
                                .put("description", "His exact words for this item, copied from the transcript"))
                        .put("kind", new JSONObject().put("type", "string").put("enum", new JSONArray()
                                .put("task").put("reminder").put("note").put("plan").put("done").put("question")
                                .put("other")))
                        .put("summary", new JSONObject().put("type", "string")
                                .put("description", "One short line in his language")))
                .put("required", new JSONArray().put("quote").put("kind").put("summary"));
        JSONObject tool = new JSONObject().put("type", "function").put("function", new JSONObject()
                .put("name", TOOL)
                .put("description", "Every separate item in the capture, in the order he said them.")
                .put("parameters", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject()
                                .put("items", new JSONObject().put("type", "array").put("items", item))
                                .put("no_action", new JSONObject().put("type", "array")
                                        .put("description", "Exact words that need nothing (filler, false starts)")
                                        .put("items", new JSONObject().put("type", "string"))))
                        .put("required", new JSONArray().put("items"))));
        return new JSONObject()
                .put("model", cfg.modelId())
                .put("messages", new JSONArray()
                        .put(new JSONObject().put("role", "system").put("content", system))
                        .put(new JSONObject().put("role", "user").put("content", user)))
                .put("tools", new JSONArray().put(tool))
                .put("tool_choice", "auto")
                .put("temperature", 0)
                .put("max_tokens", 8000);
    }

    /** Reads the list_items call. Returns null when the model did not call it (caller falls back to one pass). */
    public static Split parse(String body) throws SortException {
        try {
            JSONObject root = new JSONObject(body);
            if (root.has("error")) {
                JSONObject err = root.optJSONObject("error");
                int code = err == null ? 0 : err.optInt("code", 0);
                throw new SortException("Provider error (split): " + JsonHttp.snippet(
                        err == null ? root.optString("error") : err.optString("message")),
                        code == 0 || SortException.isRetryableStatus(code), null);
            }
            JSONObject msg = root.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            JSONArray calls = msg.optJSONArray("tool_calls");
            if (calls == null) {
                return null;
            }
            for (int i = 0; i < calls.length(); i++) {
                JSONObject fn = calls.getJSONObject(i).optJSONObject("function");
                if (fn == null || !TOOL.equals(fn.optString("name"))) {
                    continue;
                }
                Object args = fn.opt("arguments");
                JSONObject a = args instanceof JSONObject ? (JSONObject) args
                        : new JSONObject(fn.optString("arguments", "{}"));
                Split s = new Split();
                JSONArray items = a.optJSONArray("items");
                for (int k = 0; items != null && k < items.length(); k++) {
                    JSONObject o = items.optJSONObject(k);
                    if (o == null) {
                        continue;
                    }
                    Item it = new Item();
                    it.quote = o.optString("quote", "").trim();
                    it.summary = SortAction.oneLine(o.optString("summary", ""));
                    it.kind = o.optString("kind", "other").trim().toLowerCase(Locale.ROOT);
                    if (it.quote.isEmpty() && it.summary.isEmpty()) {
                        continue;
                    }
                    it.n = s.items.size() + 1;
                    s.items.add(it);
                }
                JSONArray na = a.optJSONArray("no_action");
                for (int k = 0; na != null && k < na.length(); k++) {
                    String q = na.optString(k, "").trim();
                    if (!q.isEmpty()) {
                        s.noAction.add(q);
                    }
                }
                return s;
            }
            return null;
        } catch (JSONException e) {
            throw new SortException("Unexpected split response: " + JsonHttp.snippet(body), false, e);
        }
    }

    /** Pass 2 user-message section listing the items to file. */
    static String itemsSection(List<Item> items) {
        StringBuilder sb = new StringBuilder("## Items in this capture (pass 1 found them; file every one)\n");
        sb.append("Set item to the item's number on every tool call. An item can need several calls ")
                .append("(e.g. add_task and link_entity). Do not skip an item: if you are unsure, use ")
                .append("add_task with unsure: true.\n");
        for (Item it : items) {
            sb.append(it.n).append(". [").append(it.kind).append("] ").append(it.summary)
                    .append(" — “").append(it.quote.replace('\n', ' ')).append("”\n");
        }
        return sb.append('\n').toString();
    }

    /**
     * Adds what pass 2 missed: an unfiled task item becomes add_task, any other unfiled item becomes an
     * Unsorted item, and every sentence no item covers becomes an Unsorted item. Returns how many were added.
     */
    public static int complete(List<SortAction> actions, Split split, String transcript) {
        Set<Integer> filed = new HashSet<>();
        List<String> loose = new ArrayList<>();
        for (SortAction a : actions) {
            if (a.item > 0) {
                filed.add(a.item);
            } else {
                loose.add(text(a));
            }
        }
        int added = 0;
        for (Item it : split.items) {
            if (filed.contains(it.n) || matchesLoose(it, loose)) {
                continue;
            }
            SortAction s = new SortAction("task".equals(it.kind) ? SortAction.TASK : SortAction.UNSORTED);
            s.title = it.summary.isEmpty() ? SortAction.oneLine(it.quote) : it.summary;
            s.quote = SortAction.oneLine(it.quote);
            s.item = it.n;
            actions.add(s);
            added++;
        }
        List<String> quotes = new ArrayList<>(split.noAction);
        for (Item it : split.items) {
            quotes.add(it.quote);
        }
        for (String sentence : Coverage.uncovered(transcript, quotes)) {
            SortAction s = new SortAction(SortAction.UNSORTED);
            String line = SortAction.oneLine(sentence);
            s.title = line.length() > 200 ? line.substring(0, 200) + "…" : line;
            s.quote = line;
            actions.add(s);
            added++;
        }
        return added;
    }

    private static boolean matchesLoose(Item it, List<String> loose) {
        for (String l : loose) {
            if (Coverage.mostlyIn(it.quote, l) || (!it.summary.isEmpty() && Coverage.mostlyIn(it.summary, l))) {
                return true;
            }
        }
        return false;
    }

    private static String text(SortAction a) {
        return a.title + " " + a.quote + " " + a.markdown + " " + a.question;
    }
}
