package com.matmuh.matmuhsite.business.concretes;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.matmuh.matmuhsite.core.exceptions.ResourceNotFoundException;
import com.matmuh.matmuhsite.dataAccess.abstracts.UserDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityManagerLogTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(SecurityManager.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @AfterEach
    void cleanUp() {
        logger.detachAppender(appender);
        SecurityContextHolder.clearContext();
    }

    @Test
    void unresolvedPrincipalIsLoggedMasked() {
        appender.start();
        logger.addAppender(appender);

        var email = "yusuf@std.yildiz.edu.tr";
        var userDao = mock(UserDao.class);
        when(userDao.findByEmail(email)).thenReturn(Optional.empty());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, AuthorityUtils.NO_AUTHORITIES));

        var manager = new SecurityManager(userDao, mock(JdbcTemplate.class));
        assertThrows(ResourceNotFoundException.class, manager::getAuthenticatedUserFromContext);

        var lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertTrue(lines.stream().anyMatch(l -> l.endsWith("y***@std.yildiz.edu.tr")), lines.toString());
        assertFalse(lines.stream().anyMatch(l -> l.contains(email)), lines.toString());
    }
}
