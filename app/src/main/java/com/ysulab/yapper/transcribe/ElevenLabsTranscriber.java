package com.ysulab.yapper.transcribe;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * ElevenLabs Scribe: {@code POST https://api.elevenlabs.io/v1/speech-to-text} with
 * {@code xi-api-key}, {@code model_id} and the file. Included in the benchmark because Scribe is
 * marketed as strong on Arabic dialects.
 */
public final class ElevenLabsTranscriber implements Transcriber {

    public static final String DEFAULT_URL = "https://api.elevenlabs.io/v1";

    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final String language;

    public ElevenLabsTranscriber(String baseUrl, String model, String apiKey, String language) {
        this.baseUrl = OpenAiCompatibleTranscriber.stripSlash(
                baseUrl == null || baseUrl.isEmpty() ? DEFAULT_URL : baseUrl);
        this.model = model;
        this.apiKey = apiKey;
        this.language = language;
    }

    @Override
    public String id() {
        return TranscriberConfig.ELEVENLABS;
    }

    @Override
    public String label() {
        return "ElevenLabs " + model;
    }

    @Override
    public Transcript transcribe(File audio) throws TranscriptionException {
        Http.Multipart form = new Http.Multipart();
        form.fields.put("model_id", model);
        form.fields.put("tag_audio_events", "false");
        if (language != null && !language.isEmpty()) {
            form.fields.put("language_code", language);
        }
        form.file = audio;
        form.fileMime = Http.mimeFor(audio);
        Map<String, String> headers = new HashMap<>();
        headers.put("xi-api-key", apiKey);
        long start = System.nanoTime();
        Http.Response r;
        try {
            r = Http.postMultipart(baseUrl + "/speech-to-text", headers, form);
        } catch (IOException e) {
            throw new TranscriptionException("Network error: " + e.getClass().getSimpleName(), true, 0, e);
        }
        long latency = (System.nanoTime() - start) / 1_000_000;
        if (r.code / 100 != 2) {
            throw new TranscriptionException("HTTP " + r.code + ": " + Http.snippet(r.body),
                    TranscriptionException.isRetryableStatus(r.code), r.code, null);
        }
        try {
            return new Transcript(new JSONObject(r.body).optString("text", "").trim(), latency);
        } catch (JSONException e) {
            throw new TranscriptionException("Unexpected response: " + Http.snippet(r.body), false, r.code, e);
        }
    }
}
