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
        Orders orders) {

    public record Jwt(String issuer, String privateKeyFile, boolean generateIfMissing, Duration accessTtl,
                      Duration refreshTtl, boolean refreshCookieSecure) {}

    public record Payments(String baseUrl, String secretKey, String webhookSecret,
                           Duration webhookTolerance, Duration paymentWindow) {}

    public record Search(String indexAlias, boolean reindexOnStartup) {}

    public record HotMenu(int windowHours, int topN) {}

    public record RateLimit(boolean enabled) {}

    public record Seed(boolean demo) {}

    public record Outbox(Duration retention) {}

    /** Placement transactions allowed at once per replica, and how long a request may queue for one. */
    public record Orders(int placementConcurrency, Duration placementWait) {}
}
