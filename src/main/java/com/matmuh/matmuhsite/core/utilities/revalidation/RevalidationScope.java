package com.matmuh.matmuhsite.core.utilities.revalidation;

import com.matmuh.matmuhsite.business.abstracts.CmsCollectionProvider;
import com.matmuh.matmuhsite.business.constants.CollectionRegistry;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which collections the frontend caches stale after a successful write.
 *
 * Generic collections (news, announcements) stand alone. The provider-backed
 * ones are built from the same relational data and carry each other's (note,
 * statistics and elective-group counts on lectures, lecture data on elective
 * groups, staff and slots on offerings, all of them in the weekly schedule), so
 * a write to any of them refreshes the whole group rather than a list of
 * dependencies that would have to keep up with every new derived field.
 */
@Component
public class RevalidationScope {

    public enum Kind { REVALIDATE, IGNORED, UNMAPPED }

    public record Decision(Kind kind, Set<String> collections) {

        static Decision revalidate(Set<String> collections) {
            return new Decision(Kind.REVALIDATE, collections);
        }

        static Decision ignored() {
            return new Decision(Kind.IGNORED, Set.of());
        }

        static Decision unmapped() {
            return new Decision(Kind.UNMAPPED, Set.of());
        }
    }

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private static final PathPatternParser PARSER = new PathPatternParser();

    // Writes that change nothing the frontend caches: drafts, already archived
    // rows, files, accounts, pending notes, calendar events. Page content is
    // published from the drawer, which revalidates it itself.
    private static final List<PathPattern> IGNORED = patterns(
            "/api/auth/**",
            "/api/users/**",
            "/api/service-keys/**",
            "/api/enrollments/**",
            "/api/cms/content",
            "/api/cms/draft",
            "/api/cms/sync",
            "/api/cms/media",
            "/api/cms/collections/*/drafts",
            "/api/cms/collections/*/purge",
            "/api/cms/collections/*/*/draft",
            "/api/cms/collections/*/*/purge",
            "/api/lectures/*/notes",
            "/api/calendar-admin/events/**");

    private static final PathPattern COLLECTION_WRITE = PARSER.parse("/api/cms/collections/{key}/**");

    private static final List<PathPattern> PROVIDER_DATA = patterns(
            "/api/lectures/**",
            "/api/lecture-notes/**",
            "/api/lecture-offerings/**",
            "/api/staff/**",
            "/api/elective-groups/**",
            "/api/calendar-admin/terms/**",
            "/api/calendar-admin/slots/**");

    private final CollectionRegistry registry;

    private final Set<String> providerBacked;

    public RevalidationScope(CollectionRegistry registry, List<CmsCollectionProvider> providers) {
        this.registry = registry;
        this.providerBacked = providers.stream()
                .map(provider -> registry.resolve(provider.collectionKey()).key())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public Decision decide(String method, String path) {
        if (method == null || !WRITE_METHODS.contains(method.toUpperCase())) {
            return Decision.ignored();
        }
        var container = PathContainer.parsePath(path);
        if (IGNORED.stream().anyMatch(pattern -> pattern.matches(container))) {
            return Decision.ignored();
        }
        var collectionWrite = COLLECTION_WRITE.matchAndExtract(container);
        if (collectionWrite != null) {
            var key = collectionWrite.getUriVariables().get("key");
            if (!registry.exists(key)) {
                return Decision.ignored();
            }
            var canonical = registry.resolve(key).key();
            return Decision.revalidate(providerBacked.contains(canonical) ? providerBacked : Set.of(canonical));
        }
        if (PROVIDER_DATA.stream().anyMatch(pattern -> pattern.matches(container))) {
            return Decision.revalidate(providerBacked);
        }
        return Decision.unmapped();
    }

    public Set<String> allCollections() {
        return registry.all().stream()
                .map(CollectionRegistry.CollectionDefinition::key)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<PathPattern> patterns(String... sources) {
        return Arrays.stream(sources).map(PARSER::parse).toList();
    }
}
