package com.wokflow.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.wokflow.api.support.Http;
import com.wokflow.api.support.IntegrationTest;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class PasswordIT extends IntegrationTest {
    static final Pattern LINK = Pattern.compile("/reset#token=([A-Za-z0-9_-]{43})");

    JsonNode mailpit(String path) throws Exception {
        String url = "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + path;
        var res = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
        return Http.JSON.readTree(res.body());
    }

    int messagesTo(String email) throws Exception {
        return mailpit("/api/v1/search?query=" + URLEncoder.encode("to:" + email, StandardCharsets.UTF_8)).get("messages_count").asInt();
    }

    /** Waits for the reset email and returns its token. */
    String tokenFrom(String email) throws Exception {
        waitUntil(() -> {
            try {
                return messagesTo(email) > 0;
            } catch (Exception e) {
                return false;
            }
        });
        var msg = mailpit("/api/v1/search?query=" + URLEncoder.encode("to:" + email, StandardCharsets.UTF_8)).get("messages").get(0);
        String text = mailpit("/api/v1/message/" + msg.get("ID").asText()).get("Text").asText();
        Matcher m = LINK.matcher(text);
        assertThat(m.find()).as(text).isTrue();
        return m.group(1);
    }

    @Test
    void resetByEmailEndsEverySessionAndTheLinkWorksOnce() throws Exception {
        String email = unique("forgot") + "@example.com";
        newAccount("CUSTOMER", email);
        var signedIn = http.post("/api/auth/signin", null, Map.of("email", email, "password", "correct-horse-1"));
        String cookie = signedIn.header("set-cookie").split(";")[0];

        assertThat(http.post("/api/auth/forgot", null, Map.of("email", email.toUpperCase())).status()).isEqualTo(202);
        String token = tokenFrom(email);

        assertThat(http.post("/api/auth/reset", null, Map.of("token", token, "password", "short")).status()).isEqualTo(400);
        assertThat(http.post("/api/auth/reset", null, Map.of("token", token, "password", "a-brand-new-secret")).status()).isEqualTo(204);
        assertThat(http.post("/api/auth/reset", null, Map.of("token", token, "password", "another-new-secret")).code())
                .isEqualTo("invalid_reset_link");

        // old password gone, new one works, the old session's refresh cookie is revoked
        assertThat(http.post("/api/auth/signin", null, Map.of("email", email, "password", "correct-horse-1")).status()).isEqualTo(401);
        assertThat(http.post("/api/auth/signin", null, Map.of("email", email, "password", "a-brand-new-secret")).status()).isEqualTo(200);
        var refresh = http.raw("POST", "/api/auth/refresh", null, Map.of("Cookie", cookie));
        assertThat(refresh.status()).isEqualTo(401);
    }

    @Test
    void unknownAddressesGetTheSameAnswerAndNoMail() throws Exception {
        String nobody = unique("nobody") + "@example.com";
        var r = http.post("/api/auth/forgot", null, Map.of("email", nobody));
        assertThat(r.status()).isEqualTo(202);
        Thread.sleep(1000);
        assertThat(messagesTo(nobody)).isZero();
        assertThat(http.post("/api/auth/reset", null, Map.of("token", "x".repeat(43), "password", "a-brand-new-secret")).code())
                .isEqualTo("invalid_reset_link");
    }

    @Test
    void linksExpireAfterThirtyMinutesAndChineseRequestsGetChineseMail() throws Exception {
        String email = unique("zh") + "@example.com";
        newAccount("CUSTOMER", email);
        http.send("POST", "/api/auth/forgot", null, Map.of("email", email), Map.of("Accept-Language", "zh-CN,zh;q=0.9"));
        String token = tokenFrom(email);
        var msg = mailpit("/api/v1/search?query=" + URLEncoder.encode("to:" + email, StandardCharsets.UTF_8)).get("messages").get(0);
        assertThat(msg.get("Subject").asText()).isEqualTo("重置你的 Wokflow 密码");
        clock.advance(Duration.ofMinutes(31));
        assertThat(http.post("/api/auth/reset", null, Map.of("token", token, "password", "a-brand-new-secret")).code())
                .isEqualTo("invalid_reset_link");
    }

    @Test
    void signedInChangeNeedsTheCurrentPassword() {
        String email = unique("change") + "@example.com";
        String token = newAccount("CUSTOMER", email);
        assertThat(http.post("/api/auth/password", token, Map.of("currentPassword", "wrong-one-entirely", "newPassword", "a-brand-new-secret")).code())
                .isEqualTo("wrong_password");
        assertThat(http.post("/api/auth/password", token, Map.of("currentPassword", "correct-horse-1", "newPassword", "a-brand-new-secret")).status())
                .isEqualTo(204);
        assertThat(http.post("/api/auth/signin", null, Map.of("email", email, "password", "a-brand-new-secret")).status()).isEqualTo(200);
    }
}
