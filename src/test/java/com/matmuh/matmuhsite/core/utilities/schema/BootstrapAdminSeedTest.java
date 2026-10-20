package com.matmuh.matmuhsite.core.utilities.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matmuh.matmuhsite.core.properties.BootstrapAdminProperties;
import com.matmuh.matmuhsite.core.utilities.schema.BootstrapAdminSeed.Outcome;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

// İkinci dağıtımın veritabanı boş ve sunucuya SSH yok: ilk ADMIN yalnız env ile açılabiliyor.
// Her test kendi işleminde koşar ve geri alınır; paylaşılan test veritabanındaki adminler
// işlemin içinde soft-delete edilerek "boş veritabanı" kurulur.
@SpringBootTest
@Transactional
class BootstrapAdminSeedTest {

    private static final String PASSWORD = "dogru-at-pil-zimba";

    @Autowired
    private BootstrapAdminSeed configuredSeed;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter securityFilterChain;

    @BeforeEach
    void noActiveAdmin() {
        jdbc.update("UPDATE users SET is_deleted = true WHERE id IN (SELECT user_id FROM authorities WHERE role = 'ROLE_ADMIN')");
    }

    private BootstrapAdminSeed seedWith(String email, String password) {
        var properties = new BootstrapAdminProperties();
        properties.setEmail(email);
        properties.setPassword(password);
        return new BootstrapAdminSeed(jdbc, passwordEncoder, properties);
    }

    private String uniqueEmail() {
        return "bootstrap-" + UUID.randomUUID() + "@example.com";
    }

    private List<String> rolesOf(String email) {
        return jdbc.queryForList("SELECT a.role FROM authorities a JOIN users u ON u.id = a.user_id "
                + "WHERE u.email = ? AND NOT u.is_deleted ORDER BY a.role", String.class, email);
    }

    private int liveUsers(String email) {
        return jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(email) = ? AND NOT is_deleted", Integer.class, email);
    }

    private UUID insertUser(String email, String password, String... roles) {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, is_deleted, first_name, last_name, email, password, is_email_verified, provider) "
                + "VALUES (?, false, 'Var', 'Olan', ?, ?, true, 'YTU_MAIL')", id, email, password);
        for (var role : roles) {
            jdbc.update("INSERT INTO authorities (user_id, role) VALUES (?, ?)", id, role);
        }
        return id;
    }

    @Test
    void createsALocalAdminOnAnEmptyDatabase() {
        var email = uniqueEmail();

        assertEquals(Outcome.CREATED, seedWith(email, PASSWORD).seed());

        assertEquals(List.of("ROLE_ADMIN", "ROLE_USER"), rolesOf(email));
        var row = jdbc.queryForMap("SELECT password, provider FROM users WHERE email = ?", email);
        assertEquals("LOCAL", row.get("provider"));
        assertTrue(passwordEncoder.matches(PASSWORD, (String) row.get("password")));
    }

    @Test
    void runsOnlyOnceBecauseTheNewAdminCounts() {
        var email = uniqueEmail();
        assertEquals(Outcome.CREATED, seedWith(email, PASSWORD).seed());

        assertEquals(Outcome.SKIPPED, seedWith(email, PASSWORD).seed());
        assertEquals(1, liveUsers(email));
    }

    @Test
    void doesNothingWhenAnActiveAdminExists() {
        insertUser(uniqueEmail(), null, "ROLE_USER", "ROLE_ADMIN");
        var email = uniqueEmail();

        assertEquals(Outcome.SKIPPED, seedWith(email, PASSWORD).seed());
        assertEquals(0, liveUsers(email));
    }

    @Test
    void aSoftDeletedAdminDoesNotCount() {
        var deleted = insertUser(uniqueEmail(), null, "ROLE_USER", "ROLE_ADMIN");
        jdbc.update("UPDATE users SET is_deleted = true WHERE id = ?", deleted);

        assertEquals(Outcome.CREATED, seedWith(uniqueEmail(), PASSWORD).seed());
    }

    // Microsoft ile önceden açılmış hesap: parolası yok, e-posta yazımı Microsoft'unki.
    @Test
    void promotesAnExistingPasswordlessUserAndSetsThePassword() {
        var stored = "Bootstrap-" + UUID.randomUUID() + "@Example.com";
        var id = insertUser(stored, null, "ROLE_USER");

        assertEquals(Outcome.PROMOTED, seedWith(stored.toUpperCase(Locale.ROOT), PASSWORD).seed());

        assertEquals(List.of("ROLE_ADMIN", "ROLE_USER"), rolesOf(stored));
        var row = jdbc.queryForMap("SELECT email, password, provider FROM users WHERE id = ?", id);
        assertEquals(stored, row.get("email"));
        assertEquals("YTU_MAIL", row.get("provider"));
        assertTrue(passwordEncoder.matches(PASSWORD, (String) row.get("password")));
        assertEquals(1, liveUsers(stored.toLowerCase(Locale.ROOT)));
    }

    @Test
    void neverOverwritesAnExistingPassword() {
        var email = uniqueEmail();
        var original = passwordEncoder.encode("sahibinin-kendi-parolasi");
        var id = insertUser(email, original, "ROLE_USER", "ROLE_EDITOR");

        assertEquals(Outcome.PROMOTED, seedWith(email, PASSWORD).seed());

        assertEquals(List.of("ROLE_ADMIN", "ROLE_EDITOR", "ROLE_USER"), rolesOf(email));
        assertEquals(original, jdbc.queryForObject("SELECT password FROM users WHERE id = ?", String.class, id));
    }

    @Test
    void aShortPasswordStopsTheStartup() {
        var email = uniqueEmail();

        var error = assertThrows(IllegalStateException.class, () -> seedWith(email, "kisa-parola").seed());

        assertTrue(error.getMessage().contains("at least 12"), error.getMessage());
        assertEquals(0, liveUsers(email));
    }

    @Test
    void aShortPasswordFailsEvenWhenAnAdminExists() {
        insertUser(uniqueEmail(), null, "ROLE_USER", "ROLE_ADMIN");

        assertThrows(IllegalStateException.class, () -> seedWith(uniqueEmail(), "kisa").seed());
    }

    @Test
    void halfAConfigurationStopsTheStartup() {
        assertThrows(IllegalStateException.class, () -> seedWith(uniqueEmail(), null).seed());
        assertThrows(IllegalStateException.class, () -> seedWith("  ", PASSWORD).seed());
        assertThrows(IllegalStateException.class, () -> seedWith("not-an-email", PASSWORD).seed());
    }

    @Test
    void theSystemUserIsNeverPromoted() {
        assertThrows(IllegalStateException.class, () -> seedWith("System@Matmuh.Local", PASSWORD).seed());
    }

    @Test
    void emptyConfigurationDoesNothing() {
        var before = jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);

        assertEquals(Outcome.DISABLED, seedWith(null, null).seed());
        assertEquals(Outcome.DISABLED, seedWith("", "").seed());
        assertEquals(Outcome.DISABLED, configuredSeed.seed());
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM users", Integer.class));
    }

    // "ADMIN".toLowerCase() tr'de "admın" verir; giriş e-postayı birebir aradığı için hesap açılmaz olurdu.
    @Test
    void emailIsLowercasedIndependentOfTheDefaultLocale() {
        var local = "BOOTSTRAP-ADMIN-" + UUID.randomUUID();
        var previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            assertEquals(Outcome.CREATED, seedWith(local + "@EXAMPLE.COM", PASSWORD).seed());
        } finally {
            Locale.setDefault(previous);
        }

        assertEquals(1, liveUsers(local.toLowerCase(Locale.ROOT) + "@example.com"));
    }

    @Test
    void theBootstrappedAdminSignsInWithEmailAndPassword() throws Exception {
        var email = uniqueEmail();
        assertEquals(Outcome.CREATED, seedWith(email, PASSWORD).seed());
        var mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(securityFilterChain).build();
        var json = new ObjectMapper();

        var wrong = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginBody(email, PASSWORD + "x"))))
                .andReturn().getResponse();
        assertFalse(wrong.getStatus() >= 200 && wrong.getStatus() < 300, "wrong password was accepted");

        var login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginBody(email, PASSWORD))))
                .andReturn().getResponse();
        assertEquals(200, login.getStatus(), login.getContentAsString());
        var body = json.readTree(login.getContentAsString());
        var accessToken = body.path("accessToken").asText();
        assertFalse(accessToken.isBlank());
        assertFalse(body.path("refreshToken").asText().isBlank());
        assertTrue(body.path("expiresInSeconds").asLong() > 0);
        assertTrue(login.getHeaders("Set-Cookie").isEmpty(), "login is not expected to set cookies");

        var me = mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken)).andReturn().getResponse();
        assertEquals(200, me.getStatus(), me.getContentAsString());
        var roles = json.readTree(me.getContentAsString()).path("data").path("authorities").toString();
        assertTrue(roles.contains("ROLE_ADMIN"), roles);

        var adminOnly = mvc.perform(get("/api/service-keys").header("Authorization", "Bearer " + accessToken))
                .andReturn().getResponse();
        assertEquals(200, adminOnly.getStatus(), adminOnly.getContentAsString());
        assertNotNull(json.readTree(adminOnly.getContentAsString()).path("data"));
    }

    private record LoginBody(String email, String password) {
    }
}
