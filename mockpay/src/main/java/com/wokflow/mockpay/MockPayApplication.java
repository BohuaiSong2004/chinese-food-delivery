package com.wokflow.mockpay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.util.List;

/** A sandbox payment provider so the platform's payment flow (intents, hosted checkout, signed webhooks, refunds) runs end to end locally. */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(MockPayApplication.Props.class)
public class MockPayApplication {
    @ConfigurationProperties("mockpay")
    public record Props(String secretKey, String webhookSecret, String webhookUrl, String publicUrl,
                        List<String> allowedReturnOrigins, double duplicateRate) {}

    public static void main(String[] args) {
        SpringApplication.run(MockPayApplication.class, args);
    }
}
