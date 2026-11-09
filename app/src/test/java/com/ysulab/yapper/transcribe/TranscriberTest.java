package com.ysulab.yapper.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TranscriberTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File audio() throws Exception {
        File f = tmp.newFile("audio.ogg");
        Files.write(f.toPath(), "OggS-fake-audio".getBytes(StandardCharsets.UTF_8));
        return f;
    }

    @Test
    public void openAiCompatibleSendsMultipartAndReadsText() throws Exception {
        try (FakeServer s = new FakeServer()) {
            s.response = "ذكرني بكرة عن الـ meeting\n";
            Transcriber t = new OpenAiCompatibleTranscriber("openai", "OpenAI gpt-4o-transcribe",
                    s.url() + "/", "gpt-4o-transcribe", "sk-test", "", "hint");
            Transcriber.Transcript r = t.transcribe(audio());
            assertEquals("ذكرني بكرة عن الـ meeting", r.text);
            assertEquals("/v1/audio/transcriptions", s.path);
            assertEquals("Bearer sk-test", s.auth);
            assertTrue(s.contentType.startsWith("multipart/form-data; boundary="));
            assertTrue(s.body.contains("name=\"model\"\r\n\r\ngpt-4o-transcribe\r\n"));
            assertTrue(s.body.contains("name=\"response_format\"\r\n\r\ntext\r\n"));
            assertTrue(s.body.contains("name=\"prompt\"\r\n\r\nhint\r\n"));
            assertFalse(s.body.contains("name=\"language\""));
            assertTrue(s.body.contains("filename=\"audio.ogg\"\r\nContent-Type: audio/ogg"));
            assertTrue(s.body.contains("OggS-fake-audio"));
            assertTrue(r.latencyMs >= 0);
        }
    }

    @Test
    public void selfHostedWithoutKeySendsNoAuthHeader() throws Exception {
        try (FakeServer s = new FakeServer()) {
            s.response = "ok";
            new OpenAiCompatibleTranscriber("selfhosted", "Self", s.url(), "m", "", "ar", "")
                    .transcribe(audio());
            assertNull(s.auth);
            assertTrue(s.body.contains("name=\"language\"\r\n\r\nar\r\n"));
        }
    }

    @Test
    public void authErrorIsFinal() throws Exception {
        try (FakeServer s = new FakeServer()) {
            s.status = 401;
            s.response = "{\"error\":\"bad key\"}";
            try {
                new OpenAiCompatibleTranscriber("openai", "O", s.url(), "m", "k", "", "").transcribe(audio());
                fail();
            } catch (TranscriptionException e) {
                assertFalse(e.retryable);
                assertEquals(401, e.httpCode);
                assertTrue(e.getMessage().contains("401"));
                assertFalse(e.getMessage().contains("Bearer"));
            }
        }
    }

    @Test
    public void rateLimitIsRetryable() throws Exception {
        try (FakeServer s = new FakeServer()) {
            s.status = 429;
            try {
                new OpenAiCompatibleTranscriber("groq", "G", s.url(), "m", "k", "", "").transcribe(audio());
                fail();
            } catch (TranscriptionException e) {
                assertTrue(e.retryable);
            }
        }
    }

    @Test
    public void networkErrorIsRetryable() throws Exception {
        try {
            new OpenAiCompatibleTranscriber("x", "X", "http://127.0.0.1:1/v1", "m", "k", "", "").transcribe(audio());
            fail();
        } catch (TranscriptionException e) {
            assertTrue(e.retryable);
        }
    }

    @Test
    public void elevenLabsParsesJson() throws Exception {
        try (FakeServer s = new FakeServer()) {
            s.response = "{\"language_code\":\"ara\",\"text\":\" بكرة exam الساعة ٩ \",\"words\":[]}";
            Transcriber.Transcript r = new ElevenLabsTranscriber(s.url(), "scribe_v2", "xi-test", "")
                    .transcribe(audio());
            assertEquals("بكرة exam الساعة ٩", r.text);
            assertEquals("/v1/speech-to-text", s.path);
            assertEquals("xi-test", s.xiKey);
            assertTrue(s.body.contains("name=\"model_id\"\r\n\r\nscribe_v2\r\n"));
            assertFalse(s.body.contains("name=\"language_code\""));
        }
    }

    @Test
    public void elevenLabsBadJsonIsFinal() throws Exception {
        try (FakeServer s = new FakeServer()) {
            s.response = "<html>proxy error</html>";
            try {
                new ElevenLabsTranscriber(s.url(), "scribe_v2", "k", "").transcribe(audio());
                fail();
            } catch (TranscriptionException e) {
                assertFalse(e.retryable);
            }
        }
    }

    @Test
    public void retryableStatuses() {
        assertTrue(TranscriptionException.isRetryableStatus(429));
        assertTrue(TranscriptionException.isRetryableStatus(503));
        assertFalse(TranscriptionException.isRetryableStatus(400));
        assertFalse(TranscriptionException.isRetryableStatus(401));
        assertFalse(TranscriptionException.isRetryableStatus(413));
    }

    @Test
    public void mimeTypes() {
        assertEquals("audio/ogg", Http.mimeFor(new File("a.ogg")));
        assertEquals("audio/mp4", Http.mimeFor(new File("a.m4a")));
        assertEquals("application/octet-stream", Http.mimeFor(new File("a.bin")));
    }
}
