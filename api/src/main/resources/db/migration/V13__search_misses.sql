-- Searches whose words matched no dish anywhere in the catalog, counted so an admin can see which
-- names people use that the menu and synonyms.txt do not know yet. Written in batches every 30 s
-- (SearchMisses), kept for 30 days and at most 5,000 rows.
create table search_misses (
    query      text        primary key check (length(query) between 1 and 100),
    misses     integer     not null check (misses > 0),
    first_seen timestamptz not null default now(),
    last_seen  timestamptz not null default now()
);
create index search_misses_recent on search_misses (last_seen desc);
