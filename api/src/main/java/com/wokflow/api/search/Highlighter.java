package com.wokflow.api.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Marks the words of a query (and its synonyms) in the text shown for a hit.
 *
 * <p>Done here on the page of hits rather than by Elasticsearch: with its highlighter, search
 * throughput under load fell from ~150 to ~65 req/s on the demo stack, because Elasticsearch is the
 * busiest container and the highlighter re-analyzes every hit against the query. Twenty short names
 * and descriptions are microseconds of string work here, and the Postgres fallback gets the same
 * marks.
 *
 * <p>Rules, close to how the query matches: a Latin word is marked when it starts with a typed word
 * (typing "dump" marks "Dumplings"), or is within the edit distance Elasticsearch's AUTO fuzziness
 * allows (0 for 1–2 letters, 1 for 3–5, 2 for longer: "tofo" marks "Tofu"). In Chinese, each pair of
 * adjacent typed characters found in the text is marked (牛肉面 marks 牛肉 in 兰州牛肉拉面), or the
 * character itself when one was typed. Pinyin and initials ("mpdf") match through hidden fields, so
 * they mark nothing rather than something wrong.
 */
public final class Highlighter {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern HAN = Pattern.compile("\\p{IsHan}+");

    private final Set<String> words;
    private final Set<String> hanGrams;

    private Highlighter(Set<String> words, Set<String> hanGrams) {
        this.words = words;
        this.hanGrams = hanGrams;
    }

    /** For the query as typed plus its synonym variants. */
    public static Highlighter of(String query) {
        Set<String> words = new LinkedHashSet<>();
        Set<String> grams = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            return new Highlighter(words, grams);
        }
        List<String> phrases = new ArrayList<>();
        phrases.add(query);
        phrases.addAll(Synonyms.variants(query));
        for (String phrase : phrases) {
            String lower = phrase.toLowerCase(Locale.ROOT);
            Matcher han = HAN.matcher(lower);
            while (han.find()) {
                String run = han.group();
                if (run.length() == 1) {
                    grams.add(run);
                }
                for (int i = 0; i + 2 <= run.length(); i++) {
                    grams.add(run.substring(i, i + 2));
                }
            }
            Matcher w = WORD.matcher(HAN.matcher(lower).replaceAll(" "));
            while (w.find()) {
                if (w.group().length() >= 2 && !SearchService.STOP.contains(w.group())) {
                    words.add(w.group());
                }
            }
        }
        return new Highlighter(words, grams);
    }

    public boolean isEmpty() { return words.isEmpty() && hanGrams.isEmpty(); }

    /** The text with matches wrapped in the markers, or null when nothing matched. */
    public String mark(String text) {
        if (text == null || text.isEmpty() || isEmpty()) {
            return null;
        }
        boolean[] marked = new boolean[text.length()];
        boolean any = false;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.length() != text.length()) {
            return null; // a case mapping that changes length (rare): offsets would not line up
        }
        if (!words.isEmpty()) {
            Matcher w = WORD.matcher(lower);
            while (w.find()) {
                if (HAN.matcher(w.group()).find()) {
                    continue;
                }
                if (matches(w.group())) {
                    for (int i = w.start(); i < w.end(); i++) {
                        marked[i] = true;
                    }
                    any = true;
                }
            }
        }
        for (String gram : hanGrams) {
            for (int at = lower.indexOf(gram); at >= 0; at = lower.indexOf(gram, at + 1)) {
                for (int i = at; i < at + gram.length(); i++) {
                    marked[i] = true;
                }
                any = true;
            }
        }
        if (!any) {
            return null;
        }
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            if (marked[i] && (i == 0 || !marked[i - 1])) {
                out.append(SearchService.MARK_START);
            }
            out.append(text.charAt(i));
            if (marked[i] && (i == text.length() - 1 || !marked[i + 1])) {
                out.append(SearchService.MARK_END);
            }
        }
        return out.toString();
    }

    private boolean matches(String word) {
        for (String typed : words) {
            if (word.startsWith(typed) || word.equals(typed)) {
                return true;
            }
            int allowed = typed.length() <= 2 ? 0 : typed.length() <= 5 ? 1 : 2;
            if (allowed > 0 && word.charAt(0) == typed.charAt(0) && Math.abs(word.length() - typed.length()) <= allowed
                    && distance(word, typed, allowed) <= allowed) {
                return true;
            }
        }
        return false;
    }

    /** Damerau–Levenshtein (adjacent transpositions count as one edit, as in Elasticsearch), stopping early past max. */
    static int distance(String a, String b, int max) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int rowMin = Integer.MAX_VALUE;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
                rowMin = Math.min(rowMin, d[i][j]);
            }
            if (rowMin > max) {
                return max + 1;
            }
        }
        return d[a.length()][b.length()];
    }
}
