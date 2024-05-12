package com.wokflow.api.order;

import com.wokflow.api.auth.CurrentUser;
import com.wokflow.api.common.ApiException;
import com.wokflow.api.live.LiveHub;
import com.wokflow.api.order.OrderViews.OrderView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class OrderController {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    public record Item(@Positive long dishId, @Min(1) @Max(50) int quantity) {}

    public record PlaceOrderRequest(@Positive long restaurantId,
                                    @NotEmpty @Size(max = 20) List<@Valid Item> items,
                                    @NotBlank @Size(max = 200) String deliveryAddress,
                                    @Size(max = 300) String deliveryNotes) {}

    public record ActionRequest(@Size(max = 200) String reason) {}

    private final OrderService orders;
    private final LiveHub live;

    public OrderController(OrderService orders, LiveHub live) {
        this.orders = orders;
        this.live = live;
    }

    @PostMapping("/api/orders")
    ResponseEntity<OrderView> place(@Valid @RequestBody PlaceOrderRequest req,
                                    @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw ApiException.badRequest("idempotency_key_required",
                    "Send an Idempotency-Key header (8-64 letters, digits, '-' or '_') so retries cannot create a second order");
        }
        var placed = orders.place(CurrentUser.require(), new OrderService.PlaceOrder(req.restaurantId(),
                req.items().stream().map(i -> new OrderService.ItemRequest(i.dishId(), i.quantity())).toList(),
                req.deliveryAddress(), req.deliveryNotes()), key);
        return ResponseEntity.status(placed.created() ? HttpStatus.CREATED : HttpStatus.OK).body(placed.order());
    }

    @GetMapping("/api/orders")
    List<OrderView> mine() { return orders.mine(CurrentUser.require()); }

    @GetMapping("/api/orders/{id}")
    OrderView get(@PathVariable UUID id) { return orders.get(id, CurrentUser.require()); }

    /** Retry point if the provider was unreachable when the order was placed. */
    @PostMapping("/api/orders/{id}/checkout")
    Map<String, String> checkout(@PathVariable UUID id) {
        return orders.checkout(id, CurrentUser.require())
                .map(url -> Map.of("checkoutUrl", url))
                .orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "payments_unavailable",
                        "The payment provider is not responding; try again in a moment"));
    }

    @PostMapping("/api/orders/{id}/cancel")
    OrderView cancel(@PathVariable UUID id, @RequestBody(required = false) ActionRequest body,
                     @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return orders.act(id, OrderAction.CANCEL, CurrentUser.require(), Versions.parse(ifMatch),
                body == null || body.reason() == null ? "cancelled by customer" : body.reason());
    }

    /** Snapshot first, then live status changes. Closes when the access token expires so the client reconnects with a fresh one. */
    @GetMapping(path = "/api/orders/{id}/events", produces = "text/event-stream")
    SseEmitter events(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        OrderView snapshot = orders.get(id, CurrentUser.from(jwt));
        return live.open("order:" + id, lifetime(jwt), snapshot);
    }

    @GetMapping(path = "/api/me/events", produces = "text/event-stream")
    SseEmitter myEvents(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser u = CurrentUser.from(jwt);
        return live.open(switch (u.role()) {
            case CUSTOMER -> "customer:" + u.id();
            case RIDER -> "rider:" + u.id();
            default -> throw ApiException.badRequest("no_stream", "Use the restaurant stream");
        }, lifetime(jwt), null);
    }

    static Duration lifetime(Jwt jwt) {
        Instant exp = jwt.getExpiresAt();
        Duration left = exp == null ? Duration.ofMinutes(15) : Duration.between(Instant.now(), exp);
        return left.isNegative() || left.isZero() ? Duration.ofSeconds(1) : left;
    }
}
