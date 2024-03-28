package com.wokflow.api.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("wokflow")
public record AppProperties(
        String publicOrigin,
        Jwt jwt,
        Payments payments,
        Search search,
        HotMenu hotMenu,
        RateLimit rateLimit,
        Seed seed,
        Outbox outbox,
        Orders orders,
        Images images,
        Delivery delivery) {

    /**
     * previousPublicKeys / previousPublicKeysFile: PEM "PUBLIC KEY" blocks of retired signing keys,
     * still accepted (and published in the JWKS) so tokens signed before a rotation keep working
     * until they expire.
     */
    public record Jwt(String issuer, String privateKey, String privateKeyFile, boolean generateIfMissing, Duration accessTtl,
                      Duration refreshTtl, boolean refreshCookieSecure, String previousPublicKeys, String previousPublicKeysFile) {}

    /** provider: "mockpay" (the bundled sandbox, default) or "stripe" (Checkout Sessions). */
    public record Payments(String baseUrl, String secretKey, String webhookSecret,
                           Duration webhookTolerance, Duration paymentWindow, String provider, Stripe stripe) {}

    public record Stripe(String baseUrl, String secretKey, String webhookSecret) {}

    public record Search(String indexAlias, boolean reindexOnStartup) {}

    public record HotMenu(int windowHours, int topN) {}

    public record RateLimit(boolean enabled) {}

    public record Seed(boolean demo) {}

    public record Outbox(Duration retention) {}

    /** Placement transactions allowed at once per replica, and how long a request may queue for one. */
    public record Orders(int placementConcurrency, Duration placementWait) {}

    /** Platform delivery pricing on top of each restaurant's base fee. */
    public record Delivery(int feePerKmCents, double freeKm) {}

    /** store = local (a directory, e.g. a compose volume) or s3; publicBase is what image URLs start with. */
    public record Images(String store, String dir, String bucket, String prefix, String region, String endpoint,
                         String publicBase, boolean generateDemo) {}
}
