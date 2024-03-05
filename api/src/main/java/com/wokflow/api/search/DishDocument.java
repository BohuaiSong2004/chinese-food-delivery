package com.wokflow.api.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DishDocument(
        @JsonProperty("dish_id") long dishId,
        @JsonProperty("restaurant_id") long restaurantId,
        @JsonProperty("restaurant_slug") String restaurantSlug,
        @JsonProperty("restaurant_name") String restaurantName,
        @JsonProperty("restaurant_name_zh") String restaurantNameZh,
        @JsonProperty("cuisine") String cuisine,
        @JsonProperty("neighborhood") String neighborhood,
        @JsonProperty("name") String name,
        @JsonProperty("name_zh") String nameZh,
        @JsonProperty("name_joined") String nameJoined,
        @JsonProperty("pinyin") String pinyin,
        @JsonProperty("pinyin_joined") String pinyinJoined,
        @JsonProperty("pinyin_initials") String pinyinInitials,
        @JsonProperty("description") String description,
        @JsonProperty("tags") List<String> tags,
        @JsonProperty("spice_level") int spiceLevel,
        @JsonProperty("price_cents") int priceCents,
        @JsonProperty("available") boolean available,
        @JsonProperty("accepting_orders") boolean acceptingOrders,
        @JsonProperty("popularity") double popularity,
        @JsonProperty("version") long version,
        /** Restaurant LIVE and dish not archived: only listed documents are ever returned. */
        @JsonProperty("listed") boolean listed) {}
