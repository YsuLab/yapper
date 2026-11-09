package com.ysulab.yapper.transcribe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Minimal local HTTP/1.1 server on a plain {@link ServerSocket} (com.sun.net.httpserver is not on
 * the Android unit-test classpath). Records the last request, returns a canned response, and
 * understands chunked request bodies, which our client sends.
 */
public final class FakeServer implements AutoCloseable {

    private final ServerSocket socket;
    private final Thread thread;
    public volatile String path;
    public volatile String auth;
    public volatile String xiKey;
    public volatile String contentType;
    public volatile String body;
    public volatile int status = 200;
    public volatile String response = "";

    public FakeServer() throws IOException {
        socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        thread = new Thread(this::serve, "fake-http");
        thread.setDaemon(true);
        thread.start();
    }

    private void serve() {
        while (!socket.isClosed()) {
            try (Socket s = socket.accept()) {
                handle(s);
            } catch (IOException e) {
                // closed or client gone
            }
        }
    }

    private void handle(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        String requestLine = readLine(in);
        if (requestLine == null) return;
        path = requestLine.split(" ")[1];
        auth = null;
        xiKey = null;
        contentType = null;
        boolean chunked = false;
        int length = 0;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int i = line.indexOf(':');
            String name = line.substring(0, i).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(i + 1).trim();
            switch (name) {
                case "authorization": auth = value; break;
                case "xi-api-key": xiKey = value; break;
                case "content-type": contentType = value; break;
                case "content-length": length = Integer.parseInt(value); break;
                case "transfer-encoding": chunked = value.equalsIgnoreCase("chunked"); break;
                default: break;
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (chunked) {
            while (true) {
                int size = Integer.parseInt(readLine(in).split(";")[0].trim(), 16);
                if (size == 0) {
                    readLine(in);
                    break;
                }
                bos.write(readN(in, size));
                readLine(in);
            }
        } else if (length > 0) {
            bos.write(readN(in, length));
        }
        body = bos.toString("UTF-8");

        byte[] out = response.getBytes(StandardCharsets.UTF_8);
        OutputStream os = s.getOutputStream();
        os.write(("HTTP/1.1 " + status + " X\r\nContent-Length: " + out.length
                + "\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        os.write(out);
        os.flush();
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new IOException("eof");
            off += r;
        }
        return b;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        if (c < 0 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    public String url() {
        return "http://127.0.0.1:" + socket.getLocalPort() + "/v1";
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
