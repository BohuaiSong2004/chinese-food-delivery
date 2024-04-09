package com.wokflow.api.geo;

import com.wokflow.api.common.ApiException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The ZIP codes we deliver to, with their centroids. Geocoding is deliberately server-side: the
 * delivery fee depends on distance, so a client cannot claim to live next door to the restaurant.
 * A customer may refine the point with a pin (browser location or a map), accepted only within
 * {@link #MAX_PIN_OFFSET_M} of the ZIP's centroid.
 */
@Component
public class ServiceArea {
    public static final double MAX_PIN_OFFSET_M = 3_000;

    public record Place(String zip, String city, GeoPoint centroid) {}

    private final Map<String, Place> zips = new LinkedHashMap<>();

    public ServiceArea() {
        try (var in = new BufferedReader(new InputStreamReader(
                new ClassPathResource("geo/service-area.csv").getInputStream(), StandardCharsets.UTF_8))) {
            in.lines().filter(l -> !l.isBlank() && !l.startsWith("#") && !l.startsWith("zip,")).forEach(l -> {
                String[] f = l.split(",");
                zips.put(f[0], new Place(f[0], f[1], new GeoPoint(Double.parseDouble(f[2]), Double.parseDouble(f[3]))));
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Collection<Place> places() { return zips.values(); }

    public Optional<Place> place(String zip) {
        return Optional.ofNullable(zip == null ? null : zips.get(zip.strip()));
    }

    /** The coordinate to use for an address: the ZIP centroid, or the customer's pin if it is plausibly inside the ZIP. */
    public GeoPoint locate(String zip, Double pinLat, Double pinLng) {
        Place p = place(zip).orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "outside_service_area",
                "We don't deliver to " + zip + " yet"));
        if (pinLat == null || pinLng == null) {
            return p.centroid();
        }
        GeoPoint pin;
        try {
            pin = new GeoPoint(pinLat, pinLng);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("bad_location", "That location is not a valid coordinate");
        }
        if (pin.distanceTo(p.centroid()) > MAX_PIN_OFFSET_M) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "pin_outside_zip",
                    "The pinned location is not in " + p.zip() + " " + p.city());
        }
        return pin;
    }
}
