package com.wokflow.api.review;

import com.wokflow.api.auth.CurrentUser;
import com.wokflow.api.auth.Role;
import com.wokflow.api.common.ApiException;
import com.wokflow.api.common.Cursor;
import com.wokflow.api.common.RateLimiter;
import com.wokflow.api.common.Tx;
import com.wokflow.api.order.OrderRepository;
import com.wokflow.api.order.OrderStatus;
import com.wokflow.api.outbox.Outbox;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Reviews of delivered orders. Only the customer who received the order can review it, once,
 * within {@link #WINDOW}; the restaurant's rating sum and count change in the same transaction.
 * Restaurants reply; admins can hide abusive reviews (which takes them out of the rating too).
 */
@Service
public class ReviewService {
    static final Duration WINDOW = Duration.ofDays(14);

    public record Review(UUID orderId, long restaurantId, String author, int rating, String comment, Instant createdAt,
                         String reply, Instant repliedAt, boolean hidden) {}

    public record Rating(double average, int count) {
        public static Rating of(long sum, int count) {
            return new Rating(count == 0 ? 0 : Math.round(sum * 10.0 / count) / 10.0, count);
        }

        /** Shrinks towards a prior with few reviews (one 5-star review should not top the rankings). */
        public static double bayesian(long sum, int count) {
            return (sum + 5 * 4.2) / (count + 5);
        }
    }

    private final JdbcClient db;
    private final OrderRepository orders;
    private final Outbox outbox;
    private final RateLimiter limiter;
    private final Tx tx;
    private final Clock clock;

    public ReviewService(JdbcClient db, OrderRepository orders, Outbox outbox, RateLimiter limiter, Tx tx, Clock clock) {
        this.db = db;
        this.orders = orders;
        this.outbox = outbox;
        this.limiter = limiter;
        this.tx = tx;
        this.clock = clock;
    }

    public Review review(CurrentUser u, UUID orderId, int rating, String comment) {
        var o = orders.find(orderId).orElseThrow(() -> ApiException.notFound("Order"));
        if (u.role() != Role.CUSTOMER || o.customerId() != u.id()) {
            throw ApiException.notFound("Order");
        }
        if (o.status() != OrderStatus.DELIVERED) {
            throw ApiException.conflict("not_delivered", "You can review an order once it has been delivered");
        }
        Instant delivered = db.sql("select max(at) from order_status_history where order_id = ? and status = 'DELIVERED'")
                .param(orderId).query(Timestamp.class).single().toInstant();
        if (delivered.plus(WINDOW).isBefore(clock.instant())) {
            throw ApiException.conflict("review_window_closed", "Reviews are open for " + WINDOW.toDays() + " days after delivery");
        }
        limiter.check("review", Long.toString(u.id()), 20, Duration.ofHours(1));
        String text = comment == null ? "" : comment.strip();
        tx.runVoid(() -> {
            try {
                db.sql("insert into reviews (order_id, restaurant_id, customer_id, rating, comment) values (?, ?, ?, ?, ?)")
                        .params(orderId, o.restaurantId(), u.id(), rating, text).update();
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("already_reviewed", "You already reviewed this order");
            }
            db.sql("update restaurants set rating_sum = rating_sum + ?, rating_count = rating_count + 1 where id = ?")
                    .params(rating, o.restaurantId()).update();
            reindex(o.restaurantId());
        });
        return find(orderId).orElseThrow();
    }

    public Review reply(CurrentUser u, UUID orderId, String reply) {
        Review r = find(orderId).orElseThrow(() -> ApiException.notFound("Review"));
        u.requireStaffOf(r.restaurantId());
        db.sql("update reviews set reply = ?, replied_at = now() where order_id = ?").params(reply.strip(), orderId).update();
        return find(orderId).orElseThrow();
    }

    /** Hiding (or un-hiding) moves the review out of (or back into) the restaurant's rating atomically. */
    public Review moderate(CurrentUser admin, UUID orderId, boolean hide, String reason) {
        if (!admin.is(Role.ADMIN)) {
            throw ApiException.forbidden();
        }
        if (hide && (reason == null || reason.isBlank())) {
            throw ApiException.badRequest("note_required", "Say why the review is hidden");
        }
        tx.runVoid(() -> {
            var row = db.sql(hide
                            ? "update reviews set hidden_at = now(), hidden_reason = ? where order_id = ? and hidden_at is null returning rating, restaurant_id"
                            : "update reviews set hidden_at = null, hidden_reason = ? where order_id = ? and hidden_at is not null returning rating, restaurant_id")
                    .params(hide ? reason.strip() : null, orderId)
                    .query((rs, n) -> new long[] {rs.getInt(1), rs.getLong(2)}).optional()
                    .orElseThrow(() -> ApiException.conflict("no_change", hide ? "Already hidden" : "Not hidden"));
            int sign = hide ? -1 : 1;
            db.sql("update restaurants set rating_sum = rating_sum + ?, rating_count = rating_count + ? where id = ?")
                    .params(sign * row[0], sign, row[1]).update();
            db.sql("insert into audit_log (actor_id, action, subject, note) values (?, ?, ?, ?)")
                    .params(admin.id(), hide ? "review.hide" : "review.unhide", "order:" + orderId, reason).update();
            reindex(row[1]);
        });
        return find(orderId).orElseThrow();
    }

    /** Public, newest first, hidden reviews excluded; authors shown as "Mei L." */
    public Cursor.Page<Review> forRestaurant(long restaurantId, String cursor, Integer limit, boolean includeHidden) {
        int n = Cursor.limit(limit, 10);
        Cursor c = Cursor.decode(cursor);
        List<Review> rows = db.sql("""
                select r.order_id, r.restaurant_id, u.display_name, r.rating, r.comment, r.created_at, r.reply, r.replied_at,
                       r.hidden_at is not null
                from reviews r join users u on u.id = r.customer_id
                where r.restaurant_id = :rid and (:all or r.hidden_at is null)
                  and (:at::timestamptz is null or (r.created_at, r.order_id) < (:at::timestamptz, :id::uuid))
                order by r.created_at desc, r.order_id desc limit :n""")
                .param("rid", restaurantId).param("all", includeHidden)
                .param("at", c == null ? null : Timestamp.from(c.at())).param("id", c == null ? null : c.id())
                .param("n", n + 1)
                .query((rs, i) -> map(rs)).list();
        return Cursor.page(rows, n, r -> new Cursor(r.createdAt(), r.orderId().toString()));
    }

    public Optional<Review> find(UUID orderId) {
        return db.sql("""
                select r.order_id, r.restaurant_id, u.display_name, r.rating, r.comment, r.created_at, r.reply, r.replied_at,
                       r.hidden_at is not null
                from reviews r join users u on u.id = r.customer_id where r.order_id = ?""")
                .param(orderId).query((rs, i) -> map(rs)).optional();
    }

    public Rating rating(long restaurantId) {
        return db.sql("select rating_sum, rating_count from restaurants where id = ?").param(restaurantId)
                .query((rs, i) -> Rating.of(rs.getLong(1), rs.getInt(2))).single();
    }

    private void reindex(long restaurantId) {
        // ratings feed search ranking; restaurant version is not bumped (nothing the owner edited changed)
        outbox.publish(Outbox.RESTAURANT_CHANGED, Long.toString(restaurantId), Map.of("restaurantId", restaurantId));
    }

    private static Review map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Review(rs.getObject(1, UUID.class), rs.getLong(2), shortName(rs.getString(3)), rs.getInt(4), rs.getString(5),
                rs.getTimestamp(6).toInstant(), rs.getString(7), rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant(),
                rs.getBoolean(9));
    }

    /** "Mei Lin" -> "Mei L."; a single name stays as it is. Reviews never show full names or emails. */
    static String shortName(String displayName) {
        String[] parts = displayName.strip().split("\\s+");
        if (parts.length < 2) {
            return parts[0];
        }
        return parts[0] + " " + parts[parts.length - 1].substring(0, 1).toUpperCase(java.util.Locale.ROOT) + ".";
    }

    static void requireRating(int rating) {
        if (rating < 1 || rating > 5) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_rating", "Rating must be 1 to 5 stars");
        }
    }
}
