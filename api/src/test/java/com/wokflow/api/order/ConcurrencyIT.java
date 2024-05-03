package com.wokflow.api.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.wokflow.api.ledger.LedgerAudit;
import com.wokflow.api.ledger.LedgerService;
import com.wokflow.api.payment.RefundWorker;
import com.wokflow.api.support.FakePaymentProvider;
import com.wokflow.api.support.Http;
import com.wokflow.api.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The "peak hour" guarantees: no oversold stock, no double transitions, no double postings, no deadlocks. */
class ConcurrencyIT extends IntegrationTest {
    @Autowired
    LedgerAudit audit;
    @Autowired
    LedgerService ledger;
    @Autowired
    RefundWorker refunds;
    @Autowired
    MeterRegistry meters;

    /** Starts every task at the same instant and collects the results. */
    static <T> List<T> burst(int n, Function<Integer, Callable<T>> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<T> c = task.apply(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return c.call();
                }));
            }
            start.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get());
            }
            return out;
        }
    }

    double retries() {
        return meters.find("wokflow.tx.retries").counters().stream().mapToDouble(c -> c.count()).sum();
    }

    @Test
    void twoHundredCustomersRaceForFiftyBasketsAndNoneIsOversold() throws Exception {
        var k = newKitchen(50);
        List<String> tokens = customers(200);
        long deadlocksBefore = deadlocks();
        double retriesBefore = retries();

        List<Http.Res> results = burst(200, i -> () ->
                placeOrder(tokens.get(i), k.id(), Map.of(k.limitedDish(), 1, k.plainDish(), 1), unique("k")));

        Map<Integer, Long> byStatus = results.stream().collect(Collectors.groupingBy(Http.Res::status, Collectors.counting()));
        assertThat(byStatus).containsExactlyInAnyOrderEntriesOf(Map.of(201, 50L, 409, 150L));
        assertThat(results.stream().filter(r -> r.status() == 409).map(Http.Res::code).distinct()).containsExactly("sold_out");
        assertThat(db.sql("select stock_remaining from dishes where id = ?").param(k.limitedDish()).query(Integer.class).single()).isZero();
        assertThat(db.sql("select count(*) from orders where restaurant_id = ?").param(k.id()).query(Integer.class).single()).isEqualTo(50);
        assertThat(deadlocks()).isEqualTo(deadlocksBefore);
        assertThat(retries()).isEqualTo(retriesBefore);
    }

    @Test
    void ordersSharingDishesInAnyOrderNeverDeadlockWithCancellations() throws Exception {
        var k = newKitchen(10_000);
        long second = db.sql("""
                insert into dishes (restaurant_id, category_id, name, name_zh, price_cents, daily_stock, stock_remaining, stock_day)
                select restaurant_id, category_id, 'Pan-Fried Buns', '生煎', 900, 10000, 10000, current_date from dishes where id = ?
                returning id""").param(k.limitedDish()).query(Long.class).single();
        List<String> tokens = customers(150);
        // pre-place orders that will be cancelled during the burst (stock flows back the other way)
        List<UUID> toCancel = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            toCancel.add(placeOrder(tokens.get(100 + i), k.id(), Map.of(k.limitedDish(), 2, second, 2)));
        }
        long deadlocksBefore = deadlocks();

        List<Integer> statuses = burst(150, i -> () -> {
            if (i >= 100) {
                return http.post("/api/orders/" + toCancel.get(i - 100) + "/cancel", tokens.get(i), null).status();
            }
            // the JSON lists the two dishes in alternating order; the server always locks in id order
            var items = new ArrayList<Map<String, Object>>(List.of(Map.of("dishId", k.limitedDish(), "quantity", 1),
                    Map.of("dishId", second, "quantity", 1)));
            if (i % 2 == 1) {
                Collections.reverse(items);
            }
            return http.post("/api/orders", tokens.get(i), Map.of("restaurantId", k.id(), "items", items,
                    "deliveryAddress", "1 Race St"), Map.of("Idempotency-Key", unique("k"))).status();
        });

        assertThat(statuses.subList(0, 100)).containsOnly(201);
        assertThat(statuses.subList(100, 150)).containsOnly(200);
        assertThat(deadlocks()).isEqualTo(deadlocksBefore);
        // 10,000 - 100 placed + 0 net for the cancelled ones
        assertThat(db.sql("select stock_remaining from dishes where id = ?").param(second).query(Integer.class).single()).isEqualTo(9_900);
    }

    @Test
    void acceptAndCancelRaceExactlyOneWinsPerOrder() throws Exception {
        var k = newKitchen(1000);
        String merchant = signIn(k.merchantEmail());
        List<String> tokens = customers(40);
        List<UUID> orders = new ArrayList<>();
        for (String t : tokens) {
            UUID id = placeOrder(t, k.id(), Map.of(k.limitedDish(), 1));
            pay(id);
            orders.add(id);
        }
        List<int[]> outcomes = burst(80, i -> () -> {
            UUID id = orders.get(i / 2);
            int s = i % 2 == 0
                    ? http.post("/api/merchant/orders/" + id + "/accept", merchant, null).status()
                    : http.post("/api/orders/" + id + "/cancel", tokens.get(i / 2), null).status();
            return new int[] {i / 2, i % 2, s};
        });
        Map<Integer, List<int[]>> perOrder = outcomes.stream().collect(Collectors.groupingBy(o -> o[0]));
        int accepted = 0;
        for (var e : perOrder.entrySet()) {
            var wins = e.getValue().stream().filter(o -> o[2] == 200).toList();
            assertThat(wins).hasSize(1);
            assertThat(e.getValue().stream().filter(o -> o[2] == 409)).hasSize(1);
            String expected = wins.getFirst()[1] == 0 ? "ACCEPTED" : "CANCELLED";
            assertThat(status(orders.get(e.getKey()))).isEqualTo(expected);
            accepted += expected.equals("ACCEPTED") ? 1 : 0;
        }
        int cancelled = 40 - accepted;
        assertThat(db.sql("select count(*) from refunds f join orders o on o.id = f.order_id where o.restaurant_id = ?")
                .param(k.id()).query(Integer.class).single()).isEqualTo(cancelled);
        while (refunds.runOnce() > 0) { }
        assertThat(LedgerAudit.consistent(audit.run())).isTrue();
    }

    @Test
    void ridersRacingForTheSameJobsEachJobGoesToExactlyOneRider() throws Exception {
        var k = newKitchen(1000);
        String merchant = signIn(k.merchantEmail());
        List<String> riders = List.of(signIn("rider@example.com"), signIn("rider2@example.com"));
        List<UUID> orders = new ArrayList<>();
        for (String t : customers(20)) {
            UUID id = placeOrder(t, k.id(), Map.of(k.plainDish(), 1));
            pay(id);
            http.post("/api/merchant/orders/" + id + "/accept", merchant, null);
            orders.add(id);
        }
        List<Integer> statuses = burst(40, i -> () ->
                http.post("/api/rider/orders/" + orders.get(i / 2) + "/claim", riders.get(i % 2), null).status());
        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(20);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(20);
        assertThat(db.sql("select count(*) from orders where restaurant_id = ? and rider_id is not null").param(k.id())
                .query(Integer.class).single()).isEqualTo(20);
    }

    @Test
    void webhookStormCapturesEachPaymentOnce() throws Exception {
        var k = newKitchen(1000);
        List<UUID> orders = new ArrayList<>();
        for (String t : customers(30)) {
            orders.add(placeOrder(t, k.id(), Map.of(k.plainDish(), 1)));
        }
        Map<UUID, String> eventIds = new ConcurrentHashMap<>();
        orders.forEach(o -> eventIds.put(o, unique("evt_")));
        // per order: the same event 5 times plus 2 distinct retries, all at once
        List<String> results = burst(30 * 7, i -> () -> {
            UUID id = orders.get(i / 7);
            String evt = i % 7 < 5 ? eventIds.get(id) : unique("evt_");
            return webhook(evt, "payment_intent.succeeded", FakePaymentProvider.intentId(id),
                    clock.instant().getEpochSecond(), WEBHOOK_SECRET).body().get("result").asText();
        });
        assertThat(results).doesNotContain("ignored");
        for (UUID id : orders) {
            assertThat(status(id)).isEqualTo("PAID");
            assertThat(db.sql("select count(*) from ledger_entries where kind = 'CAPTURE' and order_id = ?").param(id)
                    .query(Integer.class).single()).isEqualTo(1);
        }
        assertThat(LedgerAudit.consistent(audit.run())).isTrue();
    }

    @Test
    void concurrentPayoutsCannotOverdrawTheMerchant() throws Exception {
        var k = newKitchen(1000);
        String merchant = signIn(k.merchantEmail());
        for (String t : customers(6)) {
            UUID id = placeOrder(t, k.id(), Map.of(k.plainDish(), 10)); // 100.00 subtotal -> 85.00 to merchant
            pay(id);
        }
        long balance = ledger.merchantBalance(k.id());
        assertThat(balance).isEqualTo(6 * 8500);
        long each = balance / 3;
        List<Integer> statuses = burst(10, i -> () -> http.post("/api/merchant/restaurants/" + k.id() + "/payouts", merchant,
                Map.of("amountCents", each)).status());
        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(3);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(7);
        assertThat(ledger.merchantBalance(k.id())).isEqualTo(balance - 3 * each).isNotNegative();
        assertThat(LedgerAudit.consistent(audit.run())).isTrue();
    }
}
