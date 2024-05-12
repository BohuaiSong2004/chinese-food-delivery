package com.wokflow.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wokflow.api.support.IntegrationTest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class LedgerIT extends IntegrationTest {
    @Autowired
    LedgerService ledger;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    DataSource dataSource;

    @Test
    void theDatabaseRefusesUnbalancedEntriesAtCommit() {
        long clearing = db.sql("select id from ledger_accounts where code = 'provider_clearing'").query(Long.class).single();
        long fees = db.sql("select id from ledger_accounts where code = 'delivery_fees'").query(Long.class).single();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            long e = db.sql("insert into ledger_entries (kind, ref) values ('PAYOUT', ?) returning id").param(unique("x")).query(Long.class).single();
            db.sql("insert into ledger_lines (entry_id, line_no, account_id, amount_cents) values (?, 1, ?, 100)").params(e, clearing).update();
            db.sql("insert into ledger_lines (entry_id, line_no, account_id, amount_cents) values (?, 2, ?, -99)").params(e, fees).update();
        })).hasMessageContaining("unbalanced");
        // the Java side refuses before it gets that far
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> ledger.post("PAYOUT", unique("y"), null, "",
                List.of(new LedgerService.Posting(LedgerService.CLEARING, 5)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ledgerRowsCannotBeUpdatedOrDeleted() {
        String ref = unique("p");
        tx.executeWithoutResult(s -> ledger.post("PAYOUT", ref, null, "test", List.of(
                new LedgerService.Posting(LedgerService.CLEARING, 100), new LedgerService.Posting(LedgerService.DELIVERY, -100))));
        assertThatThrownBy(() -> db.sql("update ledger_lines set amount_cents = 1 where entry_id = (select id from ledger_entries where ref = ?)")
                .param(ref).update()).rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> db.sql("delete from ledger_entries where ref = ?").param(ref).update())
                .rootCause().hasMessageContaining("append-only");
    }

    @Test
    void postingTheSameBusinessEventTwiceIsANoOp() {
        String ref = unique("dup");
        var lines = List.of(new LedgerService.Posting(LedgerService.CLEARING, 7), new LedgerService.Posting(LedgerService.DELIVERY, -7));
        Boolean first = tx.execute(s -> ledger.post("PAYOUT", ref, null, "", lines));
        Boolean second = tx.execute(s -> ledger.post("PAYOUT", ref, null, "", lines));
        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }

    /**
     * Negative control for the "zero deadlocks" claims elsewhere: locking two
     * rows in opposite orders really does deadlock, Postgres aborts one side
     * with 40P01, and pg_stat_database counts it. So a zero delta is evidence.
     */
    @Test
    void oppositeLockOrderDoesDeadlockAndIsCounted() throws Exception {
        var k = newKitchen(10);
        long before = deadlocks();
        CountDownLatch bothLockedFirst = new CountDownLatch(2);
        var a = CompletableFuture.supplyAsync(() -> lockBoth(k.plainDish(), k.limitedDish(), bothLockedFirst));
        var b = CompletableFuture.supplyAsync(() -> lockBoth(k.limitedDish(), k.plainDish(), bothLockedFirst));
        List<String> states = List.of(a.get(), b.get());
        assertThat(states).contains("40P01");
        assertThat(deadlocks()).isEqualTo(before + 1);
    }

    private String lockBoth(long first, long second, CountDownLatch latch) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("update dishes set price_cents = price_cents where id = " + first);
                latch.countDown();
                latch.await();
                s.execute("update dishes set price_cents = price_cents where id = " + second);
                c.commit();
                return "ok";
            } catch (SQLException e) {
                c.rollback();
                return e.getSQLState();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
