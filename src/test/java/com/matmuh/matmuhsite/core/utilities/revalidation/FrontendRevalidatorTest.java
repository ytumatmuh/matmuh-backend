package com.matmuh.matmuhsite.core.utilities.revalidation;

import com.matmuh.matmuhsite.core.properties.RevalidationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FrontendRevalidatorTest {

    private static final String URL = "https://site.test/cms-revalidate";

    /** Holds scheduled tasks until the test runs them. */
    private static final class ManualScheduler implements FrontendRevalidator.Scheduler {

        final List<Runnable> tasks = new ArrayList<>();

        final List<Duration> delays = new ArrayList<>();

        @Override
        public void schedule(Runnable task, Duration delay) {
            tasks.add(task);
            delays.add(delay);
        }

        void runNext() {
            tasks.remove(0).run();
        }
    }

    private final RestClient.Builder builder = RestClient.builder();

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private final ManualScheduler scheduler = new ManualScheduler();

    @Test
    void sendsTheWritesOfOneWindowAsOneCall() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer s3cret"))
                .andExpect(jsonPath("$.collections[0]").value("news"))
                .andExpect(jsonPath("$.collections[1]").value("staff"))
                .andExpect(jsonPath("$.collections.length()").value(2))
                .andRespond(withSuccess());
        var revalidator = new FrontendRevalidator(properties(URL, "s3cret"), builder.build(), scheduler);

        revalidator.request(List.of("news"));
        revalidator.request(List.of("staff", "news"));

        assertThat(scheduler.tasks).hasSize(1);
        assertThat(scheduler.delays).containsExactly(Duration.ofSeconds(2));
        scheduler.runNext();
        server.verify();
    }

    @Test
    void retriesAFailedCallAndThenGivesUp() {
        server.expect(ExpectedCount.times(4), requestTo(URL)).andRespond(withServerError());
        var properties = properties(URL, "s3cret");
        properties.setRetryDelays(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3)));
        var revalidator = new FrontendRevalidator(properties, builder.build(), scheduler);

        revalidator.request(List.of("news"));
        for (var call = 0; call < 4; call++) {
            scheduler.runNext();
        }

        assertThat(scheduler.tasks).isEmpty();
        assertThat(scheduler.delays).containsExactly(
                Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3));
        server.verify();
    }

    @Test
    void sendsNothingWithoutAUrlOrASecret() {
        new FrontendRevalidator(properties("", "s3cret"), builder.build(), scheduler).request(List.of("news"));
        new FrontendRevalidator(properties(URL, ""), builder.build(), scheduler).request(List.of("news"));

        assertThat(scheduler.tasks).isEmpty();
    }

    private static RevalidationProperties properties(String url, String secret) {
        var properties = new RevalidationProperties();
        properties.setUrl(url);
        properties.setSecret(secret);
        return properties;
    }
}
