package com.wokflow.api.auth;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    public record User(long id, String email, String passwordHash, String displayName, Role role, boolean approved) {
        public User(long id, String email, String passwordHash, String displayName, Role role) {
            this(id, email, passwordHash, displayName, role, true);
        }
    }

    private final JdbcClient db;

    public UserRepository(JdbcClient db) { this.db = db; }

    public Optional<User> findByEmail(String email) {
        return db.sql("select id, email, password_hash, display_name, role, approved from users where email = ?")
                .param(email).query(UserRepository::map).optional();
    }

    public Optional<User> findById(long id) {
        return db.sql("select id, email, password_hash, display_name, role, approved from users where id = ?")
                .param(id).query(UserRepository::map).optional();
    }

    /** Returns the new id, or empty when the email is taken. */
    public Optional<Long> insert(String email, String hash, String name, Role role) {
        return insert(email, hash, name, role, true);
    }

    public Optional<Long> insert(String email, String hash, String name, Role role, boolean approved) {
        return db.sql("""
                insert into users (email, password_hash, display_name, role, approved) values (?, ?, ?, ?, ?)
                on conflict (email) do nothing returning id""")
                .params(email, hash, name, role.name(), approved).query(Long.class).optional();
    }

    public List<Long> restaurantIds(long userId) {
        return db.sql("select restaurant_id from restaurant_staff where user_id = ? order by restaurant_id")
                .param(userId).query(Long.class).list();
    }

    private static User map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new User(rs.getLong("id"), rs.getString("email"), rs.getString("password_hash"),
                rs.getString("display_name"), Role.valueOf(rs.getString("role")), rs.getBoolean("approved"));
    }
}
