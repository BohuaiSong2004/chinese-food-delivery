package com.wokflow.api.catalog;

import com.wokflow.api.common.ApiException;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** The fixed tag vocabulary: every tag has a label in both languages and can be a search filter. */
public final class Tags {
    private Tags() {}

    public static final Set<String> ALLOWED = Set.of(
            "spicy", "vegetarian", "vegan", "halal", "signature", "dumplings", "noodles", "soup", "rice", "seafood",
            "dim-sum", "congee", "drink", "dessert", "gluten-free", "grill", "bbq", "tofu", "peanuts", "late-night");

    /** Normalizes (trim, lower case, de-duplicate) and rejects tags outside the vocabulary. */
    public static List<String> check(Collection<String> tags) {
        if (tags == null) {
            return List.of();
        }
        List<String> out = tags.stream().map(t -> t.strip().toLowerCase(java.util.Locale.ROOT)).distinct().toList();
        for (String t : out) {
            if (!ALLOWED.contains(t)) {
                throw ApiException.badRequest("unknown_tag", "Unknown tag: " + t);
            }
        }
        return out;
    }
}
