package com.matmuh.matmuhsite.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CorrelationIdFilterTest {

    private static final String HEADER = "X-Correlation-Id";

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    private String run(String incoming, AtomicReference<String> seenInMdc) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/x");
        if (incoming != null) {
            request.addHeader(HEADER, incoming);
        }
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> seenInMdc.set(MDC.get("correlationId")));
        return response.getHeader(HEADER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc-123", "req_1.2", "0b3c9d1e-7f00-4a3b-9c2d-1e2f3a4b5c6d"})
    void acceptsSafeIncomingId(String incoming) throws Exception {
        var mdc = new AtomicReference<String>();
        assertEquals(incoming, run(incoming, mdc));
        assertEquals(incoming, mdc.get());
        assertNull(MDC.get("correlationId"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc\r\nFAKE LOG LINE", "has space", "a;b", "<script>"})
    void replacesUnsafeIncomingIdWithUuid(String incoming) throws Exception {
        var mdc = new AtomicReference<String>();
        var echoed = run(incoming, mdc);
        assertNotEquals(incoming, echoed);
        assertDoesNotThrow(() -> UUID.fromString(echoed));
        assertEquals(echoed, mdc.get());
    }

    @Test
    void rejectsOverlongId() throws Exception {
        var incoming = "a".repeat(65);
        var echoed = run(incoming, new AtomicReference<>());
        assertDoesNotThrow(() -> UUID.fromString(echoed));
        assertEquals("a".repeat(64), run("a".repeat(64), new AtomicReference<>()));
    }

    @Test
    void generatesIdWhenHeaderMissing() throws Exception {
        var echoed = run(null, new AtomicReference<>());
        assertDoesNotThrow(() -> UUID.fromString(echoed));
    }
}
