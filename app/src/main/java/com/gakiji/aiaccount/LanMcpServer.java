package com.gakiji.aiaccount;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.function.Predicate;

final class LanMcpServer implements AutoCloseable {
    private final ServerSocket listener;
    private final String host;
    private final String token;
    private final Predicate<InetAddress> allowedPeer;
    private final McpProtocol.Statistics statistics;
    private Socket client;

    LanMcpServer(InetAddress address, int port, Predicate<InetAddress> allowedPeer,
                 McpProtocol.Statistics statistics) throws IOException {
        var random = new byte[32];
        new SecureRandom().nextBytes(random);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        this.allowedPeer = allowedPeer;
        this.statistics = statistics;
        listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(address, port), 4);
        } catch (IOException bindFailed) {
            listener.close();
            throw bindFailed;
        }
        host = address.getHostAddress() + ":" + listener.getLocalPort();
        Thread worker = new Thread(this::listen, "aiaccount-lan-mcp");
        worker.setDaemon(true);
        worker.start();
    }

    String url() { return "http://" + host + "/mcp"; }
    String token() { return token; }

    private void listen() {
        while (!listener.isClosed()) {
            try (Socket socket = listener.accept()) {
                synchronized (this) {
                    if (listener.isClosed()) return;
                    client = socket;
                }
                if (allowedPeer.test(socket.getInetAddress())) handle(socket);
                else deny(socket);
            } catch (Exception ignored) {
                // A failed or interrupted request must not end the listening session.
            } finally {
                synchronized (this) { client = null; }
            }
        }
    }

    /** 来源不在手机 Wi-Fi 网段时回一个能看懂原因的状态，避免客户端只看到连接被掐断。 */
    private static void deny(Socket socket) throws IOException {
        byte[] body = ("此连接来自 " + socket.getInetAddress().getHostAddress()
                + "，不在手机 Wi-Fi 同一网段。请让电脑与手机连接同一个 Wi-Fi，"
                + "并关闭电脑上的代理或 VPN 后重试。\n").getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 403 Forbidden\r\nConnection: close\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\nContent-Length: " + body.length + "\r\n\r\n";
        var out = socket.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private void handle(Socket socket) throws Exception {
        socket.setSoTimeout(2000);
        InputStream in = new BufferedInputStream(socket.getInputStream());
        long deadline = System.nanoTime() + 4_000_000_000L;
        int[] remaining = {8192};
        String[] request = line(in, remaining, deadline).split(" ", -1);
        if (request.length != 3 || !request[2].equals("HTTP/1.1")) {
            send(socket, 400, null);
            return;
        }
        var headers = new HashMap<String, String>();
        for (String header; !(header = line(in, remaining, deadline)).isEmpty();) {
            int colon = header.indexOf(':');
            if (colon <= 0 || !header.substring(0, colon).matches("[A-Za-z0-9-]+")
                    || headers.putIfAbsent(header.substring(0, colon).toLowerCase(Locale.ROOT),
                    header.substring(colon + 1).strip()) != null) {
                send(socket, 400, null);
                return;
            }
        }
        if (headers.containsKey("origin") || !host.equals(headers.get("host"))) {
            send(socket, 403, null);
            return;
        }
        if (!"/mcp".equals(request[1])) {
            send(socket, 404, null);
            return;
        }
        if (!MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.US_ASCII),
                headers.getOrDefault("authorization", "").getBytes(StandardCharsets.US_ASCII))) {
            send(socket, 401, null);
            return;
        }
        if (!"POST".equals(request[0])) {
            send(socket, 405, null);
            return;
        }
        String version = headers.get("mcp-protocol-version");
        if (version != null && !McpProtocol.VERSIONS.contains(version)) {
            send(socket, 400, null);
            return;
        }
        String media = headers.getOrDefault("content-type", "").split(";", 2)[0].strip();
        if (!"application/json".equalsIgnoreCase(media)) {
            send(socket, 415, null);
            return;
        }
        String length = headers.getOrDefault("content-length", "");
        if (headers.containsKey("transfer-encoding") || !length.matches("[0-9]{1,5}")) {
            send(socket, 400, null);
            return;
        }
        int size = Integer.parseInt(length);
        if (size > 16384) {
            send(socket, 413, null);
            return;
        }
        byte[] body = new byte[size];
        for (int read = 0; read < size;) {
            if (System.nanoTime() > deadline) throw new IOException("Read timeout");
            int count = in.read(body, read, size - read);
            if (count < 0) throw new IOException("Incomplete body");
            read += count;
        }
        var result = McpProtocol.handle(new String(body, StandardCharsets.UTF_8), statistics);
        send(socket, result.status(), result.body() == null ? null : result.body().toString());
    }

    private static String line(InputStream in, int[] remaining, long deadline) throws IOException {
        var text = new StringBuilder();
        while (--remaining[0] >= 0 && System.nanoTime() < deadline) {
            int next = in.read();
            if (next == '\r') {
                if (--remaining[0] >= 0 && in.read() == '\n') return text.toString();
                break;
            }
            if (next < 32 || next > 126) break;
            text.append((char) next);
        }
        throw new IOException("Invalid header");
    }

    private static void send(Socket socket, int status, String json) throws IOException {
        byte[] body = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
        String reason = switch (status) {
            case 200 -> "OK";
            case 202 -> "Accepted";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 413 -> "Content Too Large";
            case 415 -> "Unsupported Media Type";
            default -> "Bad Request";
        };
        String response = "HTTP/1.1 " + status + " " + reason + "\r\nConnection: close\r\n"
                + "Cache-Control: no-store\r\nContent-Length: " + body.length + "\r\n"
                + (status == 405 ? "Allow: POST\r\n" : "")
                + (status == 401 ? "WWW-Authenticate: Bearer\r\n" : "")
                + (json == null ? "" : "Content-Type: application/json; charset=utf-8\r\n") + "\r\n";
        var out = socket.getOutputStream();
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    @Override public synchronized void close() {
        try { listener.close(); } catch (IOException ignored) {}
        if (client != null) try { client.close(); } catch (IOException ignored) {}
    }
}
