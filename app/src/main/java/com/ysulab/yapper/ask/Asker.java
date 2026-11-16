package com.ysulab.yapper.ask;

import com.ysulab.yapper.sort.JsonHttp;
import com.ysulab.yapper.sort.SortException;
import com.ysulab.yapper.sort.SorterConfig;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Answers a question from the vault. Round 1: the model gets the question plus the excerpts the
 * local {@link VaultSearch} found. If those are not enough it may call {@code search_vault} or
 * {@code read_note} (at most {@link #MAX_ROUNDS} model calls in total), then answers in plain text
 * with [[links]] to its sources. Any OpenAI-compatible {@code /chat/completions} server works, the
 * same as the sorter (OpenRouter now, a home-lab model later). Pure Java; the network is a
 * {@link Chat} so tests run without a server.
 */
public final class Asker {

    public static final int MAX_ROUNDS = 3;
    static final int TOOL_BUDGET = 12_000;
    static final int MAX_ANSWER = 4_000;

    /** One POST of a chat request body. */
    public interface Chat {
        JsonHttp.Response post(String json) throws IOException;
    }

    public static final class Answer {
        public String text = "";
        /** Vault paths the answer links to, in order. */
        public final List<String> sources = new ArrayList<>();
        public String model = "";
        public long latencyMs;
        public int rounds;
        /** Searches the model ran itself, e.g. "search: GCP, Tabby" (for the capture note). */
        public final List<String> steps = new ArrayList<>();
    }

    private final SorterConfig cfg;
    private final String template;
    private final VaultSearch search;
    private final ZonedDateTime now;
    private final Chat chat;

    public Asker(SorterConfig cfg, String template, VaultSearch search, ZonedDateTime now, Chat chat) {
        this.cfg = cfg;
        this.template = template;
        this.search = search;
        this.now = now;
        this.chat = chat;
    }

    /** The real network: POST {baseUrl}/chat/completions with the sorter's key. */
    public static Chat http(SorterConfig cfg) {
        return json -> {
            Map<String, String> headers = new HashMap<>();
            if (cfg.apiKey != null && !cfg.apiKey.trim().isEmpty()) {
                headers.put("Authorization", "Bearer " + cfg.apiKey.trim());
            }
            headers.put("X-Title", "Yapper");
            return JsonHttp.post(cfg.url() + "/chat/completions", headers, json);
        };
    }

    public Answer ask(String question, VaultSearch.Query first) throws SortException {
        Answer ans = new Answer();
        long start = System.nanoTime();
        try {
            JSONArray messages = new JSONArray()
                    .put(new JSONObject().put("role", "system").put("content", system()))
                    .put(new JSONObject().put("role", "user").put("content", userText(question, first)));
            String text = null;
            for (int round = 0; round < MAX_ROUNDS && text == null; round++) {
                boolean last = round == MAX_ROUNDS - 1;
                if (last && round > 0) {
                    messages.put(new JSONObject().put("role", "user")
                            .put("content", "No more searches. Answer now from what you have."));
                }
                JSONObject msg = call(request(messages, last), ans);
                ans.rounds = round + 1;
                JSONArray calls = msg.optJSONArray("tool_calls");
                if (calls != null && calls.length() > 0 && !last) {
                    JSONObject echo = new JSONObject().put("role", "assistant").put("tool_calls", calls);
                    String c = msg.optString("content", "");
                    echo.put("content", c.isEmpty() || "null".equals(c) ? JSONObject.NULL : c);
                    messages.put(echo);
                    for (int i = 0; i < calls.length(); i++) {
                        JSONObject call = calls.getJSONObject(i);
                        messages.put(new JSONObject().put("role", "tool")
                                .put("tool_call_id", call.optString("id", "call_" + i))
                                .put("content", runTool(call.optJSONObject("function"), ans)));
                    }
                    continue;
                }
                String c = msg.isNull("content") ? "" : msg.optString("content", "").trim();
                text = c;
            }
            ans.text = clean(text);
        } catch (JSONException e) {
            throw new SortException("Could not read the answer: " + e.getMessage(), false, e);
        }
        ans.latencyMs = (System.nanoTime() - start) / 1_000_000;
        ans.sources.addAll(search.sources(ans.text));
        return ans;
    }

    static String clean(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return "I couldn't find an answer to that in your vault.";
        }
        return t.length() > MAX_ANSWER ? t.substring(0, MAX_ANSWER) + "…" : t;
    }

    String system() {
        return template
                .replace("{{NOW}}", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .replace("{{ZONE}}", now.getZone().getId())
                .replace("{{WEEKDAY}}", now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
    }

    String userText(String question, VaultSearch.Query q) {
        StringBuilder sb = new StringBuilder("## Question\n").append(question.trim()).append("\n\n");
        sb.append("## Excerpts the app found");
        sb.append(describe(q)).append('\n');
        sb.append(VaultSearch.render(search.search(q))).append('\n');
        return sb.toString();
    }

    static String describe(VaultSearch.Query q) {
        StringBuilder sb = new StringBuilder();
        if (!q.terms.isEmpty()) {
            sb.append(" for: ").append(String.join(", ", q.terms));
        }
        if (q.since != null || q.until != null) {
            sb.append(" (").append(q.since == null ? "…" : q.since).append(" → ")
                    .append(q.until == null ? "…" : q.until).append(')');
        }
        return sb.toString();
    }

    private JSONObject request(JSONArray messages, boolean last) throws JSONException {
        JSONObject r = new JSONObject()
                .put("model", cfg.modelId())
                .put("messages", messages)
                // Tools stay declared even on the last round: Anthropic models reject a history with
                // tool calls when no tools are declared. "none" makes the model answer in text.
                .put("tools", new JSONArray(TOOLS))
                .put("tool_choice", last ? "none" : "auto")
                .put("temperature", 0.2)
                .put("max_tokens", 4000);
        return r;
    }

    private JSONObject call(JSONObject request, Answer ans) throws SortException, JSONException {
        JsonHttp.Response r;
        try {
            r = chat.post(request.toString());
        } catch (IOException e) {
            throw new SortException("Network error: " + e.getClass().getSimpleName(), true, e);
        }
        if (r.code / 100 != 2) {
            throw new SortException("HTTP " + r.code + ": " + JsonHttp.snippet(r.body),
                    SortException.isRetryableStatus(r.code), null);
        }
        JSONObject root;
        try {
            root = new JSONObject(r.body);
        } catch (JSONException e) {
            throw new SortException("Unexpected response: " + JsonHttp.snippet(r.body), false, e);
        }
        if (root.has("error")) {
            JSONObject err = root.optJSONObject("error");
            int code = err == null ? 0 : err.optInt("code", 0);
            String m = err == null ? root.optString("error") : err.optString("message");
            throw new SortException("Provider error: " + JsonHttp.snippet(m),
                    code == 0 || SortException.isRetryableStatus(code), null);
        }
        if (ans.model.isEmpty()) {
            ans.model = root.optString("model", "");
        }
        return root.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
    }

    /** Runs one tool call locally. Bad arguments become a short error the model can read. */
    String runTool(JSONObject fn, Answer ans) {
        if (fn == null) {
            return "error: missing function";
        }
        String name = fn.optString("name");
        JSONObject a;
        try {
            Object raw = fn.opt("arguments");
            a = raw instanceof JSONObject ? (JSONObject) raw : new JSONObject(fn.optString("arguments", "{}"));
        } catch (JSONException e) {
            return "error: arguments are not JSON";
        }
        switch (name) {
            case "search_vault": {
                VaultSearch.Query q = query(a.optJSONArray("terms"), a.optString("since", ""),
                        a.optString("until", ""));
                q.budget = TOOL_BUDGET;
                ans.steps.add("search" + describe(q));
                return VaultSearch.render(search.search(q));
            }
            case "read_note": {
                String path = a.optString("path", "").trim();
                String p = search.pathFor(path);
                ans.steps.add("read " + path);
                String text = p == null ? null : search.note(p);
                if (text == null) {
                    return "No note at " + path;
                }
                return text.length() > TOOL_BUDGET ? text.substring(0, TOOL_BUDGET) + "\n…(cut)" : text;
            }
            default:
                return "error: unknown tool " + name;
        }
    }

    /** A query from tool arguments; bad dates are ignored rather than failing the question. */
    public static VaultSearch.Query query(JSONArray terms, String since, String until) {
        VaultSearch.Query q = new VaultSearch.Query();
        if (terms != null) {
            for (int i = 0; i < terms.length() && q.terms.size() < 20; i++) {
                String t = terms.optString(i, "").trim();
                if (!t.isEmpty() && !q.terms.contains(t)) {
                    q.terms.add(t);
                }
            }
        }
        q.since = date(since);
        q.until = date(until);
        return q;
    }

    static LocalDate date(String s) {
        try {
            return s == null || s.trim().isEmpty() ? null : LocalDate.parse(s.trim().substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }

    static final String TOOLS = "["
            + "{\"type\":\"function\",\"function\":{\"name\":\"search_vault\","
            + "\"description\":\"Keyword search over the vault. Give every spelling: English, Arabic, short forms.\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{"
            + "\"terms\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
            + "\"since\":{\"type\":\"string\",\"description\":\"YYYY-MM-DD, optional\"},"
            + "\"until\":{\"type\":\"string\",\"description\":\"YYYY-MM-DD, optional\"}},"
            + "\"required\":[\"terms\"]}}},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"read_note\","
            + "\"description\":\"Read one whole note by its vault path or name.\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{"
            + "\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}}}"
            + "]";
}
