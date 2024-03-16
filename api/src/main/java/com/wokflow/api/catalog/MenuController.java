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

    public MenuController(MenuEditor editor) { this.editor = editor; }

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
