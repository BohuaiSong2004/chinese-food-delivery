package com.wokflow.api.images;

import com.wokflow.api.config.AppProperties;
import com.wokflow.api.outbox.Outbox;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Gives seeded demo dishes an illustration if they have no photo. Runs once in the background after
 * startup (one replica at a time, via an advisory lock); a merchant's own upload is never replaced
 * because only rows with no image are updated.
 */
@Component
public class DemoImages {
    private static final Logger log = LoggerFactory.getLogger(DemoImages.class);
    private static final long LOCK_KEY = 0x77_6f_6b_5f_69_6d_67L; // "wok_img"

    private final JdbcClient db;
    private final DataSource dataSource;
    private final ImageService images;
    private final Outbox outbox;
    private final TransactionTemplate tx;
    private final AppProperties props;

    public DemoImages(JdbcClient db, DataSource dataSource, ImageService images, Outbox outbox, TransactionTemplate tx,
                      AppProperties props) {
        this.db = db;
        this.dataSource = dataSource;
        this.images = images;
        this.outbox = outbox;
        this.tx = tx;
        this.props = props;
    }

    record Pending(long id, long restaurantId, String name, List<String> tags, int spice, String cuisine) {}

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        if (props.seed().demo() && props.images().generateDemo()) {
            Thread.ofVirtual().name("demo-images").start(this::generate);
        }
    }

    public int generate() {
        try (Connection lock = dataSource.getConnection()) {
            if (!advisory(lock, "select pg_try_advisory_lock(?)")) {
                return 0; // another replica is drawing them
            }
            try {
                List<Pending> todo = db.sql("""
                        select d.id, d.restaurant_id, d.name, d.tags, d.spice_level, r.cuisine
                        from dishes d join restaurants r on r.id = d.restaurant_id
                        where d.image_key is null and d.archived_at is null and r.slug = any(?)""")
                        .param(com.wokflow.api.seed.DemoSeeder.demoSlugs().toArray(String[]::new))
                        .query((rs, n) -> new Pending(rs.getLong(1), rs.getLong(2), rs.getString(3),
                                Arrays.asList((String[]) rs.getArray(4).getArray()), rs.getInt(5), rs.getString(6)))
                        .list();
                for (Pending p : todo) {
                    String key = images.storeGenerated("d", "demo-v1|" + p.name(),
                            DishArt.render(p.name(), p.tags(), p.spice(), p.cuisine()));
                    tx.executeWithoutResult(s -> {
                        if (db.sql("update dishes set image_key = ?, version = version + 1, updated_at = now() where id = ? and image_key is null")
                                .params(key, p.id()).update() == 1) {
                            db.sql("update restaurants set menu_version = menu_version + 1 where id = ?").param(p.restaurantId()).update();
                            outbox.publish(Outbox.DISH_CHANGED, Long.toString(p.id()),
                                    Map.of("dishId", p.id(), "restaurantId", p.restaurantId()));
                        }
                    });
                }
                if (!todo.isEmpty()) {
                    log.info("Drew illustrations for {} demo dishes", todo.size());
                }
                return todo.size();
            } finally {
                advisory(lock, "select pg_advisory_unlock(?)");
            }
        } catch (Exception e) {
            log.warn("Demo illustrations not generated: {}", e.toString());
            return 0;
        }
    }

    private static boolean advisory(Connection c, String sql) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, LOCK_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }
}
