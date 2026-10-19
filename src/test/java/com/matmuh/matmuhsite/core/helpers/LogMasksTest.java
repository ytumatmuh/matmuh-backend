package com.matmuh.matmuhsite.core.helpers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogMasksTest {

    @Test
    void emailKeepsFirstLetterAndDomain() {
        assertEquals("y***@std.yildiz.edu.tr", LogMasks.email("yusuf@std.yildiz.edu.tr"));
        assertEquals("***", LogMasks.email("noatsign"));
        assertEquals("-", LogMasks.email(null));
    }

    @Test
    void principalMasksEmailsButKeepsServiceKeys() {
        assertEquals("e***@yildiz.edu.tr", LogMasks.principal("editor@yildiz.edu.tr"));
        assertEquals("service:3f2a", LogMasks.principal("service:3f2a"));
        assertEquals("-", LogMasks.principal(null));
    }

    @Test
    void queryNamesDropsValues() {
        assertEquals("?q&page", LogMasks.queryNames("q=ali%20veli&page=2"));
        assertEquals("?flag&size", LogMasks.queryNames("flag&size=10"));
    }

    @Test
    void queryNamesIsCompactAndDeterministic() {
        assertEquals("?type&q", LogMasks.queryNames("type=A&q=x&type=B"));
        assertEquals("?a", LogMasks.queryNames("&&a=1&=orphan&"));
    }

    @Test
    void queryNamesIsEmptyWithoutParameters() {
        assertEquals("", LogMasks.queryNames(null));
        assertEquals("", LogMasks.queryNames(""));
        assertEquals("", LogMasks.queryNames("=x&"));
    }
}
