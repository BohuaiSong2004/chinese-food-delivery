package com.wokflow.api.order;

import com.wokflow.api.common.ApiException;

/** Parses an If-Match header holding the order version the client acted on ("3" or W/"3"). */
final class Versions {
    private Versions() {}

    static Integer parse(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null;
        }
        String v = ifMatch.strip();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        v = v.replace("\"", "");
        try {
            return Integer.valueOf(v);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("bad_if_match", "If-Match must be the order version");
        }
    }
}
