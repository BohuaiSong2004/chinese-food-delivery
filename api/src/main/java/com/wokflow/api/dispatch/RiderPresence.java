package com.wokflow.api.dispatch;

import com.wokflow.api.geo.GeoPoint;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Where online riders are, in a Redis GEO set (nearest-first search in one GEOSEARCH), with a
 * sorted set of last-seen times so a rider whose app went away stops being offered work.
 * Ephemeral by design: if Redis loses it, riders reappear with their next location ping.
 */
@Component
public class RiderPresence {
    static final String GEO = "riders:geo";
    static final String SEEN = "riders:seen";
    /** A rider who has not pinged for this long is treated as offline. */
    static final Duration STALE = Duration.ofSeconds(60);

    public record Nearby(long riderId, int distanceM) {}

    public record Position(GeoPoint point, java.time.Instant at) {}

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RiderPresence(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    public void update(long riderId, GeoPoint p) {
        redis.opsForGeo().add(GEO, new Point(p.lng(), p.lat()), Long.toString(riderId));
        redis.opsForZSet().add(SEEN, Long.toString(riderId), clock.millis());
    }

    public void offline(long riderId) {
        redis.opsForZSet().remove(SEEN, Long.toString(riderId));
        redis.opsForGeo().remove(GEO, Long.toString(riderId));
    }

    /** Drops riders whose app stopped pinging, so the GEO set does not grow forever. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT30S")
    public void prune() {
        try {
            var stale = redis.opsForZSet().rangeByScore(SEEN, 0, clock.millis() - STALE.toMillis());
            if (stale != null && !stale.isEmpty()) {
                redis.opsForZSet().remove(SEEN, stale.toArray());
                redis.opsForGeo().remove(GEO, stale.toArray(String[]::new));
            }
        } catch (RuntimeException e) {
            // Redis unavailable: nothing to prune; presence rebuilds from pings
        }
    }

    public boolean online(long riderId) {
        Double seen = redis.opsForZSet().score(SEEN, Long.toString(riderId));
        return seen != null && clock.millis() - seen <= STALE.toMillis();
    }

    public Optional<Position> position(long riderId) {
        Double seen = redis.opsForZSet().score(SEEN, Long.toString(riderId));
        List<Point> pos = redis.opsForGeo().position(GEO, Long.toString(riderId));
        if (seen == null || pos == null || pos.isEmpty() || pos.getFirst() == null) {
            return Optional.empty();
        }
        Point p = pos.getFirst();
        return Optional.of(new Position(new GeoPoint(p.getY(), p.getX()), java.time.Instant.ofEpochMilli(seen.longValue())));
    }

    /** Online riders within radius of a point, nearest first (GEOSEARCH ... ASC WITHDIST). */
    public List<Nearby> nearby(GeoPoint p, double radiusKm, int limit) {
        var args = RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().sortAscending().limit(limit * 2L);
        var res = redis.opsForGeo().search(GEO, org.springframework.data.redis.domain.geo.GeoReference.fromCoordinate(p.lng(), p.lat()),
                new Distance(radiusKm, Metrics.KILOMETERS), args);
        if (res == null) {
            return List.of();
        }
        long now = clock.millis();
        return res.getContent().stream()
                .filter(r -> {
                    Double seen = redis.opsForZSet().score(SEEN, r.getContent().getName());
                    return seen != null && now - seen <= STALE.toMillis();
                })
                .limit(limit)
                .map(r -> new Nearby(Long.parseLong(r.getContent().getName()), (int) Math.round(r.getDistance().getValue() * 1000)))
                .toList();
    }
}
