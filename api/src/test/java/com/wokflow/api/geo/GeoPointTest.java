package com.wokflow.api.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.wokflow.api.common.ApiException;
import org.junit.jupiter.api.Test;

class GeoPointTest {
    static final GeoPoint ALHAMBRA = new GeoPoint(34.0911, -118.1293);

    @Test
    void haversineMatchesKnownDistances() {
        // LAX to JFK is about 3,974 km great-circle
        assertThat(new GeoPoint(33.9416, -118.4085).distanceTo(new GeoPoint(40.6413, -73.7781)) / 1000).isCloseTo(3974, within(10.0));
        assertThat(ALHAMBRA.distanceTo(ALHAMBRA)).isZero();
        // offset() and distanceTo() agree
        for (int bearing = 0; bearing < 360; bearing += 45) {
            assertThat(ALHAMBRA.distanceTo(ALHAMBRA.offset(5_000, bearing))).isCloseTo(5_000, within(1.0));
        }
    }

    @Test
    void circlePolygonIsClosedAndOnTheRadius() {
        double[][] ring = ALHAMBRA.circle(8_000, 32);
        assertThat(ring).hasNumberOfRows(33);
        assertThat(ring[0]).isEqualTo(ring[32]);
        for (double[] p : ring) {
            assertThat(ALHAMBRA.distanceTo(new GeoPoint(p[1], p[0]))).isCloseTo(8_000, within(1.0));
        }
        assertThatThrownBy(() -> new GeoPoint(91, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void quotesChargePerKilometreBeyondTheFreeDistance() {
        GeoPoint to = ALHAMBRA.offset(5_000, 90);
        DeliveryQuote q = DeliveryQuote.of(ALHAMBRA, to, 8, 299, 60, 2, 15);
        assertThat(q.distanceM()).isEqualTo(5_000);
        assertThat(q.feeCents()).isEqualTo(479);   // 2.99 + 3 km x 0.60
        assertThat(q.etaMinutes()).isEqualTo(15 + 5 + 17);
        assertThat(q.deliverable()).isTrue();
        assertThat(DeliveryQuote.of(ALHAMBRA, ALHAMBRA.offset(1_000, 0), 8, 299, 60, 2, 15).feeCents()).isEqualTo(299); // inside the free distance: exactly the base fee
        assertThat(DeliveryQuote.of(ALHAMBRA, ALHAMBRA.offset(8_100, 0), 8, 299, 60, 2, 15).deliverable()).isFalse();
    }

    @Test
    void serviceAreaGeocodesAndBoundsPins() {
        ServiceArea area = new ServiceArea();
        assertThat(area.locate("91801", null, null)).isEqualTo(ALHAMBRA);
        GeoPoint near = ALHAMBRA.offset(1_500, 30);
        assertThat(area.locate("91801", near.lat(), near.lng())).isEqualTo(near);
        GeoPoint far = ALHAMBRA.offset(5_000, 30);
        assertThatThrownBy(() -> area.locate("91801", far.lat(), far.lng()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("pin_outside_zip"));
        assertThatThrownBy(() -> area.locate("10001", null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("outside_service_area"));
        assertThat(area.places()).hasSizeGreaterThan(25);
    }
}
