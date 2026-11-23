package com.wokflow.api.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.wokflow.api.config.AppProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/** Issues short-lived ES256 access tokens. */
@Service
public class TokenService {
    public record AccessToken(String value, Instant expiresAt) {}

    private final JwtEncoder encoder;
    private final JwtKeys keys;
    private final AppProperties props;
    private final Clock clock;

    public TokenService(JwtKeys keys, AppProperties props, Clock clock) {
        this.keys = keys;
        this.props = props;
        this.clock = clock;
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.signingJwk())));
    }

    public AccessToken issue(UserRepository.User user, List<Long> restaurantIds) {
        Instant now = clock.instant();
        Instant exp = now.plus(props.jwt().accessTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.jwt().issuer())
                .subject(Long.toString(user.id()))
                .audience(List.of("wokflow-api"))
                .issuedAt(now)
                .expiresAt(exp)
                .id(UUID.randomUUID().toString())
                .claim("role", user.role().name())
                .claim("name", user.displayName())
                .claim("rid", restaurantIds)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(keys.keyId()).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(token, exp);
    }
}
