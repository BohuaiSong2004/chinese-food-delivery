package com.wokflow.api.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wokflow.api.config.AppProperties;
import com.wokflow.api.support.TestProps;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;

/**
 * Runs the Stripe adapter against stripe-mock, which rejects any request that does not match
 * Stripe's OpenAPI spec (unknown or misspelled parameters, wrong types, missing required ones).
 * Responses are canned fixtures, so this proves the request shapes, not Stripe's behaviour.
 * Set STRIPE_MOCK_URL to use a stripe-mock that is already running (CI installs v0.206.0 with
 * `go install`; Docker Hub images lag the releases).
 */
class StripeClientIT {
    static GenericContainer<?> container;
    static String url;

    @BeforeAll
    static void start() {
        url = System.getenv("STRIPE_MOCK_URL");
        if (url == null) {
            container = new GenericContainer<>("stripe/stripe-mock:v0.192.0").withExposedPorts(12111);
            container.start();
            url = "http://" + container.getHost() + ":" + container.getMappedPort(12111);
        }
    }

    @AfterAll
    static void stop() {
        if (container != null) {
            container.stop();
        }
    }

    static AppProperties props(String key) {
        return TestProps.payments(new AppProperties.Payments(null, null, "x", Duration.ofMinutes(5), null, "stripe",
                new AppProperties.Stripe(url, key, null)));
    }

    @Test
    void createsACheckoutSessionAndRefundsItsPaymentIntent() {
        var client = new StripeClient(props("sk_test_wokflow"), RestClient.builder());
        UUID order = UUID.randomUUID();
        var intent = client.createIntent(order, 2350, "Wokflow order " + order.toString().substring(0, 8), "http://localhost:8000/orders/" + order);
        assertThat(intent.id()).startsWith("cs_");
        assertThat(intent.checkoutUrl()).startsWith("https://checkout.stripe.com/");

        var refund = client.refund(intent.id(), 1200, UUID.randomUUID());
        assertThat(refund.id()).startsWith("re_");
        assertThat(refund.status()).isNotBlank();
    }

    @Test
    void theMockReallyValidatesRequests() {
        // guards the test above: a misspelled parameter is refused, so passing means the shapes are right
        var raw = RestClient.builder().baseUrl(url).defaultHeader("Authorization", "Bearer sk_test_wokflow").build();
        var form = StripeClient.sessionForm(UUID.randomUUID(), 100, "x", "http://localhost/");
        form.add("sucess_url", "http://localhost/");
        assertThatThrownBy(() -> raw.post().uri("/v1/checkout/sessions").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    void theProviderSettingPicksExactlyOneAdapter() {
        var runner = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withBean(AppProperties.class, () -> TestProps.payments(new AppProperties.Payments("http://localhost:1", "sk", "x",
                        Duration.ofMinutes(5), null, null, new AppProperties.Stripe(url, "sk_test_wokflow", null))))
                .withBean(RestClient.Builder.class, RestClient::builder)
                .withUserConfiguration(MockPayClient.class, StripeClient.class);
        runner.run(ctx -> assertThat(ctx.getBean(PaymentProvider.class)).isInstanceOf(MockPayClient.class));
        runner.withPropertyValues("wokflow.payments.provider=stripe")
                .run(ctx -> assertThat(ctx.getBean(PaymentProvider.class)).isInstanceOf(StripeClient.class));
    }

    @Test
    void refusesLiveKeys() {
        assertThatThrownBy(() -> new StripeClient(props("sk_live_123"), RestClient.builder()))
                .hasMessageContaining("test-mode");
    }
}
