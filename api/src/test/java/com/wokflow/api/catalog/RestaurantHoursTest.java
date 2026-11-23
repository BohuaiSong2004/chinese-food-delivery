package com.wokflow.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class RestaurantHoursTest {
    static final ZoneId LA = ZoneId.of("America/Los_Angeles");

    static Restaurant r(String open, String close, boolean h24) {
        return new Restaurant(1, "x", "X", "X", "", "c", List.of(), "n", "a", 0, 0, 10, 1500, LocalTime.parse(open),
                LocalTime.parse(close), h24, LA.getId(), true, 1);
    }

    static java.time.Instant la(int y, int m, int d, int h, int min) {
        return ZonedDateTime.of(y, m, d, h, min, 0, 0, LA).toInstant();
    }

    @Test
    void sameDayHours() {
        var dimSum = r("09:00", "15:00", false);
        assertThat(dimSum.isOpenAt(la(2026, 11, 23, 8, 59))).isFalse();
        assertThat(dimSum.isOpenAt(la(2026, 11, 23, 9, 0))).isTrue();
        assertThat(dimSum.isOpenAt(la(2026, 11, 23, 14, 59))).isTrue();
        assertThat(dimSum.isOpenAt(la(2026, 11, 23, 15, 0))).isFalse();
        assertThat(dimSum.nextOpening(la(2026, 11, 23, 16, 0))).isEqualTo(la(2026, 11, 24, 9, 0));
        assertThat(dimSum.nextOpening(la(2026, 11, 23, 7, 0))).isEqualTo(la(2026, 11, 23, 9, 0));
    }

    @Test
    void hoursPastMidnight() {
        var night = r("17:00", "03:00", false);
        assertThat(night.isOpenAt(la(2026, 11, 23, 23, 30))).isTrue();
        assertThat(night.isOpenAt(la(2026, 11, 24, 2, 59))).isTrue();
        assertThat(night.isOpenAt(la(2026, 11, 24, 3, 0))).isFalse();
        assertThat(night.isOpenAt(la(2026, 11, 24, 12, 0))).isFalse();
    }

    @Test
    void localTimeIsUsedAcrossDaylightSavingChanges() {
        var lunch = r("11:00", "14:00", false);
        // the same UTC hour is inside opening hours in summer (PDT) but not in winter (PST)
        assertThat(lunch.isOpenAt(java.time.Instant.parse("2026-07-01T18:30:00Z"))).isTrue();  // 11:30 PDT
        assertThat(lunch.isOpenAt(java.time.Instant.parse("2026-12-01T18:30:00Z"))).isFalse(); // 10:30 PST
        assertThat(lunch.isOpenAt(java.time.Instant.parse("2026-12-01T19:00:00Z"))).isTrue();  // 11:00 PST
    }

    @Test
    void alwaysOpen() {
        var congee = r("00:00", "00:00", true);
        assertThat(congee.isOpenAt(la(2026, 1, 1, 4, 0))).isTrue();
        assertThat(r("00:00", "00:00", false).isOpenAt(la(2026, 1, 1, 4, 0))).isFalse();
    }
}
