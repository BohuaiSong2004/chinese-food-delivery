package com.wokflow.mockpay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SignaturesTest {
    /**
     * Fixed vector computed independently with
     * printf '1700000000.{}' | openssl dgst -sha256 -hmac whsec_vector
     * The API's WebhookVerifierTest checks the same value, so signer and verifier agree on the format.
     */
    static final String VECTOR = "1f8abe454aaf2153a9ae950a6602cbee309c717fb69ac06067bcf0e2ee2f359d";

    @Test
    void knownVector() {
        assertThat(Signatures.header("whsec_vector", 1700000000L, "{}")).isEqualTo("t=1700000000,v1=" + VECTOR);
    }
}
