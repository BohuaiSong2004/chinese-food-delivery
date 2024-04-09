package com.wokflow.api.geo;

import com.wokflow.api.catalog.Restaurant;
import com.wokflow.api.config.AppProperties;
import org.springframework.stereotype.Component;

/** Quotes delivery from a restaurant to a point with the platform's per-kilometre pricing. */
@Component
public class DeliveryPricing {
    private final AppProperties.Delivery cfg;

    public DeliveryPricing(AppProperties props) { this.cfg = props.delivery(); }

    /** Empty when the restaurant has no location yet. */
    public java.util.Optional<DeliveryQuote> quote(Restaurant r, GeoPoint to) {
        GeoPoint from = r.location();
        if (from == null || to == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(DeliveryQuote.of(from, to, r.deliveryRadiusKm(), r.deliveryFeeCents(),
                cfg.feePerKmCents(), cfg.freeKm(), r.prepMinutes()));
    }
}
