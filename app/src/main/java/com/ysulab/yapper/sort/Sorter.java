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
        Map<String, String> headers = new HashMap<>();
        if (cfg.apiKey != null && !cfg.apiKey.trim().isEmpty()) {
            headers.put("Authorization", "Bearer " + cfg.apiKey.trim());
        }
        headers.put("X-Title", "Yapper");
        long start = System.nanoTime();
        JsonHttp.Response r;
        try {
            r = JsonHttp.post(cfg.url() + "/chat/completions", headers, body);
        } catch (IOException e) {
            throw new SortException("Network error: " + e.getClass().getSimpleName(), true, e);
        }
        long latency = (System.nanoTime() - start) / 1_000_000;
        if (r.code / 100 != 2) {
            throw new SortException("HTTP " + r.code + ": " + JsonHttp.snippet(r.body),
                    SortException.isRetryableStatus(r.code), null);
        }
        Result res = parse(r.body);
        res.latencyMs = latency;
        return res;
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
