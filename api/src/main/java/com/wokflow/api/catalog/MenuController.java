package com.wokflow.api.catalog;

import com.wokflow.api.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Menu editing for restaurant staff (the quick price/stock PATCH stays in MerchantController). */
@RestController
@RequestMapping("/api/merchant")
public class MenuController {
    public record CategoryUpdate(@Valid @NotNull MenuEditor.CategoryInput category, @Positive long version) {}

    public record DishUpdate(@Valid @NotNull MenuEditor.DishInput dish, @Positive long version) {}

    public record Order(@NotNull @Size(max = 100) List<@NotNull Long> ids) {}

    private final MenuEditor editor;
    private final com.wokflow.api.images.ImageService images;
    private final com.wokflow.api.common.RateLimiter limiter;
    private final com.wokflow.api.common.Bulkhead processing;

    public MenuController(MenuEditor editor, com.wokflow.api.images.ImageService images,
                          com.wokflow.api.common.RateLimiter limiter, io.micrometer.core.instrument.MeterRegistry meters) {
        this.editor = editor;
        this.images = images;
        this.limiter = limiter;
        // Decoding and resizing a 12-megapixel photo is CPU-heavy: two at a time per replica, so a
        // burst of uploads queues instead of slowing every order placement on the same cores.
        this.processing = new com.wokflow.api.common.Bulkhead("image-processing", 2, java.time.Duration.ofSeconds(20), meters);
    }

    private String process(CurrentUser u, org.springframework.web.multipart.MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw com.wokflow.api.common.ApiException.badRequest("file_required", "Choose a photo to upload");
        }
        limiter.check("image-upload", Long.toString(u.id()), 60, java.time.Duration.ofHours(1));
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (java.io.IOException e) {
            throw com.wokflow.api.common.ApiException.badRequest("upload_failed", "The upload did not complete");
        }
        return processing.run(() -> images.storeUpload("d", bytes));
    }

    @PostMapping(path = "/dishes/{id}/image", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    Dish uploadDishImage(@PathVariable long id, @org.springframework.web.bind.annotation.RequestParam("file")
                         org.springframework.web.multipart.MultipartFile file) {
        CurrentUser u = CurrentUser.require();
        com.wokflow.api.catalog.Dish d = editor.requireOwnDish(u, id); // check access before spending CPU
        return editor.setDishImage(u, d.id(), process(u, file));
    }

    @DeleteMapping("/dishes/{id}/image")
    Dish removeDishImage(@PathVariable long id) { return editor.setDishImage(CurrentUser.require(), id, null); }

    @PostMapping(path = "/restaurants/{rid}/image", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    java.util.Map<String, String> uploadCover(@PathVariable long rid, @org.springframework.web.bind.annotation.RequestParam("file")
                                              org.springframework.web.multipart.MultipartFile file) {
        CurrentUser u = CurrentUser.require();
        u.requireStaffOf(rid);
        String key = process(u, file);
        editor.setRestaurantImage(u, rid, key);
        return java.util.Map.of("image", images.url(key));
    }

    @DeleteMapping("/restaurants/{rid}/image")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeCover(@PathVariable long rid) { editor.setRestaurantImage(CurrentUser.require(), rid, null); }

    @PostMapping("/restaurants/{rid}/categories")
    @ResponseStatus(HttpStatus.CREATED)
    CatalogRepository.Category createCategory(@PathVariable long rid, @Valid @RequestBody MenuEditor.CategoryInput in) {
        return editor.createCategory(CurrentUser.require(), rid, in);
    }

    @PutMapping("/categories/{id}")
    CatalogRepository.Category updateCategory(@PathVariable long id, @Valid @RequestBody CategoryUpdate req) {
        return editor.updateCategory(CurrentUser.require(), id, req.category(), req.version());
    }

    @PostMapping("/restaurants/{rid}/categories/order")
    List<CatalogRepository.Category> reorder(@PathVariable long rid, @Valid @RequestBody Order req) {
        return editor.reorder(CurrentUser.require(), rid, req.ids());
    }

    @DeleteMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archiveCategory(@PathVariable long id) { editor.archiveCategory(CurrentUser.require(), id); }

    @PostMapping("/restaurants/{rid}/dishes")
    @ResponseStatus(HttpStatus.CREATED)
    Dish createDish(@PathVariable long rid, @Valid @RequestBody MenuEditor.DishInput in) {
        return editor.createDish(CurrentUser.require(), rid, in);
    }

    @PutMapping("/dishes/{id}")
    Dish updateDish(@PathVariable long id, @Valid @RequestBody DishUpdate req) {
        return editor.updateDish(CurrentUser.require(), id, req.dish(), req.version());
    }

    @DeleteMapping("/dishes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archiveDish(@PathVariable long id) { editor.archiveDish(CurrentUser.require(), id); }
}
