package com.wokflow.api.search;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;

/**
 * Query rewriting from {@code search/synonyms.txt}: "boba" also searches for "bubble milk tea",
 * 馄饨 also for 云吞 and 抄手.
 *
 * <p>Done in the application rather than with an Elasticsearch synonym filter so that one list
 * serves both Elasticsearch and the Postgres fallback, and editing it needs no reindex. Each
 * variant swaps one phrase; the original query always ranks above its variants.
 */
public final class Synonyms {
    static final int MAX_VARIANTS = 6;
    private static final Pattern HAN = Pattern.compile("\\p{IsHan}");

    /** from → the phrases it also searches for. */
    record Rule(String from, List<String> to) {}

    private static final List<Rule> RULES = load();

    private Synonyms() {}

    /** Other queries to try besides {@code query}, most specific match first, at most {@link #MAX_VARIANTS}. */
    public static List<String> variants(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String q = normalize(query);
        Set<String> out = new LinkedHashSet<>();
        for (Rule rule : RULES) {
            Matcher m = matcher(rule.from(), q);
            if (!m.find()) {
                continue;
            }
            for (String to : rule.to()) {
                String variant = (q.substring(0, m.start()) + to + q.substring(m.end())).strip();
                if (!variant.equals(q)) {
                    out.add(variant);
                }
            }
            if (out.size() >= MAX_VARIANTS) {
                break;
            }
        }
        return out.stream().limit(MAX_VARIANTS).toList();
    }

    static String normalize(String s) {
        return s.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static Matcher matcher(String phrase, String q) {
        String quoted = Pattern.quote(phrase);
        // Latin phrases match whole words ("ha gow" must not fire inside "shanghai gow…"); Chinese has no spaces.
        return Pattern.compile(HAN.matcher(phrase).find() ? quoted : "(?<![\\p{L}\\p{N}])" + quoted + "(?![\\p{L}\\p{N}])")
                .matcher(q);
    }

    static List<Rule> parse(List<String> lines) {
        List<Rule> rules = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.contains("=>")) {
                String[] sides = line.split("=>", 2);
                List<String> to = split(sides[1]);
                for (String from : split(sides[0])) {
                    List<String> targets = new ArrayList<>(split(sides[0]));
                    targets.remove(from);
                    targets.addAll(to);
                    rules.add(new Rule(from, targets));
                }
            } else {
                List<String> group = split(line);
                for (String from : group) {
                    rules.add(new Rule(from, group.stream().filter(g -> !g.equals(from)).toList()));
                }
            }
        }
        // Longest phrase first, so "pearl milk tea" is replaced as a whole before anything inside it.
        rules.sort((a, b) -> Integer.compare(b.from().length(), a.from().length()));
        return List.copyOf(rules);
    }

    private static List<String> split(String side) {
        List<String> out = new ArrayList<>();
        for (String part : side.split(",")) {
            String p = normalize(part);
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    private static List<Rule> load() {
        try (var in = new ClassPathResource("search/synonyms.txt").getInputStream();
             var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return parse(reader.lines().toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static int ruleCount() { return RULES.size(); }
}
