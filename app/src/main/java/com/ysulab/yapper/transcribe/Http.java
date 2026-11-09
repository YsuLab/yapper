package com.ysulab.yapper.transcribe;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Tiny multipart/form-data POST over {@link HttpURLConnection}. No dependencies, unit-testable. */
final class Http {

    static final int CONNECT_TIMEOUT_MS = 15_000;
    static final int READ_TIMEOUT_MS = 120_000;

    private Http() {}

    static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    static final class Multipart {
        final Map<String, String> fields = new LinkedHashMap<>();
        String fileField = "file";
        File file;
        String fileMime = "application/octet-stream";
    }

    static Response postMultipart(String url, Map<String, String> headers, Multipart form)
            throws IOException {
        String boundary = "yapper-" + UUID.randomUUID();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            c.setRequestProperty("Accept", "application/json, text/plain");
            for (Map.Entry<String, String> h : headers.entrySet()) {
                c.setRequestProperty(h.getKey(), h.getValue());
            }
            c.setChunkedStreamingMode(64 * 1024);
            try (OutputStream out = c.getOutputStream()) {
                for (Map.Entry<String, String> f : form.fields.entrySet()) {
                    write(out, "--" + boundary + "\r\n");
                    write(out, "Content-Disposition: form-data; name=\"" + f.getKey() + "\"\r\n\r\n");
                    write(out, f.getValue() + "\r\n");
                }
                if (form.file != null) {
                    write(out, "--" + boundary + "\r\n");
                    write(out, "Content-Disposition: form-data; name=\"" + form.fileField
                            + "\"; filename=\"" + form.file.getName() + "\"\r\n");
                    write(out, "Content-Type: " + form.fileMime + "\r\n\r\n");
                    try (InputStream in = new FileInputStream(form.file)) {
                        byte[] buf = new byte[16 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                    }
                    write(out, "\r\n");
                }
                write(out, "--" + boundary + "--\r\n");
            }
            int code;
            try {
                code = c.getResponseCode();
            } catch (java.net.HttpRetryException e) {
                // Streaming mode can't replay the body for an auth challenge: report the status.
                return new Response(e.responseCode(), "");
            }
            InputStream body = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Response(code, body == null ? "" : readAll(body));
        } finally {
            c.disconnect();
        }
    }

    static String mimeFor(File f) {
        String n = f.getName().toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".ogg") || n.endsWith(".opus")) return "audio/ogg";
        if (n.endsWith(".m4a") || n.endsWith(".mp4")) return "audio/mp4";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        return "application/octet-stream";
    }

    /** Short, single-line error detail for notes and logs. Never includes request headers. */
    static String snippet(String body) {
        if (body == null) return "";
        String s = body.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static void write(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
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
