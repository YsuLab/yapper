package com.ysulab.yapper.reminders;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * One HTTP request. An interface so the Microsoft clients are unit-testable with a lambda.
 * The real implementation is {@link #DEFAULT} (HttpURLConnection, no dependencies).
 */
public interface WebCall {

    final class Response {
        public final int code;
        public final String body;

        public Response(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }

        public boolean ok() {
            return code / 100 == 2;
        }
    }

    /** {@code body} may be null (GET, DELETE). Headers include Content-Type when there is a body. */
    Response send(String method, String url, Map<String, String> headers, String body) throws IOException;

    WebCall DEFAULT = (method, url, headers, body) -> {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15_000);
            c.setReadTimeout(30_000);
            c.setRequestMethod(method);
            for (Map.Entry<String, String> h : headers.entrySet()) {
                c.setRequestProperty(h.getKey(), h.getValue());
            }
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) {
                return new Response(code, "");
            }
            try (InputStream is = in) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                return new Response(code, bos.toString("UTF-8"));
            }
        } finally {
            c.disconnect();
        }
    };

    /** Short, single-line error detail for notifications. Never includes request headers. */
    static String snippet(String body) {
        if (body == null) return "";
        String s = body.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
