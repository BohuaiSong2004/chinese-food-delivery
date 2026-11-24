package com.wokflow.api.auth;

import com.wokflow.api.common.ApiException;
import com.wokflow.api.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    static final String COOKIE = "wf_refresh";

    public record SignUpRequest(@NotBlank @Email @Size(max = 254) String email,
                                @NotBlank @Size(min = 10, max = 128, message = "must be 10 to 128 characters") String password,
                                @NotBlank @Size(max = 60) String name,
                                @jakarta.validation.constraints.Pattern(regexp = "CUSTOMER|MERCHANT|RIDER") String accountType) {}

    public record SignInRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 128) String password) {}

    public record UserView(long id, String email, String name, Role role, List<Long> restaurantIds, boolean pending) {}

    public record SessionView(String accessToken, Instant expiresAt, UserView user) {}

    private final AuthService auth;
    private final AppProperties props;
    private final PasswordService passwords;

    public AuthController(AuthService auth, AppProperties props, PasswordService passwords) {
        this.auth = auth;
        this.passwords = passwords;
        this.props = props;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    SessionView signUp(@Valid @RequestBody SignUpRequest req, HttpServletRequest http, HttpServletResponse res) {
        Role type = req.accountType() == null ? Role.CUSTOMER : Role.valueOf(req.accountType());
        return respond(auth.signUp(req.email(), req.password(), req.name(), http.getRemoteAddr(), type), res);
    }

    @PostMapping("/signin")
    SessionView signIn(@Valid @RequestBody SignInRequest req, HttpServletRequest http, HttpServletResponse res) {
        return respond(auth.signIn(req.email(), req.password(), http.getRemoteAddr()), res);
    }

    /**
     * Cookie-authenticated, so it checks Origin (SameSite=Strict is the first line of defence).
     * No cookie at all is the normal signed-out state, answered with 204 rather than an error.
     */
    @PostMapping("/refresh")
    org.springframework.http.ResponseEntity<SessionView> refresh(@CookieValue(name = COOKIE, required = false) String token,
                                                                 HttpServletRequest http, HttpServletResponse res) {
        requireSameOrigin(http);
        if (token == null || token.isBlank()) {
            return org.springframework.http.ResponseEntity.noContent().build();
        }
        try {
            return org.springframework.http.ResponseEntity.ok(respond(auth.refresh(token), res));
        } catch (ApiException e) {
            clearCookie(res);
            throw e;
        }
    }

    @PostMapping("/signout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void signOut(@CookieValue(name = COOKIE, required = false) String token, HttpServletRequest http,
                 HttpServletResponse res) {
        requireSameOrigin(http);
        auth.signOut(token);
        clearCookie(res);
    }

    public record Forgot(@NotBlank @Email @Size(max = 254) String email) {}

    public record Reset(@NotBlank @Size(min = 20, max = 100) String token,
                        @NotBlank @Size(min = 10, max = 128, message = "must be 10 to 128 characters") String password) {}

    public record Change(@NotBlank @Size(max = 128) String currentPassword,
                         @NotBlank @Size(min = 10, max = 128, message = "must be 10 to 128 characters") String newPassword) {}

    /** Always 202, whether or not the address has an account. */
    @PostMapping("/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, String> forgot(@Valid @RequestBody Forgot req, HttpServletRequest http) {
        passwords.forgot(req.email(), http.getRemoteAddr(), PasswordService.chinese(http.getHeader(HttpHeaders.ACCEPT_LANGUAGE)));
        return Map.of("status", "If that address has an account, a reset link is on its way.");
    }

    @PostMapping("/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reset(@Valid @RequestBody Reset req) { passwords.reset(req.token(), req.password()); }

    /** Bearer-authenticated; ends every session, including this browser's refresh cookie. */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void change(@Valid @RequestBody Change req, HttpServletResponse res) {
        passwords.change(CurrentUser.require(), req.currentPassword(), req.newPassword());
        clearCookie(res);
    }

    @GetMapping("/me")
    Map<String, Object> me() {
        CurrentUser u = CurrentUser.require();
        return Map.of("id", u.id(), "name", u.name(), "role", u.role(), "restaurantIds", u.restaurantIds(), "pending", u.pending());
    }

    private SessionView respond(AuthService.Session s, HttpServletResponse res) {
        if (s.refreshToken() != null) {
            res.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE, s.refreshToken())
                    .httpOnly(true).secure(props.jwt().refreshCookieSecure()).sameSite("Strict")
                    .path("/api/auth").maxAge(props.jwt().refreshTtl()).build().toString());
        }
        res.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        var u = s.user();
        return new SessionView(s.access().value(), s.access().expiresAt(),
                new UserView(u.id(), u.email(), u.displayName(), u.role(), s.restaurantIds(), !u.approved()));
    }

    private void clearCookie(HttpServletResponse res) {
        res.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE, "").httpOnly(true)
                .secure(props.jwt().refreshCookieSecure()).sameSite("Strict").path("/api/auth").maxAge(0).build().toString());
    }

    /**
     * The refresh cookie only moves between this site and itself. Same origin means the configured
     * public origin, or the origin the request was sent to (its Host header): opening the site as
     * http://127.0.0.1:8000 or from a phone on the LAN used to sign in fine and then lose the session
     * on every reload, because only "http://localhost:8000" was accepted.
     */
    private void requireSameOrigin(HttpServletRequest http) {
        String origin = http.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !origin.equals(props.publicOrigin()) && !isRequestHost(origin, http.getHeader(HttpHeaders.HOST))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "bad_origin", "Cross-origin request refused");
        }
        if (origin == null && "cross-site".equals(http.getHeader("Sec-Fetch-Site"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "bad_origin", "Cross-origin request refused");
        }
    }

    static boolean isRequestHost(String origin, String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            String authority = java.net.URI.create(origin).getRawAuthority();
            return authority != null && authority.equalsIgnoreCase(host.strip());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
