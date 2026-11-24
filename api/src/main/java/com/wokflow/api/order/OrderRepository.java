package com.wokflow.api.order;

import com.wokflow.api.order.OrderViews.Line;
import com.wokflow.api.order.OrderViews.Step;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {

    public record Order(UUID id, long customerId, long restaurantId, OrderStatus status, int subtotalCents,
                        int deliveryFeeCents, int commissionCents, int totalCents, String deliveryAddress,
                        String deliveryNotes, Long riderId, String requestHash, String cancelReason,
                        Instant paymentDeadline, int version, Instant createdAt, Instant updatedAt,
                        Double deliveryLat, Double deliveryLng, Integer distanceM) {

        public com.wokflow.api.geo.GeoPoint deliveryPoint() {
            return deliveryLat == null ? null : new com.wokflow.api.geo.GeoPoint(deliveryLat, deliveryLng);
        }
    }

    /** Result of a successful compare-and-set. */
    public record Changed(int version, OrderStatus status, long customerId, long restaurantId, Long riderId,
                          Instant updatedAt) {}

    static final String COLS = """
            id, customer_id, restaurant_id, status, subtotal_cents, delivery_fee_cents, commission_cents, total_cents,
            delivery_address, delivery_notes, rider_id, request_hash, cancel_reason, payment_deadline, version,
            created_at, updated_at, delivery_lat, delivery_lng, distance_m""";

    private final JdbcClient db;

    public OrderRepository(JdbcClient db) { this.db = db; }

    public Optional<Order> find(UUID id) {
        return db.sql("select " + COLS + " from orders where id = ?").param(id).query(OrderRepository::map).optional();
    }

    public Optional<Order> findByKey(long customerId, String key) {
        return db.sql("select " + COLS + " from orders where customer_id = ? and idempotency_key = ?")
                .params(customerId, key).query(OrderRepository::map).optional();
    }

    /**
     * Inserts the order unless this customer already used the idempotency key.
     * A concurrent request with the same key blocks on the unique index until
     * the first one commits, then gets "absent" here.
     */
    public boolean insertIfAbsent(UUID id, long customerId, long restaurantId, int subtotal, int deliveryFee,
                                  int commission, String address, String notes, String key, String requestHash,
                                  Instant deadline, double lat, double lng, int distanceM) {
        return db.sql("""
                insert into orders (id, customer_id, restaurant_id, status, subtotal_cents, delivery_fee_cents,
                                    commission_cents, total_cents, delivery_address, delivery_notes,
                                    idempotency_key, request_hash, payment_deadline, delivery_lat, delivery_lng, distance_m)
                values (?, ?, ?, 'PENDING_PAYMENT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (customer_id, idempotency_key) do nothing""")
                .params(id, customerId, restaurantId, subtotal, deliveryFee, commission, subtotal + deliveryFee,
                        address, notes, key, requestHash, Timestamp.from(deadline), lat, lng, distanceM)
                .update() == 1;
    }

    public void releaseStock(long dishId, int qty) {
        db.sql("""
                update dishes set stock_remaining = least(daily_stock, stock_remaining + ?)
                where id = ? and daily_stock is not null""")
                .params(qty, dishId).update();
    }

    public void insertItems(UUID orderId, List<Line> lines) {
        int n = 1;
        for (Line l : lines) {
            db.sql("""
                    insert into order_items (order_id, line_no, dish_id, name, name_zh, unit_price_cents, quantity)
                    values (?, ?, ?, ?, ?, ?, ?)""")
                    .params(orderId, n++, l.dishId(), l.name(), l.nameZh(), l.unitPriceCents(), l.quantity()).update();
        }
    }

    public record HistoryEntry(OrderStatus status, Long actorId, String note) {}

    public Optional<HistoryEntry> historyAt(UUID orderId, int version) {
        return db.sql("select status, actor_id, note from order_status_history where order_id = ? and version = ?")
                .params(orderId, version)
                .query((rs, i) -> new HistoryEntry(OrderStatus.valueOf(rs.getString(1)), (Long) rs.getObject(2), rs.getString(3)))
                .optional();
    }

    public void insertHistory(UUID orderId, int version, OrderStatus status, Long actorId, String note) {
        db.sql("insert into order_status_history (order_id, version, status, actor_id, note) values (?, ?, ?, ?, ?)")
                .params(orderId, version, status.name(), actorId, note).update();
    }

    /**
     * The only way an order changes. A single-row UPDATE guarded by the
     * allowed source statuses (and optionally the version the caller saw), so
     * two conflicting actions cannot both succeed and nothing waits on a lock
     * longer than one statement.
     */
    /** {@code to == null} keeps the current status. */
    public Optional<Changed> compareAndSet(UUID id, Collection<OrderStatus> from, OrderStatus to, Integer expectedVersion,
                                           Long requireRider, boolean requireNoRider, Long setRider, String reason) {
        return db.sql("""
                update orders set status = coalesce(:to::text, status), version = version + 1, updated_at = now(),
                       rider_id = coalesce(:setRider, rider_id),
                       cancel_reason = coalesce(:reason, cancel_reason)
                where id = :id and status = any(:from)
                  and (:expected::int is null or version = :expected::int)
                  and (:requireRider::bigint is null or rider_id = :requireRider::bigint)
                  and (not :requireNoRider or rider_id is null)
                returning version, status, customer_id, restaurant_id, rider_id, updated_at""")
                .param("to", to == null ? null : to.name())
                .param("setRider", setRider)
                .param("reason", reason)
                .param("id", id)
                .param("from", from.stream().map(Enum::name).toArray(String[]::new))
                .param("expected", expectedVersion)
                .param("requireRider", requireRider)
                .param("requireNoRider", requireNoRider)
                .query((rs, n) -> new Changed(rs.getInt("version"), OrderStatus.valueOf(rs.getString("status")),
                        rs.getLong("customer_id"), rs.getLong("restaurant_id"), (Long) rs.getObject("rider_id"),
                        rs.getTimestamp("updated_at").toInstant()))
                .optional();
    }

    public List<Line> items(UUID orderId) {
        return db.sql("""
                select dish_id, name, name_zh, unit_price_cents, quantity from order_items
                where order_id = ? order by line_no""")
                .param(orderId)
                .query((rs, n) -> new Line(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5)))
                .list();
    }

    public List<Step> history(UUID orderId) {
        return db.sql("select version, status, at, note from order_status_history where order_id = ? order by version")
                .param(orderId)
                .query((rs, n) -> new Step(rs.getInt(1), OrderStatus.valueOf(rs.getString(2)),
                        rs.getTimestamp(3).toInstant(), rs.getString(4)))
                .list();
    }

    /** One keyset page (limit + 1 rows, see Cursor.page) of orders matching an owner column, newest first. */
    public List<Order> page(String ownerColumn, Long ownerId, String status, com.wokflow.api.common.Cursor after, int limit) {
        if (!java.util.Set.of("customer_id", "restaurant_id", "rider_id", "none").contains(ownerColumn)) {
            throw new IllegalArgumentException(ownerColumn);
        }
        String owner = ownerColumn.equals("none") ? "true" : ownerColumn + " = :owner";
        return db.sql("select " + COLS + " from orders where " + owner + """
                  and (:status::text is null or status = :status::text)
                  and (:at::timestamptz is null or (created_at, id) < (:at::timestamptz, :id::uuid))
                order by created_at desc, id desc limit :n""")
                .param("owner", ownerId).param("status", status)
                .param("at", after == null ? null : Timestamp.from(after.at())).param("id", after == null ? null : after.id())
                .param("n", limit + 1)
                .query(OrderRepository::map).list();
    }

    /** A rider's deliveries that are not finished yet. */
    public List<Order> activeForRider(long riderId) {
        return db.sql("select " + COLS + " from orders where rider_id = ? and status in ('ACCEPTED', 'READY', 'PICKED_UP') order by created_at")
                .param(riderId).query(OrderRepository::map).list();
    }

    /** Active orders plus the last day of finished ones, for the merchant board. */
    public List<Order> forRestaurant(long restaurantId) {
        return db.sql("select " + COLS + """
                 from orders where restaurant_id = ? and status <> 'PENDING_PAYMENT'
                  and (status in ('PAID', 'ACCEPTED', 'READY', 'PICKED_UP') or updated_at > now() - interval '1 day')
                order by created_at desc limit 200""")
                .param(restaurantId).query(OrderRepository::map).list();
    }

    public List<Order> openJobs(int limit) {
        return db.sql("select " + COLS + """
                 from orders where rider_id is null and status in ('ACCEPTED', 'READY')
                order by updated_at limit ?""")
                .param(limit).query(OrderRepository::map).list();
    }

    public List<UUID> overdue(Instant now, int limit) {
        return db.sql("""
                select id from orders where status = 'PENDING_PAYMENT' and payment_deadline < ?
                order by payment_deadline limit ?""")
                .params(Timestamp.from(now), limit).query(UUID.class).list();
    }

    public String customerName(long customerId) {
        return db.sql("select display_name from users where id = ?").param(customerId).query(String.class).single();
    }

    static Order map(ResultSet rs, int n) throws SQLException {
        return new Order(rs.getObject("id", UUID.class), rs.getLong("customer_id"), rs.getLong("restaurant_id"),
                OrderStatus.valueOf(rs.getString("status")), rs.getInt("subtotal_cents"), rs.getInt("delivery_fee_cents"),
                rs.getInt("commission_cents"), rs.getInt("total_cents"), rs.getString("delivery_address"),
                rs.getString("delivery_notes"), (Long) rs.getObject("rider_id"), rs.getString("request_hash"),
                rs.getString("cancel_reason"), rs.getTimestamp("payment_deadline").toInstant(), rs.getInt("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                (Double) rs.getObject("delivery_lat"), (Double) rs.getObject("delivery_lng"), (Integer) rs.getObject("distance_m"));
    }
}
