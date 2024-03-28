package com.wokflow.api.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Minimal JSON-over-HTTP helper for black-box tests against the running server. */
public class Http {
    public record Res(int status, JsonNode body, Map<String, java.util.List<String>> headers) {
        public String header(String name) {
            var v = headers.get(name.toLowerCase());
            return v == null || v.isEmpty() ? null : v.getFirst();
        }

        public String code() { return body == null || body.get("code") == null ? null : body.get("code").asText(); }

        @Override
        public String toString() { return status + " " + body; }
    }

    public static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final String base;

    public Http(int port) { this.base = "http://localhost:" + port; }

    public String base() { return base; }

    public Res get(String path, String token) { return send("GET", path, token, null, Map.of()); }

    public Res post(String path, String token, Object body) { return send("POST", path, token, body, Map.of()); }

    public Res post(String path, String token, Object body, Map<String, String> headers) {
        return send("POST", path, token, body, headers);
    }

    public Res patch(String path, String token, Object body) { return send("PATCH", path, token, body, Map.of()); }

    public Res raw(String method, String path, byte[] body, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(b::header);
        try {
            HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node = r.body() == null || r.body().isBlank() ? null : JSON.readTree(r.body());
            Map<String, java.util.List<String>> h = new LinkedHashMap<>();
            r.headers().map().forEach((k, v) -> h.put(k.toLowerCase(), v));
            return new Res(r.statusCode(), node, h);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Res send(String method, String path, String token, Object body, Map<String, String> extra) {
        Map<String, String> h = new LinkedHashMap<>(extra);
        h.putIfAbsent("Content-Type", "application/json");
        if (token != null) {
            h.put("Authorization", "Bearer " + token);
        }
        return raw(method, path, body == null ? null : JSON.writeValueAsBytes(body), h);
    }
}
