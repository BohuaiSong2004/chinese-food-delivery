package com.wokflow.api.images;

import com.wokflow.api.config.AppProperties;
import java.net.URI;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class ImageConfig {

    @Bean
    ImageStore imageStore(AppProperties props) {
        AppProperties.Images cfg = props.images();
        if ("s3".equals(cfg.store())) {
            var b = S3Client.builder().region(Region.of(cfg.region())).httpClient(UrlConnectionHttpClient.create())
                    .credentialsProvider(DefaultCredentialsProvider.builder().build());
            if (cfg.endpoint() != null && !cfg.endpoint().isBlank()) {
                b.endpointOverride(URI.create(cfg.endpoint())).forcePathStyle(true);
            }
            return new S3ImageStore(b.build(), cfg.bucket(), cfg.prefix());
        }
        return new LocalImageStore(Path.of(cfg.dir()));
    }

    @Bean
    ImageService imageService(ImageStore store, AppProperties props) {
        return new ImageService(store, props.images().publicBase());
    }
}
