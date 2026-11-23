package com.wokflow.api.ledger;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Cross-checks the ledger against orders. Every value is a count of
 * violations except the balances, so "all zero" means consistent. Used by the
 * admin endpoint, the integration tests and the load-test verifier.
 */
@Component
public class LedgerAudit {
    private final JdbcClient db;

    public LedgerAudit(JdbcClient db) { this.db = db; }

    public Map<String, Long> run() {
        Map<String, Long> r = new LinkedHashMap<>();
        r.put("unbalancedEntries", count("""
                select count(*) from (select entry_id from ledger_lines group by entry_id having sum(amount_cents) <> 0) x"""));
        r.put("trialBalance", count("select coalesce(sum(amount_cents), 0) from ledger_lines"));
        r.put("chargedWithoutCapture", count("""
                select count(*) from orders o where o.status in ('PAID','ACCEPTED','READY','PICKED_UP','DELIVERED')
                  and not exists (select 1 from ledger_entries e where e.kind = 'CAPTURE' and e.order_id = o.id)"""));
        r.put("captureAmountMismatch", count("""
                select count(*) from ledger_entries e join orders o on o.id = e.order_id
                join ledger_lines l on l.entry_id = e.id
                join ledger_accounts a on a.id = l.account_id and a.code = 'provider_clearing'
                where e.kind = 'CAPTURE' and l.amount_cents <> o.total_cents"""));
        r.put("refundedButStillCharged", count("""
                select count(*) from orders o where o.status in ('PAID','ACCEPTED','READY','PICKED_UP','DELIVERED')
                  and exists (select 1 from ledger_entries e where e.kind = 'REFUND' and e.order_id = o.id)"""));
        r.put("cancelledCapturedNotRefunded", count("""
                select count(*) from orders o where o.status in ('CANCELLED','REJECTED')
                  and exists (select 1 from ledger_entries e where e.kind = 'CAPTURE' and e.order_id = o.id)
                  and not exists (select 1 from ledger_entries e where e.kind = 'REFUND' and e.order_id = o.id)
                  and not exists (select 1 from refunds f where f.order_id = o.id and f.status = 'REQUESTED')"""));
        r.put("negativeMerchantBalances", count("""
                select count(*) from (select a.id from ledger_accounts a join ledger_lines l on l.account_id = a.id
                where a.kind = 'LIABILITY' group by a.id having sum(l.amount_cents) > 0) x"""));
        r.put("pendingRefunds", count("select count(*) from refunds where status = 'REQUESTED'"));
        r.put("clearingBalanceCents", count("""
                select coalesce(sum(l.amount_cents), 0) from ledger_lines l
                join ledger_accounts a on a.id = l.account_id where a.code = 'provider_clearing'"""));
        return r;
    }

    /** True when no invariant is violated (pending refunds and balances are informational). */
    public static boolean consistent(Map<String, Long> r) {
        return r.entrySet().stream()
                .filter(e -> !e.getKey().equals("pendingRefunds") && !e.getKey().equals("clearingBalanceCents"))
                .allMatch(e -> e.getValue() == 0);
    }

    private long count(String sql) {
        return db.sql(sql).query(Long.class).single();
    }
}
