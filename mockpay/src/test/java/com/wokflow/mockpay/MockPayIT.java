package com.wokflow.mockpay;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MockPayIT {
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:18.7-alpine");
    static final HttpServer RECEIVER;
    static final List<String[]> RECEIVED = new CopyOnWriteArrayList<>(); // {signature, body}
    static final AtomicInteger FAIL_NEXT = new AtomicInteger();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    static {
        PG.start();
        try {
            RECEIVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            RECEIVER.createContext("/hook", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                int status = FAIL_NEXT.getAndDecrement() > 0 ? 503 : 200;
                if (status == 200) {
                    RECEIVED.add(new String[] {ex.getRequestHeaders().getFirst("MockPay-Signature"), body});
                }
                ex.sendResponseHeaders(status, -1);
                ex.close();
            });
            RECEIVER.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            RECEIVER.start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterAll
    static void stop() { RECEIVER.stop(0); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("mockpay.webhook-url", () -> "http://127.0.0.1:" + RECEIVER.getAddress().getPort() + "/hook");
        r.add("mockpay.allowed-return-origins", () -> "https://shop.test");
        r.add("mockpay.secret-key", () -> "sk_it");
        r.add("mockpay.webhook-secret", () -> "whsec_it");
    }

    @LocalServerPort
    int port;

    record Res(int status, JsonNode body, HttpResponse<String> raw) {}

    Res call(String method, String path, String json, String key, String auth) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        if (key != null) b.header("Idempotency-Key", key);
        var r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode body = r.body().startsWith("{") ? JSON.readTree(r.body()) : null;
        return new Res(r.statusCode(), body, r);
    }

    Res createIntent(String key, int amount, String returnUrl) throws Exception {
        return call("POST", "/v1/payment_intents", """
                {"amount": %d, "currency": "usd", "description": "Order", "reference": "ref-%s", "returnUrl": "%s"}"""
                .formatted(amount, key, returnUrl), key, "Bearer sk_it");
    }

    static void await(java.util.function.BooleanSupplier ok) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!ok.getAsBoolean() && System.nanoTime() < end) Thread.sleep(50);
    }

    @Test
    void intentsAreIdempotentAuthenticatedAndOnlyReturnToAllowedOrigins() throws Exception {
        String key = UUID.randomUUID().toString();
        var a = createIntent(key, 1234, "https://shop.test/orders/1");
        var b = createIntent(key, 1234, "https://shop.test/orders/1");
        assertThat(a.status()).isEqualTo(200);
        assertThat(b.body().get("id")).isEqualTo(a.body().get("id"));
        assertThat(a.body().get("checkoutUrl").asText()).endsWith("/checkout/" + a.body().get("id").asText());
        assertThat(createIntent(key, 999, "https://shop.test/orders/1").status()).isEqualTo(422);
        assertThat(createIntent(UUID.randomUUID().toString(), 100, "https://evil.test/steal").status()).isEqualTo(400);
        assertThat(createIntent(UUID.randomUUID().toString(), 100, "https://shop.test.evil.test/x").status()).isEqualTo(400);
        assertThat(call("POST", "/v1/payment_intents", "{}", "k", "Bearer wrong").status()).isEqualTo(401);
        assertThat(call("POST", "/v1/payment_intents", "{}", null, "Bearer sk_it").status()).isEqualTo(400);
    }

    @Test
    void hostedCheckoutConfirmsOnceRedirectsAndDeliversASignedWebhook() throws Exception {
        var intent = createIntent(UUID.randomUUID().toString(), 2500, "https://shop.test/orders/42").body();
        String id = intent.get("id").asText();
        var page = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/checkout/" + id)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(page.body()).contains("Pay $25.00").contains("Sandbox");
        assertThat(page.headers().firstValue("Content-Security-Policy").orElseThrow()).contains("frame-ancestors 'none'");
        String token = page.body().replaceAll("(?s).*name=\"token\" value=\"([0-9a-f]+)\".*", "$1");

        var forged = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/checkout/" + id + "/confirm"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("outcome=succeed&token=00")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(forged.statusCode()).isEqualTo(403);

        FAIL_NEXT.set(2); // the receiver is down for the first two deliveries
        int before = RECEIVED.size();
        var ok = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/checkout/" + id + "/confirm"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("outcome=succeed&token=" + token)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(ok.statusCode()).isEqualTo(303);
        assertThat(ok.headers().firstValue("Location").orElseThrow()).isEqualTo("https://shop.test/orders/42?payment=succeeded");
        // a second click does not flip the outcome or send another event
        assertThat(call("POST", "/v1/payment_intents/" + id + "/confirm", "{\"outcome\":\"decline\"}", null, "Bearer sk_it")
                .body().get("status").asText()).isEqualTo("succeeded");

        await(() -> RECEIVED.size() > before);
        String[] got = RECEIVED.get(before);
        JsonNode event = JSON.readTree(got[1]);
        assertThat(event.get("type").asText()).isEqualTo("payment_intent.succeeded");
        assertThat(event.get("data").get("paymentIntent").asText()).isEqualTo(id);
        long t = Long.parseLong(got[0].substring(2, got[0].indexOf(',')));
        assertThat(got[0]).isEqualTo(Signatures.header("whsec_it", t, got[1]));
        Thread.sleep(500);
        assertThat(RECEIVED.stream().filter(r -> r[1].contains(id)).count()).isEqualTo(1);
    }

    @Test
    void concurrentRefundsNeverExceedTheCapturedAmount() throws Exception {
        String id = createIntent(UUID.randomUUID().toString(), 1000, "https://shop.test/o").body().get("id").asText();
        assertThat(call("POST", "/v1/refunds", "{\"paymentIntent\":\"" + id + "\",\"amount\":100}", "r0", "Bearer sk_it").status())
                .isEqualTo(409); // not paid yet
        call("POST", "/v1/payment_intents/" + id + "/confirm", "{\"outcome\":\"succeed\"}", null, "Bearer sk_it");
        List<Integer> statuses = new CopyOnWriteArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                String key = id + "-r" + i;
                fs.add(pool.submit(() -> {
                    statuses.add(call("POST", "/v1/refunds", "{\"paymentIntent\":\"" + id + "\",\"amount\":300}", key, "Bearer sk_it").status());
                    return null;
                }));
            }
            for (var f : fs) f.get();
        }
        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(3);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(7);
        // replaying a refund key returns the same refund instead of refunding again
        var again = call("POST", "/v1/refunds", "{\"paymentIntent\":\"" + id + "\",\"amount\":300}", id + "-r0", "Bearer sk_it");
        assertThat(again.status()).isIn(200, 409);
        assertThat(call("GET", "/v1/payment_intents/" + id, null, null, "Bearer sk_it").body().get("refunded").asInt()).isEqualTo(900);
    }
}
