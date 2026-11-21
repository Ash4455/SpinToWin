package com.spintowin.gateway.support;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 測試用的下游：同一個 HTTP server 同時扮演
 * Keycloak 的 JWKS 端點（{@code /certs}）與被路由到的服務（其餘路徑，記下收到的請求並回 200）。
 */
public final class FakeUpstream {

    public static final String JWKS_PATH = "/certs";

    public record ReceivedRequest(String method, String path, String authorization) {
    }

    private final HttpServer server;
    private final List<ReceivedRequest> received = new CopyOnWriteArrayList<>();
    private final List<Headers> receivedHeaders = new CopyOnWriteArrayList<>();

    private FakeUpstream(RSAKey publicKey) throws IOException {
        String jwks = new JWKSet(publicKey).toString();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (JWKS_PATH.equals(exchange.getRequestURI().getPath())) {
                respond(exchange, jwks);
                return;
            }
            received.add(new ReceivedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization")));
            receivedHeaders.add(exchange.getRequestHeaders());
            respond(exchange, "{\"servedBy\":\"upstream\"}");
        });
        server.start();
    }

    public static FakeUpstream start(RSAKey publicKey) {
        try {
            return new FakeUpstream(publicKey);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<ReceivedRequest> received() {
        return List.copyOf(received);
    }

    /** 每個收到的請求中該 header 的值（依收到順序；沒有該 header 時為 null）。 */
    public List<String> receivedHeader(String name) {
        return receivedHeaders.stream().map(h -> h.getFirst(name)).toList();
    }

    public void reset() {
        received.clear();
        receivedHeaders.clear();
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
