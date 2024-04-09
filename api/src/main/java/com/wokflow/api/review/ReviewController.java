package com.wokflow.api.review;

import com.wokflow.api.auth.CurrentUser;
import com.wokflow.api.catalog.CatalogRepository;
import com.wokflow.api.catalog.Restaurant;
import com.wokflow.api.common.ApiException;
import com.wokflow.api.common.Cursor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReviewController {
    public record NewReview(@Min(1) @Max(5) int rating, @Size(max = 1000) String comment) {}

    public record Reply(@NotBlank @Size(max = 1000) String reply) {}

    public record Note(@Size(max = 300) String note) {}

    public record RestaurantReviews(ReviewService.Rating rating, Cursor.Page<ReviewService.Review> page) {}

    private final ReviewService reviews;
    private final CatalogRepository catalog;

    public ReviewController(ReviewService reviews, CatalogRepository catalog) {
        this.reviews = reviews;
        this.catalog = catalog;
    }

    @PostMapping("/api/orders/{id}/review")
    @ResponseStatus(HttpStatus.CREATED)
    ReviewService.Review review(@PathVariable UUID id, @Valid @RequestBody NewReview req) {
        return reviews.review(CurrentUser.require(), id, req.rating(), req.comment());
    }

    @GetMapping("/api/restaurants/{slug}/reviews")
    RestaurantReviews forRestaurant(@PathVariable String slug, @RequestParam(required = false) String cursor,
                                    @RequestParam(required = false) Integer limit) {
        Restaurant r = catalog.restaurantBySlug(slug).filter(Restaurant::live).orElseThrow(() -> ApiException.notFound("Restaurant"));
        return new RestaurantReviews(reviews.rating(r.id()), reviews.forRestaurant(r.id(), cursor, limit, false));
    }

    @GetMapping("/api/merchant/restaurants/{rid}/reviews")
    RestaurantReviews merchantView(@PathVariable long rid, @RequestParam(required = false) String cursor,
                                   @RequestParam(required = false) Integer limit) {
        CurrentUser.require().requireStaffOf(rid);
        return new RestaurantReviews(reviews.rating(rid), reviews.forRestaurant(rid, cursor, limit, true));
    }

    @PostMapping("/api/merchant/reviews/{orderId}/reply")
    ReviewService.Review reply(@PathVariable UUID orderId, @Valid @RequestBody Reply req) {
        return reviews.reply(CurrentUser.require(), orderId, req.reply());
    }

    @PostMapping("/api/admin/reviews/{orderId}/{action:hide|unhide}")
    ReviewService.Review moderate(@PathVariable UUID orderId, @PathVariable String action, @RequestBody(required = false) Note body) {
        return reviews.moderate(CurrentUser.require(), orderId, action.equals("hide"), body == null ? null : body.note());
    }
}
