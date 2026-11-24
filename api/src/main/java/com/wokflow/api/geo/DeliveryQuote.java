package com.wokflow.api.geo;

/**
 * What delivering from a restaurant to a point costs and takes. Distance is straight-line
 * (haversine); the fee is the restaurant's base fee plus a platform per-kilometre charge beyond
 * the first few kilometres (that distance charge rounded to the nearest 10 cents, so inside the free
 * distance the fee is exactly what the restaurant set).
 */
public record DeliveryQuote(int distanceM, int feeCents, int etaMinutes, boolean deliverable) {
    public static final double RIDER_KMH = 18; // urban e-bike/scooter average, including stops

    public static DeliveryQuote of(GeoPoint restaurant, GeoPoint to, double radiusKm, int baseFeeCents,
                                   int perKmCents, double freeKm, int prepMinutes) {
        double km = restaurant.distanceTo(to) / 1000;
        double extraKm = Math.max(0, km - freeKm);
        int fee = baseFeeCents + (int) (Math.round(extraKm * perKmCents / 10.0) * 10);
        int eta = prepMinutes + 5 + (int) Math.ceil(km / RIDER_KMH * 60);
        return new DeliveryQuote((int) Math.round(km * 1000), fee, eta, km <= radiusKm);
    }
}
