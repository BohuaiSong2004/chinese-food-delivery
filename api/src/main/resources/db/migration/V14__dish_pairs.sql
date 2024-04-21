-- "Often ordered together": for each pair of dishes of one restaurant, how many paid orders in
-- the last 90 days contained both, and a cosine score (together / sqrt(orders with a × orders
-- with b)) so a popular dish is not suggested with everything. Rebuilt hourly by Pairings.
create table dish_pairs (
    dish_id       bigint  not null references dishes (id) on delete cascade,
    other_dish_id bigint  not null references dishes (id) on delete cascade,
    restaurant_id bigint  not null references restaurants (id) on delete cascade,
    together      integer not null check (together > 0),
    score         real    not null,
    primary key (dish_id, other_dish_id)
);
