package com.ysulab.yapper.transcribe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One transcriber slot as the owner configured it in the app. Keys come from the Keystore-backed
 * secret store; nothing here is persisted by this class. Pure Java, testable.
 */
public final class TranscriberConfig {

    public static final String OPENAI = "openai";
    public static final String GROQ = "groq";
    public static final String ELEVENLABS = "elevenlabs";
    public static final String SELF_HOSTED = "selfhosted";
    public static final String OPENROUTER = "openrouter";

    /** Order = priority. With "compare all" off, only the first configured one runs. */
    public static final List<String> ALL = Arrays.asList(OPENROUTER, OPENAI, ELEVENLABS, GROQ, SELF_HOSTED);

    /**
     * OpenRouter slot default: several speech-to-text models behind one key, so the benchmark can
     * compare them without other accounts. Comma-separated; each runs as its own transcriber.
     */
    public static final String OPENROUTER_DEFAULT_MODELS =
            "openai/gpt-4o-transcribe, openai/whisper-large-v3, microsoft/mai-transcribe-2";

    /**
     * Whisper-style prompt: biases toward Saudi dialect written in Arabic script, with English terms
     * kept in Latin script (code-switching).
     */
    public static final String DEFAULT_PROMPT =
            "تسجيل صوتي باللهجة السعودية مع كلمات إنجليزية مثل deadline و meeting و DevOps و PR.";

    public final String id;
    public String baseUrl;
    public String model;
    public String apiKey = "";
    public String language = "";
    public String prompt = "";

    public TranscriberConfig(String id) {
        this.id = id;
        this.baseUrl = defaultUrl(id);
        this.model = defaultModel(id);
        // OpenRouter's transcription endpoint has no prompt field.
        this.prompt = ELEVENLABS.equals(id) || OPENROUTER.equals(id) ? "" : DEFAULT_PROMPT;
    }

    public static String defaultUrl(String id) {
        switch (id) {
            case OPENAI: return "https://api.openai.com/v1";
            case GROQ: return "https://api.groq.com/openai/v1";
            case ELEVENLABS: return ElevenLabsTranscriber.DEFAULT_URL;
            case OPENROUTER: return "https://openrouter.ai/api/v1";
            default: return "";
        }
    }

    public static String defaultModel(String id) {
        switch (id) {
            case OPENAI: return "gpt-4o-transcribe";
            case GROQ: return "whisper-large-v3";
            case ELEVENLABS: return "scribe_v2";
            case OPENROUTER: return OPENROUTER_DEFAULT_MODELS;
            default: return "Systran/faster-whisper-large-v3";
        }
    }

    public static String displayName(String id) {
        switch (id) {
            case OPENAI: return "OpenAI";
            case GROQ: return "Groq";
            case ELEVENLABS: return "ElevenLabs";
            case OPENROUTER: return "OpenRouter";
            default: return "Self-hosted";
        }
    }

    /**
     * Cloud slots need a key. The self-hosted slot needs an https URL (key optional, e.g. behind
     * Tailscale). Cleartext http is blocked by Android and by this check.
     */
    public boolean isConfigured() {
        if (SELF_HOSTED.equals(id)) {
            return baseUrl != null && baseUrl.trim().toLowerCase(java.util.Locale.ROOT).startsWith("https://");
        }
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    /** Model ids of this slot (the OpenRouter slot may list several, comma-separated). */
    public List<String> models() {
        List<String> out = new ArrayList<>();
        String raw = model == null || model.trim().isEmpty() ? defaultModel(id) : model;
        for (String m : raw.split(",")) {
            if (!m.trim().isEmpty()) {
                out.add(m.trim());
            }
        }
        return out;
    }

    /** One transcriber per model; only the OpenRouter slot can have more than one. */
    public List<Transcriber> buildAll() {
        List<Transcriber> out = new ArrayList<>();
        if (!OPENROUTER.equals(id)) {
            out.add(build());
            return out;
        }
        String url = baseUrl == null || baseUrl.trim().isEmpty() ? defaultUrl(id) : baseUrl.trim();
        for (String m : models()) {
            out.add(new OpenAiCompatibleTranscriber(OPENROUTER + ":" + m, "OpenRouter " + m, url, m,
                    apiKey == null ? "" : apiKey.trim(), language, "", true));
        }
        return out;
    }

    /** Every transcriber to run, in priority order. Compare mode off → just the first one. */
    public static List<Transcriber> transcribers(List<TranscriberConfig> all, boolean compareAll) {
        List<Transcriber> out = new ArrayList<>();
        for (TranscriberConfig c : active(all, compareAll)) {
            out.addAll(c.buildAll());
        }
        if (!compareAll && out.size() > 1) {
            return out.subList(0, 1);
        }
        return out;
    }

    public Transcriber build() {
        String m = model == null || model.trim().isEmpty() ? defaultModel(id) : model.trim();
        String url = baseUrl == null || baseUrl.trim().isEmpty() ? defaultUrl(id) : baseUrl.trim();
        if (ELEVENLABS.equals(id)) {
            return new ElevenLabsTranscriber(url, m, apiKey.trim(), language);
        }
        return new OpenAiCompatibleTranscriber(id, displayName(id) + " " + m, url, m,
                apiKey == null ? "" : apiKey.trim(), language, prompt);
    }

    /** The transcribers to run: all configured ones in compare mode, else just the first. */
    public static List<TranscriberConfig> active(List<TranscriberConfig> all, boolean compareAll) {
        List<TranscriberConfig> out = new ArrayList<>();
        for (String id : ALL) {
            for (TranscriberConfig c : all) {
                if (c.id.equals(id) && c.isConfigured()) {
                    out.add(c);
                    if (!compareAll) {
                        return out;
                    }
                }
            }
        }
        return out;
    }
}
