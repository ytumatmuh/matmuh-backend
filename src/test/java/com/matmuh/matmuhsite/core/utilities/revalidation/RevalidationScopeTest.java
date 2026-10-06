package com.matmuh.matmuhsite.core.utilities.revalidation;

import com.matmuh.matmuhsite.business.abstracts.CmsCollectionProvider;
import com.matmuh.matmuhsite.business.constants.AcademicTermCollectionSchema;
import com.matmuh.matmuhsite.business.constants.AnnouncementCollectionSchema;
import com.matmuh.matmuhsite.business.constants.CollectionRegistry;
import com.matmuh.matmuhsite.business.constants.ElectiveGroupCollectionSchema;
import com.matmuh.matmuhsite.business.constants.LectureCollectionSchema;
import com.matmuh.matmuhsite.business.constants.LectureOfferingCollectionSchema;
import com.matmuh.matmuhsite.business.constants.NewsCollectionSchema;
import com.matmuh.matmuhsite.business.constants.StaffCollectionSchema;
import com.matmuh.matmuhsite.core.utilities.revalidation.RevalidationScope.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RevalidationScopeTest {

    static final Set<String> PROVIDER_BACKED = Set.of(
            LectureCollectionSchema.KEY,
            StaffCollectionSchema.KEY,
            ElectiveGroupCollectionSchema.KEY,
            LectureOfferingCollectionSchema.KEY,
            AcademicTermCollectionSchema.KEY);

    static RevalidationScope scope() {
        var providers = PROVIDER_BACKED.stream().map(key -> {
            var provider = mock(CmsCollectionProvider.class);
            when(provider.collectionKey()).thenReturn(key);
            return provider;
        }).toList();
        return new RevalidationScope(new CollectionRegistry(), providers);
    }

    private final RevalidationScope scope = scope();

    @Test
    void aGenericCollectionWriteRefreshesThatCollectionOnly() {
        assertRevalidates("PUT", "/api/cms/collections/news/bahar-senligi", Set.of(NewsCollectionSchema.KEY));
        assertRevalidates("POST", "/api/cms/collections/NEWS", Set.of(NewsCollectionSchema.KEY));
        assertRevalidates("PUT", "/api/cms/collections/announcements/sinav/slug", Set.of(AnnouncementCollectionSchema.KEY));
        assertRevalidates("POST", "/api/cms/collections/announcements/sinav/restore", Set.of(AnnouncementCollectionSchema.KEY));
    }

    @Test
    void aWriteToProviderDataRefreshesTheWholeGroup() {
        assertRevalidates("DELETE", "/api/cms/collections/lectures/mat1011", PROVIDER_BACKED);
        assertRevalidates("PATCH", "/api/staff/7f0c2a3e-1b2c-4d5e-8f90-123456789abc", PROVIDER_BACKED);
        assertRevalidates("PATCH", "/api/lecture-notes/7f0c2a3e-1b2c-4d5e-8f90-123456789abc", PROVIDER_BACKED);
        assertRevalidates("PUT", "/api/lecture-offerings/7f0c2a3e/exam-statistics/MIDTERM", PROVIDER_BACKED);
        assertRevalidates("POST", "/api/lecture-offerings/import", PROVIDER_BACKED);
        assertRevalidates("PUT", "/api/calendar-admin/terms", PROVIDER_BACKED);
        assertRevalidates("DELETE", "/api/calendar-admin/slots/7f0c2a3e", PROVIDER_BACKED);
    }

    @Test
    void writesThatChangeNothingCachedAreIgnored() {
        for (var write : List.of(
                "PUT /api/cms/collections/news/bahar-senligi/draft",
                "DELETE /api/cms/collections/news/drafts",
                "DELETE /api/cms/collections/news/purge",
                "DELETE /api/cms/collections/news/bahar-senligi/purge",
                "PUT /api/cms/content",
                "POST /api/cms/sync",
                "POST /api/lectures/7f0c2a3e/notes",
                "POST /api/calendar-admin/events",
                "POST /api/auth/login",
                "POST /api/cms/collections/unknown-collection")) {
            var parts = write.split(" ");
            assertThat(scope.decide(parts[0], parts[1]).kind()).as(write).isEqualTo(Kind.IGNORED);
        }
        assertThat(scope.decide("GET", "/api/staff").kind()).isEqualTo(Kind.IGNORED);
    }

    @Test
    void aWriteNobodyClassifiedIsUnmapped() {
        assertThat(scope.decide("POST", "/api/reports").kind()).isEqualTo(Kind.UNMAPPED);
    }

    @Test
    void startupRefreshesEveryCollection() {
        assertThat(scope.allCollections()).containsExactlyInAnyOrder(
                LectureCollectionSchema.KEY, StaffCollectionSchema.KEY, ElectiveGroupCollectionSchema.KEY,
                LectureOfferingCollectionSchema.KEY, AcademicTermCollectionSchema.KEY,
                AnnouncementCollectionSchema.KEY, NewsCollectionSchema.KEY);
    }

    private void assertRevalidates(String method, String path, Set<String> collections) {
        var decision = scope.decide(method, path);
        assertThat(decision.kind()).as(method + " " + path).isEqualTo(Kind.REVALIDATE);
        assertThat(decision.collections()).as(method + " " + path).containsExactlyInAnyOrderElementsOf(collections);
    }
}
