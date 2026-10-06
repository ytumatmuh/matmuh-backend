package com.matmuh.matmuhsite.core.utilities.revalidation;

import com.matmuh.matmuhsite.core.utilities.revalidation.RevalidationScope.Kind;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every write endpoint has to be either revalidated or deliberately ignored, so
 * a new endpoint cannot leave the frontend's cache stale by being forgotten.
 */
class RevalidationEndpointCoverageTest {

    private static final Set<RequestMethod> WRITES = Set.of(
            RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    @Test
    void everyWriteEndpointIsRevalidatedOrIgnored() throws ClassNotFoundException {
        var scope = RevalidationScopeTest.scope();
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        var unmapped = new ArrayList<String>();
        var checked = 0;
        for (var candidate : scanner.findCandidateComponents("com.matmuh.matmuhsite.webAPI.controllers")) {
            var type = Class.forName(candidate.getBeanClassName());
            var base = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            var prefixes = base == null || base.path().length == 0 ? new String[] {""} : base.path();
            for (var method : type.getDeclaredMethods()) {
                var mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                var methods = mapping.method().length == 0 ? WRITES : Set.of(mapping.method());
                var paths = mapping.path().length == 0 ? new String[] {""} : mapping.path();
                for (var httpMethod : methods) {
                    if (!WRITES.contains(httpMethod)) {
                        continue;
                    }
                    for (var prefix : prefixes) {
                        for (var path : paths) {
                            var template = normalize(prefix + "/" + path);
                            for (var sample : samples(template)) {
                                checked++;
                                if (scope.decide(httpMethod.name(), sample).kind() == Kind.UNMAPPED) {
                                    unmapped.add(httpMethod + " " + template + " (" + type.getSimpleName() + "." + method.getName() + ")");
                                }
                            }
                        }
                    }
                }
            }
        }

        assertThat(checked).isGreaterThan(40);
        assertThat(unmapped)
                .as("write endpoints with no revalidation decision; add them to RevalidationScope as revalidated or ignored")
                .isEmpty();
    }

    // {key} is tried as a generic and as a provider-backed collection, every
    // other variable as a placeholder.
    private static List<String> samples(String template) {
        var filled = template.replaceAll("\\{(?!key})[^/}]+}", "sample");
        return filled.contains("{key}")
                ? List.of(filled.replace("{key}", "news"), filled.replace("{key}", "lectures"))
                : List.of(filled);
    }

    private static String normalize(String path) {
        var collapsed = ("/" + path).replaceAll("/+", "/");
        return collapsed.length() > 1 && collapsed.endsWith("/") ? collapsed.substring(0, collapsed.length() - 1) : collapsed;
    }
}
