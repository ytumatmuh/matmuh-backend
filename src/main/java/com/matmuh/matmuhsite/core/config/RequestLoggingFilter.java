package com.matmuh.matmuhsite.core.config;

import com.matmuh.matmuhsite.core.helpers.LogMasks;
import com.matmuh.matmuhsite.entities.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;


@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String USER_ID_ATTRIBUTE = "app.userId";

    private static final Logger logger = LoggerFactory.getLogger("http.access");

    private static final Set<String> IGNORED_PREFIXES = Set.of(
            "/api/swagger-ui", "/api/v3/api-docs", "/actuator");

    private static final int SERVER_ERROR = 500;
    private static final int CLIENT_ERROR = 400;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        var startedAt = System.nanoTime();

        try {
            filterChain.doFilter(request, response);
        } finally {
            var durationNanos = System.nanoTime() - startedAt;
            log(request, response, durationNanos);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var path = request.getRequestURI();
        return IGNORED_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private void log(HttpServletRequest request, HttpServletResponse response, long durationNanos) {
        var status = response.getStatus();
        var userId = currentUserId(request);
        var durationMillis = durationNanos / 1_000_000;

        MDC.put("http.request.method", request.getMethod());
        MDC.put("url.path", request.getRequestURI());
        MDC.put("http.response.status_code", String.valueOf(status));
        MDC.put("event.duration", String.valueOf(durationNanos));
        MDC.put("client.ip", clientIp(request));
        if (userId != null) {
            MDC.put("user.id", userId);
        }

        try {
            var message = "{} {}{} -> {} ({} ms) user={}";
            var arguments = new Object[]{
                    request.getMethod(),
                    request.getRequestURI(),
                    LogMasks.queryNames(request.getQueryString()),
                    status,
                    durationMillis,
                    userId == null ? "anonymous" : userId
            };

            if (status >= SERVER_ERROR) {
                logger.error(message, arguments);
            } else if (status >= CLIENT_ERROR) {
                logger.warn(message, arguments);
            } else {
                logger.info(message, arguments);
            }
        } finally {
            MDC.remove("http.request.method");
            MDC.remove("url.path");
            MDC.remove("http.response.status_code");
            MDC.remove("event.duration");
            MDC.remove("client.ip");
            MDC.remove("user.id");
        }
    }


    private String currentUserId(HttpServletRequest request) {
        var fromChain = request.getAttribute(USER_ID_ATTRIBUTE);
        if (fromChain instanceof String userId) {
            return userId;
        }

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof User user && user.getId() != null) {
            return user.getId().toString();
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        var forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            var comma = forwarded.indexOf(',');
            return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
        }
        return request.getRemoteAddr();
    }
}
