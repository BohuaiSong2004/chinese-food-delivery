package com.wokflow.api.seed;

import com.wokflow.api.auth.AuthService;
import com.wokflow.api.config.AppProperties;
import com.wokflow.api.ledger.LedgerService;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Demo data. Insert-only (ON CONFLICT DO NOTHING): re-running it on every
 * start never overwrites a merchant's price or stock changes, and it contains
 * no dates — limited stock is stamped with each restaurant's local "today".
 * SEED_LOADTEST_USERS=n also creates loadtest+1..n@example.com customers.
 */
@Component
@Order(1)
public class DemoSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);
    public static final String DEMO_PASSWORD = "wokflow-demo";

    record DishSeed(String name, String nameZh, String description, int priceCents, List<String> tags, int spice, Integer dailyStock) {}
    record CategorySeed(String name, String nameZh, List<DishSeed> dishes) {}
    record RestaurantSeed(String slug, String name, String nameZh, String description, String cuisine, List<String> tags,
                          String neighborhood, String address, int deliveryFeeCents, int minOrderCents, int prepMinutes,
                          String opensAt, String closesAt, boolean open24h, List<String> staff, List<CategorySeed> categories) {}
    record UserSeed(String email, String name, String role) {}
    record Seed(List<RestaurantSeed> restaurants, List<UserSeed> users) {}

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final AuthService auth;
    private final LedgerService ledger;
    private final ObjectMapper json;
    private final AppProperties props;
    private final Environment env;
    private final Clock clock;

    public DemoSeeder(JdbcClient db, TransactionTemplate tx, AuthService auth, LedgerService ledger, ObjectMapper json,
                      AppProperties props, Environment env, Clock clock) {
        this.db = db;
        this.tx = tx;
        this.auth = auth;
        this.ledger = ledger;
        this.json = json;
        this.props = props;
        this.env = env;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!props.seed().demo()) {
            return;
        }
        Seed seed;
        try (InputStream in = new ClassPathResource("seed/demo-restaurants.json").getInputStream()) {
            seed = json.readValue(in, Seed.class);
        }
        String hash = auth.hash(DEMO_PASSWORD);
        tx.executeWithoutResult(s -> {
            for (UserSeed u : seed.users()) {
                db.sql("insert into users (email, password_hash, display_name, role) values (?, ?, ?, ?) on conflict (email) do nothing")
                        .params(u.email(), hash, u.name(), u.role()).update();
            }
            int loadUsers = env.getProperty("SEED_LOADTEST_USERS", Integer.class, 0);
            for (int i = 1; i <= loadUsers; i++) {
                db.sql("insert into users (email, password_hash, display_name, role) values (?, ?, ?, 'CUSTOMER') on conflict (email) do nothing")
                        .params("loadtest+" + i + "@example.com", hash, "Load Tester " + i).update();
            }
            for (RestaurantSeed r : seed.restaurants()) {
                seedRestaurant(r);
            }
        });
        log.info("Demo data present: {} restaurants, {} dishes",
                db.sql("select count(*) from restaurants").query(Long.class).single(),
                db.sql("select count(*) from dishes").query(Long.class).single());
    }

    private void seedRestaurant(RestaurantSeed r) {
        db.sql("""
                insert into restaurants (slug, name, name_zh, description, cuisine, tags, neighborhood, address,
                    delivery_fee_cents, min_order_cents, prep_minutes, opens_at, closes_at, open_24h)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (slug) do nothing""")
                .params(r.slug(), r.name(), r.nameZh(), r.description(), r.cuisine(), r.tags().toArray(String[]::new),
                        r.neighborhood(), r.address(), r.deliveryFeeCents(), r.minOrderCents(), r.prepMinutes(),
                        LocalTime.parse(r.opensAt()), LocalTime.parse(r.closesAt()), r.open24h())
                .update();
        long rid = db.sql("select id from restaurants where slug = ?").param(r.slug()).query(Long.class).single();
        String tz = db.sql("select time_zone from restaurants where id = ?").param(rid).query(String.class).single();
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(tz)));
        ledger.ensureMerchantAccount(rid, r.name());
        for (String email : r.staff()) {
            db.sql("""
                    insert into restaurant_staff (restaurant_id, user_id)
                    select ?, id from users where email = ? on conflict do nothing""").params(rid, email).update();
        }
        int position = 0;
        for (CategorySeed c : r.categories()) {
            // Insert-only, and never resurrect something the merchant archived: skip any name that ever existed.
            db.sql("""
                    insert into menu_categories (restaurant_id, name, name_zh, position)
                    select ?, ?, ?, ? where not exists (select 1 from menu_categories where restaurant_id = ? and name = ?)
                    on conflict do nothing""")
                    .params(rid, c.name(), c.nameZh(), position++, rid, c.name()).update();
            Long cid = db.sql("select id from menu_categories where restaurant_id = ? and name = ? and archived_at is null")
                    .params(rid, c.name()).query(Long.class).optional().orElse(null);
            if (cid == null) {
                continue; // archived by the merchant
            }
            for (DishSeed d : c.dishes()) {
                db.sql("""
                        insert into dishes (restaurant_id, category_id, name, name_zh, description, price_cents, tags,
                                            spice_level, daily_stock, stock_remaining, stock_day)
                        select ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                        where not exists (select 1 from dishes where restaurant_id = ? and name = ?)
                        on conflict do nothing""")
                        .params(rid, cid, d.name(), d.nameZh(), d.description(), d.priceCents(),
                                d.tags().toArray(String[]::new), d.spice(), d.dailyStock(), d.dailyStock(),
                                d.dailyStock() == null ? null : today, rid, d.name())
                        .update();
            }
        }
    }
}
