package com.wokflow.api.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.wokflow.api.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WebhookVerifierTest {
    static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    final WebhookVerifier v = new WebhookVerifier(com.wokflow.api.support.TestProps.payments(
            new AppProperties.Payments(null, null, "whsec_unit", Duration.ofMinutes(5), null, null, null)),
            Clock.fixed(NOW, ZoneOffset.UTC));
    final byte[] body = "{\"id\":\"evt_1\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void acceptsAFreshValidSignature() {
        assertThat(v.verify(body, WebhookVerifier.header("whsec_unit", NOW.getEpochSecond(), body))).isTrue();
    }

    @Test
    void acceptsAnyOfSeveralSignaturesDuringSecretRotation() {
        String old = WebhookVerifier.header("whsec_old", NOW.getEpochSecond(), body);
        String current = WebhookVerifier.header("whsec_unit", NOW.getEpochSecond(), body);
        assertThat(v.verify(body, old + "," + current.substring(current.indexOf("v1=")))).isTrue();
    }

    @Test
    void acceptsTheNewAndTheOldSecretWhileTheSettingListsBoth() {
        var rotating = new WebhookVerifier(com.wokflow.api.support.TestProps.payments(
                new AppProperties.Payments(null, null, "whsec_new, whsec_unit", Duration.ofMinutes(5), null, null, null)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        long t = NOW.getEpochSecond();
        assertThat(rotating.verify(body, WebhookVerifier.header("whsec_new", t, body))).isTrue();
        assertThat(rotating.verify(body, WebhookVerifier.header("whsec_unit", t, body))).isTrue();
        assertThat(rotating.verify(body, WebhookVerifier.header("whsec_other", t, body))).isFalse();
        assertThat(v.verify(body, WebhookVerifier.header("whsec_new", t, body))).isFalse(); // not before the setting lists it
    }

    @Test
    void rejectsTamperingReplayAndGarbage() {
        long t = NOW.getEpochSecond();
        String good = WebhookVerifier.header("whsec_unit", t, body);
        assertThat(v.verify("{\"id\":\"evt_2\"}".getBytes(), good)).isFalse();                       // body changed
        assertThat(v.verify(body, good.replace("t=" + t, "t=" + (t + 1)))).isFalse();                 // timestamp changed
        assertThat(v.verify(body, WebhookVerifier.header("whsec_unit", t - 301, body))).isFalse();     // too old
        assertThat(v.verify(body, WebhookVerifier.header("whsec_unit", t + 301, body))).isFalse();     // from the future
        assertThat(v.verify(body, WebhookVerifier.header("other", t, body))).isFalse();
        assertThat(v.verify(body, null)).isFalse();
        assertThat(v.verify(body, "t=abc,v1=00")).isFalse();
        assertThat(v.verify(body, "t=" + t + ",v1=zz")).isFalse();
        assertThat(v.verify(body, "v1=" + good.substring(good.indexOf("v1=") + 3))).isFalse();         // no timestamp
    }

    /** Same fixed vector as mockpay's SignaturesTest (computed with openssl, not with this code). */
    @Test
    void knownVector() {
        assertThat(WebhookVerifier.header("whsec_vector", 1700000000L, "{}".getBytes()))
                .isEqualTo("t=1700000000,v1=" + VECTOR);
    }

    static final String VECTOR = "1f8abe454aaf2153a9ae950a6602cbee309c717fb69ac06067bcf0e2ee2f359d";
}
