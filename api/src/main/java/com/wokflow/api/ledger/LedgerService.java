package com.wokflow.api.ledger;

import com.wokflow.api.common.ApiException;
import com.wokflow.api.order.OrderRepository.Order;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Double-entry postings. Debits are positive, credits negative; each entry
 * sums to zero (enforced by the database at commit) and is unique per
 * (kind, ref), so a retried webhook or refund cannot post twice.
 *
 * <pre>
 * CAPTURE  Dr provider_clearing            total
 *          Cr merchant_payable:{r}         subtotal - commission
 *          Cr platform_commission          commission
 *          Cr delivery_fees                delivery fee
 * REFUND   the exact reverse of CAPTURE
 * PAYOUT   Dr merchant_payable:{r} / Cr provider_clearing
 * </pre>
 */
@Service
public class LedgerService {
    public static final String CLEARING = "provider_clearing";
    public static final String COMMISSION = "platform_commission";
    public static final String DELIVERY = "delivery_fees";

    public record Posting(String account, long amountCents) {}

    public record EntryView(long id, String kind, String ref, UUID orderId, String memo, Instant createdAt, long amountCents) {}

    public record Balance(long restaurantId, long balanceCents, long pendingRefundCents, long availableCents) {}

    private final JdbcClient db;
    private final Map<String, Long> accountIds = new ConcurrentHashMap<>();

    public LedgerService(JdbcClient db) { this.db = db; }

    public static String merchantAccount(long restaurantId) { return "merchant_payable:" + restaurantId; }

    long accountId(String code) {
        return accountIds.computeIfAbsent(code, c -> db.sql("select id from ledger_accounts where code = ?").param(c)
                .query(Long.class).optional()
                .orElseThrow(() -> new IllegalStateException("ledger account " + c + " does not exist")));
    }

    /** Posts an entry; returns false if this (kind, ref) was already posted. Must run inside a transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean post(String kind, String ref, UUID orderId, String memo, List<Posting> postings) {
        long sum = postings.stream().mapToLong(Posting::amountCents).sum();
        if (sum != 0) {
            throw new IllegalArgumentException("unbalanced entry " + kind + "/" + ref + ": " + sum);
        }
        Long entryId = db.sql("""
                insert into ledger_entries (kind, ref, order_id, memo) values (?, ?, ?, ?)
                on conflict (kind, ref) do nothing returning id""")
                .params(kind, ref, orderId, memo).query(Long.class).optional().orElse(null);
        if (entryId == null) {
            return false;
        }
        short line = 1;
        for (Posting p : postings) {
            if (p.amountCents() == 0) {
                continue;
            }
            db.sql("insert into ledger_lines (entry_id, line_no, account_id, amount_cents) values (?, ?, ?, ?)")
                    .params(entryId, line++, accountId(p.account()), p.amountCents()).update();
        }
        return true;
    }

    static List<Posting> capturePostings(Order o) {
        List<Posting> p = new ArrayList<>();
        p.add(new Posting(CLEARING, o.totalCents()));
        p.add(new Posting(merchantAccount(o.restaurantId()), -(long) (o.subtotalCents() - o.commissionCents())));
        p.add(new Posting(COMMISSION, -(long) o.commissionCents()));
        p.add(new Posting(DELIVERY, -(long) o.deliveryFeeCents()));
        return p;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean postCapture(Order o) {
        return post("CAPTURE", o.id().toString(), o.id(), "payment captured", capturePostings(o));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean postRefund(Order o) {
        List<Posting> reversed = capturePostings(o).stream().map(p -> new Posting(p.account(), -p.amountCents())).toList();
        return post("REFUND", o.id().toString(), o.id(), "refunded to customer", reversed);
    }

    /** Liability balance (credit-normal): what the platform owes the restaurant. */
    public long merchantBalance(long restaurantId) {
        return -db.sql("select coalesce(sum(amount_cents), 0) from ledger_lines where account_id = ?")
                .param(accountId(merchantAccount(restaurantId))).query(Long.class).single();
    }

    /** Merchant share of orders whose refund is requested but not yet booked. */
    long pendingRefunds(long restaurantId) {
        return db.sql("""
                select coalesce(sum(o.subtotal_cents - o.commission_cents), 0) from refunds f
                join orders o on o.id = f.order_id
                where o.restaurant_id = ? and f.status = 'REQUESTED'
                  and exists (select 1 from ledger_entries e where e.kind = 'CAPTURE' and e.order_id = o.id)""")
                .param(restaurantId).query(Long.class).single();
    }

    public Balance balance(long restaurantId) {
        long bal = merchantBalance(restaurantId);
        long pending = pendingRefunds(restaurantId);
        return new Balance(restaurantId, bal, pending, Math.max(0, bal - pending));
    }

    /**
     * Pays out to the restaurant. Locks the restaurant's ledger account row so
     * two concurrent payouts cannot both pass the balance check; captures do
     * not take this lock (they only insert lines), so orders never wait on it.
     */
    @Transactional
    public UUID payout(long restaurantId, long amountCents, long requestedBy) {
        if (amountCents <= 0) {
            throw ApiException.badRequest("bad_amount", "Payout amount must be positive");
        }
        db.sql("select id from ledger_accounts where restaurant_id = ? for update").param(restaurantId).query(Long.class).single();
        Balance b = balance(restaurantId);
        if (amountCents > b.availableCents()) {
            throw ApiException.conflict("insufficient_balance", "Only " + b.availableCents() + " cents are available for payout");
        }
        UUID id = UUID.randomUUID();
        db.sql("insert into payouts (id, restaurant_id, amount_cents, requested_by) values (?, ?, ?, ?)")
                .params(id, restaurantId, amountCents, requestedBy).update();
        post("PAYOUT", id.toString(), null, "payout to restaurant",
                List.of(new Posting(merchantAccount(restaurantId), amountCents), new Posting(CLEARING, -amountCents)));
        return id;
    }

    public List<EntryView> merchantEntries(long restaurantId, int limit) {
        return db.sql("""
                select e.id, e.kind, e.ref, e.order_id, e.memo, e.created_at, -l.amount_cents as amount
                from ledger_lines l join ledger_entries e on e.id = l.entry_id
                where l.account_id = ? order by e.id desc limit ?""")
                .params(accountId(merchantAccount(restaurantId)), limit)
                .query((rs, n) -> new EntryView(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, UUID.class), rs.getString(5), rs.getTimestamp(6).toInstant(), rs.getLong(7)))
                .list();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void ensureMerchantAccount(long restaurantId, String name) {
        db.sql("""
                insert into ledger_accounts (code, kind, name, restaurant_id) values (?, 'LIABILITY', ?, ?)
                on conflict (code) do nothing""")
                .params(merchantAccount(restaurantId), "Payable to " + name, restaurantId).update();
    }
}
