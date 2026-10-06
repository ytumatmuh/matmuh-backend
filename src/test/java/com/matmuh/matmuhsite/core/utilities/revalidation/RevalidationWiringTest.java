package com.matmuh.matmuhsite.core.utilities.revalidation;

import com.matmuh.matmuhsite.business.abstracts.CmsCollectionProvider;
import com.matmuh.matmuhsite.business.constants.CollectionRegistry;
import com.matmuh.matmuhsite.business.constants.LectureCollectionSchema;
import com.matmuh.matmuhsite.core.config.RevalidationConfig;
import com.matmuh.matmuhsite.core.properties.RevalidationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The revalidation beans start without a database, off by default. */
class RevalidationWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(RevalidationProperties.class, CollectionRegistry.class, RevalidationScope.class,
                    FrontendRevalidator.class, RevalidationInterceptor.class, RevalidationConfig.class)
            .withBean(CmsCollectionProvider.class, () -> {
                var provider = mock(CmsCollectionProvider.class);
                when(provider.collectionKey()).thenReturn(LectureCollectionSchema.KEY);
                return provider;
            });

    @Test
    void startsWithRevalidationOff() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RevalidationProperties.class).isEnabled()).isFalse();
        });
    }

    @Test
    void bindsTheSettings() {
        runner.withPropertyValues(
                        "revalidation.url=https://site.test/cms-revalidate",
                        "revalidation.secret=s3cret",
                        "revalidation.window=500ms",
                        "revalidation.retry-delays=1s,10s")
                .run(context -> {
                    var properties = context.getBean(RevalidationProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getWindow()).isEqualTo(Duration.ofMillis(500));
                    assertThat(properties.getRetryDelays()).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(10));
                });
    }
}
