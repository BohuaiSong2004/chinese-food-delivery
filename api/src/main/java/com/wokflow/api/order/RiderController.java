package com.wokflow.api.order;

import com.wokflow.api.auth.CurrentUser;
import com.wokflow.api.common.ApiException;
import com.wokflow.api.live.LiveHub;
import com.wokflow.api.order.OrderViews.OrderView;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/rider")
public class RiderController {
    private final OrderService orders;
    private final LiveHub live;

    public RiderController(OrderService orders, LiveHub live) {
        this.orders = orders;
        this.live = live;
    }

    @GetMapping("/jobs")
    List<OrderView> jobs() {
        CurrentUser.require().requireApproved();
        return orders.openJobs();
    }

    /** Deliveries in progress (not paged: a rider has a handful at most). */
    @GetMapping("/orders")
    List<OrderView> mine() { return orders.riderOrders(CurrentUser.require()); }

    @GetMapping("/orders/history")
    com.wokflow.api.common.Cursor.Page<OrderView> history(@org.springframework.web.bind.annotation.RequestParam(required = false) String cursor,
                                                          @org.springframework.web.bind.annotation.RequestParam(required = false) Integer limit) {
        return orders.riderHistory(CurrentUser.require(), cursor, limit);
    }

    @GetMapping(path = "/jobs/events", produces = "text/event-stream")
    SseEmitter jobEvents(@AuthenticationPrincipal Jwt jwt,
                         @org.springframework.web.bind.annotation.RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        CurrentUser.from(jwt).requireApproved();
        return live.open("jobs", OrderController.lifetime(jwt), null, lastEventId);
    }

    @PostMapping("/orders/{id}/{action}")
    OrderView act(@PathVariable UUID id, @PathVariable String action,
                  @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        OrderAction a = switch (action) {
            case "claim" -> OrderAction.CLAIM;
            case "pickup" -> OrderAction.PICK_UP;
            case "deliver" -> OrderAction.DELIVER;
            default -> throw ApiException.notFound("Action");
        };
        CurrentUser u = CurrentUser.require();
        u.requireApproved();
        return orders.act(id, a, u, Versions.parse(ifMatch), null);
    }
}
