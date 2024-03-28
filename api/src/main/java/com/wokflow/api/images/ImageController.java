package com.wokflow.api.images;

import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves images from the local store. Names are content hashes, so responses can be cached for a
 * year and never revalidated. (On AWS, CloudFront serves the S3 bucket and this is not used.)
 */
@RestController
public class ImageController {
    private final ImageStore store;

    public ImageController(ImageStore store) { this.store = store; }

    @GetMapping("/api/images/{dir:[a-z]}/{file:[0-9a-f]{20}-\\d{3,4}\\.jpg}")
    ResponseEntity<byte[]> image(@PathVariable String dir, @PathVariable String file) {
        return store.get(dir + "/" + file)
                .map(bytes -> ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                        .eTag(file).body(bytes))
                .orElseGet(() -> ResponseEntity.notFound().cacheControl(CacheControl.maxAge(Duration.ofMinutes(1))).build());
    }
}
