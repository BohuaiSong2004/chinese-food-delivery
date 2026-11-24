package com.wokflow.api.onboarding;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** What a restaurant owner fills in to apply, and later edits in the kitchen console. */
public record RestaurantProfile(
        @NotBlank @Size(max = 80) String name,
        @NotBlank @Size(max = 40) String nameZh,
        @Size(max = 300) String description,
        @NotBlank @Pattern(regexp = "[a-z-]{2,30}") String cuisine,
        @Size(max = 4) List<@Pattern(regexp = "[a-z-]{2,30}") String> tags,
        @NotBlank @Size(max = 60) String neighborhood,
        @NotBlank @Size(max = 200) String address,
        @Min(0) @Max(2_000) int deliveryFeeCents,
        @Min(0) @Max(10_000) int minOrderCents,
        @Min(5) @Max(120) int prepMinutes,
        @NotNull @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d") String opensAt,
        @NotNull @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d") String closesAt,
        boolean open24h,
        @Size(max = 40) String timeZone) {

    /** Cuisines the storefront has filters, labels and translations for. */
    public static final java.util.Set<String> CUISINES = java.util.Set.of(
            "sichuan", "cantonese", "northwestern", "shanghainese", "hunan", "shaanxi", "taiwanese", "dongbei",
            "yunnan", "fujian", "hakka", "hong-kong", "beijing");
}
