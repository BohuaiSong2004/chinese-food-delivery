package com.wokflow.api.images;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** The production image store against an S3-compatible server (adobe/s3mock). */
class S3ImageStoreIT {
    static final GenericContainer<?> S3 = new GenericContainer<>("adobe/s3mock:5.2.0")
            .withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", "wokflow-images")
            .withExposedPorts(9090)
            .waitingFor(Wait.forHttp("/").forPort(9090).forStatusCodeMatching(c -> c < 500));
    static S3Client client;

    @BeforeAll
    static void start() {
        S3.start();
        client = S3Client.builder().region(Region.US_WEST_2).httpClient(UrlConnectionHttpClient.create())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .endpointOverride(URI.create("http://" + S3.getHost() + ":" + S3.getMappedPort(9090)))
                .forcePathStyle(true).build();
    }

    @AfterAll
    static void stop() {
        S3.stop();
    }

    @Test
    void putGetExistsWithCacheHeaders() throws Exception {
        S3ImageStore store = new S3ImageStore(client, "wokflow-images", "images/");
        ImageService service = new ImageService(store, "https://cdn.example.com/images");
        String key = service.storeUpload("d", ImageServiceTest.png(1200, 900));
        assertThat(store.exists(key + "-640.jpg")).isTrue();
        assertThat(store.exists("d/00000000000000000000-640.jpg")).isFalse();
        assertThat(store.get(key + "-320.jpg")).isPresent();
        assertThat(store.get("d/00000000000000000000-320.jpg")).isEmpty();
        var head = client.headObject(b -> b.bucket("wokflow-images").key("images/" + key + "-1024.jpg"));
        assertThat(head.contentType()).isEqualTo("image/jpeg");
        assertThat(head.cacheControl()).contains("immutable");
        assertThat(service.url(key)).isEqualTo("https://cdn.example.com/images/" + key);
    }
}
