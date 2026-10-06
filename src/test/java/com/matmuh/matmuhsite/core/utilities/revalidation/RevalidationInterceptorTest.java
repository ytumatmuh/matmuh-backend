package com.matmuh.matmuhsite.core.utilities.revalidation;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RevalidationInterceptorTest {

    private final FrontendRevalidator revalidator = mock(FrontendRevalidator.class);

    private final RevalidationInterceptor interceptor =
            new RevalidationInterceptor(RevalidationScopeTest.scope(), revalidator);

    @Test
    void reportsASuccessfulWrite() {
        complete("PUT", "/api/cms/collections/news/bahar-senligi", 200, null);

        verify(revalidator).request(Set.of("news"));
    }

    @Test
    void staysQuietWhenTheWriteFailed() {
        complete("PUT", "/api/cms/collections/news/bahar-senligi", 409, null);
        complete("PATCH", "/api/staff/7f0c2a3e", 200, new IllegalStateException("boom"));

        verify(revalidator, never()).request(any());
    }

    @Test
    void staysQuietForAReadOrAnIgnoredWrite() {
        complete("GET", "/api/staff", 200, null);
        complete("PUT", "/api/cms/collections/news/bahar-senligi/draft", 200, null);

        verify(revalidator, never()).request(any());
    }

    private void complete(String method, String path, int status, Exception exception) {
        var request = new MockHttpServletRequest(method, path);
        var response = new MockHttpServletResponse();
        response.setStatus(status);
        interceptor.afterCompletion(request, response, new Object(), exception);
    }
}
