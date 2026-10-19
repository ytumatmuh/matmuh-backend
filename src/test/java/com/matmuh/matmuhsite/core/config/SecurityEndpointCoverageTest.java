package com.matmuh.matmuhsite.core.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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

import static org.assertj.core.api.Assertions.assertThat;

// SecurityConfig'in son kuralı denyAll: kuralı yazılmamış uç kimseye açılmaz. Bu test bir ucun
// unutulup sessizce kapanmasını yakalar — ADMIN her uca güvenlik zincirinden geçebilmeli.
@SpringBootTest
class SecurityEndpointCoverageTest {

    private static final Set<RequestMethod> ALL = Set.of(
            RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    // ADMIN'e bilerek kapalı uçlar ve gerekçeleri.
    private static final Map<String, String> NOT_FOR_ADMIN = Map.of(
            "POST /api/cms/sync", "yalnız SCHEMA_SYNC servis anahtarı; insan token'ı prod şemasını bayat manifest'le ezemesin (CLAUDE.md §5)");

    abstract static class Coverage {

        @Autowired
        WebInvocationPrivilegeEvaluator evaluator;

        @Autowired
        @Qualifier("requestMappingHandlerMapping")
        RequestMappingHandlerMapping handlerMapping;

        @Test
        void adminPassesTheSecurityChainOnEveryEndpoint() throws ClassNotFoundException {
            var admin = UsernamePasswordAuthenticationToken.authenticated(
                    "admin", null, AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
            var endpoints = endpoints();

            var denied = new TreeSet<String>();
            for (var endpoint : endpoints.keySet()) {
                var parts = endpoint.split(" ", 2);
                if (!evaluator.isAllowed("", parts[1], parts[0], admin)) {
                    denied.add(endpoint + " (" + endpoints.get(endpoint) + ")");
                }
            }

            assertThat(endpoints).hasSizeGreaterThan(100);
            assertThat(endpoints.keySet()).containsAll(NOT_FOR_ADMIN.keySet());
            assertThat(denied)
                    .as("endpoints the security chain denies to ADMIN; give them a rule in SecurityConfig "
                            + "or, if ADMIN must not reach them, list them in NOT_FOR_ADMIN with the reason")
                    .containsExactlyInAnyOrderElementsOf(NOT_FOR_ADMIN.keySet().stream()
                            .map(endpoint -> endpoint + " (" + endpoints.get(endpoint) + ")")
                            .toList());
        }

        private Map<String, String> endpoints() throws ClassNotFoundException {
            var endpoints = new TreeMap<String, String>();
            handlerMapping.getHandlerMethods().forEach((info, handler) -> {
                var methods = info.getMethodsCondition().getMethods().isEmpty() ? ALL : info.getMethodsCondition().getMethods();
                for (var method : methods) {
                    for (var pattern : info.getPatternValues()) {
                        endpoints.put(method + " " + sample(pattern), handler.getShortLogMessage());
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
                                endpoints.putIfAbsent(httpMethod + " " + sample("/" + prefix + "/" + path),
                                        type.getSimpleName() + "#" + method.getName());
                            }
                        }
                    }
                }
            }
            return endpoints;
        }

        private static String sample(String pattern) {
            var path = pattern.replaceAll("\\{[^}]+}", "sample").replace("**", "sample").replace("*", "sample");
            var collapsed = path.replaceAll("/+", "/");
            return collapsed.length() > 1 && collapsed.endsWith("/") ? collapsed.substring(0, collapsed.length() - 1) : collapsed;
        }
    }

    @Nested
    @TestPropertySource(properties = "features.student-portal.enabled=false")
    class OfficialSite extends Coverage {
    }

    @Nested
    @TestPropertySource(properties = "features.student-portal.enabled=true")
    class StudentPortal extends Coverage {
    }
}
