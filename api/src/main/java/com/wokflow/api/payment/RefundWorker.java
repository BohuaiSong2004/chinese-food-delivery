package com.wokflow.api.payment;

import com.wokflow.api.ledger.LedgerService;
import com.wokflow.api.order.OrderRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends requested refunds to the provider. Refund rows are written in the
 * same transaction as the rejection/cancellation, so a refund is never lost if
 * the process dies; this worker retries with backoff until the provider
 * confirms, and the REFUND ledger entry is booked on confirmation.
 */
@Component
public class RefundWorker {
    private static final Logger log = LoggerFactory.getLogger(RefundWorker.class);

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final PaymentProvider provider;
    private final LedgerService ledger;
    private final OrderRepository orders;

    public RefundWorker(JdbcClient db, TransactionTemplate tx, PaymentProvider provider, LedgerService ledger,
                        OrderRepository orders) {
        this.db = db;
        this.tx = tx;
        this.provider = provider;
        this.ledger = ledger;
        this.orders = orders;
    }

    record Due(UUID id, UUID orderId, String intentId, int amount, int attempts) {}

    @Scheduled(fixedDelayString = "PT2S", initialDelayString = "PT5S")
    public void run() {
        int n;
        do {
            n = runOnce();
        } while (n > 0);
    }

    /** Processes one batch; returns how many refunds were attempted. */
    public int runOnce() {
        Integer n = tx.execute(s -> {
            List<Due> due = db.sql("""
                    select f.id, f.order_id, p.provider_intent_id, f.amount_cents, f.attempts
                    from refunds f join payments p on p.id = f.payment_id
                    where f.status = 'REQUESTED' and f.next_attempt_at <= now()
                    order by f.next_attempt_at limit 20 for update of f skip locked""")
                    .query((rs, i) -> new Due(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                            rs.getInt(4), rs.getInt(5)))
                    .list();
            for (Due d : due) {
                try {
                    var r = provider.refund(d.intentId(), d.amount(), d.id());
                    if ("succeeded".equals(r.status())) {
                        markSucceeded(db, ledger, orders, d.orderId(), r.id());
                    } else {
                        db.sql("update refunds set provider_refund_id = ?, attempts = attempts + 1, next_attempt_at = now() + interval '5 seconds', updated_at = now() where id = ?")
                                .params(r.id(), d.id()).update();
                    }
                } catch (RuntimeException e) {
                    long delay = Math.min(300, 2L << Math.min(d.attempts(), 7));
                    log.warn("Refund {} failed (attempt {}): {}", d.id(), d.attempts() + 1, e.toString());
                    db.sql("""
                            update refunds set attempts = attempts + 1, last_error = ?, updated_at = now(),
                                   next_attempt_at = now() + make_interval(secs => ?) where id = ?""")
                            .params(e.toString().length() > 300 ? e.toString().substring(0, 300) : e.toString(), (double) delay, d.id())
                            .update();
                }
            }
            return due.size();
        });
        return n == null ? 0 : n;
    }

    /** Idempotent: the refund row flips once and the REFUND entry is unique per order. */
    static void markSucceeded(JdbcClient db, LedgerService ledger, OrderRepository orders, UUID orderId, String providerRefundId) {
        int changed = db.sql("""
                update refunds set status = 'SUCCEEDED', provider_refund_id = coalesce(?, provider_refund_id), updated_at = now()
                where order_id = ? and status = 'REQUESTED'""")
                .params(providerRefundId, orderId).update();
        if (changed == 1) {
            orders.find(orderId).ifPresent(ledger::postRefund);
        }
    }
}
