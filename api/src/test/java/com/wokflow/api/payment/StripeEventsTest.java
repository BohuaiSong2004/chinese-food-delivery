package com.wokflow.api.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Event bodies trimmed from Stripe's documented examples to the fields the normalizer reads. */
class StripeEventsTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    static Optional<JsonNode> run(String event) {
        return StripeEvents.normalize(JSON.readTree(event)).map(JSON::readTree);
    }

    @Test
    void aPaidCheckoutSessionIsAPayment() {
        var e = run("""
                {"id":"evt_1","type":"checkout.session.completed","data":{"object":
                  {"id":"cs_test_a1","object":"checkout.session","payment_status":"paid","payment_intent":"pi_1"}}}""").orElseThrow();
        assertThat(e.get("id").asText()).isEqualTo("stripe:evt_1");
        assertThat(e.get("type").asText()).isEqualTo("payment_intent.succeeded");
        assertThat(e.at("/data/paymentIntent").asText()).isEqualTo("cs_test_a1");
    }

    @Test
    void delayedMethodsWaitForTheAsyncResult() {
        assertThat(run("""
                {"id":"evt_2","type":"checkout.session.completed","data":{"object":{"id":"cs_2","payment_status":"unpaid"}}}""")).isEmpty();
        assertThat(run("""
                {"id":"evt_3","type":"checkout.session.async_payment_succeeded","data":{"object":{"id":"cs_2"}}}""")
                .orElseThrow().get("type").asText()).isEqualTo("payment_intent.succeeded");
        assertThat(run("""
                {"id":"evt_4","type":"checkout.session.async_payment_failed","data":{"object":{"id":"cs_2"}}}""")
                .orElseThrow().get("type").asText()).isEqualTo("payment_intent.payment_failed");
        assertThat(run("""
                {"id":"evt_5","type":"checkout.session.expired","data":{"object":{"id":"cs_3"}}}""")
                .orElseThrow().get("type").asText()).isEqualTo("payment_intent.payment_failed");
    }

    @Test
    void aSucceededRefundFindsItsSessionThroughMetadata() {
        var e = run("""
                {"id":"evt_6","type":"refund.updated","data":{"object":
                  {"id":"re_1","object":"refund","status":"succeeded","payment_intent":"pi_1","metadata":{"session":"cs_test_a1"}}}}""")
                .orElseThrow();
        assertThat(e.get("type").asText()).isEqualTo("refund.succeeded");
        assertThat(e.at("/data/paymentIntent").asText()).isEqualTo("cs_test_a1");
        assertThat(e.at("/data/refund").asText()).isEqualTo("re_1");
        assertThat(run("""
                {"id":"evt_7","type":"refund.created","data":{"object":{"id":"re_2","status":"pending","metadata":{"session":"cs_1"}}}}"""))
                .isEmpty();
        assertThat(run("""
                {"id":"evt_8","type":"refund.updated","data":{"object":{"id":"re_3","status":"succeeded","metadata":{}}}}"""))
                .isEmpty(); // not one of ours
    }

    @Test
    void everythingElseIsAcknowledgedAndIgnored() {
        assertThat(run("""
                {"id":"evt_9","type":"customer.created","data":{"object":{"id":"cus_1"}}}""")).isEmpty();
        assertThat(run("{\"type\":\"checkout.session.completed\"}")).isEmpty();
    }
}
