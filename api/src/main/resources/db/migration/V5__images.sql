-- Content-addressed image keys ("d/<sha256 prefix>"); the files are <key>-320/640/1024.jpg in the image store.
alter table dishes add column image_key text check (image_key ~ '^[a-z]/[0-9a-f]{20}$');
alter table restaurants add column image_key text check (image_key ~ '^[a-z]/[0-9a-f]{20}$');
