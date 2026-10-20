package com.matmuh.matmuhsite.core.utilities.schema;

import com.matmuh.matmuhsite.core.helpers.LogMasks;
import com.matmuh.matmuhsite.core.properties.BootstrapAdminProperties;
import com.matmuh.matmuhsite.entities.AuthProvider;
import com.matmuh.matmuhsite.entities.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

@Component
@Order(8)
@Transactional
public class BootstrapAdminSeed implements ApplicationRunner {

    public enum Outcome { DISABLED, SKIPPED, CREATED, PROMOTED }

    public static final int MIN_PASSWORD_LENGTH = 12;

    // BCrypt yalnız ilk 72 baytı okur; Spring Security daha uzununu reddediyor.
    private static final int MAX_PASSWORD_BYTES = 72;

    private static final String SYSTEM_USER_EMAIL = "system@matmuh.local";

    private static final Logger logger = LoggerFactory.getLogger(BootstrapAdminSeed.class);

    private static final String TABLES_EXIST = "SELECT to_regclass('users') IS NOT NULL AND to_regclass('authorities') IS NOT NULL";

    private static final String ACTIVE_ADMIN_EXISTS = """
            SELECT EXISTS (
                SELECT 1 FROM users u JOIN authorities a ON a.user_id = u.id
                WHERE a.role = 'ROLE_ADMIN' AND NOT u.is_deleted
            )
            """;

    private static final String INSERT_USER = """
            INSERT INTO users (id, is_deleted, first_name, last_name, email, password, is_email_verified, provider)
            VALUES (?, false, ?, ?, ?, ?, false, ?)
            """;

    private static final String GRANT_ROLE = """
            INSERT INTO authorities (user_id, role)
            SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM authorities WHERE user_id = ? AND role = ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final BootstrapAdminProperties properties;

    public BootstrapAdminSeed(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder, BootstrapAdminProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    public Outcome seed() {
        var email = blankToNull(properties.getEmail());
        var password = properties.getPassword() == null || properties.getPassword().isEmpty() ? null : properties.getPassword();
        if (email == null && password == null) {
            logger.debug("Bootstrap admin not configured");
            return Outcome.DISABLED;
        }
        email = validate(email, password);

        if (!Boolean.TRUE.equals(jdbcTemplate.queryForObject(TABLES_EXIST, Boolean.class))) {
            throw new IllegalStateException("Bootstrap admin: users/authorities tables are missing");
        }

        if (Boolean.TRUE.equals(jdbcTemplate.queryForObject(ACTIVE_ADMIN_EXISTS, Boolean.class))) {
            logger.info("Bootstrap admin skipped: an active admin already exists (configured {}); BOOTSTRAP_ADMIN_* can be removed",
                    LogMasks.email(email));
            return Outcome.SKIPPED;
        }

        var existing = jdbcTemplate.queryForList(
                "SELECT id, email, password FROM users WHERE lower(email) = ? AND NOT is_deleted", email);
        if (existing.size() > 1) {
            throw new IllegalStateException("Bootstrap admin: " + existing.size()
                    + " active users share the e-mail " + LogMasks.email(email) + " (case-insensitive); resolve the duplicates first");
        }

        if (existing.isEmpty()) {
            var id = UUID.randomUUID();
            jdbcTemplate.update(INSERT_USER, id,
                    nameOr(properties.getFirstName(), "Site"), nameOr(properties.getLastName(), "Yöneticisi"),
                    email, passwordEncoder.encode(password), AuthProvider.LOCAL.name());
            grant(id);
            logger.info("Bootstrap admin created: {} ({})", id, LogMasks.email(email));
            return Outcome.CREATED;
        }

        var row = existing.get(0);
        var id = (UUID) row.get("id");
        var storedPassword = (String) row.get("password");
        grant(id);
        // Var olan parola hesabın sahibinindir; env'deki parola onu asla ezmez.
        if (storedPassword == null || storedPassword.isBlank()) {
            jdbcTemplate.update("UPDATE users SET password = ? WHERE id = ?", passwordEncoder.encode(password), id);
            logger.info("Bootstrap admin promoted existing user {} ({}) and set its password", id, LogMasks.email(email));
        } else {
            logger.warn("Bootstrap admin promoted existing user {} ({}); it already had a password, BOOTSTRAP_ADMIN_PASSWORD was not applied",
                    id, LogMasks.email(email));
        }
        return Outcome.PROMOTED;
    }

    private void grant(UUID userId) {
        for (var role : new Role[]{Role.ROLE_USER, Role.ROLE_ADMIN}) {
            jdbcTemplate.update(GRANT_ROLE, userId, role.name(), userId, role.name());
        }
    }

    private static String validate(String email, String password) {
        if (email == null || password == null) {
            throw new IllegalStateException("Bootstrap admin: BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD must be set together");
        }
        var normalized = email.toLowerCase(Locale.ROOT);
        var at = normalized.indexOf('@');
        if (at <= 0 || at != normalized.lastIndexOf('@') || at == normalized.length() - 1 || normalized.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalStateException("Bootstrap admin: BOOTSTRAP_ADMIN_EMAIL is not a valid e-mail address");
        }
        if (SYSTEM_USER_EMAIL.equals(normalized)) {
            throw new IllegalStateException("Bootstrap admin: the system user cannot be made an admin");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException("Bootstrap admin: BOOTSTRAP_ADMIN_PASSWORD must be at least "
                    + MIN_PASSWORD_LENGTH + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new IllegalStateException("Bootstrap admin: BOOTSTRAP_ADMIN_PASSWORD must be at most "
                    + MAX_PASSWORD_BYTES + " bytes (BCrypt limit)");
        }
        return normalized;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nameOr(String value, String fallback) {
        var trimmed = blankToNull(value);
        return trimmed == null ? fallback : trimmed;
    }
}
