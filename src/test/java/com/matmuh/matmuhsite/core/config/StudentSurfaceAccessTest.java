package com.matmuh.matmuhsite.core.config;

import com.matmuh.matmuhsite.core.security.JwtService;
import com.matmuh.matmuhsite.dataAccess.abstracts.UserDao;
import com.matmuh.matmuhsite.entities.Role;
import com.matmuh.matmuhsite.entities.User;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

// Aynı kod iki siteye çıkıyor (features.student-portal.enabled): resmi sitede öğrenci yüzeyi
// kapalı (ROLE_USER yalnız /users/me okur), tam özellikli sitede main'deki kurallar aynen durur.
// Gerçek filtre zinciri ve gerçek JWT ile.
@SpringBootTest
class StudentSurfaceAccessTest {

    private static final String ID = "00000000-0000-0000-0000-00000000beef";

    private record Endpoint(String name, Supplier<AbstractMockHttpServletRequestBuilder<?>> request, boolean editorReadable) {
    }

    private static final List<Endpoint> STUDENT_SURFACE = List.of(
            new Endpoint("POST /api/enrollments",
                    () -> post("/api/enrollments").contentType(MediaType.APPLICATION_JSON).content("{}"), false),
            new Endpoint("GET /api/enrollments/me", () -> get("/api/enrollments/me"), false),
            new Endpoint("DELETE /api/enrollments/{id}", () -> delete("/api/enrollments/" + ID), false),
            new Endpoint("GET /api/calendar/me",
                    () -> get("/api/calendar/me").param("from", "2026-10-01").param("to", "2026-10-31"), false),
            new Endpoint("POST /api/lectures/{id}/notes", () -> multipart("/api/lectures/" + ID + "/notes"), false),
            new Endpoint("GET /api/lectures/{id}/notes", () -> get("/api/lectures/" + ID + "/notes"), false),
            new Endpoint("GET /api/lecture-notes/me", () -> get("/api/lecture-notes/me"), false),
            new Endpoint("PATCH /api/lecture-notes/{id}",
                    () -> patch("/api/lecture-notes/" + ID).contentType(MediaType.APPLICATION_JSON).content("{}"), false),
            new Endpoint("DELETE /api/lecture-notes/{id}", () -> delete("/api/lecture-notes/" + ID), false),
            new Endpoint("GET /api/staff/{id}/notes", () -> get("/api/staff/" + ID + "/notes"), false),
            new Endpoint("GET /api/lectures/{id}/offerings", () -> get("/api/lectures/" + ID + "/offerings"), true),
            new Endpoint("GET /api/lectures/{id}/statistics", () -> get("/api/lectures/" + ID + "/statistics"), true),
            new Endpoint("GET /api/staff/{id}/offerings", () -> get("/api/staff/" + ID + "/offerings"), true),
            new Endpoint("GET /api/lecture-offerings/{id}", () -> get("/api/lecture-offerings/" + ID), true),
            new Endpoint("GET /api/uploads/files/**", () -> get("/api/uploads/files/" + ID + ".pdf"), true));

    abstract static class Matrix {

        @Autowired
        WebApplicationContext context;

        @Autowired
        @Qualifier("springSecurityFilterChain")
        Filter securityFilterChain;

        @Autowired
        UserDao userDao;

        @Autowired
        JwtService jwtService;

        @Autowired
        JdbcTemplate jdbc;

        MockMvc mvc;
        final List<UUID> users = new ArrayList<>();
        String student;
        String editor;
        String admin;

        @BeforeEach
        void signIn() {
            mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(securityFilterChain).build();
            student = tokenFor("student", Set.of(Role.ROLE_USER));
            editor = tokenFor("editor", Set.of(Role.ROLE_USER, Role.ROLE_EDITOR));
            admin = tokenFor("admin", Set.of(Role.ROLE_USER, Role.ROLE_ADMIN));
        }

        @AfterEach
        void removeUsers() {
            for (var id : users) {
                jdbc.update("DELETE FROM authorities WHERE user_id = ?", id);
                jdbc.update("DELETE FROM users WHERE id = ?", id);
            }
        }

        private String tokenFor(String name, Set<Role> roles) {
            var user = userDao.save(User.builder()
                    .email("access-" + name + "-" + UUID.randomUUID() + "@std.yildiz.edu.tr")
                    .firstName("Access")
                    .lastName(name)
                    .authorities(roles)
                    .build());
            users.add(user.getId());
            return jwtService.generateToken(user);
        }

        int statusOf(AbstractMockHttpServletRequestBuilder<?> request, String token) throws Exception {
            if (token != null) {
                request.header("Authorization", "Bearer " + token);
            }
            return mvc.perform(request).andReturn().getResponse().getStatus();
        }

        void assertPasses(Endpoint endpoint, String token, String who) throws Exception {
            var status = statusOf(endpoint.request().get(), token);
            assertTrue(status != 401 && status != 403, who + " was refused " + endpoint.name() + " with " + status);
        }

        void assertStatus(int expected, Endpoint endpoint, String token, String who) throws Exception {
            assertEquals(expected, statusOf(endpoint.request().get(), token), who + " on " + endpoint.name());
        }

        @Test
        void anonymousMustSignIn() throws Exception {
            for (var endpoint : STUDENT_SURFACE) {
                assertStatus(401, endpoint, null, "anonymous");
            }
        }

        @Test
        void everyoneSignedInReadsTheirOwnProfile() throws Exception {
            assertEquals(200, statusOf(get("/api/users/me"), student));
            assertEquals(401, statusOf(get("/api/users/me"), null));
        }
    }

    @Nested
    @TestPropertySource(properties = "features.student-portal.enabled=false")
    class OfficialSite extends Matrix {

        @Autowired
        MessageSource messages;

        @Test
        void studentIsRefusedTheWholeStudentSurface() throws Exception {
            for (var endpoint : STUDENT_SURFACE) {
                assertStatus(403, endpoint, student, "USER");
            }
        }

        @Test
        void editorReadsOfferingDataButNotStudentFeatures() throws Exception {
            for (var endpoint : STUDENT_SURFACE) {
                if (endpoint.editorReadable()) {
                    assertPasses(endpoint, editor, "EDITOR");
                } else {
                    assertStatus(403, endpoint, editor, "EDITOR");
                }
            }
        }

        @Test
        void adminCanStillExerciseEveryStudentEndpoint() throws Exception {
            for (var endpoint : STUDENT_SURFACE) {
                assertPasses(endpoint, admin, "ADMIN");
            }
        }

        @Test
        void privateCmsAttachmentIsRejectedWithAReason() throws Exception {
            for (var locale : List.of(Locale.forLanguageTag("tr"), Locale.ENGLISH)) {
                var response = mvc.perform(multipart("/api/cms/media")
                                .file(new MockMultipartFile("file", "dilekce.pdf", "application/pdf", new byte[]{1, 2, 3}))
                                .param("publicAccess", "false")
                                .header("Accept-Language", locale.toLanguageTag())
                                .header("Authorization", "Bearer " + admin))
                        .andReturn().getResponse();

                assertEquals(400, response.getStatus());
                assertTrue(response.getContentType().startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE));
                var detail = new ObjectMapper().readTree(response.getContentAsString()).get("detail").asText();
                assertEquals(messages.getMessage("file.private.attachment.not.supported", null, locale), detail);
            }
        }
    }

    @Nested
    @TestPropertySource(properties = "features.student-portal.enabled=true")
    class StudentPortal extends Matrix {

        @Test
        void studentUsesEveryFeatureAsOnMain() throws Exception {
            for (var endpoint : STUDENT_SURFACE) {
                assertPasses(endpoint, student, "USER");
            }
        }

        @Test
        void editorAndAdminKeepTheirAccess() throws Exception {
            for (var endpoint : STUDENT_SURFACE) {
                assertPasses(endpoint, editor, "EDITOR");
                assertPasses(endpoint, admin, "ADMIN");
            }
        }
    }
}
