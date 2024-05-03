package com.wokflow.api.ledger;

import com.wokflow.api.search.SearchIndexer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final LedgerAudit audit;
    private final SearchIndexer indexer;
    private final JdbcClient db;

    public AdminController(LedgerAudit audit, SearchIndexer indexer, JdbcClient db) {
        this.audit = audit;
        this.indexer = indexer;
        this.db = db;
    }

    @GetMapping("/ledger/audit")
    Map<String, Object> audit() {
        Map<String, Long> r = audit.run();
        Map<String, Object> out = new LinkedHashMap<>(r);
        out.put("consistent", LedgerAudit.consistent(r));
        return out;
    }

    /** Postgres-level health of the order path: deadlocks since stats reset, and outbox backlog. */
    @GetMapping("/stats")
    Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deadlocks", db.sql("select deadlocks from pg_stat_database where datname = current_database()").query(Long.class).single());
        out.put("outboxPending", db.sql("select count(*) from outbox where published_at is null").query(Long.class).single());
        out.put("ordersByStatus", db.sql("select status, count(*) from orders group by status order by status")
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getLong(2))).list());
        return out;
    }

    @PostMapping("/search/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, String> reindex() throws Exception {
        indexer.reindexAll();
        return Map.of("status", "rebuilt");
    }
}
