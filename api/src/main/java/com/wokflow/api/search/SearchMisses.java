package com.wokflow.api.search;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Counts searches that matched nothing, so the gaps in dish names and synonyms.txt show up on the
 * admin page. Counting is in memory and flushed every 30 s: a search never waits for a database
 * write, and a Postgres outage only loses the counts of that window. The buffer holds at most
 * {@link #MAX_BUFFERED} distinct queries between flushes, so a flood of random strings cannot grow
 * memory; the table is trimmed to 30 days and {@link #MAX_ROWS} rows.
 */
@Component
public class SearchMisses {
    private static final Logger log = LoggerFactory.getLogger(SearchMisses.class);
    static final int MAX_BUFFERED = 1000;
    static final int MAX_ROWS = 5000;

    public record Miss(String query, int misses, Instant firstSeen, Instant lastSeen) {}

    private final JdbcClient db;
    private final Map<String, LongAdder> buffer = new ConcurrentHashMap<>();

    public SearchMisses(JdbcClient db) { this.db = db; }

    public void record(String query) {
        if (query == null) {
            return;
        }
        String q = Synonyms.normalize(query);
        if (q.isEmpty() || q.length() > 100 || looksPersonal(q)) {
            return;
        }
        LongAdder n = buffer.get(q);
        if (n == null) {
            if (buffer.size() >= MAX_BUFFERED) {
                return;
            }
            n = buffer.computeIfAbsent(q, k -> new LongAdder());
        }
        n.increment();
    }

    private static final java.util.regex.Pattern PERSONAL = java.util.regex.Pattern.compile("@|\\d[\\d\\s().-]{5,}\\d");

    /**
     * People paste odd things into a search box. An email address or a phone/card-like run of digits is never
     * a dish name, and admins should not see it, so it is not kept.
     */
    static boolean looksPersonal(String q) {
        return PERSONAL.matcher(q).find();
    }

    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT30S")
    public void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        List<Map.Entry<String, Integer>> batch = new ArrayList<>();
        for (String q : List.copyOf(buffer.keySet())) {
            LongAdder n = buffer.remove(q);
            if (n != null && n.sum() > 0) {
                batch.add(Map.entry(q, (int) Math.min(Integer.MAX_VALUE, n.sum())));
            }
        }
        try {
            for (var e : batch) {
                db.sql("""
                        insert into search_misses (query, misses) values (:q, :n)
                        on conflict (query) do update set misses = least(2147483647::bigint, search_misses.misses::bigint + excluded.misses)::int,
                                                          last_seen = now()""")
                        .param("q", e.getKey()).param("n", e.getValue()).update();
            }
        } catch (RuntimeException e) {
            log.warn("Could not save {} search misses (dropped): {}", batch.size(), e.toString());
        }
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT10M")
    public void purge() {
        db.sql("delete from search_misses where last_seen < now() - interval '30 days'").update();
        db.sql("""
                delete from search_misses where query in (
                    select query from search_misses order by last_seen desc offset :keep)""")
                .param("keep", MAX_ROWS).update();
    }

    public List<Miss> top(int days, int limit) {
        return db.sql("""
                select query, misses, first_seen, last_seen from search_misses
                where last_seen > now() - make_interval(days => :days)
                order by misses desc, last_seen desc limit :limit""")
                .param("days", days).param("limit", limit)
                .query((rs, n) -> new Miss(rs.getString("query"), rs.getInt("misses"),
                        rs.getTimestamp("first_seen").toInstant(), rs.getTimestamp("last_seen").toInstant()))
                .list();
    }

    int buffered() { return buffer.size(); }
}
