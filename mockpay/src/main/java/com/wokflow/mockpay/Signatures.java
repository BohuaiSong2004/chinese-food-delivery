package com.wokflow.mockpay;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class Signatures {
    private Signatures() {}

    /** "t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + '.' + body)>" */
    static String header(String secret, long t, String body) {
        return "t=" + t + ",v1=" + HexFormat.of().formatHex(hmac(secret, t + "." + body));
    }

    static byte[] hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
