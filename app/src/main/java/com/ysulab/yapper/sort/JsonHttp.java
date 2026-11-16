package com.ysulab.yapper.sort;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** JSON POST over {@link HttpURLConnection}. No dependencies, unit-testable with a local server. */
public final class JsonHttp {

    static final int CONNECT_TIMEOUT_MS = 15_000;
    static final int READ_TIMEOUT_MS = 180_000;

    private JsonHttp() {}

    public static final class Response {
        public final int code;
        public final String body;

        public Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    public static Response post(String url, Map<String, String> headers, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/json");
            for (Map.Entry<String, String> h : headers.entrySet()) {
                c.setRequestProperty(h.getKey(), h.getValue());
            }
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(bytes);
            }
            int code = c.getResponseCode();
            InputStream body = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Response(code, body == null ? "" : readAll(body));
        } finally {
            c.disconnect();
        }
    }

    /** Short, single-line error detail. Never includes request headers. */
    public static String snippet(String body) {
        if (body == null) return "";
        String s = body.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        }
    }
}
