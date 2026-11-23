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
    void readsTheOpensslLayoutAndRejectsAFileWithoutThePublicKey() throws Exception {
        var gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = gen.generateKeyPair();
        Path both = dir.resolve("jwt.pem");
        Files.writeString(both, pem("PRIVATE KEY", pair.getPrivate().getEncoded()) + pem("PUBLIC KEY", pair.getPublic().getEncoded()));
        assertThat(JwtKeys.read(both).getPublic()).isEqualTo(pair.getPublic());

        Path onlyPrivate = dir.resolve("private.pem");
        Files.writeString(onlyPrivate, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        assertThatThrownBy(() -> JwtKeys.read(onlyPrivate)).hasMessageContaining("PUBLIC KEY");
    }
}
