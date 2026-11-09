package com.ysulab.yapper.sort;

import java.util.Locale;

/**
 * The sorter's model settings. Any OpenAI-compatible {@code /chat/completions} server with tool
 * calls works: OpenRouter (default), OpenAI, or a self-hosted vLLM / llama.cpp server. Pure Java.
 */
public final class SorterConfig {

    public static final String DEFAULT_URL = "https://openrouter.ai/api/v1";
    public static final String DEFAULT_MODEL = "anthropic/claude-sonnet-5.5";

    public String baseUrl = DEFAULT_URL;
    public String model = DEFAULT_MODEL;
    public String apiKey = "";
    /** Shadow mode: write proposals into the capture note instead of the vault. */
    public boolean shadow = false;
    /** Move a sorted capture note (and its audio/screenshot) from Inbox/ to _yapper/captures/. */
    public boolean moveSorted = true;
    /** Send the screenshot when the screen text is too thin to understand the screen. */
    public boolean screenshots = true;

    public String url() {
        String u = baseUrl == null || baseUrl.trim().isEmpty() ? DEFAULT_URL : baseUrl.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    public String modelId() {
        return model == null || model.trim().isEmpty() ? DEFAULT_MODEL : model.trim();
    }

    /** https only. A key is required for the default (OpenRouter) URL; self-hosted may have none. */
    public boolean isConfigured() {
        String u = url().toLowerCase(Locale.ROOT);
        if (!u.startsWith("https://")) {
            return false;
        }
        boolean hasKey = apiKey != null && !apiKey.trim().isEmpty();
        return hasKey || !u.startsWith(DEFAULT_URL);
    }

    /** Anthropic models on OpenRouter take {@code cache_control} breakpoints; others ignore or reject them. */
    public boolean promptCaching() {
        return modelId().startsWith("anthropic/");
    }
}
