package com.wokflow.mockpay;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.NumberFormat;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

/** The hosted checkout page the customer is redirected to. No card fields: it is a sandbox with Pay / Decline buttons. */
@Controller
class CheckoutController {
    private final Intents intents;
    private final MockPayApplication.Props props;

    CheckoutController(Intents intents, MockPayApplication.Props props) {
        this.intents = intents;
        this.props = props;
    }

    @GetMapping("/checkout/{id}")
    ResponseEntity<String> page(@PathVariable String id) {
        var i = intents.find(id).orElseThrow(() -> new ApiError(HttpStatus.NOT_FOUND, "No such payment"));
        String amount = NumberFormat.getCurrencyInstance(Locale.US).format(i.amount() / 100.0);
        String body;
        if (!"requires_action".equals(i.status())) {
            body = """
                    <h1>Payment %s</h1>
                    <p><a href="%s">Return to the merchant</a></p>""".formatted(esc(i.status()), esc(returnUrl(i.returnUrl(), i.status())));
        } else {
            String token = token(i.id());
            body = """
                    <p class="badge">Sandbox — no real money moves</p>
                    <h1>Pay %s</h1>
                    <p class="desc">%s</p>
                    <form method="post" action="/checkout/%s/confirm">
                      <input type="hidden" name="token" value="%s">
                      <button type="submit" name="outcome" value="succeed" class="pay">Pay %s</button>
                      <button type="submit" name="outcome" value="decline" class="decline">Decline payment</button>
                    </form>""".formatted(esc(amount), esc(i.description()), esc(i.id()), token, esc(amount));
        }
        String html = """
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>MockPay checkout</title>
                <style>
                  body{font-family:system-ui,sans-serif;background:#f4f1ec;color:#1f1a17;display:grid;place-items:center;min-height:100vh;margin:0}
                  main{background:#fff;padding:2rem;border-radius:12px;max-width:24rem;width:calc(100%% - 2rem);box-shadow:0 2px 12px #0002}
                  h1{font-size:1.5rem;margin:.25rem 0}.desc{color:#5a514b}.badge{display:inline-block;background:#fff3cd;color:#5c4400;padding:.2rem .5rem;border-radius:4px;font-size:.85rem}
                  button{display:block;width:100%%;padding:.8rem;margin-top:.75rem;border-radius:8px;font-size:1rem;cursor:pointer;border:2px solid #1f1a17}
                  .pay{background:#1f1a17;color:#fff}.decline{background:#fff;color:#1f1a17}
                  button:focus-visible{outline:3px solid #b3261e;outline-offset:2px}
                </style></head><body><main>%s</main></body></html>""".formatted(body);
        return ResponseEntity.ok()
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self' "
                        + String.join(" ", props.allowedReturnOrigins()) + "; frame-ancestors 'none'; base-uri 'none'")
                .header("Referrer-Policy", "no-referrer")
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8)).body(html);
    }

    @PostMapping("/checkout/{id}/confirm")
    ResponseEntity<Void> confirm(@PathVariable String id, @RequestParam String outcome, @RequestParam String token) {
        if (!MessageDigest.isEqual(token(id).getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiError(HttpStatus.FORBIDDEN, "Invalid form token");
        }
        var i = intents.confirm(id, "succeed".equals(outcome));
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .header(HttpHeaders.LOCATION, returnUrl(i.returnUrl(), i.status())).build();
    }

    static String returnUrl(String base, String status) {
        return UriComponentsBuilder.fromUri(URI.create(base)).queryParam("payment", status).build().toUriString();
    }

    /** Binds the form to the intent so another site cannot post a confirmation for it. */
    private String token(String id) {
        return HexFormat.of().formatHex(Signatures.hmac(props.secretKey(), "checkout:" + id));
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    @ExceptionHandler(ApiError.class)
    ResponseEntity<String> error(ApiError e) {
        return ResponseEntity.status(e.status).contentType(MediaType.TEXT_PLAIN).body(e.getMessage());
    }
}
