package com.ysulab.yapper.hub;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Talks to the hub with this phone's token. Plain {@link HttpURLConnection}, testable with a local server. */
public final class HubClient {

    public static final String DEFAULT_URL = "https://board.yasiraloufi.dev";
    static final int CONNECT_TIMEOUT_MS = 15_000;
    static final int READ_TIMEOUT_MS = 30_000;

    public static final class Response {
        public final int code;
        public final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }

        public boolean ok() {
            return code / 100 == 2;
        }
    }

    private final String base;
    private final String token;

    public HubClient(String baseUrl, String token) {
        String b = baseUrl == null || baseUrl.trim().isEmpty() ? DEFAULT_URL : baseUrl.trim();
        this.base = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        this.token = token == null ? "" : token.trim();
    }

    public boolean isConfigured() {
        return !token.isEmpty();
    }

    /** 5xx, 408 and 429 are worth another try later; other 4xx are not (bad token, bad data). */
    public static boolean retryable(int code) {
        return code >= 500 || code == 408 || code == 429;
    }

    public Response health() throws IOException {
        return send("GET", "/api/health", null);
    }

    public Response postCapture(String json) throws IOException {
        return send("POST", "/api/captures", json);
    }

    public Response deleteTask(String id) throws IOException {
        return send("DELETE", "/api/tasks/" + URLEncoder.encode(id, "UTF-8"), null);
    }

    public Response doneOn(String day) throws IOException {
        return send("GET", "/api/tasks?view=done&day=" + URLEncoder.encode(day, "UTF-8"), null);
    }

    public Response send(String method, String path, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestMethod(method);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Accept", "application/json");
            if (json != null) {
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int code = c.getResponseCode();
            InputStream body = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Response(code, body == null ? "" : readAll(body));
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
