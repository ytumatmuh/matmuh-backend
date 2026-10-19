package com.matmuh.matmuhsite.business.concretes;

import com.matmuh.matmuhsite.business.abstracts.SearchService;
import com.matmuh.matmuhsite.business.constants.AnnouncementCollectionSchema;
import com.matmuh.matmuhsite.business.constants.NewsCollectionSchema;
import com.matmuh.matmuhsite.business.constants.SearchMessages;
import com.matmuh.matmuhsite.core.dtos.cms.response.CollectionSchema;
import com.matmuh.matmuhsite.core.dtos.cms.response.FieldDefinition;
import com.matmuh.matmuhsite.core.dtos.search.response.SearchGroupDto;
import com.matmuh.matmuhsite.core.dtos.search.response.SearchHitDto;
import com.matmuh.matmuhsite.core.dtos.search.response.SearchResultDto;
import com.matmuh.matmuhsite.core.helpers.CmsLocaleResolver;
import com.matmuh.matmuhsite.core.helpers.CollectionSortParser.CollectionSort;
import com.matmuh.matmuhsite.core.helpers.MessageResolver;
import com.matmuh.matmuhsite.core.helpers.OffsetPageable;
import com.matmuh.matmuhsite.dataAccess.abstracts.ElectiveGroupDao;
import com.matmuh.matmuhsite.dataAccess.abstracts.LectureDao;
import com.matmuh.matmuhsite.dataAccess.abstracts.StaffDao;
import com.matmuh.matmuhsite.dataAccess.abstracts.cms.CollectionItemDao;
import com.matmuh.matmuhsite.entities.ElectiveGroup;
import com.matmuh.matmuhsite.entities.Lecture;
import com.matmuh.matmuhsite.entities.SearchResultType;
import com.matmuh.matmuhsite.entities.Staff;
import com.matmuh.matmuhsite.entities.cms.CollectionItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class SearchManager implements SearchService {

    private static final Sort LECTURE_SORT = Sort.by(Sort.Direction.ASC, "code");
    private static final Sort STAFF_SORT = Sort.by(Sort.Direction.ASC, "lastName");
    private static final Sort GROUP_SORT = Sort.by(Sort.Direction.ASC, "code");
    private static final List<CollectionSort> NEWEST_FIRST = List.of(
            new CollectionSort(null, "publishedAt", true),
            new CollectionSort("created_at", null, true));
    private static final String ENGLISH = "en";

    private final LectureDao lectureDao;
    private final ElectiveGroupDao electiveGroupDao;
    private final StaffDao staffDao;
    private final CollectionItemDao collectionItemDao;
    private final CmsLocaleResolver localeResolver;
    private final MessageResolver messageResolver;

    public SearchManager(LectureDao lectureDao,
                         ElectiveGroupDao electiveGroupDao,
                         StaffDao staffDao,
                         CollectionItemDao collectionItemDao,
                         CmsLocaleResolver localeResolver,
                         MessageResolver messageResolver) {
        this.lectureDao = lectureDao;
        this.electiveGroupDao = electiveGroupDao;
        this.staffDao = staffDao;
        this.collectionItemDao = collectionItemDao;
        this.localeResolver = localeResolver;
        this.messageResolver = messageResolver;
    }

    @Override
    @Transactional(readOnly = true)
    public SearchResultDto search(String query, Set<SearchResultType> types, String locale, int limit) {
        var normalized = query == null ? "" : query.trim();

        var result = new SearchResultDto();
        result.setQuery(normalized);

        if (normalized.isEmpty()) {
            return result;
        }

        log.debug("Global search length={} types={} limit={}", normalized.length(), types, limit);

        var wanted = types == null || types.isEmpty() ? Set.of(SearchResultType.values()) : types;
        var resolvedLocale = localeResolver.resolveForRead(locale);
        var english = ENGLISH.equals(CmsLocaleResolver.normalize(locale));
        var groups = new ArrayList<SearchGroupDto>();

        if (wanted.contains(SearchResultType.LECTURE)) {
            groups.add(lectureGroup(normalized, english, limit));
        }
        if (wanted.contains(SearchResultType.ELECTIVE_GROUP)) {
            groups.add(electiveGroupGroup(normalized, english, limit));
        }
        if (wanted.contains(SearchResultType.STAFF)) {
            groups.add(staffGroup(normalized, english, limit));
        }
        if (wanted.contains(SearchResultType.ANNOUNCEMENT)) {
            groups.add(collectionGroup(SearchResultType.ANNOUNCEMENT, AnnouncementCollectionSchema.KEY,
                    AnnouncementCollectionSchema.SCHEMA, SearchMessages.GROUP_ANNOUNCEMENTS, normalized, resolvedLocale, limit));
        }
        if (wanted.contains(SearchResultType.NEWS)) {
            groups.add(collectionGroup(SearchResultType.NEWS, NewsCollectionSchema.KEY,
                    NewsCollectionSchema.SCHEMA, SearchMessages.GROUP_NEWS, normalized, resolvedLocale, limit));
        }

        groups.removeIf(group -> group.getTotal() == 0);

        result.setGroups(groups);
        result.setTotal(groups.stream().mapToLong(SearchGroupDto::getTotal).sum());
        return result;
    }

    private SearchGroupDto lectureGroup(String query, boolean english, int limit) {
        var page = lectureDao.search(null, null, null, null, null, null, query, OffsetPageable.of(0, limit, LECTURE_SORT));

        var items = page.getContent().stream().map(lecture -> toHit(lecture, english)).toList();
        return group(SearchResultType.LECTURE, SearchMessages.GROUP_LECTURES, page.getTotalElements(), items);
    }

    private SearchGroupDto electiveGroupGroup(String query, boolean english, int limit) {
        var page = electiveGroupDao.search(null, null, null, query, OffsetPageable.of(0, limit, GROUP_SORT));

        var items = page.getContent().stream().map(group -> toHit(group, english)).toList();
        return group(SearchResultType.ELECTIVE_GROUP, SearchMessages.GROUP_ELECTIVE_GROUPS, page.getTotalElements(), items);
    }

    private SearchGroupDto staffGroup(String query, boolean english, int limit) {
        var page = staffDao.search(query, null, null, OffsetPageable.of(0, limit, STAFF_SORT));

        var items = page.getContent().stream().map(staff -> toHit(staff, english)).toList();
        return group(SearchResultType.STAFF, SearchMessages.GROUP_STAFF, page.getTotalElements(), items);
    }

    private SearchGroupDto collectionGroup(SearchResultType type, String key, CollectionSchema schema,
                                           String labelKey, String query, String locale, int limit) {
        var searchFields = schema.fields().stream()
                .filter(FieldDefinition::searchable)
                .map(FieldDefinition::name)
                .toList();

        var total = collectionItemDao.countByFilter(key, null, false, locale, searchFields, query);
        var items = collectionItemDao.searchByFilter(key, null, NEWEST_FIRST, false, locale, searchFields, query, 0, limit)
                .stream()
                .map(item -> toHit(type, item))
                .toList();

        return group(type, labelKey, total, items);
    }

    private SearchHitDto toHit(Lecture lecture, boolean english) {
        return new SearchHitDto(SearchResultType.LECTURE, lecture.getId().toString(), lecture.getSlug(),
                inLanguage(english, lecture.getName(), lecture.getNameEn()), lecture.getCode());
    }

    private SearchHitDto toHit(ElectiveGroup group, boolean english) {
        return new SearchHitDto(SearchResultType.ELECTIVE_GROUP, group.getId().toString(), group.getSlug(),
                inLanguage(english, group.getName(), group.getNameEn()), group.getCode());
    }

    private SearchHitDto toHit(Staff staff, boolean english) {
        var name = join(staff.getFirstName(), staff.getLastName());
        var subtitle = staff.getAcademicTitle() == null || staff.getAcademicTitle().isBlank()
                ? inLanguage(english, staff.getRole(), staff.getRoleEn())
                : staff.getAcademicTitle();
        return new SearchHitDto(SearchResultType.STAFF, staff.getId().toString(), staff.getSlug(), name, subtitle);
    }

    private SearchHitDto toHit(SearchResultType type, CollectionItem item) {
        return new SearchHitDto(type, item.getId().toString(), item.getSlug(),
                text(item.getData(), "title"), text(item.getData(), "summary"));
    }

    private SearchGroupDto group(SearchResultType type, String labelKey, long total, List<SearchHitDto> items) {
        return new SearchGroupDto(type, messageResolver.resolve(labelKey), total, items);
    }

    private String text(JsonNode data, String field) {
        if (data == null) {
            return null;
        }
        var node = data.get(field);
        return node == null || node.isNull() ? null : node.asString();
    }

    private String inLanguage(boolean english, String turkish, String inEnglish) {
        return english && inEnglish != null && !inEnglish.isBlank() ? inEnglish : turkish;
    }

    private String join(String first, String last) {
        if (first == null || first.isBlank()) {
            return last;
        }
        if (last == null || last.isBlank()) {
            return first;
        }
        return first + " " + last;
    }
}
