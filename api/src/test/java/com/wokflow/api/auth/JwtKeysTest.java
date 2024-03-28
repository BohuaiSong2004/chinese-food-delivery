package com.wokflow.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JwtKeysTest {
    @TempDir
    Path dir;

    static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    @Test
    void racingReplicasEndUpWithTheSameGeneratedKey() throws Exception {
        Path file = dir.resolve("shared.pem");
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
        try (var files = Files.list(dir)) {
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
}
