package com.wokflow.api.catalog;

import com.wokflow.api.outbox.Outbox;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Refills limited dishes once per local calendar day of each restaurant. */
@Component
public class RestockJob {
    private final CatalogRepository repo;
    private final TransactionTemplate tx;
    private final Outbox outbox;
    private final Clock clock;

    public RestockJob(CatalogRepository repo, TransactionTemplate tx, Outbox outbox, Clock clock) {
        this.repo = repo;
        this.tx = tx;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT5S")
    public void run() {
        for (Restaurant r : repo.restaurants()) {
            LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(r.timeZone())));
            tx.executeWithoutResult(s -> {
                List<Long> ids = repo.restockIfNewDay(r.id(), today);
                if (!ids.isEmpty()) {
                    outbox.publish(Outbox.RESTAURANT_CHANGED, Long.toString(r.id()), Map.of("restaurantId", r.id()));
                }
            });
        }
    }
}
