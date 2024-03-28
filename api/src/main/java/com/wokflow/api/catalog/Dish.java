package com.wokflow.api.catalog;

import java.util.List;

public record Dish(long id, long restaurantId, long categoryId, String name, String nameZh, String description,
                   int priceCents, List<String> tags, int spiceLevel, boolean available, Integer dailyStock,
                   Integer stockRemaining, long version) {

    public boolean limited() { return dailyStock != null; }
}
