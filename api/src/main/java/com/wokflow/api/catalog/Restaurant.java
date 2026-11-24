package com.wokflow.api.catalog;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

public record Restaurant(
        long id, String slug, String name, String nameZh, String description, String cuisine, List<String> tags,
        String neighborhood, String address, int deliveryFeeCents, int minOrderCents, int prepMinutes,
        int commissionBps, LocalTime opensAt, LocalTime closesAt, boolean open24h, String timeZone,
        boolean acceptingOrders, long version, RestaurantStatus status, String imageKey, String coverKey) {

    /** Listed on the storefront, searchable and able to take new orders. */
    public boolean live() { return status == RestaurantStatus.LIVE; }

    /** Opening hours in the restaurant's own time zone; a close time before the open time means "past midnight". */
    public boolean isOpenAt(Instant at) {
        if (open24h) {
            return true;
        }
        LocalTime t = at.atZone(ZoneId.of(timeZone)).toLocalTime();
        if (opensAt.equals(closesAt)) {
            return false;
        }
        return opensAt.isBefore(closesAt)
                ? !t.isBefore(opensAt) && t.isBefore(closesAt)
                : !t.isBefore(opensAt) || t.isBefore(closesAt);
    }

    /** The next time the kitchen opens, or {@code at} itself if it is open. */
    public Instant nextOpening(Instant at) {
        if (isOpenAt(at)) {
            return at;
        }
        ZonedDateTime local = at.atZone(ZoneId.of(timeZone));
        ZonedDateTime candidate = local.with(opensAt).withSecond(0).withNano(0);
        if (!candidate.isAfter(local)) {
            candidate = candidate.plusDays(1);
        }
        return candidate.toInstant();
    }
}
