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

    /** Order = priority. With "compare all" off, only the first configured one runs. */
    public static final List<String> ALL = Arrays.asList(OPENAI, ELEVENLABS, GROQ, SELF_HOSTED);

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
        this.prompt = ELEVENLABS.equals(id) ? "" : DEFAULT_PROMPT;
    }

    public static String defaultUrl(String id) {
        switch (id) {
            case OPENAI: return "https://api.openai.com/v1";
            case GROQ: return "https://api.groq.com/openai/v1";
            case ELEVENLABS: return ElevenLabsTranscriber.DEFAULT_URL;
            default: return "";
        }
    }

    public static String defaultModel(String id) {
        switch (id) {
            case OPENAI: return "gpt-4o-transcribe";
            case GROQ: return "whisper-large-v3";
            case ELEVENLABS: return "scribe_v2";
            default: return "Systran/faster-whisper-large-v3";
        }
    }

    public static String displayName(String id) {
        switch (id) {
            case OPENAI: return "OpenAI";
            case GROQ: return "Groq";
            case ELEVENLABS: return "ElevenLabs";
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
