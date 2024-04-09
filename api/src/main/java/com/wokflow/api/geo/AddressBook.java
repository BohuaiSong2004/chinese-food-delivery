package com.wokflow.api.geo;

import com.wokflow.api.auth.CurrentUser;
import com.wokflow.api.common.ApiException;
import com.wokflow.api.common.Tx;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** A customer's saved delivery addresses, each geocoded on save. */
@Service
public class AddressBook {
    static final int MAX_ADDRESSES = 10;

    public record Input(@NotBlank @Size(max = 30) String label,
                        @NotBlank @Size(max = 120) String line1,
                        @Size(max = 60) String line2,
                        @NotBlank @Pattern(regexp = "\\d{5}") String zip,
                        @Size(max = 200) String notes,
                        Double pinLat,
                        Double pinLng) {}

    public record Address(long id, String label, String line1, String line2, String city, String zip, double lat,
                          double lng, String notes, Instant createdAt) {
        public GeoPoint point() { return new GeoPoint(lat, lng); }

        /** One line for receipts, the kitchen ticket and the rider. */
        public String oneLine() {
            return line1 + (line2 == null || line2.isBlank() ? "" : ", " + line2) + ", " + city + " " + zip;
        }
    }

    private final JdbcClient db;
    private final ServiceArea area;
    private final Tx tx;

    public AddressBook(JdbcClient db, ServiceArea area, Tx tx) {
        this.db = db;
        this.area = area;
        this.tx = tx;
    }

    public List<Address> list(CurrentUser u) {
        return db.sql("select " + COLS + " from addresses where user_id = ? and archived_at is null order by created_at, id")
                .param(u.id()).query(AddressBook::map).list();
    }

    public Optional<Address> find(long userId, long id) {
        return db.sql("select " + COLS + " from addresses where id = ? and user_id = ? and archived_at is null")
                .params(id, userId).query(AddressBook::map).optional();
    }

    public Address add(CurrentUser u, Input in) {
        GeoPoint p = area.locate(in.zip(), in.pinLat(), in.pinLng());
        String city = area.place(in.zip()).orElseThrow().city();
        long id = tx.run(() -> {
            db.sql("select id from users where id = ? for update").param(u.id()).query(Long.class).single();
            long count = db.sql("select count(*) from addresses where user_id = ? and archived_at is null")
                    .param(u.id()).query(Long.class).single();
            if (count >= MAX_ADDRESSES) {
                throw ApiException.conflict("too_many_addresses", "You can save up to " + MAX_ADDRESSES + " addresses");
            }
            return db.sql("""
                    insert into addresses (user_id, label, line1, line2, city, zip, lat, lng, notes)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                    .params(u.id(), in.label().strip(), in.line1().strip(), blank(in.line2()), city, in.zip().strip(),
                            p.lat(), p.lng(), blank(in.notes()))
                    .query(Long.class).single();
        });
        return find(u.id(), id).orElseThrow();
    }

    public Address update(CurrentUser u, long id, Input in) {
        GeoPoint p = area.locate(in.zip(), in.pinLat(), in.pinLng());
        String city = area.place(in.zip()).orElseThrow().city();
        int n = db.sql("""
                update addresses set label = ?, line1 = ?, line2 = ?, city = ?, zip = ?, lat = ?, lng = ?, notes = ?
                where id = ? and user_id = ? and archived_at is null""")
                .params(in.label().strip(), in.line1().strip(), blank(in.line2()), city, in.zip().strip(), p.lat(), p.lng(),
                        blank(in.notes()), id, u.id())
                .update();
        if (n == 0) {
            throw ApiException.notFound("Address");
        }
        return find(u.id(), id).orElseThrow();
    }

    public void remove(CurrentUser u, long id) {
        int n = db.sql("update addresses set archived_at = now() where id = ? and user_id = ? and archived_at is null")
                .params(id, u.id()).update();
        if (n == 0) {
            throw ApiException.notFound("Address");
        }
    }

    static final String COLS = "id, label, line1, line2, city, zip, lat, lng, notes, created_at";

    static Address map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Address(rs.getLong("id"), rs.getString("label"), rs.getString("line1"), rs.getString("line2"),
                rs.getString("city"), rs.getString("zip"), rs.getDouble("lat"), rs.getDouble("lng"), rs.getString("notes"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static String blank(String s) {
        return s == null ? "" : s.strip();
    }
}
