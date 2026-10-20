package com.matmuh.matmuhsite.core.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.access.WebInvocationPrivilegeEvaluator;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// SecurityConfig'in son kuralı denyAll. İki yönden korur: ADMIN her uca güvenlik zincirinden
// geçebilmeli (unutulan uç sessizce kapanmasın) ve anonimin / düz ROLE_USER'ın ulaşabildiği uçlar
// aşağıdaki listelerle birebir aynı olmalı (geniş bir permitAll'a düşen yeni uç sessizce açılmasın).
@SpringBootTest
class SecurityEndpointCoverageTest {

    private static final Set<RequestMethod> ALL = Set.of(
            RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    private static final Map<String, String> NOT_FOR_ADMIN = Map.of(
            "POST /api/cms/sync", "yalnız SCHEMA_SYNC servis anahtarı; insan token'ı prod şemasını bayat manifest'le ezemesin (CLAUDE.md §5)");

    private static final Set<String> ERROR_PAGE = Stream.of("GET", "POST", "PUT", "PATCH", "DELETE")
            .map(method -> method + " /error")
            .collect(Collectors.toSet());

    // Anonimin ulaşabildiği uçlar; iki modda aynı.
    private static final Set<String> PUBLIC = union(ERROR_PAGE, Set.of(
            "GET /api/academic-years",
            "GET /api/auth/login",
            "POST /api/auth/login",
            "POST /api/auth/logout",
            "POST /api/auth/refresh",
            "GET /api/calendar",
            "GET /api/calendar/weekly",
            "GET /api/cms/collections/{key}",
            "GET /api/cms/collections/{key}/lookup",
            "GET /api/cms/collections/{key}/schema",
            "GET /api/cms/collections/{key}/{slug}",
            "GET /api/cms/content",
            "GET /api/cms/content/all",
            "GET /api/cms/data",
            "GET /api/cms/public/{clientKey}/content",
            "GET /api/cms/public/{clientKey}/content/all",
            "GET /api/cms/public/{clientKey}/data",
            "GET /api/elective-groups",
            "GET /api/elective-groups/by-code/{code}",
            "GET /api/elective-groups/{id}",
            "GET /api/elective-groups/{id}/options",
            "GET /api/lectures",
            "GET /api/lectures/by-code/{code}",
            "GET /api/lectures/{id}",
            "GET /api/lectures/{id}/elective-groups",
            "GET /api/search",
            "GET /api/staff",
            "GET /api/staff/{id}",
            "GET /api/swagger-ui.html",
            "GET /api/uploads/images/**",
            "GET /api/uploads/public/**",
            "GET /api/v3/api-docs",
            "GET /api/v3/api-docs/swagger-config",
            "GET /cdn/**"));

    // Resmi sitede ROLE_USER'ın anonime ek olarak yapabildiği tek şey kendi profilini okumak.
    private static final Set<String> OFFICIAL_USER = union(PUBLIC, Set.of(
            "GET /api/users/me"));

    // Tam özellikli sitede öğrenci yüzeyi main'deki gibi açık.
    private static final Set<String> PORTAL_USER = union(OFFICIAL_USER, Set.of(
            "DELETE /api/enrollments/{lectureOfferingId}",
            "DELETE /api/lecture-notes/{id}",
            "GET /api/calendar/me",
            "GET /api/enrollments/me",
            "GET /api/lecture-notes/me",
            "GET /api/lecture-offerings/{id}",
            "GET /api/lectures/{id}/notes",
            "GET /api/lectures/{id}/offerings",
            "GET /api/lectures/{id}/statistics",
            "GET /api/staff/{id}/notes",
            "GET /api/staff/{id}/offerings",
            "GET /api/uploads/files/**",
            "PATCH /api/lecture-notes/{id}",
            "POST /api/enrollments",
            "POST /api/lectures/{id}/notes"));

    private static Set<String> union(Set<String> first, Set<String> second) {
        var union = new TreeSet<>(first);
        union.addAll(second);
        return union;
    }

    abstract static class Coverage {

        @Autowired
        WebInvocationPrivilegeEvaluator evaluator;

        @Autowired
        @Qualifier("requestMappingHandlerMapping")
        RequestMappingHandlerMapping handlerMapping;

        abstract Set<String> userAllowlist();

        @Test
        void adminPassesTheSecurityChainOnEveryEndpoint() throws ClassNotFoundException {
            var endpoints = endpoints();
            var denied = new TreeSet<>(endpoints.keySet());
            denied.removeAll(allowedFor(endpoints, principal("ROLE_ADMIN")));

            assertThat(endpoints).hasSizeGreaterThan(100);
            assertThat(endpoints.keySet()).containsAll(NOT_FOR_ADMIN.keySet());
            assertThat(denied)
                    .as("endpoints the security chain denies to ADMIN; give them a rule in SecurityConfig "
                            + "or, if ADMIN must not reach them, list them in NOT_FOR_ADMIN with the reason")
                    .containsExactlyInAnyOrderElementsOf(NOT_FOR_ADMIN.keySet());
        }

        @Test
        void anonymousReachesExactlyThePublicEndpoints() throws ClassNotFoundException {
            var anonymous = new AnonymousAuthenticationToken(
                    "coverage", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
            assertMatchesAllowlist("anonymous", allowedFor(endpoints(), anonymous), PUBLIC);
        }

        @Test
        void plainUserReachesExactlyTheirAllowlist() throws ClassNotFoundException {
            assertMatchesAllowlist("ROLE_USER", allowedFor(endpoints(), principal("ROLE_USER")), userAllowlist());
        }

        private void assertMatchesAllowlist(String who, Set<String> actual, Set<String> allowlist) {
            var unexpected = new TreeSet<>(actual);
            unexpected.removeAll(allowlist);
            var missing = new TreeSet<>(allowlist);
            missing.removeAll(actual);
            assertThat(unexpected)
                    .as(who + " reaches endpoints that are not on its allowlist; if that is intended, add them "
                            + "to the allowlist in this test, otherwise tighten SecurityConfig. Unexpected: "
                            + unexpected + " Missing: " + missing)
                    .isEmpty();
            assertThat(missing)
                    .as(who + " no longer reaches allowlisted endpoints (or they were removed); update the allowlist. "
                            + "Missing: " + missing)
                    .isEmpty();
        }

        private Set<String> allowedFor(Map<String, String> endpoints, Authentication authentication) {
            var allowed = new TreeSet<String>();
            for (var endpoint : endpoints.keySet()) {
                var parts = endpoint.split(" ", 2);
                if (evaluator.isAllowed("", sample(parts[1]), parts[0], authentication)) {
                    allowed.add(endpoint);
                }
            }
            return allowed;
        }

        private static Authentication principal(String role) {
            return UsernamePasswordAuthenticationToken.authenticated(
                    "coverage", null, AuthorityUtils.createAuthorityList(role));
        }

        private Map<String, String> endpoints() throws ClassNotFoundException {
            var endpoints = new TreeMap<String, String>();
            handlerMapping.getHandlerMethods().forEach((info, handler) -> {
                var methods = info.getMethodsCondition().getMethods().isEmpty() ? ALL : info.getMethodsCondition().getMethods();
                for (var method : methods) {
                    for (var pattern : info.getPatternValues()) {
                        endpoints.put(method + " " + normalize(pattern), handler.getShortLogMessage());
                    }
                }
            });

            // @ConditionalOnProperty ile yalnız bir ortamda kaydolan controller'lar (S3 indirme ucu)
            // test bağlamında handler mapping'de yok; kaynaktan okunur.
            var scanner = new ClassPathScanningCandidateComponentProvider(false) {
                @Override
                protected boolean isCandidateComponent(MetadataReader reader) {
                    return reader.getAnnotationMetadata().hasAnnotation(RestController.class.getName());
                }
            };
            for (var candidate : scanner.findCandidateComponents("com.matmuh.matmuhsite.webAPI.controllers")) {
                var type = Class.forName(candidate.getBeanClassName());
                var base = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
                var prefixes = base == null || base.path().length == 0 ? new String[]{""} : base.path();
                for (var method : type.getDeclaredMethods()) {
                    var mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                    if (mapping == null) {
                        continue;
                    }
                    var methods = mapping.method().length == 0 ? ALL : Set.of(mapping.method());
                    var paths = mapping.path().length == 0 ? new String[]{""} : mapping.path();
                    for (var httpMethod : methods) {
                        for (var prefix : prefixes) {
                            for (var path : paths) {
                                endpoints.putIfAbsent(httpMethod + " " + normalize(prefix + "/" + path),
                                        type.getSimpleName() + "#" + method.getName());
                            }
                        }
                    }
                }
            }
            return endpoints;
        }

        private static String normalize(String pattern) {
            var collapsed = ("/" + pattern).replaceAll("/+", "/");
            return collapsed.length() > 1 && collapsed.endsWith("/") ? collapsed.substring(0, collapsed.length() - 1) : collapsed;
        }

        private static String sample(String pattern) {
            return pattern.replaceAll("\\{[^}]+}", "sample").replace("**", "sample").replace("*", "sample");
        }
    }

    @Nested
    @TestPropertySource(properties = "features.student-portal.enabled=false")
    class OfficialSite extends Coverage {

        @Override
        Set<String> userAllowlist() {
            return OFFICIAL_USER;
        }
    }

    @Nested
    @TestPropertySource(properties = "features.student-portal.enabled=true")
    class StudentPortal extends Coverage {

        @Override
        Set<String> userAllowlist() {
            return PORTAL_USER;
        }
    }
}
