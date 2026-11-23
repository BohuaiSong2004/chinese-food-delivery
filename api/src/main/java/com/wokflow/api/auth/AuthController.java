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
                                @NotBlank @Size(max = 60) String name) {}

    public record SignInRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 128) String password) {}

    public record UserView(long id, String email, String name, Role role, List<Long> restaurantIds) {}

    public record SessionView(String accessToken, Instant expiresAt, UserView user) {}

    private final AuthService auth;
    private final AppProperties props;

    public AuthController(AuthService auth, AppProperties props) {
        this.auth = auth;
        this.props = props;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    SessionView signUp(@Valid @RequestBody SignUpRequest req, HttpServletRequest http, HttpServletResponse res) {
        return respond(auth.signUp(req.email(), req.password(), req.name(), http.getRemoteAddr()), res);
    }

    @PostMapping("/signin")
    SessionView signIn(@Valid @RequestBody SignInRequest req, HttpServletRequest http, HttpServletResponse res) {
        return respond(auth.signIn(req.email(), req.password(), http.getRemoteAddr()), res);
    }

    /** Cookie-authenticated, so it checks Origin (SameSite=Strict is the first line of defence). */
    @PostMapping("/refresh")
    SessionView refresh(@CookieValue(name = COOKIE, required = false) String token, HttpServletRequest http,
                        HttpServletResponse res) {
        requireSameOrigin(http);
        try {
            return respond(auth.refresh(token), res);
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

    @GetMapping("/me")
    Map<String, Object> me() {
        CurrentUser u = CurrentUser.require();
        return Map.of("id", u.id(), "name", u.name(), "role", u.role(), "restaurantIds", u.restaurantIds());
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
                new UserView(u.id(), u.email(), u.displayName(), u.role(), s.restaurantIds()));
    }

    private void clearCookie(HttpServletResponse res) {
        res.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE, "").httpOnly(true)
                .secure(props.jwt().refreshCookieSecure()).sameSite("Strict").path("/api/auth").maxAge(0).build().toString());
    }

    private void requireSameOrigin(HttpServletRequest http) {
        String origin = http.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !origin.equals(props.publicOrigin())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "bad_origin", "Cross-origin request refused");
        }
        if (origin == null && "cross-site".equals(http.getHeader("Sec-Fetch-Site"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "bad_origin", "Cross-origin request refused");
        }
    }
}
