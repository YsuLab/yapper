package com.ysulab.yapper.sort;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Calls the model once and turns its tool calls into validated {@link SortAction}s. */
public final class Sorter {

    public static final class Result {
        public final List<SortAction> actions = new ArrayList<>();
        /** Tool calls that failed validation (unsafe path, unknown tool, bad date). */
        public final List<String> dropped = new ArrayList<>();
        public long latencyMs;
        public String model = "";
        /** Split-then-sort: items pass 1 found (0 = one pass) and how many the app added after pass 2. */
        public int splitItems;
        public int filledIn;
    }

    /** The HTTP call, swappable in tests. */
    interface Transport {
        JsonHttp.Response post(String url, Map<String, String> headers, String body) throws IOException;
    }

    private Transport transport = JsonHttp::post;

    Sorter withTransport(Transport t) {
        this.transport = t;
        return this;
    }

    private final SorterConfig cfg;
    private final String template;
    private final String toolsJson;

    public Sorter(SorterConfig cfg, String template, String toolsJson) {
        this.cfg = cfg;
        this.template = template;
        this.toolsJson = toolsJson;
    }

    public Result sort(SorterInput in) throws SortException {
        String body;
        try {
            body = SorterPrompt.request(cfg, template, toolsJson, in).toString();
        } catch (JSONException e) {
            throw new SortException("Could not build request", false, e);
        }
        long start = System.nanoTime();
        JsonHttp.Response r = call(body);
        Result res = parse(r.body);
        res.latencyMs = (System.nanoTime() - start) / 1_000_000;
        return res;
    }

    /**
     * Split, then sort (spec 4.2): pass 1 lists every item with his exact words, pass 2 files each
     * numbered item, then {@link Splitter#complete} adds anything still unfiled as a task or an Unsorted
     * item. Short captures, shares and weekly reviews, or a pass 1 that returns nothing, use one pass.
     */
    public Result sortSplit(SorterInput in, String splitTemplate) throws SortException {
        if (splitTemplate == null || !Splitter.worthSplitting(in)) {
            return sort(in);
        }
        long start = System.nanoTime();
        Splitter.Split split;
        try {
            split = Splitter.parse(call(Splitter.request(cfg, splitTemplate, in).toString()).body);
        } catch (JSONException e) {
            throw new SortException("Could not build split request", false, e);
        } catch (SortException e) {
            if (e.retryable) {
                throw e;
            }
            split = null; // a malformed pass 1 must not block sorting: fall back to one pass
        }
        if (split == null || split.items.isEmpty()) {
            return sort(in);
        }
        long splitMs = (System.nanoTime() - start) / 1_000_000;
        in.items = split.items;
        Result res;
        try {
            res = sort(in);
        } finally {
            in.items = new java.util.ArrayList<>();
        }
        res.splitItems = split.items.size();
        res.filledIn = Splitter.complete(res.actions, split, in.transcript);
        res.latencyMs += splitMs;
        return res;
    }

    private JsonHttp.Response call(String body) throws SortException {
        Map<String, String> headers = new HashMap<>();
        if (cfg.apiKey != null && !cfg.apiKey.trim().isEmpty()) {
            headers.put("Authorization", "Bearer " + cfg.apiKey.trim());
        }
        headers.put("X-Title", "Yapper");
        JsonHttp.Response r;
        try {
            r = transport.post(cfg.url() + "/chat/completions", headers, body);
        } catch (IOException e) {
            throw new SortException("Network error: " + e.getClass().getSimpleName(), true, e);
        }
        if (r.code / 100 != 2) {
            throw new SortException("HTTP " + r.code + ": " + JsonHttp.snippet(r.body),
                    SortException.isRetryableStatus(r.code), null);
        }
        return r;
    }

    /** Parses an OpenAI-style chat completion. Package-private for tests. */
    static Result parse(String body) throws SortException {
        Result res = new Result();
        try {
            JSONObject root = new JSONObject(body);
            if (root.has("error")) {
                // OpenRouter can return 200 with an error object (e.g. upstream failure mid-request).
                JSONObject err = root.optJSONObject("error");
                int code = err == null ? 0 : err.optInt("code", 0);
                String msg = err == null ? root.optString("error") : err.optString("message");
                throw new SortException("Provider error: " + JsonHttp.snippet(msg),
                        code == 0 || SortException.isRetryableStatus(code), null);
            }
            res.model = root.optString("model", "");
            JSONArray choices = root.getJSONArray("choices");
            JSONObject msg = choices.getJSONObject(0).getJSONObject("message");
            JSONArray calls = msg.optJSONArray("tool_calls");
            if (calls != null) {
                for (int i = 0; i < calls.length(); i++) {
                    JSONObject fn = calls.getJSONObject(i).optJSONObject("function");
                    if (fn == null) {
                        continue;
                    }
                    String name = fn.optString("name");
                    Object args = fn.opt("arguments");
                    String argStr = args instanceof JSONObject ? args.toString() : fn.optString("arguments", "{}");
                    try {
                        res.actions.add(SortAction.parse(name, argStr));
                    } catch (Exception e) { // JSONException (checked on Android) or a bad date
                        res.dropped.add(name + ": " + e.getMessage());
                    }
                }
            }
            if (res.actions.isEmpty() && res.dropped.isEmpty()) {
                // A model that ignored "tools only" and answered in text: keep it, ask the owner.
                String text = msg.optString("content", "").trim();
                SortAction ask = new SortAction(SortAction.ASK);
                ask.question = text.isEmpty() ? "Sorter returned nothing for this capture. Where should it go?"
                        : SortAction.oneLine(text.length() > 300 ? text.substring(0, 300) + "…" : text);
                res.actions.add(ask);
            }
        } catch (JSONException e) {
            throw new SortException("Unexpected response: " + JsonHttp.snippet(body), false, e);
        }
        return res;
    }
}
