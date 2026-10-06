package com.matmuh.matmuhsite.core.config;

import com.matmuh.matmuhsite.core.utilities.revalidation.FrontendRevalidator;
import com.matmuh.matmuhsite.core.utilities.revalidation.RevalidationInterceptor;
import com.matmuh.matmuhsite.core.utilities.revalidation.RevalidationScope;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class RevalidationConfig implements WebMvcConfigurer {

    private final RevalidationInterceptor revalidationInterceptor;

    private final RevalidationScope revalidationScope;

    private final FrontendRevalidator frontendRevalidator;

    public RevalidationConfig(RevalidationInterceptor revalidationInterceptor,
                              RevalidationScope revalidationScope,
                              FrontendRevalidator frontendRevalidator) {
        this.revalidationInterceptor = revalidationInterceptor;
        this.revalidationScope = revalidationScope;
        this.frontendRevalidator = frontendRevalidator;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(revalidationInterceptor).addPathPatterns("/api/**");
    }

    // The schema utilities that run at startup rewrite data (note counts,
    // orphan slots, attachments), so every deploy refreshes every collection.
    @EventListener(ApplicationReadyEvent.class)
    public void revalidateAfterStartup() {
        frontendRevalidator.request(revalidationScope.allCollections());
    }
}
