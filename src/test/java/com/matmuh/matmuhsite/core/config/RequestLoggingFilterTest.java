package com.matmuh.matmuhsite.core.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestLoggingFilterTest {

    private final Logger accessLogger = (Logger) LoggerFactory.getLogger("http.access");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void attach() {
        previousLevel = accessLogger.getLevel();
        accessLogger.setLevel(Level.INFO);
        appender.start();
        accessLogger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        accessLogger.detachAppender(appender);
        accessLogger.setLevel(previousLevel);
    }

    private String logLineFor(String query) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/search");
        request.setQueryString(query);
        new RequestLoggingFilter().doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        assertEquals(1, appender.list.size());
        return appender.list.get(0).getFormattedMessage();
    }

    @Test
    void logsQueryParameterNamesWithoutValues() throws Exception {
        var line = logLineFor("q=ali.veli%40std.yildiz.edu.tr&page=2");
        assertTrue(line.startsWith("GET /api/search?q&page -> 200"), line);
        assertFalse(line.contains("ali"), line);
        assertFalse(line.contains("=2"), line);
    }

    @Test
    void logsBarePathWithoutQuery() throws Exception {
        var line = logLineFor(null);
        assertTrue(line.startsWith("GET /api/search -> 200"), line);
    }
}
