package com.wokflow.api.onboarding;

import com.wokflow.api.auth.CurrentUser;
import com.wokflow.api.catalog.Restaurant;
import com.wokflow.api.common.ApiException;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin review queue: restaurant applications and rider sign-ups. */
@RestController
@RequestMapping("/api/admin")
public class ReviewController {
    public record Note(@Size(max = 300) String note) {}

    private final OnboardingService onboarding;

    public ReviewController(OnboardingService onboarding) { this.onboarding = onboarding; }

    @GetMapping("/applications")
    Map<String, Object> queue() { return onboarding.queue(CurrentUser.require()); }

    @PostMapping("/restaurants/{rid}/{decision:approve|reject|suspend|reinstate}")
    Map<String, Object> decide(@PathVariable long rid, @PathVariable String decision,
                               @RequestBody(required = false) Note body) {
        Restaurant r = onboarding.decide(CurrentUser.require(), rid,
                OnboardingService.Decision.valueOf(decision.toUpperCase(java.util.Locale.ROOT)),
                body == null ? null : body.note());
        return Map.of("id", r.id(), "status", r.status());
    }

    @PostMapping("/riders/{uid}/{decision:approve|reject}")
    Map<String, Object> rider(@PathVariable long uid, @PathVariable String decision, @RequestBody(required = false) Note body) {
        onboarding.decideRider(CurrentUser.require(), uid, decision.equals("approve"), body == null ? null : body.note());
        return Map.of("id", uid, "approved", decision.equals("approve"));
    }

    @GetMapping("/audit")
    List<OnboardingService.AuditEntry> audit(@RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 200) {
            throw ApiException.badRequest("bad_limit", "limit must be 1..200");
        }
        return onboarding.auditLog(CurrentUser.require(), limit);
    }
}
