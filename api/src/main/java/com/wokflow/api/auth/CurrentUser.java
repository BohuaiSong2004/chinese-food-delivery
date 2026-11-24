package com.wokflow.api.auth;

import com.wokflow.api.common.ApiException;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The caller, read from the verified access token only (no database lookup). */
public record CurrentUser(long id, Role role, String name, Set<Long> restaurantIds, boolean pending) {

    public CurrentUser(long id, Role role, String name, Set<Long> restaurantIds) {
        this(id, role, name, restaurantIds, false);
    }

    public static CurrentUser from(Jwt jwt) {
        Object raw = jwt.getClaims().get("rid");
        List<?> rids = raw instanceof List<?> l ? l : List.of();
        return new CurrentUser(
                Long.parseLong(jwt.getSubject()),
                Role.valueOf(jwt.getClaimAsString("role")),
                jwt.getClaimAsString("name"),
                Set.copyOf(rids.stream().map(o -> o instanceof Number n ? n.longValue() : Long.parseLong(o.toString())).toList()),
                Boolean.TRUE.equals(jwt.getClaim("pending")));
    }

    public static CurrentUser require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            return from(token.getToken());
        }
        throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "unauthenticated", "Sign in first");
    }

    public void requireStaffOf(long restaurantId) {
        if (role == Role.ADMIN) {
            return;
        }
        if (role != Role.MERCHANT || !restaurantIds.contains(restaurantId)) {
            throw ApiException.forbidden();
        }
    }

    public boolean is(Role r) { return role == r; }

    /** A rider whose application an admin has not approved yet. */
    public void requireApproved() {
        if (pending) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "pending_approval",
                    "Your account is waiting for approval");
        }
    }
}
