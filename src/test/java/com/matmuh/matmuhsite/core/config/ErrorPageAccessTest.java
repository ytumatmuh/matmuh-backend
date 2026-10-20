package com.matmuh.matmuhsite.core.config;

import com.matmuh.matmuhsite.core.security.JwtService;
import com.matmuh.matmuhsite.dataAccess.abstracts.UserDao;
import com.matmuh.matmuhsite.entities.Role;
import com.matmuh.matmuhsite.entities.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Hata sayfası ERROR dispatch'iyle gelir ve JwtAuthFilter orada koşmaz; /error kapalıyken her
// konteyner hatası (firewall reddi dahil, ADMIN için bile) 401'e dönüyordu. MockMvc ERROR dispatch
// yapmadığı için gerçek sunucu gerekiyor.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorPageAccessTest {

    @LocalServerPort
    int port;

    @Autowired
    UserDao userDao;

    @Autowired
    JwtService jwtService;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();
    private UUID adminId;
    private String admin;

    @BeforeEach
    void signIn() {
        var user = userDao.save(User.builder()
                .email("error-page-" + UUID.randomUUID() + "@std.yildiz.edu.tr")
                .firstName("Error")
                .lastName("Page")
                .authorities(Set.of(Role.ROLE_USER, Role.ROLE_ADMIN))
                .build());
        adminId = user.getId();
        admin = jwtService.generateToken(user);
    }

    @AfterEach
    void removeUser() {
        jdbc.update("DELETE FROM authorities WHERE user_id = ?", adminId);
        jdbc.update("DELETE FROM users WHERE id = ?", adminId);
    }

    private int statusOf(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void firewallRejectionKeepsItsRealStatus() throws Exception {
        assertEquals(400, statusOf("/api/lectures/;x=1", admin));
        assertEquals(400, statusOf("/api/lectures//x", admin));
        assertEquals(400, statusOf("/api/lectures/;x=1", null));
    }

    // ADMIN'in geçtiğini SecurityEndpointCoverageTest söylüyor; burada belgeyi ürettirmiyoruz,
    // springdoc'un ilk üretimi bu bağlamda dakikalar sürüyor.
    @Test
    void yamlApiDocsNeedSignIn() throws Exception {
        assertEquals(401, statusOf("/api/v3/api-docs.yaml", null));
    }
}
