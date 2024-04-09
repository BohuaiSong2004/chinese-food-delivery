package com.wokflow.api.geo;

/** A WGS84 coordinate. */
public record GeoPoint(double lat, double lng) {
    static final double EARTH_RADIUS_M = 6_371_008.8;

    public GeoPoint {
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180 || Double.isNaN(lat) || Double.isNaN(lng)) {
            throw new IllegalArgumentException("not a coordinate: " + lat + "," + lng);
        }
    }

    /** Great-circle (haversine) distance in metres. */
    public double distanceTo(GeoPoint o) {
        double p1 = Math.toRadians(lat);
        double p2 = Math.toRadians(o.lat);
        double dp = p2 - p1;
        double dl = Math.toRadians(o.lng - lng);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** The point reached by going {@code metres} on {@code bearingDeg} (0 = north). */
    public GeoPoint offset(double metres, double bearingDeg) {
        double d = metres / EARTH_RADIUS_M;
        double b = Math.toRadians(bearingDeg);
        double p1 = Math.toRadians(lat);
        double l1 = Math.toRadians(lng);
        double p2 = Math.asin(Math.sin(p1) * Math.cos(d) + Math.cos(p1) * Math.sin(d) * Math.cos(b));
        double l2 = l1 + Math.atan2(Math.sin(b) * Math.sin(d) * Math.cos(p1), Math.cos(d) - Math.sin(p1) * Math.sin(p2));
        return new GeoPoint(Math.toDegrees(p2), ((Math.toDegrees(l2) + 540) % 360) - 180);
    }

    /** A circle as a closed polygon ([lng, lat] pairs, counter-clockwise) for a GeoJSON geo_shape. */
    public double[][] circle(double radiusM, int vertices) {
        double[][] ring = new double[vertices + 1][];
        for (int i = 0; i < vertices; i++) {
            GeoPoint p = offset(radiusM, 360.0 - i * 360.0 / vertices);
            ring[i] = new double[] {p.lng, p.lat};
        }
        ring[vertices] = ring[0];
        return ring;
    }
}
