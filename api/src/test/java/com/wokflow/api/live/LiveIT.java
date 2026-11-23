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

    /** Collects SSE "event: name" + "data:" pairs in a background thread. */
    record Stream(List<String> events, Thread reader) {
        List<String> statuses() { return events.stream().filter(e -> e.startsWith("status:")).map(e -> e.substring(7)).toList(); }
    }

    Stream open(String path, String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(http.base() + path)).header("Authorization", "Bearer " + token)
                .header("Accept", "text/event-stream").build();
        HttpResponse<java.io.InputStream> res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(res.statusCode()).isEqualTo(200);
        List<String> events = new CopyOnWriteArrayList<>();
        Thread t = Thread.ofVirtual().start(() -> {
            try (var in = new BufferedReader(new InputStreamReader(res.body()))) {
                String name = null;
                for (String line; (line = in.readLine()) != null; ) {
                    if (line.startsWith("event:")) {
                        name = line.substring(6).strip();
                    } else if (line.startsWith("data:") && name != null) {
                        JsonNode d = com.wokflow.api.support.Http.JSON.readTree(line.substring(5));
                        events.add(name + ":" + d.get("status").asText() + "@" + d.get("version").asInt());
                        name = null;
                    }
                }
            } catch (Exception ignored) {
                // stream closed
            }
        });
        return new Stream(events, t);
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
