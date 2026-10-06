package com.matmuh.matmuhsite.core.utilities.revalidation;

import com.matmuh.matmuhsite.core.properties.RevalidationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Tells the frontend which collections changed, so it marks their cached reads
 * stale (inscribed's revalidation route). Writes are gathered for a short
 * window and sent as one call; a failed call is retried a few times and then
 * logged, never thrown at the write that caused it.
 */
@Component
public class FrontendRevalidator {

    interface Scheduler {
        void schedule(Runnable task, Duration delay);
    }

    private static final Logger logger = LoggerFactory.getLogger(FrontendRevalidator.class);

    private final RevalidationProperties properties;

    private final RestClient restClient;

    private final Scheduler scheduler;

    private final Set<String> pending = new LinkedHashSet<>();

    private boolean flushScheduled;

    @Autowired
    public FrontendRevalidator(RevalidationProperties properties) {
        this(properties,
                RestClient.builder().requestFactory(requestFactory(properties.getTimeoutSeconds())).build(),
                singleThreadScheduler());
    }

    FrontendRevalidator(RevalidationProperties properties, RestClient restClient, Scheduler scheduler) {
        this.properties = properties;
        this.restClient = restClient;
        this.scheduler = scheduler;
        if (!properties.isEnabled() && properties.getUrl() != null && !properties.getUrl().isBlank()) {
            logger.warn("Frontend revalidation is off: revalidation.url is set but revalidation.secret is not");
        }
    }

    public void request(Collection<String> collections) {
        if (!properties.isEnabled() || collections.isEmpty()) {
            return;
        }
        synchronized (pending) {
            pending.addAll(collections);
            if (flushScheduled) {
                return;
            }
            flushScheduled = true;
        }
        scheduler.schedule(this::flush, properties.getWindow());
    }

    private void flush() {
        List<String> batch;
        synchronized (pending) {
            batch = List.copyOf(pending);
            pending.clear();
            flushScheduled = false;
        }
        send(batch, 0);
    }

    private void send(List<String> batch, int retry) {
        try {
            restClient.post()
                    .uri(properties.getUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getSecret())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("collections", batch))
                    .retrieve()
                    .toBodilessEntity();
            logger.info("Frontend revalidated {}", batch);
        } catch (RuntimeException exception) {
            var delays = properties.getRetryDelays();
            if (retry < delays.size()) {
                var delay = delays.get(retry);
                logger.warn("Frontend revalidation of {} failed, retrying in {}: {}", batch, delay, exception.getMessage());
                scheduler.schedule(() -> send(batch, retry + 1), delay);
            } else {
                logger.error("Frontend revalidation of {} failed after {} retries: {}", batch, retry, exception.getMessage());
            }
        }
    }

    private static Scheduler singleThreadScheduler() {
        var executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "frontend-revalidator");
            thread.setDaemon(true);
            return thread;
        });
        return (task, delay) -> executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static SimpleClientHttpRequestFactory requestFactory(int timeoutSeconds) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        return factory;
    }
}
