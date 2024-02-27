package com.wokflow.api.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.wokflow.api.support.Http;
import com.wokflow.api.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class StockReservationsIT extends IntegrationTest {
    @Autowired
    StockReservations reservations;

    int stock(long dish) {
        return db.sql("select stock_remaining from dishes where id = ?").param(dish).query(Integer.class).single();
    }

    int holds(UUID order) {
        return db.sql("select count(*) from stock_holds where order_id = ?").param(order).query(Integer.class).single();
    }

    @Test
    void reservationIsAllOrNothingAcrossDishes() {
        var k = newKitchen(5);
        long second = db.sql("""
                insert into dishes (restaurant_id, category_id, name, name_zh, price_cents, daily_stock, stock_remaining, stock_day)
                select restaurant_id, category_id, 'Scallion Pancake', '葱油饼', 600, 1, 1, current_date from dishes where id = ? returning id""")
                .param(k.limitedDish()).query(Long.class).single();
        UUID order = UUID.randomUUID();
        var qty = new TreeMap<Long, Integer>(Map.of(k.limitedDish(), 2, second, 2)); // second has only 1
        assertThat(reservations.reserve(order, 1L, unique("k"), qty)).isEqualTo(new StockReservations.Result.SoldOut(second));
        assertThat(stock(k.limitedDish())).isEqualTo(5); // the first dish was given back
        assertThat(stock(second)).isEqualTo(1);
        assertThat(holds(order)).isZero();
    }

    @Test
    void abandonedReservationsAreSweptBackIntoStock() {
        var k = newKitchen(10);
        UUID neverCreated = UUID.randomUUID();
        assertThat(reservations.reserve(neverCreated, 1L, unique("k"), new TreeMap<>(Map.of(k.limitedDish(), 3))))
                .isInstanceOf(StockReservations.Result.Reserved.class);
        assertThat(stock(k.limitedDish())).isEqualTo(7);
        assertThat(reservations.sweepOrphans()).isZero(); // too recent: the order may still be committing
        db.sql("update stock_holds set created_at = now() - interval '10 minutes' where order_id = ?").param(neverCreated).update();
        assertThat(reservations.sweepOrphans()).isEqualTo(1);
        assertThat(stock(k.limitedDish())).isEqualTo(10);
    }

    @Test
    void aRetryWhileTheOriginalHoldsStockIsToldItIsInFlight() {
        var k = newKitchen(10);
        String key = unique("k");
        var qty = new TreeMap<Long, Integer>(Map.of(k.limitedDish(), 2));
        assertThat(reservations.reserve(UUID.randomUUID(), 7L, key, qty)).isInstanceOf(StockReservations.Result.Reserved.class);
        assertThat(reservations.reserve(UUID.randomUUID(), 7L, key, qty)).isInstanceOf(StockReservations.Result.InFlight.class);
        assertThat(stock(k.limitedDish())).isEqualTo(8); // the retry's decrement was rolled back with its statement
        assertThat(reservations.held(7L, key)).isTrue();
    }

    @org.junit.jupiter.api.RepeatedTest(5) // a race: repeat to give it more chances to show up
    void placedOrdersLeaveNoHoldsAndConcurrentRetriesReserveOnce() throws Exception {
        // the stock covers exactly one order: if retries reserved too, some would wrongly see "sold out"
        var k = newKitchen(2);
        String customer = newCustomer();
        String key = unique("same-key-");
        List<Http.Res> results = ConcurrencyIT.burst(10, i -> () -> placeOrder(customer, k.id(), Map.of(k.limitedDish(), 2), key));
        assertThat(results).allSatisfy(r -> assertThat(r.body().has("id")).as(r.toString()).isTrue());
        assertThat(results.stream().map(r -> r.body().get("id").asText()).distinct()).hasSize(1);
        assertThat(results.stream().filter(r -> r.status() == 201)).hasSize(1);
        assertThat(results).allSatisfy(r -> assertThat(r.status()).isIn(200, 201));
        assertThat(stock(k.limitedDish())).isZero();
        assertThat(db.sql("select count(*) from stock_holds h join dishes d on d.id = h.dish_id where d.restaurant_id = ?")
                .param(k.id()).query(Integer.class).single()).isZero();
    }
}
