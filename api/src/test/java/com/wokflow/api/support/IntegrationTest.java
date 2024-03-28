package com.wokflow.api.support;

import com.wokflow.api.WokflowApiApplication;
import com.wokflow.api.outbox.OutboxPublisher;
import com.wokflow.api.payment.PaymentProvider;
import com.wokflow.api.payment.WebhookVerifier;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Boots the real application against real Postgres, Redis and Elasticsearch
 * containers (started once for the whole test run) and talks to it over HTTP.
 */
@SpringBootTest(classes = {WokflowApiApplication.class, IntegrationTest.TestBeans.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"wokflow.rate-limit.enabled=false", "spring.main.allow-bean-definition-overriding=true"})
public abstract class IntegrationTest {
    public static final String WEBHOOK_SECRET = "whsec_test";
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.7-alpine");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.8.4-alpine").withExposedPorts(6379);
    static final GenericContainer<?> ES = new GenericContainer<>("elasticsearch:9.5.5")
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("cluster.routing.allocation.disk.threshold_enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/_cluster/health?wait_for_status=yellow").forPort(9200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    static {
        List.of(POSTGRES, REDIS, ES).parallelStream().forEach(GenericContainer::start);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("spring.elasticsearch.uris", () -> "http://" + ES.getHost() + ":" + ES.getMappedPort(9200));
        r.add("wokflow.payments.webhook-secret", () -> WEBHOOK_SECRET);
        r.add("spring.datasource.hikari.maximum-pool-size", () -> 30);
    }

    @TestConfiguration
    public static class TestBeans {
        @Bean
        @Primary
        public MutableClock clock() { return new MutableClock(); }

        @Bean
        @Primary
        public FakePaymentProvider fakePaymentProvider() { return new FakePaymentProvider(); }

        @Bean
        public FlakyHandler flakyHandler() { return new FlakyHandler(); }
    }

    /** Fails a configurable number of times, then succeeds: exercises outbox retries. */
    public static class FlakyHandler implements com.wokflow.api.outbox.OutboxHandler {
        public final AtomicInteger failuresLeft = new AtomicInteger();
        public final java.util.List<String> handled = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public java.util.Set<String> topics() { return java.util.Set.of("test.flaky"); }

        @Override
        public void handle(String topic, String aggregateId, tools.jackson.databind.JsonNode payload) {
            if (failuresLeft.getAndDecrement() > 0) {
                throw new IllegalStateException("flaky (test)");
            }
            handled.add(aggregateId);
        }
    }

    private static final AtomicInteger SEQ = new AtomicInteger();

    @LocalServerPort
    protected int port;
    @Autowired
    protected JdbcClient db;
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected FakePaymentProvider provider;
    @Autowired
    protected OutboxPublisher outbox;

    protected Http http;

    @BeforeEach
    void setUpHttp() {
        http = new Http(port);
    }

    @AfterEach
    void resetClockAndProvider() {
        clock.reset();
        provider.failIntents.set(false);
        provider.failRefunds.set(false);
    }

    // ------------------------------------------------------------------ helpers

    protected static String unique(String prefix) {
        return prefix + System.nanoTime() + "-" + SEQ.incrementAndGet();
    }

    protected String signIn(String email) {
        var r = http.post("/api/auth/signin", null, Map.of("email", email, "password", "wokflow-demo"));
        if (r.status() != 200) {
            throw new AssertionError("sign in failed: " + r);
        }
        return r.body().get("accessToken").asText();
    }

    /** A fresh customer account (so tests do not share order history). */
    protected String newCustomer() {
        String email = unique("c") + "@example.com";
        var r = http.post("/api/auth/signup", null, Map.of("email", email, "password", "correct-horse-1", "name", "Test Customer"));
        if (r.status() != 201) {
            throw new AssertionError("sign up failed: " + r);
        }
        return r.body().get("accessToken").asText();
    }

    @Autowired
    protected com.wokflow.api.auth.TokenService tokenService;

    /** Many customers quickly: rows copied in SQL, tokens issued directly (no password hashing per user). */
    protected List<String> customers(int n) {
        String prefix = unique("bulk");
        db.sql("""
                insert into users (email, password_hash, display_name, role)
                select ? || g || '@example.com', (select password_hash from users where email = 'customer@example.com'),
                       'Bulk ' || g, 'CUSTOMER' from generate_series(1, ?) g""").params(prefix, n).update();
        return db.sql("select id, email, display_name from users where email like ? order by id").param(prefix + "%")
                .query((rs, i) -> new com.wokflow.api.auth.UserRepository.User(rs.getLong(1), rs.getString(2), "",
                        rs.getString(3), com.wokflow.api.auth.Role.CUSTOMER))
                .list().stream().map(u -> tokenService.issue(u, List.of()).value()).toList();
    }

    protected long deadlocks() {
        try {
            Thread.sleep(1200); // cumulative statistics are flushed about once a second
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return db.sql("select deadlocks from pg_stat_database where datname = current_database()").query(Long.class).single();
    }

    protected long restaurantId(String slug) {
        return db.sql("select id from restaurants where slug = ?").param(slug).query(Long.class).single();
    }

    protected long dishId(String restaurantSlug, String dishName) {
        return db.sql("select d.id from dishes d join restaurants r on r.id = d.restaurant_id where r.slug = ? and d.name = ?")
                .params(restaurantSlug, dishName).query(Long.class).single();
    }

    /** A 24h test restaurant with its own merchant account, isolated from other tests. */
    protected record TestKitchen(long id, String slug, String merchantEmail, long plainDish, long limitedDish) {}

    protected TestKitchen newKitchen(int limitedStock) {
        String slug = unique("kitchen-");
        String merchant = unique("m") + "@example.com";
        db.sql("insert into users (email, password_hash, display_name, role) select ?, password_hash, 'Test Merchant', 'MERCHANT' from users where email = 'merchant@example.com'")
                .param(merchant).update();
        long rid = db.sql("""
                insert into restaurants (slug, name, name_zh, cuisine, neighborhood, address, delivery_fee_cents, min_order_cents,
                                         opens_at, closes_at, open_24h)
                values (?, ?, '测试厨房', 'test', 'Testville', '1 Test Way', 300, 0, '00:00', '00:00', true) returning id""")
                .params(slug, "Kitchen " + slug).query(Long.class).single();
        db.sql("insert into restaurant_staff (restaurant_id, user_id) select ?, id from users where email = ?").params(rid, merchant).update();
        db.sql("insert into ledger_accounts (code, kind, name, restaurant_id) values (?, 'LIABILITY', 'test', ?)")
                .params("merchant_payable:" + rid, rid).update();
        long cat = db.sql("insert into menu_categories (restaurant_id, name, name_zh, position) values (?, 'Mains', '主食', 0) returning id")
                .param(rid).query(Long.class).single();
        long plain = db.sql("insert into dishes (restaurant_id, category_id, name, name_zh, price_cents) values (?, ?, 'Fried Rice', '炒饭', 1000) returning id")
                .params(rid, cat).query(Long.class).single();
        long limited = db.sql("""
                insert into dishes (restaurant_id, category_id, name, name_zh, price_cents, daily_stock, stock_remaining, stock_day)
                values (?, ?, 'Soup Dumplings', '小笼包', 1500, ?, ?, current_date) returning id""")
                .params(rid, cat, limitedStock, limitedStock).query(Long.class).single();
        return new TestKitchen(rid, slug, merchant, plain, limited);
    }

    protected Http.Res placeOrder(String token, long restaurantId, Map<Long, Integer> items, String key) {
        var lines = items.entrySet().stream().map(e -> Map.of("dishId", e.getKey(), "quantity", e.getValue())).toList();
        return http.post("/api/orders", token,
                Map.of("restaurantId", restaurantId, "items", lines, "deliveryAddress", "1 Test Street"),
                Map.of("Idempotency-Key", key));
    }

    protected UUID placeOrder(String token, long restaurantId, Map<Long, Integer> items) {
        var r = placeOrder(token, restaurantId, items, unique("key-"));
        if (r.status() != 201) {
            throw new AssertionError("place order failed: " + r);
        }
        return UUID.fromString(r.body().get("id").asText());
    }

    protected Http.Res webhook(String eventId, String type, UUID orderId) {
        return webhook(eventId, type, FakePaymentProvider.intentId(orderId), clock.instant().getEpochSecond(), WEBHOOK_SECRET);
    }

    protected Http.Res webhook(String eventId, String type, String intentId, long timestamp, String secret) {
        String body = "{\"id\":\"" + eventId + "\",\"type\":\"" + type + "\",\"data\":{\"paymentIntent\":\"" + intentId + "\"}}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return http.raw("POST", "/api/payments/webhook", bytes, Map.of("Content-Type", "application/json",
                "MockPay-Signature", WebhookVerifier.header(secret, timestamp, bytes)));
    }

    protected Http.Res pay(UUID orderId) {
        return webhook(unique("evt_"), "payment_intent.succeeded", orderId);
    }

    protected String status(UUID orderId) {
        return db.sql("select status from orders where id = ?").param(orderId).query(String.class).single();
    }

    protected Clock systemClock() { return Clock.systemUTC(); }
}
