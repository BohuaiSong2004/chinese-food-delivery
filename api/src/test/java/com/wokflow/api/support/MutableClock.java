package com.wokflow.api.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests can move. Follows real time plus an offset, so scheduled jobs keep working. */
public class MutableClock extends Clock {
    private volatile Duration offset = Duration.ZERO;

    public void advance(Duration d) { offset = offset.plus(d); }

    public void setTo(Instant at) { offset = Duration.between(Instant.now(), at); }

    public void reset() { offset = Duration.ZERO; }

    @Override
    public ZoneId getZone() { return ZoneOffset.UTC; }

    @Override
    public Clock withZone(ZoneId zone) {
        MutableClock self = this;
        return new Clock() {
            @Override public ZoneId getZone() { return zone; }
            @Override public Clock withZone(ZoneId z) { return self.withZone(z); }
            @Override public Instant instant() { return self.instant(); }
        };
    }

    @Override
    public Instant instant() { return Instant.now().plus(offset); }
}
