package com.matmuh.matmuhsite.core.utilities.revalidation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Reports a successful write to the frontend. After completion because the
 * service transactions behind the handler have committed by then, and an error
 * the exception handlers turned into a response leaves a status outside 2xx.
 */
@Component
public class RevalidationInterceptor implements HandlerInterceptor {

    private final RevalidationScope scope;

    private final FrontendRevalidator revalidator;

    public RevalidationInterceptor(RevalidationScope scope, FrontendRevalidator revalidator) {
        this.scope = scope;
        this.revalidator = revalidator;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
        if (exception != null || response.getStatus() < 200 || response.getStatus() >= 300) {
            return;
        }
        var path = request.getRequestURI().substring(request.getContextPath().length());
        var decision = scope.decide(request.getMethod(), path);
        if (decision.kind() == RevalidationScope.Kind.REVALIDATE) {
            revalidator.request(decision.collections());
        }
    }
}
