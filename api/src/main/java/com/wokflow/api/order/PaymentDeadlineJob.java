package com.wokflow.api.order;

import com.wokflow.api.common.ApiException;
import com.wokflow.api.common.Tx;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cancels orders that were never paid and returns their reserved stock. Races with a late webhook are settled by the CAS. */
@Component
public class PaymentDeadlineJob {
    private static final Logger log = LoggerFactory.getLogger(PaymentDeadlineJob.class);
    private final OrderRepository orders;
    private final OrderTransitions transitions;
    private final Tx tx;
    private final Clock clock;

    public PaymentDeadlineJob(OrderRepository orders, OrderTransitions transitions, Tx tx, Clock clock) {
        this.orders = orders;
        this.transitions = transitions;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT15S", initialDelayString = "PT10S")
    public int run() {
        int n = 0;
        for (UUID id : orders.overdue(clock.instant(), 200)) {
            try {
                tx.run(() -> transitions.apply(new OrderTransitions.Request(id, OrderAction.EXPIRE, null, null, null, "payment_timeout")));
                n++;
            } catch (ApiException e) {
                // paid or cancelled in the meantime
            }
        }
        if (n > 0) {
            log.info("Expired {} unpaid orders", n);
        }
        return n;
    }
}
