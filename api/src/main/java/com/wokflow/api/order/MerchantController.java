package com.wokflow.api.order;

import com.wokflow.api.auth.CurrentUser;
import com.wokflow.api.catalog.CatalogRepository;
import com.wokflow.api.catalog.CatalogService;
import com.wokflow.api.catalog.CatalogViews.MenuView;
import com.wokflow.api.catalog.Dish;
import com.wokflow.api.common.ApiException;
import com.wokflow.api.common.Tx;
import com.wokflow.api.ledger.LedgerService;
import com.wokflow.api.live.LiveHub;
import com.wokflow.api.order.OrderViews.OrderView;
import com.wokflow.api.outbox.Outbox;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/merchant")
public class MerchantController {
    public record DishUpdate(@Min(1) @Max(100_000) int priceCents, boolean available,
                             @Min(1) @Max(10_000) Integer dailyStock, @Positive long version) {}

    public record PayoutRequest(@Positive @Max(100_000_000) long amountCents) {}

    public record RestaurantSummary(long id, String slug, String name, String nameZh, boolean acceptingOrders,
                                    com.wokflow.api.catalog.RestaurantStatus status, String statusNote) {
        static RestaurantSummary of(com.wokflow.api.catalog.Restaurant r, String note) {
            return new RestaurantSummary(r.id(), r.slug(), r.name(), r.nameZh(), r.acceptingOrders(), r.status(), note);
        }
    }

    private final OrderService orders;
    private final CatalogRepository catalog;
    private final CatalogService catalogService;
    private final LedgerService ledger;
    private final Outbox outbox;
    private final LiveHub live;
    private final Tx tx;

    public MerchantController(OrderService orders, CatalogRepository catalog, CatalogService catalogService,
                              LedgerService ledger, Outbox outbox, LiveHub live, Tx tx) {
        this.orders = orders;
        this.catalog = catalog;
        this.catalogService = catalogService;
        this.ledger = ledger;
        this.outbox = outbox;
        this.live = live;
        this.tx = tx;
    }

    @GetMapping("/restaurants")
    List<RestaurantSummary> mine() {
        CurrentUser u = CurrentUser.require();
        return catalog.restaurants().stream()
                .filter(r -> u.is(com.wokflow.api.auth.Role.ADMIN) || u.restaurantIds().contains(r.id()))
                .map(r -> RestaurantSummary.of(r, catalog.statusNote(r.id()))).toList();
    }

    @GetMapping("/restaurants/{rid}/orders")
    List<OrderView> board(@PathVariable long rid) {
        return orders.forRestaurant(rid, CurrentUser.require());
    }

    @GetMapping(path = "/restaurants/{rid}/events", produces = "text/event-stream")
    SseEmitter events(@PathVariable long rid, @AuthenticationPrincipal Jwt jwt) {
        CurrentUser.from(jwt).requireStaffOf(rid);
        return live.open("restaurant:" + rid, OrderController.lifetime(jwt), null);
    }

    @PostMapping("/orders/{id}/{action}")
    OrderView act(@PathVariable UUID id, @PathVariable String action,
                  @RequestHeader(name = "If-Match", required = false) String ifMatch,
                  @RequestBody(required = false) OrderController.ActionRequest body) {
        OrderAction a = switch (action) {
            case "accept" -> OrderAction.ACCEPT;
            case "reject" -> OrderAction.REJECT;
            case "ready" -> OrderAction.MARK_READY;
            default -> throw ApiException.notFound("Action");
        };
        String reason = body == null ? null : body.reason();
        if (a == OrderAction.REJECT && (reason == null || reason.isBlank())) {
            reason = "rejected by restaurant";
        }
        return orders.act(id, a, CurrentUser.require(), Versions.parse(ifMatch), reason);
    }

    /** Fresh from Postgres (the public menu is cached). */
    @GetMapping("/restaurants/{rid}/menu")
    Map<String, Object> menu(@PathVariable long rid) {
        CurrentUser.require().requireStaffOf(rid);
        MenuView v = catalogService.menu(rid);
        return Map.of("restaurant", v.restaurant(), "dishes", catalog.dishes(rid), "categories", catalog.categories(rid));
    }

    @PatchMapping("/dishes/{id}")
    Dish updateDish(@PathVariable long id, @Valid @RequestBody DishUpdate req) {
        CurrentUser u = CurrentUser.require();
        Dish d = catalog.dish(id).orElseThrow(() -> ApiException.notFound("Dish"));
        u.requireStaffOf(d.restaurantId());
        tx.runVoid(() -> {
            catalog.updateDish(id, d.restaurantId(), req.version(), req.priceCents(), req.available(), req.dailyStock())
                    .orElseThrow(() -> ApiException.conflict("stale_version", "This dish was changed elsewhere; reload and try again"));
            catalog.bumpMenuVersion(d.restaurantId());
            outbox.publish(Outbox.DISH_CHANGED, Long.toString(id), Map.of("dishId", id, "restaurantId", d.restaurantId()));
        });
        return catalog.dish(id).orElseThrow();
    }

    @PostMapping("/restaurants/{rid}/{state:pause|resume}")
    RestaurantSummary pause(@PathVariable long rid, @PathVariable String state) {
        CurrentUser.require().requireStaffOf(rid);
        tx.runVoid(() -> {
            catalog.setAccepting(rid, state.equals("resume")).orElseThrow(() -> ApiException.notFound("Restaurant"));
            outbox.publish(Outbox.RESTAURANT_CHANGED, Long.toString(rid), Map.of("restaurantId", rid));
        });
        var r = catalog.restaurant(rid).orElseThrow();
        return RestaurantSummary.of(r, catalog.statusNote(rid));
    }

    @GetMapping("/restaurants/{rid}/ledger")
    Map<String, Object> earnings(@PathVariable long rid) {
        CurrentUser.require().requireStaffOf(rid);
        return Map.of("balance", ledger.balance(rid), "entries", ledger.merchantEntries(rid, 50));
    }

    @PostMapping("/restaurants/{rid}/payouts")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> payout(@PathVariable long rid, @Valid @RequestBody PayoutRequest req) {
        CurrentUser u = CurrentUser.require();
        u.requireStaffOf(rid);
        UUID id = tx.run(() -> ledger.payout(rid, req.amountCents(), u.id()));
        return Map.of("payoutId", id, "balance", ledger.balance(rid));
    }
}
