package com.wokflow.api.search;

import com.wokflow.api.geo.GeoPoint;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Suggestions while typing: up to five dishes (the normal search, without the relaxed second pass)
 * and up to three restaurants.
 *
 * <p>Restaurants are matched in memory against a list of live restaurants refreshed every 30 s:
 * there are few of them (hundreds even for a large city), and in memory they can be matched by
 * English word prefix, characters, and pinyin or its initials ("cdys" → 成都夜市) without a
 * second index.
 */
@Service
public class Suggestions {
    static final int DISHES = 5;
    static final int RESTAURANTS = 3;
    private static final Duration REFRESH = Duration.ofSeconds(30);
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    public record RestaurantSuggestion(String slug, String name, String nameZh, String cuisine, String image) {}

    public record Result(List<SearchService.DishHit> dishes, List<RestaurantSuggestion> restaurants, boolean degraded) {}

    /** A live restaurant with the spellings it can be found by. */
    record Entry(RestaurantSuggestion view, List<String> words, String nameZh, String pinyinJoined, String initials) {}

    private record Snapshot(Instant at, List<Entry> entries) {}

    private final SearchService search;
    private final JdbcClient db;
    private final Clock clock;
    private final com.wokflow.api.images.ImageService images;
    private volatile Snapshot snapshot = new Snapshot(Instant.EPOCH, List.of());

    public Suggestions(SearchService search, JdbcClient db, Clock clock, com.wokflow.api.images.ImageService images) {
        this.search = search;
        this.db = db;
        this.clock = clock;
        this.images = images;
    }

    public Result suggest(String raw, GeoPoint deliverTo) {
        String q = raw == null ? "" : Synonyms.normalize(raw);
        if (q.isEmpty()) {
            return new Result(List.of(), List.of(), false);
        }
        SearchService.Result dishes = search.search(SearchService.Criteria.typing(q, DISHES, deliverTo));
        return new Result(dishes.hits(), restaurants(q), dishes.degraded());
    }

    List<RestaurantSuggestion> restaurants(String q) {
        String compact = q.replace(" ", "");
        List<String> typed = words(q).stream().filter(w -> !w.isEmpty()).toList();
        record Scored(Entry e, int score) {}
        List<Scored> found = new ArrayList<>();
        for (Entry e : entries()) {
            int score = score(e, q, compact, typed);
            if (score > 0) {
                found.add(new Scored(e, score));
            }
        }
        return found.stream()
                .sorted(Comparator.comparingInt(Scored::score).reversed().thenComparing(s -> s.e().view().name()))
                .limit(RESTAURANTS).map(s -> s.e().view()).toList();
    }

    /** 0 = no match; higher = better. Every typed word must start a word of the name (the last may be partial). */
    static int score(Entry e, String q, String compact, List<String> typed) {
        if (!e.nameZh().isEmpty() && e.nameZh().contains(q)) {
            return e.nameZh().startsWith(q) ? 50 : 40;
        }
        if (!typed.isEmpty() && typed.stream().allMatch(t -> e.words().stream().anyMatch(w -> w.startsWith(t)))) {
            return e.words().getFirst().startsWith(typed.getFirst()) ? 45 : 35;
        }
        if (compact.length() >= 2 && compact.chars().allMatch(Character::isLetter)) {
            if (e.pinyinJoined().startsWith(compact)) {
                return 30;
            }
            if (e.initials().startsWith(compact)) {
                return 25;
            }
        }
        return 0;
    }

    private List<Entry> entries() {
        Snapshot s = snapshot;
        if (Duration.between(s.at(), clock.instant()).compareTo(REFRESH) < 0) {
            return s.entries();
        }
        List<Entry> fresh = db.sql("""
                select slug, name, name_zh, cuisine, image_key from restaurants where status = 'LIVE' order by id""")
                .query((rs, n) -> entry(rs.getString("slug"), rs.getString("name"), rs.getString("name_zh"),
                        rs.getString("cuisine"), images.url(rs.getString("image_key"))))
                .list();
        snapshot = new Snapshot(clock.instant(), fresh);
        return fresh;
    }

    static Entry entry(String slug, String name, String nameZh, String cuisine, String image) {
        String zh = nameZh == null ? "" : nameZh;
        Pinyin.Reading py = zh.isEmpty() ? new Pinyin.Reading("", "", "") : Pinyin.of(zh);
        return new Entry(new RestaurantSuggestion(slug, name, nameZh, cuisine, image), words(name), zh, py.joined(), py.initials());
    }

    private static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        for (String w : NON_WORD.split(s.toLowerCase(Locale.ROOT))) {
            if (!w.isEmpty()) {
                out.add(w);
            }
        }
        return out.isEmpty() ? List.of("") : out;
    }
}
