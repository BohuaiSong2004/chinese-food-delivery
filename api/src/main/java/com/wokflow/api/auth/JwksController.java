package com.wokflow.api.auth;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public verification keys, so other services can validate access tokens without calling this one. */
@RestController
public class JwksController {
    private final JwtKeys keys;

    public JwksController(JwtKeys keys) { this.keys = keys; }

    @GetMapping("/.well-known/jwks.json")
    Map<String, Object> jwks() {
        return keys.publicJwks().toJSONObject();
    }
}
