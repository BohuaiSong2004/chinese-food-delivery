package com.wokflow.api.onboarding;

import com.wokflow.api.auth.CurrentUser;
import com.wokflow.api.auth.Role;
import com.wokflow.api.catalog.CatalogRepository;
import com.wokflow.api.catalog.Restaurant;
import com.wokflow.api.catalog.RestaurantStatus;
import com.wokflow.api.catalog.Tags;
import com.wokflow.api.common.ApiException;
import com.wokflow.api.common.Tx;
import com.wokflow.api.ledger.LedgerService;
import com.wokflow.api.outbox.Outbox;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * How restaurants and riders join the platform. A restaurant owner applies (the restaurant exists
 * as PENDING, invisible to customers, editable by its owner), an admin approves or rejects it, and
 * a LIVE restaurant can later be suspended and reinstated. Every review step is one compare-and-set
 * on the status, so two admins clicking at once cannot both act, and each one is audit-logged.
 */
@Service
public class OnboardingService {
    static final int MAX_PENDING_PER_OWNER = 3;
    static final Set<String> ZONES = Set.of("America/Los_Angeles", "America/New_York", "America/Chicago",
            "America/Denver", "America/Phoenix", "Pacific/Honolulu", "America/Anchorage");

    public enum Decision {
        APPROVE(EnumSet.of(RestaurantStatus.PENDING), RestaurantStatus.LIVE, false),
        REJECT(EnumSet.of(RestaurantStatus.PENDING), RestaurantStatus.REJECTED, true),
        SUSPEND(EnumSet.of(RestaurantStatus.LIVE), RestaurantStatus.SUSPENDED, true),
        REINSTATE(EnumSet.of(RestaurantStatus.SUSPENDED), RestaurantStatus.LIVE, false);

        final Set<RestaurantStatus> from;
        final RestaurantStatus to;
        final boolean needsNote;

        Decision(Set<RestaurantStatus> from, RestaurantStatus to, boolean needsNote) {
            this.from = from;
            this.to = to;
            this.needsNote = needsNote;
        }
    }

    private final JdbcClient db;
    private final CatalogRepository catalog;
    private final LedgerService ledger;
    private final Outbox outbox;
    private final Tx tx;
    private final Clock clock;

    public OnboardingService(JdbcClient db, CatalogRepository catalog, LedgerService ledger, Outbox outbox, Tx tx,
                             Clock clock) {
        this.db = db;
        this.catalog = catalog;
        this.ledger = ledger;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ restaurant owners

    /** Creates the restaurant as PENDING with the applicant as its staff. Returns the new id. */
    public long apply(CurrentUser owner, RestaurantProfile p) {
        if (owner.role() != Role.MERCHANT) {
            throw new ApiException(HttpStatus.FORBIDDEN, "merchants_only", "Sign up as a restaurant owner to apply");
        }
        validate(p);
        return tx.run(() -> {
            // Serialize one owner's applications so the pending limit cannot be raced past.
            db.sql("select id from users where id = ? for update").param(owner.id()).query(Long.class).single();
            long pending = db.sql("""
                    select count(*) from restaurants r join restaurant_staff s on s.restaurant_id = r.id
                    where s.user_id = ? and r.status = 'PENDING'""").param(owner.id()).query(Long.class).single();
            if (pending >= MAX_PENDING_PER_OWNER) {
                throw ApiException.conflict("too_many_pending", "You already have " + pending + " applications under review");
            }
            String base = slug(p.name());
            for (int n = 1; ; n++) {
                String slug = n == 1 ? base : base + "-" + n;
                Long id = db.sql("""
                        insert into restaurants (slug, name, name_zh, description, cuisine, tags, neighborhood, address,
                            delivery_fee_cents, min_order_cents, prep_minutes, opens_at, closes_at, open_24h, time_zone,
                            status, submitted_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                        on conflict (slug) do nothing returning id""")
                        .params(slug, p.name().strip(), p.nameZh().strip(), description(p), p.cuisine(),
                                Tags.check(p.tags()).toArray(String[]::new), p.neighborhood().strip(), p.address().strip(),
                                p.deliveryFeeCents(), p.minOrderCents(), p.prepMinutes(), LocalTime.parse(p.opensAt()),
                                LocalTime.parse(p.closesAt()), p.open24h(), zone(p), java.sql.Timestamp.from(clock.instant()))
                        .query(Long.class).optional().orElse(null);
                if (id != null) {
                    db.sql("insert into restaurant_staff (restaurant_id, user_id) values (?, ?)").params(id, owner.id()).update();
                    return id;
                }
                if (n > 50) {
                    throw ApiException.conflict("name_taken", "Too many restaurants with this name");
                }
            }
        });
    }

    /** The owner edits the profile while it is under review or live; optimistic on the restaurant version. */
    public Restaurant update(CurrentUser user, long restaurantId, RestaurantProfile p, long expectedVersion) {
        user.requireStaffOf(restaurantId);
        validate(p);
        tx.runVoid(() -> {
            int n = db.sql("""
                    update restaurants set name = ?, name_zh = ?, description = ?, cuisine = ?, tags = ?, neighborhood = ?,
                        address = ?, delivery_fee_cents = ?, min_order_cents = ?, prep_minutes = ?, opens_at = ?,
                        closes_at = ?, open_24h = ?, time_zone = ?, version = version + 1, menu_version = menu_version + 1,
                        updated_at = now()
                    where id = ? and version = ?""")
                    .params(p.name().strip(), p.nameZh().strip(), description(p), p.cuisine(),
                            Tags.check(p.tags()).toArray(String[]::new), p.neighborhood().strip(), p.address().strip(),
                            p.deliveryFeeCents(), p.minOrderCents(), p.prepMinutes(), LocalTime.parse(p.opensAt()),
                            LocalTime.parse(p.closesAt()), p.open24h(), zone(p), restaurantId, expectedVersion)
                    .update();
            if (n == 0) {
                catalog.restaurant(restaurantId).orElseThrow(() -> ApiException.notFound("Restaurant"));
                throw ApiException.conflict("stale_version", "The restaurant was changed elsewhere; reload and try again");
            }
            outbox.publish(Outbox.RESTAURANT_CHANGED, Long.toString(restaurantId), Map.of("restaurantId", restaurantId));
        });
        return catalog.restaurant(restaurantId).orElseThrow();
    }

    /** A rejected application can be fixed and sent back for review. */
    public void resubmit(CurrentUser user, long restaurantId) {
        user.requireStaffOf(restaurantId);
        tx.runVoid(() -> {
            int n = db.sql("""
                    update restaurants set status = 'PENDING', submitted_at = ?, version = version + 1, updated_at = now()
                    where id = ? and status = 'REJECTED'""")
                    .params(java.sql.Timestamp.from(clock.instant()), restaurantId).update();
            if (n == 0) {
                throw ApiException.conflict("not_rejected", "Only a rejected application can be resubmitted");
            }
        });
    }

    // ------------------------------------------------------------------ admin review

    public Restaurant decide(CurrentUser admin, long restaurantId, Decision d, String note) {
        requireAdmin(admin);
        if (d.needsNote && (note == null || note.isBlank())) {
            throw ApiException.badRequest("note_required", "Give the owner a reason");
        }
        tx.runVoid(() -> {
            Restaurant r = catalog.restaurant(restaurantId).orElseThrow(() -> ApiException.notFound("Restaurant"));
            List<String> from = d.from.stream().map(Enum::name).toList();
            int n = db.sql("""
                    update restaurants set status = ?, status_note = ?, reviewed_by = ?, reviewed_at = ?,
                        version = version + 1, menu_version = menu_version + 1, updated_at = now()
                    where id = ? and status = any(?)""")
                    .params(d.to.name(), note == null ? null : note.strip(), admin.id(),
                            java.sql.Timestamp.from(clock.instant()), restaurantId, from.toArray(String[]::new))
                    .update();
            if (n == 0) {
                throw ApiException.conflict("invalid_transition", "Restaurant is " + r.status() + "; cannot "
                        + d.name().toLowerCase(Locale.ROOT));
            }
            if (d.to == RestaurantStatus.LIVE) {
                ledger.ensureMerchantAccount(restaurantId, r.name());
            }
            audit(admin, "restaurant." + d.name().toLowerCase(Locale.ROOT), "restaurant:" + restaurantId, note);
            // Listing, search and caches follow the new status.
            outbox.publish(Outbox.RESTAURANT_CHANGED, Long.toString(restaurantId), Map.of("restaurantId", restaurantId));
        });
        return catalog.restaurant(restaurantId).orElseThrow();
    }

    public void decideRider(CurrentUser admin, long userId, boolean approve, String note) {
        requireAdmin(admin);
        if (!approve && (note == null || note.isBlank())) {
            throw ApiException.badRequest("note_required", "Give the rider a reason");
        }
        tx.runVoid(() -> {
            int n = db.sql("""
                    update users set approved = ?, reviewed_at = ?, review_note = ?
                    where id = ? and role = 'RIDER' and not approved""")
                    .params(approve, java.sql.Timestamp.from(clock.instant()), note, userId).update();
            if (n == 0) {
                throw ApiException.conflict("not_pending", "No rider waiting for approval with that id");
            }
            audit(admin, approve ? "rider.approve" : "rider.reject", "user:" + userId, note);
        });
    }

    public record PendingRestaurant(long id, String slug, String name, String nameZh, String cuisine, String neighborhood,
                                    String address, Instant submittedAt, String ownerName, String ownerEmail) {}

    public record PendingRider(long id, String name, String email, Instant signedUpAt) {}

    public record AuditEntry(long id, String actor, String action, String subject, String note, Instant at) {}

    public Map<String, Object> queue(CurrentUser admin) {
        requireAdmin(admin);
        List<PendingRestaurant> restaurants = db.sql("""
                select r.id, r.slug, r.name, r.name_zh, r.cuisine, r.neighborhood, r.address, r.submitted_at,
                       u.display_name, u.email
                from restaurants r
                left join lateral (select user_id from restaurant_staff s where s.restaurant_id = r.id
                                   order by user_id limit 1) s on true
                left join users u on u.id = s.user_id
                where r.status = 'PENDING' order by r.submitted_at""")
                .query((rs, i) -> new PendingRestaurant(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getTimestamp(8).toInstant(),
                        rs.getString(9), rs.getString(10)))
                .list();
        List<PendingRider> riders = db.sql("""
                select id, display_name, email, created_at from users
                where role = 'RIDER' and not approved and reviewed_at is null order by created_at""")
                .query((rs, i) -> new PendingRider(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant()))
                .list();
        return Map.of("restaurants", restaurants, "riders", riders);
    }

    public List<AuditEntry> auditLog(CurrentUser admin, int limit) {
        requireAdmin(admin);
        return db.sql("""
                select a.id, u.display_name, a.action, a.subject, a.note, a.at
                from audit_log a join users u on u.id = a.actor_id order by a.at desc, a.id desc limit ?""")
                .param(limit)
                .query((rs, i) -> new AuditEntry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getTimestamp(6).toInstant()))
                .list();
    }

    public void audit(CurrentUser actor, String action, String subject, String note) {
        db.sql("insert into audit_log (actor_id, action, subject, note) values (?, ?, ?, ?)")
                .params(actor.id(), action, subject, note).update();
    }

    // ------------------------------------------------------------------ helpers

    private static void requireAdmin(CurrentUser u) {
        if (!u.is(Role.ADMIN)) {
            throw ApiException.forbidden();
        }
    }

    static void validate(RestaurantProfile p) {
        if (!RestaurantProfile.CUISINES.contains(p.cuisine())) {
            throw ApiException.badRequest("unknown_cuisine", "Unknown cuisine: " + p.cuisine());
        }
        if (!p.open24h() && p.opensAt().equals(p.closesAt())) {
            throw ApiException.badRequest("bad_hours", "Opening and closing time are the same; tick “open 24 hours” instead");
        }
        if (p.timeZone() != null && !p.timeZone().isBlank() && !ZONES.contains(p.timeZone())) {
            throw ApiException.badRequest("bad_time_zone", "Unsupported time zone: " + p.timeZone());
        }
    }

    private static String zone(RestaurantProfile p) {
        String z = p.timeZone() == null || p.timeZone().isBlank() ? "America/Los_Angeles" : p.timeZone();
        return ZoneId.of(z).getId();
    }

    private static String description(RestaurantProfile p) {
        return p.description() == null ? "" : p.description().strip();
    }

    /** "Mama Liu's Kitchen 刘妈妈" -> "mama-lius-kitchen"; falls back to "restaurant" for an all-Chinese name. */
    static String slug(String name) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replace("'", "").replace("’", "");
        String s = ascii.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (s.length() > 48) {
            s = s.substring(0, 48).replaceAll("-+$", "");
        }
        return s.isEmpty() ? "restaurant" : s;
    }
}
