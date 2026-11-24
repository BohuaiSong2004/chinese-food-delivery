package com.wokflow.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.wokflow.api.payment.WebhookController;
import com.wokflow.api.support.IntegrationTest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Things that only go wrong days later: daily restock and table growth. */
class RestockIT extends IntegrationTest {
    @Autowired
    RestockJob restock;
    @Autowired
    WebhookController webhooks;

    int stock(long dish) {
        return db.sql("select stock_remaining from dishes where id = ?").param(dish).query(Integer.class).single();
    }

    @Test
    void limitedDishesRefillOncePerLocalDay() {
        var k = newKitchen(40);
        // "yesterday" in the restaurant's zone, not the database's UTC current_date - 1, which is the
        // restaurant's own today from 16:00 to midnight in Los Angeles (CI caught this at 17:16 local)
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("America/Los_Angeles")));
        db.sql("update dishes set stock_remaining = 3, stock_day = ? where id = ?").params(today.minusDays(1), k.limitedDish()).update();
        restock.run();
        assertThat(stock(k.limitedDish())).isEqualTo(40);
        assertThat(db.sql("select stock_day from dishes where id = ?").param(k.limitedDish()).query(LocalDate.class).single()).isEqualTo(today);

        // later the same day: sales are not undone by another run
        db.sql("update dishes set stock_remaining = 12 where id = ?").param(k.limitedDish()).update();
        restock.run();
        assertThat(stock(k.limitedDish())).isEqualTo(12);

        // the next local day (move the clock past midnight in the restaurant's zone)
        clock.advance(Duration.ofDays(1));
        restock.run();
        assertThat(stock(k.limitedDish())).isEqualTo(40);
    }

    @Test
    void oldWebhookDeduplicationRecordsArePurged() {
        String old = unique("evt_old_");
        String recent = unique("evt_new_");
        db.sql("insert into provider_events (event_id, type, received_at) values (?, 't', now() - interval '31 days'), (?, 't', now())")
                .params(old, recent).update();
        assertThat(webhooks.purgeOldEvents()).isGreaterThanOrEqualTo(1);
        assertThat(db.sql("select count(*) from provider_events where event_id in (?, ?)").params(old, recent)
                .query(Integer.class).single()).isEqualTo(1);
    }
}
