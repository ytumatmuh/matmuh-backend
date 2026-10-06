package com.matmuh.matmuhsite.core.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "revalidation")
@Getter
@Setter
public class RevalidationProperties {

    private String url = "";

    private String secret = "";

    // Writes inside this window go out as one call.
    private Duration window = Duration.ofSeconds(2);

    private List<Duration> retryDelays = new ArrayList<>(List.of(
            Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2)));

    private int timeoutSeconds = 10;

    public boolean isEnabled() {
        return url != null && !url.isBlank() && secret != null && !secret.isBlank();
    }
}
