package com.ysulab.yapper.transcribe;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** Local HTTP server that records one request and returns a canned response. */
final class FakeServer implements AutoCloseable {

    final HttpServer server;
    volatile String path;
    volatile String auth;
    volatile String xiKey;
    volatile String contentType;
    volatile String body;
    int status = 200;
    String response = "";

    FakeServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            path = ex.getRequestURI().getPath();
            auth = ex.getRequestHeaders().getFirst("Authorization");
            xiKey = ex.getRequestHeaders().getFirst("xi-api-key");
            contentType = ex.getRequestHeaders().getFirst("Content-Type");
            try (InputStream in = ex.getRequestBody()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                body = bos.toString("UTF-8");
            }
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(out);
                }
            }
            ex.close();
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
