package com.ysulab.yapper.reminders;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

public class MsClientsTest {

    static final class Recorder implements WebCall {
        final List<String> calls = new ArrayList<>();
        Map<String, String> lastHeaders;
        String lastBody;
        int code = 200;
        String response = "{}";

        @Override
        public Response send(String method, String url, Map<String, String> headers, String body) {
            calls.add(method + " " + url);
            lastHeaders = headers;
            lastBody = body;
            return new Response(code, response);
        }
    }

    static final String CLIENT = "0123abcd-0000-1111-2222-333344445555";

    @Test
    public void pkceChallengeIsBase64UrlSha256() {
        // Known answer computed with Python: urlsafe_b64encode(sha256(verifier)).rstrip("=")
        assertEquals("bwWFMyPfdG9qreDhH2lmftFx_dFeLDalzcT1gb_j68g",
                MsAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r7wW1gFWFOEjXk"));
        String v = MsAuth.randomToken(new SecureRandom());
        assertTrue(v.length() >= 43 && v.length() <= 128);
        assertTrue(v.matches("[A-Za-z0-9_-]+"));
    }

    @Test
    public void authorizeUrlHasEverythingNeeded() {
        String url = new MsAuth(CLIENT, new Recorder()).authorizeUrl("verifier-123", "st");
        assertTrue(url.startsWith("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?"));
        assertTrue(url.contains("client_id=" + CLIENT));
        assertTrue(url.contains("redirect_uri=yapper%3A%2F%2Fauth"));
        assertTrue(url.contains("scope=Tasks.ReadWrite%20offline_access"));
        assertTrue(url.contains("code_challenge_method=S256"));
        assertTrue(url.contains("state=st"));
    }

    @Test
    public void clientIdValidation() {
        assertTrue(MsAuth.isValidClientId(CLIENT));
        assertTrue(MsAuth.isValidClientId(" " + CLIENT + " "));
        assertFalse(MsAuth.isValidClientId("not-a-guid"));
        assertFalse(MsAuth.isValidClientId(null));
    }

    @Test
    public void redeemAndRefresh() throws Exception {
        Recorder web = new Recorder();
        web.response = "{\"access_token\":\"AT\",\"refresh_token\":\"RT\",\"expires_in\":3600}";
        MsAuth auth = new MsAuth(CLIENT, web);
        MsAuth.Tokens t = auth.redeemCode("CODE", "VER", 1000);
        assertEquals("AT", t.accessToken);
        assertEquals("RT", t.refreshToken);
        assertEquals(1000 + 3600_000L, t.expiresAtMs);
        assertTrue(web.lastBody.contains("grant_type=authorization_code"));
        assertTrue(web.lastBody.contains("code_verifier=VER"));
        assertEquals("POST https://login.microsoftonline.com/consumers/oauth2/v2.0/token", web.calls.get(0));

        // A refresh without a new refresh token keeps the old one.
        web.response = "{\"access_token\":\"AT2\",\"expires_in\":3600}";
        assertEquals("RT", auth.refresh("RT", 0).refreshToken);

        web.code = 400;
        web.response = "{\"error\":\"invalid_grant\",\"error_description\":\"expired\"}";
        try {
            auth.refresh("RT", 0);
            fail();
        } catch (MsAuth.AuthException e) {
            assertTrue(e.needsSignIn);
        }
        web.code = 503;
        web.response = "";
        try {
            auth.refresh("RT", 0);
            fail();
        } catch (java.io.IOException expected) {
            // retryable
        }
    }

    @Test
    public void todoListsCreateDelete() throws Exception {
        Recorder web = new Recorder();
        web.response = "{\"value\":[{\"id\":\"A\",\"displayName\":\"Tasks\",\"wellknownListName\":\"defaultList\"},"
                + "{\"id\":\"B\",\"displayName\":\"Samsung Reminder\",\"wellknownListName\":\"none\"}]}";
        TodoClient c = new TodoClient("http://x/v1.0", "TOKEN", web);
        List<TodoClient.TaskList> lists = c.lists();
        assertEquals(2, lists.size());
        assertEquals("Samsung Reminder", lists.get(1).name);
        assertEquals("Bearer TOKEN", web.lastHeaders.get("Authorization"));

        web.response = "{\"id\":\"T1\"}";
        String json = TodoClient.taskJson("Exam", "Room 12", OffsetDateTime.parse("2026-11-10T08:30:00+03:00"), true);
        assertEquals("T1", c.createTask("B", json));
        assertEquals("POST http://x/v1.0/me/todo/lists/B/tasks", web.calls.get(1));
        JSONObject sent = new JSONObject(web.lastBody);
        assertEquals("Exam", sent.getString("title"));
        assertTrue(sent.getBoolean("isReminderOn"));
        assertEquals("2026-11-10T05:30:00.0000000", sent.getJSONObject("reminderDateTime").getString("dateTime"));
        assertEquals("UTC", sent.getJSONObject("reminderDateTime").getString("timeZone"));
        assertEquals("2026-11-10T00:00:00.0000000", sent.getJSONObject("dueDateTime").getString("dateTime"));
        assertEquals("Room 12", sent.getJSONObject("body").getString("content"));

        web.code = 404;
        c.deleteTask("B", "T1"); // already gone is fine
        web.code = 429;
        try {
            c.deleteTask("B", "T1");
            fail();
        } catch (TodoClient.GraphException e) {
            assertTrue(e.retryable);
        }
    }

    @Test
    public void quietTaskHasNoReminderTime() throws Exception {
        JSONObject o = new JSONObject(TodoClient.taskJson("x", "", OffsetDateTime.parse("2026-11-10T08:30:00+03:00"), false));
        assertFalse(o.getBoolean("isReminderOn"));
        assertFalse(o.has("reminderDateTime"));
        assertFalse(o.has("body"));
    }
}
