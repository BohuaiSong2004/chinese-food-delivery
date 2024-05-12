package com.wokflow.api.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.wokflow.api.support.IntegrationTest;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class LiveIT extends IntegrationTest {

    /** Collects SSE "event: name" + "data:" pairs (and the last event id) in a background thread. */
    record Stream(List<String> events, Thread reader, java.util.concurrent.atomic.AtomicReference<String> lastId,
                  java.io.InputStream body) {
        List<String> statuses() { return events.stream().filter(e -> e.startsWith("status:")).map(e -> e.substring(7)).toList(); }

        void close() throws Exception {
            body.close();
            reader.join(2000);
        }
    }

    Stream open(String path, String token) throws Exception {
        return open(path, token, null);
    }

    Stream open(String path, String token, String lastEventId) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(http.base() + path)).header("Authorization", "Bearer " + token)
                .header("Accept", "text/event-stream");
        if (lastEventId != null) {
            b.header("Last-Event-ID", lastEventId);
        }
        HttpResponse<java.io.InputStream> res = HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(res.statusCode()).isEqualTo(200);
        List<String> events = new CopyOnWriteArrayList<>();
        var last = new java.util.concurrent.atomic.AtomicReference<String>();
        Thread t = Thread.ofVirtual().start(() -> {
            try (var in = new BufferedReader(new InputStreamReader(res.body()))) {
                String name = null;
                String id = null;
                for (String line; (line = in.readLine()) != null; ) {
                    if (line.startsWith("id:")) {
                        id = line.substring(3).strip();
                    } else if (line.startsWith("event:")) {
                        name = line.substring(6).strip();
                    } else if (line.startsWith("data:") && name != null) {
                        JsonNode d = com.wokflow.api.support.Http.JSON.readTree(line.substring(5));
                        events.add(name.equals("resync") ? "resync" : name + ":" + d.get("status").asText() + "@" + d.get("version").asInt());
                        if (id != null) {
                            last.set(id);
                        }
                        name = null;
                        id = null;
                    }
                }
            } catch (Exception ignored) {
                // stream closed
            }
        });
        return new Stream(events, t, last, res.body());
    }

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.data.redis.core.StringRedisTemplate redis;

    @Test
    void aReconnectingClientGetsExactlyWhatItMissedFromTheStream() throws Exception {
        var k = newKitchen(10);
        String customer = newCustomer();
        String merchant = signIn(k.merchantEmail());
        UUID id = placeOrder(customer, k.id(), Map.of(k.plainDish(), 1));

        Stream first = open("/api/orders/" + id + "/events", customer);
        await(() -> first.events().contains("snapshot:PENDING_PAYMENT@1"));
        pay(id);
        await(() -> first.statuses().contains("PAID@2"));
        String seen = first.lastId().get();
        assertThat(seen).matches("\\d+-\\d+");
        first.close();

        // while disconnected
        http.post("/api/merchant/orders/" + id + "/accept", merchant, null);
        http.post("/api/merchant/orders/" + id + "/ready", merchant, null);
        Thread.sleep(500); // let the outbox publish

        Stream resumed = open("/api/orders/" + id + "/events", customer, seen);
        await(() -> resumed.statuses().contains("READY@4"));
        assertThat(resumed.events()).containsExactly("status:ACCEPTED@3", "status:READY@4"); // no snapshot, nothing twice
        resumed.close();

        // a position that has been trimmed away: the order stream falls back to a snapshot, a list stream to "resync"
        redis.opsForStream().trim(LiveHub.STREAM, 0);
        http.post("/api/rider/orders/" + id + "/claim", signIn("rider@example.com"), null);
        Thread.sleep(500);
        Stream stale = open("/api/orders/" + id + "/events", customer, "1-0");
        await(() -> !stale.events().isEmpty());
        assertThat(stale.events().getFirst()).isEqualTo("snapshot:READY@5");
        Stream board = open("/api/merchant/restaurants/" + k.id() + "/events", merchant, "1-0");
        await(() -> board.events().contains("resync"));
        // a malformed id is treated as none
        Stream garbage = open("/api/orders/" + id + "/events", customer, "5");
        await(() -> !garbage.events().isEmpty());
        assertThat(garbage.events().getFirst()).startsWith("snapshot:");
        assertThat(LiveHub.compare("1700000000000-5", "1700000000000-12")).isNegative();
        assertThat(LiveHub.compare("1700000000001-0", "1700000000000-99")).isPositive();
    }

    static void await(java.util.function.BooleanSupplier ok) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!ok.getAsBoolean() && System.nanoTime() < end) {
            Thread.sleep(50);
        }
    }

    @Test
    void customerAndKitchenSeeEachStatusChangeLiveAndInOrder() throws Exception {
        var k = newKitchen(10);
        String customer = newCustomer();
        String merchant = signIn(k.merchantEmail());
        UUID id = placeOrder(customer, k.id(), Map.of(k.plainDish(), 1));

        Stream order = open("/api/orders/" + id + "/events", customer);
        Stream board = open("/api/merchant/restaurants/" + k.id() + "/events", merchant);
        await(() -> order.events().stream().anyMatch(e -> e.startsWith("snapshot:")));
        assertThat(order.events().getFirst()).isEqualTo("snapshot:PENDING_PAYMENT@1");

        pay(id);
        http.post("/api/merchant/orders/" + id + "/accept", merchant, null);
        http.post("/api/merchant/orders/" + id + "/ready", merchant, null);
        await(() -> order.statuses().contains("READY@4"));

        assertThat(order.statuses()).containsSubsequence("PAID@2", "ACCEPTED@3", "READY@4");
        await(() -> board.statuses().contains("READY@4"));
        assertThat(board.statuses()).containsSubsequence("PAID@2", "ACCEPTED@3", "READY@4");
        // other customers' streams do not receive it
        Stream stranger = open("/api/me/events", newCustomer());
        http.post("/api/rider/orders/" + id + "/claim", signIn("rider@example.com"), null);
        await(() -> order.statuses().contains("READY@5"));
        assertThat(stranger.statuses()).isEmpty();
    }

    @Test
    void streamsRequireAccess() throws Exception {
        var k = newKitchen(10);
        UUID id = placeOrder(newCustomer(), k.id(), Map.of(k.plainDish(), 1));
        HttpRequest req = HttpRequest.newBuilder(URI.create(http.base() + "/api/orders/" + id + "/events"))
                .header("Authorization", "Bearer " + newCustomer()).build();
        assertThat(HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
        HttpRequest anon = HttpRequest.newBuilder(URI.create(http.base() + "/api/orders/" + id + "/events")).build();
        assertThat(HttpClient.newHttpClient().send(anon, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
    }
}
