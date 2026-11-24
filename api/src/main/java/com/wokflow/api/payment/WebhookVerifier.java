package com.wokflow.api.payment;

import com.wokflow.api.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Verifies "MockPay-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + '.' + body)>".
 * The timestamp is signed and must be recent, so a captured request cannot be
 * replayed later. Rotation works from both ends: the sender may put several v1 values in one
 * header, and the secret setting may list several secrets ("new,old").
 */
@Component
public class WebhookVerifier {
    private final List<byte[]> secrets;
    private final Duration tolerance;
    private final Clock clock;

    public WebhookVerifier(AppProperties props, Clock clock) {
        // "new,old": during a rotation both secrets are accepted, until the provider stops signing with the old one
        this.secrets = Arrays.stream(props.payments().webhookSecret().split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).map(s -> s.getBytes(StandardCharsets.UTF_8)).toList();
        if (secrets.isEmpty()) {
            throw new IllegalStateException("MOCKPAY_WEBHOOK_SECRET is empty");
        }
        this.tolerance = props.payments().webhookTolerance();
        this.clock = clock;
    }

    public boolean verify(byte[] body, String header) {
        if (header == null || header.length() > 1024) {
            return false;
        }
        Long t = null;
        java.util.List<String> sigs = new java.util.ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.strip().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                try {
                    t = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (kv[0].equals("v1")) {
                sigs.add(kv[1]);
            }
        }
        if (t == null || sigs.isEmpty()) {
            return false;
        }
        long age = Math.abs(clock.instant().getEpochSecond() - t);
        if (age > tolerance.toSeconds()) {
            return false;
        }
        for (byte[] secret : secrets) {
            byte[] expected = sign(secret, t, body);
            for (String s : sigs) {
                try {
                    if (MessageDigest.isEqual(expected, HexFormat.of().parseHex(s))) {
                        return true;
                    }
                } catch (IllegalArgumentException ignored) {
                    // not hex
                }
            }
        }
        return false;
    }

    public static byte[] sign(byte[] secret, long t, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String header(String secret, long t, byte[] body) {
        return "t=" + t + ",v1=" + HexFormat.of().formatHex(sign(secret.getBytes(StandardCharsets.UTF_8), t, body));
    }
}
