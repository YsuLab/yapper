package com.ysulab.yapper.transcribe;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code POST <baseUrl>/audio/transcriptions} — the OpenAI-style endpoint. The same client covers
 * OpenAI ({@code gpt-4o-transcribe}), Groq ({@code whisper-large-v3}) and self-hosted servers such as
 * Speaches / faster-whisper on the owner's GPU home lab. Asks for {@code response_format=text}.
 */
public final class OpenAiCompatibleTranscriber implements Transcriber {

    private final String id;
    private final String label;
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final String language;
    private final String prompt;

    public OpenAiCompatibleTranscriber(String id, String label, String baseUrl, String model,
            String apiKey, String language, String prompt) {
        this.id = id;
        this.label = label;
        this.baseUrl = stripSlash(baseUrl);
        this.model = model;
        this.apiKey = apiKey;
        this.language = language;
        this.prompt = prompt;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public Transcript transcribe(File audio) throws TranscriptionException {
        Http.Multipart form = new Http.Multipart();
        form.fields.put("model", model);
        form.fields.put("response_format", "text");
        if (language != null && !language.isEmpty()) {
            form.fields.put("language", language);
        }
        if (prompt != null && !prompt.isEmpty()) {
            form.fields.put("prompt", prompt);
        }
        form.file = audio;
        form.fileMime = Http.mimeFor(audio);
        Map<String, String> headers = new HashMap<>();
        if (apiKey != null && !apiKey.isEmpty()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        long start = System.nanoTime();
        Http.Response r;
        try {
            r = Http.postMultipart(baseUrl + "/audio/transcriptions", headers, form);
        } catch (IOException e) {
            throw new TranscriptionException("Network error: " + e.getClass().getSimpleName(), true, 0, e);
        }
        long latency = (System.nanoTime() - start) / 1_000_000;
        if (r.code / 100 != 2) {
            throw new TranscriptionException("HTTP " + r.code + ": " + Http.snippet(r.body),
                    TranscriptionException.isRetryableStatus(r.code), r.code, null);
        }
        return new Transcript(r.body.trim(), latency);
    }

    static String stripSlash(String s) {
        String t = s == null ? "" : s.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }
}
