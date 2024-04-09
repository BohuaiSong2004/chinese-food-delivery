package com.wokflow.api.onboarding;

import com.wokflow.api.auth.CurrentUser;
import com.wokflow.api.catalog.CatalogRepository;
import com.wokflow.api.catalog.Restaurant;
import com.wokflow.api.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Restaurant owners: apply to join, edit the restaurant profile, resubmit after a rejection. */
@RestController
@RequestMapping("/api/merchant")
public class PartnerController {
    public record ProfileUpdate(@Valid RestaurantProfile profile, @Positive long version) {}

    public record Settings(long id, String slug, String status, String statusNote, long version, RestaurantProfile profile) {
        static Settings of(Restaurant r, String note) {
            return new Settings(r.id(), r.slug(), r.status().name(), note, r.version(), new RestaurantProfile(
                    r.name(), r.nameZh(), r.description(), r.cuisine(), r.tags(), r.neighborhood(), r.address(),
                    r.zip(), r.lat(), r.lng(), r.deliveryRadiusKm(), r.deliveryFeeCents(), r.minOrderCents(), r.prepMinutes(), hhmm(r.opensAt()), hhmm(r.closesAt()),
                    r.open24h(), r.timeZone()));
        }

        private static String hhmm(java.time.LocalTime t) {
            return String.format("%02d:%02d", t.getHour(), t.getMinute());
        }
    }

    private final OnboardingService onboarding;
    private final CatalogRepository catalog;

    public PartnerController(OnboardingService onboarding, CatalogRepository catalog) {
        this.onboarding = onboarding;
        this.catalog = catalog;
    }

    /** The new restaurant id is in the staff list only after a token refresh; the client refreshes next. */
    @PostMapping("/applications")
    @ResponseStatus(HttpStatus.CREATED)
    Settings apply(@Valid @RequestBody RestaurantProfile profile) {
        long id = onboarding.apply(CurrentUser.require(), profile);
        return Settings.of(catalog.restaurant(id).orElseThrow(), null);
    }

    @GetMapping("/restaurants/{rid}/settings")
    Settings settings(@PathVariable long rid) {
        CurrentUser.require().requireStaffOf(rid);
        Restaurant r = catalog.restaurant(rid).orElseThrow(() -> ApiException.notFound("Restaurant"));
        return Settings.of(r, catalog.statusNote(rid));
    }

    @PutMapping("/restaurants/{rid}/settings")
    Settings update(@PathVariable long rid, @Valid @RequestBody ProfileUpdate req) {
        Restaurant r = onboarding.update(CurrentUser.require(), rid, req.profile(), req.version());
        return Settings.of(r, catalog.statusNote(rid));
    }

    @PostMapping("/restaurants/{rid}/resubmit")
    Map<String, String> resubmit(@PathVariable long rid) {
        onboarding.resubmit(CurrentUser.require(), rid);
        return Map.of("status", "PENDING");
    }
}
