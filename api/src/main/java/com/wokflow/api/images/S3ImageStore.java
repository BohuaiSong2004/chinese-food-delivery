package com.wokflow.api.images;

import java.util.Optional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/** Images in S3; CloudFront serves /images/* from the bucket, so the API only writes. */
public class S3ImageStore implements ImageStore {
    private final S3Client s3;
    private final String bucket;
    private final String prefix;

    public S3ImageStore(S3Client s3, String bucket, String prefix) {
        this.s3 = s3;
        this.bucket = bucket;
        this.prefix = prefix == null ? "" : prefix;
    }

    @Override
    public void put(String name, byte[] bytes, String contentType) {
        s3.putObject(b -> b.bucket(bucket).key(prefix + name).contentType(contentType)
                .cacheControl("public, max-age=31536000, immutable"), RequestBody.fromBytes(bytes));
    }

    @Override
    public Optional<byte[]> get(String name) {
        try {
            return Optional.of(s3.getObjectAsBytes(b -> b.bucket(bucket).key(prefix + name)).asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean exists(String name) {
        try {
            s3.headObject(b -> b.bucket(bucket).key(prefix + name));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }
}
