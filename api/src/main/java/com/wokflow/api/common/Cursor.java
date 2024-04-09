package com.wokflow.api.common;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

/**
 * Keyset pagination: the cursor is the (timestamp, id) of the last row on the previous page, so
 * the next page is "WHERE (ts, id) < (cursor) ORDER BY ts DESC, id DESC LIMIT n". Unlike OFFSET it
 * costs the same on page 500 as on page 1, and rows inserted meanwhile never shift or duplicate
 * what the reader sees. Opaque to clients (base64url).
 */
public record Cursor(Instant at, String id) {
    public static final int MAX_PAGE = 50;

    public record Page<T>(List<T> items, String next) {}

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((at.toEpochMilli() * 1000 + at.getNano() / 1000 % 1000 + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    /** Null for the first page. */
    public static Cursor decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            String s = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
            int i = s.indexOf(':');
            long micros = Long.parseLong(s.substring(0, i));
            String id = s.substring(i + 1);
            if (id.isEmpty() || id.length() > 64) {
                throw new IllegalArgumentException();
            }
            return new Cursor(Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000), Math.floorMod(micros, 1_000_000) * 1000), id);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("bad_cursor", "Invalid page cursor");
        }
    }

    public static int limit(Integer requested, int dflt) {
        return requested == null ? dflt : Math.clamp(requested, 1, MAX_PAGE);
    }

    /** Builds a page from up to limit + 1 fetched rows: the extra row only says there is more. */
    public static <T> Page<T> page(List<T> rows, int limit, Function<T, Cursor> key) {
        if (rows.size() <= limit) {
            return new Page<>(rows, null);
        }
        List<T> items = rows.subList(0, limit);
        return new Page<>(List.copyOf(items), key.apply(items.getLast()).encode());
    }
}
