package com.gakiji.aiaccount;

import org.json.JSONObject;
import org.junit.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class LanMcpServerTest {
    @Test public void authenticatedClientCanDiscoverAndCallReadOnlyTool() throws Exception {
        var captured = new AtomicReference<McpProtocol.Query>();
        try (var server = new LanMcpServer(InetAddress.getByName("127.0.0.1"), 0,
                InetAddress::isLoopbackAddress, query -> {
                    captured.set(query);
                    return new JSONObject().put("net_expense_cents", 1234);
                })) {
            var url = URI.create(server.url());
            String init = """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}
                    """;
            assertEquals(401, post(url, null, null, init).statusCode());
            assertEquals(403, post(url, server.token(), "http://other.example", init).statusCode());
            var initialized = post(url, server.token(), null, init);
            assertEquals(200, initialized.statusCode());
            assertEquals("2025-11-25", new JSONObject(initialized.body())
                    .getJSONObject("result").getString("protocolVersion"));
            var list = post(url, server.token(), null, """
                    {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                    """);
            assertTrue(list.body().contains("ledger_statistics"));
            assertTrue(list.body().contains("readOnlyHint"));
            var notice = post(url, server.token(), null, """
                    {"jsonrpc":"2.0","method":"notifications/initialized"}
                    """);
            assertEquals(202, notice.statusCode());
            assertEquals("", notice.body());
            var report = post(url, server.token(), null, """
                    {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ledger_statistics",
                     "arguments":{"from_date":"2026-11-01","to_date":"2026-11-30","group_by":"source"}}}
                    """);
            assertEquals(200, report.statusCode());
            assertTrue(report.body().contains("1234"));
            assertEquals(new McpProtocol.Query(LocalDate.of(2026, 11, 1),
                    LocalDate.of(2026, 11, 30), "source"), captured.get());
        }
    }

    @Test public void peerOutsideWifiSubnetGetsExplicitForbidden() throws Exception {
        try (var server = new LanMcpServer(InetAddress.getByName("127.0.0.1"), 0,
                address -> false, query -> new JSONObject())) {
            var reply = post(URI.create(server.url()), server.token(), null, """
                    {"jsonrpc":"2.0","id":1,"method":"initialize"}
                    """);
            assertEquals(403, reply.statusCode());
            assertTrue(reply.body().contains("同一网段"));
        }
    }

    @Test public void invalidQueriesNeverReachLedger() throws Exception {
        String call = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"ledger_statistics",
                 "arguments":{"from_date":"2022-01-01","to_date":"2026-11-30"}}}
                """;
        var reply = McpProtocol.handle(call, query -> { throw new AssertionError("Unexpected database query"); });
        assertTrue(reply.body().getJSONObject("result").getBoolean("isError"));
        assertEquals(LocalDate.of(2026, 11, 15),
                LedgerStatistics.ledgerDate("2026年11月15日 13:40:50", 0, ZoneId.of("Asia/Shanghai")));
        assertEquals(LocalDate.of(2026, 11, 15),
                LedgerStatistics.ledgerDate("2026-11-15 13:40:50", 0, ZoneId.of("Asia/Shanghai")));
    }

    private record Response(int statusCode, String body) {}

    private static Response post(URI url, String token, String origin, String body) throws Exception {
        try (var socket = new Socket(url.getHost(), url.getPort())) {
            socket.setSoTimeout(5000);
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            String headers = "POST /mcp HTTP/1.1\r\nHost: " + url.getAuthority()
                    + "\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream"
                    + "\r\nContent-Length: " + data.length + "\r\n";
            if (token != null) headers += "Authorization: Bearer " + token + "\r\n";
            if (origin != null) headers += "Origin: " + origin + "\r\n";
            socket.getOutputStream().write((headers + "\r\n" + body).getBytes(StandardCharsets.UTF_8));
            var incoming = new java.io.DataInputStream(new java.io.BufferedInputStream(socket.getInputStream()));
            int status = Integer.parseInt(incoming.readLine().substring(9, 12));
            int length = 0;
            for (String line; !(line = incoming.readLine()).isEmpty();) {
                if (line.startsWith("Content-Length: ")) length = Integer.parseInt(line.substring(16));
            }
            byte[] payload = new byte[length];
            incoming.readFully(payload);
            return new Response(status, new String(payload, StandardCharsets.UTF_8));
        }
    }
}
