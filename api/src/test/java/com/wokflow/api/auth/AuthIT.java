package com.wokflow.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.wokflow.api.support.IntegrationTest;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AuthIT extends IntegrationTest {
    @Autowired
    TokenService tokens;
    @Autowired
    UserRepository users;

    private String refreshCookie(com.wokflow.api.support.Http.Res r) {
        String set = r.headers().get("set-cookie").stream().filter(c -> c.startsWith("wf_refresh=")).findFirst().orElseThrow();
        assertThat(set).contains("HttpOnly").contains("SameSite=Strict").contains("Path=/api/auth");
        return set.substring("wf_refresh=".length(), set.indexOf(';'));
    }

    private com.wokflow.api.support.Http.Res refresh(String cookie) {
        return http.raw("POST", "/api/auth/refresh", null, Map.of("Cookie", "wf_refresh=" + cookie));
    }

    @Test
    void signUpSignInAndAccessAProtectedEndpoint() {
        String email = unique("Auth") + "@Example.com";
        var up = http.post("/api/auth/signup", null, Map.of("email", email, "password", "long-enough-pw", "name", "Ada"));
        assertThat(up.status()).isEqualTo(201);
        assertThat(up.body().get("user").get("email").asText()).isEqualTo(email.toLowerCase());
        assertThat(up.body().get("user").get("role").asText()).isEqualTo("CUSTOMER");

        assertThat(http.post("/api/auth/signup", null, Map.of("email", email, "password", "long-enough-pw", "name", "Ada")).code())
                .isEqualTo("email_taken");
        assertThat(http.post("/api/auth/signin", null, Map.of("email", email, "password", "wrong-password")).status()).isEqualTo(401);
        assertThat(http.post("/api/auth/signin", null, Map.of("email", "nobody@example.com", "password", "whatever-123")).code())
                .isEqualTo("invalid_credentials");

        String token = http.post("/api/auth/signin", null, Map.of("email", email, "password", "long-enough-pw")).body()
                .get("accessToken").asText();
        assertThat(http.get("/api/auth/me", token).body().get("name").asText()).isEqualTo("Ada");
        assertThat(http.get("/api/auth/me", null).status()).isEqualTo(401);
    }

    @Test
    void shortPasswordsAreRejected() {
        var r = http.post("/api/auth/signup", null, Map.of("email", unique("p") + "@example.com", "password", "short", "name", "X"));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("fields").get("password").asText()).contains("10 to 128");
    }

    @Test
    void merchantTokenCarriesRestaurantScope() {
        String token = signIn("merchant@example.com");
        var me = http.get("/api/auth/me", token).body();
        assertThat(me.get("role").asText()).isEqualTo("MERCHANT");
        assertThat(me.get("restaurantIds")).hasSize(3);
        // a customer cannot reach merchant endpoints; a merchant cannot reach admin ones
        assertThat(http.get("/api/merchant/restaurants", newCustomer()).status()).isEqualTo(403);
        assertThat(http.get("/api/admin/ledger/audit", token).status()).isEqualTo(403);
    }

    @Test
    void tamperedOrForeignOrExpiredTokensAreRejected() throws Exception {
        String token = newCustomer();
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
        String forged = payload.replace("\"CUSTOMER\"", "\"ADMIN\"");
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(forged.getBytes()) + "." + parts[2];
        assertThat(http.get("/api/admin/ledger/audit", tampered).status()).isEqualTo(401);

        String none = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes())
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(forged.getBytes()) + ".";
        assertThat(http.get("/api/auth/me", none).status()).isEqualTo(401);

        var user = users.findByEmail("customer@example.com").orElseThrow();
        clock.advance(Duration.ofHours(-1)); // issued an hour ago, valid for 15 minutes
        String expired = tokens.issue(user, java.util.List.of()).value();
        clock.reset();
        assertThat(http.get("/api/auth/me", expired).status()).isEqualTo(401);
    }

    @Test
    void refreshRotatesAndDetectsReuse() {
        String email = unique("r") + "@example.com";
        var up = http.post("/api/auth/signup", null, Map.of("email", email, "password", "long-enough-pw", "name", "R"));
        String first = refreshCookie(up);

        var r1 = refresh(first);
        assertThat(r1.status()).isEqualTo(200);
        String second = refreshCookie(r1);
        assertThat(second).isNotEqualTo(first);

        // another tab refreshing with the old cookie a moment later still gets an access token
        var concurrent = refresh(first);
        assertThat(concurrent.status()).isEqualTo(200);
        assertThat(concurrent.body().get("accessToken").asText()).isNotBlank();

        // after the grace window, the old token is treated as stolen: the whole family dies
        clock.advance(AuthService.REUSE_GRACE.plusSeconds(1));
        assertThat(refresh(first).code()).isEqualTo("refresh_reused");
        assertThat(refresh(second).status()).isEqualTo(401);
    }

    @Test
    void refreshRequiresSameOriginAndSignOutRevokes() {
        var up = http.post("/api/auth/signup", null, Map.of("email", unique("o") + "@example.com", "password", "long-enough-pw", "name", "O"));
        String cookie = refreshCookie(up);
        var cross = http.raw("POST", "/api/auth/refresh", null, Map.of("Cookie", "wf_refresh=" + cookie, "Origin", "https://evil.example"));
        assertThat(cross.code()).isEqualTo("bad_origin");

        var out = http.raw("POST", "/api/auth/signout", null, Map.of("Cookie", "wf_refresh=" + cookie, "Origin", "http://localhost:5173"));
        assertThat(out.status()).isEqualTo(204);
        assertThat(refresh(cookie).status()).isEqualTo(401);
    }

    @Test
    void refreshWithoutACookieIsJustSignedOut() {
        assertThat(http.raw("POST", "/api/auth/refresh", null, Map.of()).status()).isEqualTo(204);
    }

    @Test
    void jwksPublishesTheVerificationKey() {
        var jwks = http.get("/.well-known/jwks.json", null).body();
        assertThat(jwks.get("keys").get(0).get("kty").asText()).isEqualTo("EC");
        assertThat(jwks.get("keys").get(0).get("crv").asText()).isEqualTo("P-256");
        assertThat(jwks.get("keys").get(0).has("d")).isFalse(); // never the private part
    }
}
