package com.wokflow.api.images;

import java.util.Optional;

/** Where processed images live: a local directory (compose) or an S3 bucket behind CloudFront (AWS). */
public interface ImageStore {
    void put(String name, byte[] bytes, String contentType);

    Optional<byte[]> get(String name);

    boolean exists(String name);
}
