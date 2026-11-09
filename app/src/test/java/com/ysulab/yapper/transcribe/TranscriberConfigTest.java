package com.ysulab.yapper.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class TranscriberConfigTest {

    private static List<TranscriberConfig> all() {
        List<TranscriberConfig> out = new ArrayList<>();
        for (String id : TranscriberConfig.ALL) {
            out.add(new TranscriberConfig(id));
        }
        return out;
    }

    private static TranscriberConfig get(List<TranscriberConfig> l, String id) {
        for (TranscriberConfig c : l) {
            if (c.id.equals(id)) return c;
        }
        throw new AssertionError(id);
    }

    @Test
    public void nothingConfiguredByDefault() {
        assertTrue(TranscriberConfig.active(all(), true).isEmpty());
    }

    @Test
    public void cloudSlotsNeedKeys() {
        TranscriberConfig c = new TranscriberConfig(TranscriberConfig.GROQ);
        assertFalse(c.isConfigured());
        c.apiKey = "  ";
        assertFalse(c.isConfigured());
        c.apiKey = "gsk_x";
        assertTrue(c.isConfigured());
    }

    @Test
    public void selfHostedNeedsHttps() {
        TranscriberConfig c = new TranscriberConfig(TranscriberConfig.SELF_HOSTED);
        assertFalse(c.isConfigured());
        c.baseUrl = "http://100.64.0.5:8000/v1";
        assertFalse(c.isConfigured());
        c.baseUrl = "https://gpu.tailnet.ts.net/v1";
        assertTrue(c.isConfigured());
    }

    @Test
    public void compareAllRunsEveryConfiguredInPriorityOrder() {
        List<TranscriberConfig> l = all();
        get(l, TranscriberConfig.GROQ).apiKey = "g";
        get(l, TranscriberConfig.OPENAI).apiKey = "o";
        List<String> ids = new ArrayList<>();
        for (TranscriberConfig c : TranscriberConfig.active(l, true)) ids.add(c.id);
        assertEquals(Arrays.asList("openai", "groq"), ids);
        List<TranscriberConfig> one = TranscriberConfig.active(l, false);
        assertEquals(1, one.size());
        assertEquals("openai", one.get(0).id);
    }

    @Test
    public void buildUsesDefaultsAndLabels() {
        TranscriberConfig c = new TranscriberConfig(TranscriberConfig.OPENAI);
        c.apiKey = "k";
        c.model = "";
        assertEquals("OpenAI gpt-4o-transcribe", c.build().label());
        assertEquals("openai", c.build().id());
        TranscriberConfig e = new TranscriberConfig(TranscriberConfig.ELEVENLABS);
        e.apiKey = "k";
        assertEquals("ElevenLabs scribe_v2", e.build().label());
        assertEquals("", e.prompt);
    }

    @Test
    public void openRouterOneKeyManyModels() {
        List<TranscriberConfig> l = all();
        TranscriberConfig or = get(l, TranscriberConfig.OPENROUTER);
        or.apiKey = "sk-or-x";
        or.model = "openai/gpt-4o-transcribe, openai/whisper-large-v3 ,,";
        get(l, TranscriberConfig.GROQ).apiKey = "g";
        List<String> ids = new ArrayList<>();
        for (Transcriber t : TranscriberConfig.transcribers(l, true)) ids.add(t.id());
        assertEquals(Arrays.asList("openrouter:openai/gpt-4o-transcribe",
                "openrouter:openai/whisper-large-v3", "groq"), ids);
        List<Transcriber> one = TranscriberConfig.transcribers(l, false);
        assertEquals(1, one.size());
        assertEquals("OpenRouter openai/gpt-4o-transcribe", one.get(0).label());
        assertEquals("", or.prompt);
    }

    @Test
    public void openRouterDefaultsToSeveralModels() {
        TranscriberConfig or = new TranscriberConfig(TranscriberConfig.OPENROUTER);
        assertEquals("https://openrouter.ai/api/v1", or.baseUrl);
        assertTrue(or.models().size() >= 2);
    }
}
