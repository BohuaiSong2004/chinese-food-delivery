package com.wokflow.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wokflow.api.config.AppProperties;
import com.wokflow.api.config.SecurityConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtKeysTest {
    @TempDir
    Path dir;

    static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    @Test
    void racingReplicasEndUpWithTheSameGeneratedKey() throws Exception {
        Path file = dir.resolve("keys").resolve("shared.pem"); // parent created on demand
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<java.security.KeyPair>>();
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(() -> JwtKeys.createShared(file)));
        }
        var first = futures.getFirst().get().getPublic();
        for (var f : futures) {
            assertThat(f.get().getPublic()).isEqualTo(first);
        }
        pool.shutdown();
        try (var files = Files.list(file.getParent())) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("shared.pem"); // no temp files left
        }
    }

    @Test
    void readsTheOpensslLayoutAndRejectsAFileWithoutThePublicKey() throws Exception {
        var gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = gen.generateKeyPair();
        Path both = dir.resolve("jwt.pem");
        Files.writeString(both, pem("PRIVATE KEY", pair.getPrivate().getEncoded()) + pem("PUBLIC KEY", pair.getPublic().getEncoded()));
        assertThat(JwtKeys.read(both).getPublic()).isEqualTo(pair.getPublic());
        assertThat(JwtKeys.parse(Files.readString(both), "env").getPublic()).isEqualTo(pair.getPublic()); // JWT_PRIVATE_KEY

        Path onlyPrivate = dir.resolve("private.pem");
        Files.writeString(onlyPrivate, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        assertThatThrownBy(() -> JwtKeys.read(onlyPrivate)).hasMessageContaining("PUBLIC KEY");
    }

    static AppProperties props(Path key, String previous) {
        return com.wokflow.api.support.TestProps.jwt(new AppProperties.Jwt("wokflow", null, key.toString(), true,
                Duration.ofMinutes(15), Duration.ofDays(30), false, previous, null));
    }

    static String publicPem(JwtKeys keys) {
        return pem("PUBLIC KEY", keys.publicKey().getEncoded());
    }

    @Test
    void tokensSignedBeforeARotationVerifyUntilTheOldKeyIsDropped() throws Exception {
        var clock = Clock.systemUTC();
        var user = new UserRepository.User(7, "a@example.com", "x", "A", Role.CUSTOMER, true);
        JwtKeys old = new JwtKeys(props(dir.resolve("old.pem"), null));
        String oldToken = new TokenService(old, props(dir.resolve("old.pem"), null), clock).issue(user, List.of()).value();

        // rotate: a new signing key, the old public key listed as retired
        JwtKeys rotated = new JwtKeys(props(dir.resolve("new.pem"), publicPem(old)));
        var rotatedProps = props(dir.resolve("new.pem"), publicPem(old));
        JwtDecoder decoder = SecurityConfig.decoder(rotated, rotatedProps);
        String newToken = new TokenService(rotated, rotatedProps, clock).issue(user, List.of()).value();
        assertThat(decoder.decode(oldToken).getSubject()).isEqualTo("7");
        assertThat(decoder.decode(newToken).getSubject()).isEqualTo("7");
        assertThat(rotated.publicJwks().getKeys()).extracting(k -> k.getKeyID()).containsExactly(rotated.keyId(), old.keyId());
        assertThat(rotated.publicJwks().getKeys()).allMatch(k -> !k.isPrivate());

        // after the access-token lifetime the old key is removed and its tokens stop verifying
        JwtKeys done = new JwtKeys(props(dir.resolve("new.pem"), null));
        JwtDecoder after = SecurityConfig.decoder(done, props(dir.resolve("new.pem"), null));
        assertThatThrownBy(() -> after.decode(oldToken)).isInstanceOf(JwtException.class);
        assertThat(after.decode(newToken).getSubject()).isEqualTo("7");
    }

    @Test
    void theCurrentKeyListedAsRetiredIsNotPublishedTwice() throws Exception {
        JwtKeys first = new JwtKeys(props(dir.resolve("k.pem"), null));
        JwtKeys again = new JwtKeys(props(dir.resolve("k.pem"), publicPem(first) + "\n" + publicPem(first)));
        assertThat(again.publicJwks().getKeys()).hasSize(1);
    }
}
