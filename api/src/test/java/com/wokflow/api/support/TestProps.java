package com.wokflow.api.support;

import com.wokflow.api.config.AppProperties;

/** AppProperties for unit tests that need one section; the only place that lists every section. */
public final class TestProps {
    private TestProps() {}

    public static AppProperties rateLimit(boolean enabled) {
        return new AppProperties(null, null, null, null, null, new AppProperties.RateLimit(enabled), null, null, null, null, null);
    }

    public static AppProperties jwt(AppProperties.Jwt j) {
        return new AppProperties(null, j, null, null, null, null, null, null, null, null, null);
    }

    public static AppProperties payments(AppProperties.Payments p) {
        return new AppProperties(null, null, p, null, null, null, null, null, null, null, null);
    }
}
