package com.matmuh.matmuhsite.business.concretes;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import com.matmuh.matmuhsite.business.abstracts.CmsCollectionProvider;
import com.matmuh.matmuhsite.business.abstracts.CmsCollectionService;
import com.matmuh.matmuhsite.business.constants.CmsMessages;
import com.matmuh.matmuhsite.business.constants.CollectionRegistry;
import com.matmuh.matmuhsite.core.helpers.CollectionFilterParser;
import com.matmuh.matmuhsite.core.helpers.CollectionSchemaValidator;
import com.matmuh.matmuhsite.core.helpers.CmsLocaleResolver;
import com.matmuh.matmuhsite.core.helpers.LogMasks;
import com.matmuh.matmuhsite.core.helpers.FilePreviewEnricher;
import com.matmuh.matmuhsite.core.helpers.CollectionSortParser;
import com.matmuh.matmuhsite.core.helpers.SlugGenerator;
import com.matmuh.matmuhsite.core.helpers.SlugNormalizer;
import com.matmuh.matmuhsite.core.dtos.cms.request.CreateCollectionItemRequestDto;
import com.matmuh.matmuhsite.core.dtos.cms.request.SaveDraftRequestDto;
import com.matmuh.matmuhsite.core.dtos.cms.request.SaveNewDraftRequestDto;
import com.matmuh.matmuhsite.core.dtos.cms.request.UpsertCollectionItemRequestDto;
import com.matmuh.matmuhsite.core.dtos.cms.response.ArchiveResultDto;
import com.matmuh.matmuhsite.core.dtos.cms.response.CollectionItemDto;
import com.matmuh.matmuhsite.core.dtos.cms.response.CollectionListDto;
import com.matmuh.matmuhsite.core.dtos.cms.response.MyCollectionDto;
import com.matmuh.matmuhsite.core.dtos.cms.response.PurgeResultDto;
import com.matmuh.matmuhsite.core.dtos.cms.response.TranslationRefDto;
import com.matmuh.matmuhsite.core.dtos.cms.response.VirtualItemDto;
import com.matmuh.matmuhsite.core.dtos.cms.response.CollectionSchema;
import com.matmuh.matmuhsite.core.dtos.cms.response.FieldDefinition;
import com.matmuh.matmuhsite.core.dtos.cms.response.CollectionSchemaResponseDto;
import com.matmuh.matmuhsite.entities.cms.SlugSource;
import com.matmuh.matmuhsite.core.exceptions.ArchivedException;
import com.matmuh.matmuhsite.core.exceptions.ResourceNotFoundException;
import com.matmuh.matmuhsite.core.exceptions.CmsValidationException;
import com.matmuh.matmuhsite.core.exceptions.ConcurrencyConflictException;
import com.matmuh.matmuhsite.core.exceptions.SlugConflictException;
import com.matmuh.matmuhsite.core.dtos.cms.request.RenameSlugRequestDto;
import com.matmuh.matmuhsite.dataAccess.abstracts.cms.CollectionDraftDao;
import com.matmuh.matmuhsite.dataAccess.abstracts.cms.CollectionItemDao;
import com.matmuh.matmuhsite.dataAccess.abstracts.cms.CollectionSlugAliasDao;
import com.matmuh.matmuhsite.entities.cms.CollectionSlugAlias;
import com.matmuh.matmuhsite.entities.cms.CollectionDraft;
import com.matmuh.matmuhsite.entities.cms.CollectionItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import com.matmuh.matmuhsite.core.dtos.cms.response.CollectionLookupDto;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CmsCollectionManager implements CmsCollectionService {

    private static final int LOOKUP_MAX_LIMIT = 100;


    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final Logger logger = LoggerFactory.getLogger(CmsCollectionManager.class);

    private final CollectionItemDao collectionItemDao;
    private final CollectionDraftDao collectionDraftDao;
    private final CollectionSlugAliasDao slugAliasDao;
    private final CollectionRegistry registry;
    private final CmsLocaleResolver localeResolver;
    private final FilePreviewEnricher filePreviewEnricher;
    private final Map<String, CmsCollectionProvider> providers;

    public CmsCollectionManager(CollectionItemDao collectionItemDao,
                                CollectionDraftDao collectionDraftDao,
                                CollectionSlugAliasDao slugAliasDao,
                                CollectionRegistry registry,
                                CmsLocaleResolver localeResolver,
                                FilePreviewEnricher filePreviewEnricher,
                                List<CmsCollectionProvider> providers) {
        this.collectionItemDao = collectionItemDao;
        this.collectionDraftDao = collectionDraftDao;
        this.slugAliasDao = slugAliasDao;
        this.registry = registry;
        this.localeResolver = localeResolver;
        this.filePreviewEnricher = filePreviewEnricher;
        this.providers = providers.stream()
                .collect(Collectors.toMap(CmsCollectionProvider::collectionKey, provider -> provider));
    }


    @Override
    public boolean allowsAnonymousRead(String collectionKey) {
        return registry.exists(collectionKey)
                && registry.resolve(collectionKey).allowAnonymousRead();
    }

    @Override
    public CollectionSchemaResponseDto getSchema(String collectionKey) {
        var def = registry.resolve(collectionKey);
        return new CollectionSchemaResponseDto(def.key(), def.schema(), def.slugSource(),
                def.slugEditable(), localesOf(def), def.displayName(), def.displayField());
    }


    private List<String> localesOf(CollectionRegistry.CollectionDefinition def) {
        return def.localized() ? localeResolver.declared() : List.of();
    }

    private List<String> searchableFields(CollectionSchema schema) {
        return schema.fields().stream()
                .filter(FieldDefinition::searchable)
                .map(FieldDefinition::name)
                .toList();
    }

    private String writeLocale(CollectionRegistry.CollectionDefinition def, String locale) {
        return def.localized() ? localeResolver.requireForWrite(locale, def.key()) : null;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MyCollectionDto> getMyCollections() {
        var counts = countListedItems();
        return registry.all().stream()
                .map(def -> new MyCollectionDto(def.key(), def.schema(), true, counts.getOrDefault(def.key(), 0L),
                        def.slugSource(), localesOf(def), def.slugEditable(), def.displayName(), def.displayField()))
                .collect(Collectors.toList());
    }

    // Listelemenin göstereceğini sayar: arşiv hariç, dilli koleksiyonda yalnız varsayılan dil.
    private Map<String, Long> countListedItems() {
        var counts = new HashMap<String, Long>();
        var localizedByKey = new HashMap<String, Boolean>();

        for (var def : registry.all()) {
            var provider = providers.get(def.key());
            if (provider != null) {
                counts.put(def.key(), provider.count());
            } else {
                localizedByKey.put(def.key(), def.localized());
            }
        }

        if (localizedByKey.isEmpty()) {
            return counts;
        }

        var listedLocale = localeResolver.resolveForRead(null);
        for (var row : collectionItemDao.countLiveByCollection(localizedByKey.keySet())) {
            var localized = localizedByKey.getOrDefault(row.getCollectionKey(), false);
            if (!localized || listedLocale == null || listedLocale.equals(row.getLocale())) {
                counts.merge(row.getCollectionKey(), row.getCount(), Long::sum);
            }
        }
        return counts;
    }

    @Override
    @Transactional(readOnly = true)
    public CollectionListDto list(String collectionKey, String userId, Map<String, String> filters,
                                  String sort, boolean archived, String locale, String search, int offset, int limit) {
        var def = registry.resolve(collectionKey);
        var key = def.key();


        var showArchived = archived && userId != null;

        logger.debug("Listing collection {} filters={} sort={} archived={} offset={} limit={} editor={}",
                key, filters == null ? Set.of() : filters.keySet(), sort, showArchived, offset, limit, userId != null);

        var filterNode = CollectionFilterParser.build(def.schema(), filters);
        var filterJson = filterNode == null ? null : filterNode.toString();
        var parsedSorts = CollectionSortParser.parse(def.schema(), sort);
        var resolvedLocale = localeResolver.resolveForRead(locale);

        var provider = providers.get(key);

        long total;
        List<CollectionItemDto> itemDtos;

        if (provider != null) {

            if (showArchived) {
                return new CollectionListDto(List.of(), 0, offset, limit);
            }
            var result = provider.list(filterNode, resolvedLocale, offset, limit);
            total = result.getTotal();
            itemDtos = new ArrayList<>(result.getItems());
        } else {
            var searchFields = searchableFields(def.schema());
            var items = collectionItemDao.searchByFilter(key, filterJson, parsedSorts, showArchived, resolvedLocale,
                    searchFields, search, offset, limit);
            total = collectionItemDao.countByFilter(key, filterJson, showArchived, resolvedLocale, searchFields, search);
            itemDtos = items.stream().map(this::toDto).collect(Collectors.toCollection(ArrayList::new));
        }

        var result = new CollectionListDto(itemDtos, total, offset, limit);

        if (provider == null) {
            applyTranslations(key, itemDtos);
        }

        if (userId != null) {
            var drafts = itemDrafts(key, itemDtos, userId, resolvedLocale);
            for (var dto : itemDtos) {
                dto.setCanEdit(true);
                dto.setDraftData(draftFor(drafts.get(dto.getSlug()), dto.getData()));
            }
            if (!showArchived) {
                result.setVirtualItems(pendingVirtualItems(key, userId, resolvedLocale));
            }
        }

        return result;
    }

    @Override
    @Transactional
    public ArchiveResultDto archive(String collectionKey, String slug, Integer version, String updatedBy) {
        var provider = providers.get(registry.resolve(collectionKey).key());
        if (provider != null) {
            return deleteThroughProvider(provider, slug, version, updatedBy);
        }

        var item = requireOwnItem(collectionKey, slug);

        logger.info("Archiving collection item {}/{} by {}", item.getCollectionKey(), item.getSlug(), LogMasks.principal(updatedBy));

        if (version == null) {
            throw new CmsValidationException(CmsMessages.VERSION_REQUIRED_FOR_EXISTING + item.getCollectionKey() + "/" + item.getSlug());
        }
        if (version != item.getVersion()) {
            throw new ConcurrencyConflictException(CmsMessages.VERSION_CONFLICT);
        }

        if (!item.isArchived()) {
            item.setArchived(true);
            item.setArchivedAt(Instant.now());
            item.setUpdatedBy(updatedBy);
            collectionItemDao.save(item);
        }

        // Sürüm bilerek artmıyor: içerik değişmedi. Aynı numara arşivliyor, geri yüklüyor
        // ve sonrasında yayınlamaya da yetiyor.
        return new ArchiveResultDto(item.getCollectionKey(), item.getSlug(), item.getVersion());
    }

    // Sağlayıcılı koleksiyonun arşivi yok, kendi tablosu var: SDK her koleksiyonda sil düğmesi
    // çizdiği için aynı uç burada satırı gerçekten siler. Sürüm jsonb'deki kuralla aynı sebeple
    // zorunlu — sürümsüz DELETE başkasının okuduğundan başka bir kaydı düşürebilir.
    private ArchiveResultDto deleteThroughProvider(CmsCollectionProvider provider, String slug,
                                                   Integer version, String deletedBy) {
        var key = provider.collectionKey();
        var normalizedSlug = SlugNormalizer.normalizeBlockPath(slug);

        if (version == null) {
            throw new CmsValidationException(CmsMessages.VERSION_REQUIRED_FOR_EXISTING + key + "/" + normalizedSlug);
        }

        logger.info("Deleting provider-backed collection item {}/{} by {}", key, normalizedSlug, LogMasks.principal(deletedBy));
        provider.delete(normalizedSlug, version);

        return new ArchiveResultDto(key, normalizedSlug, version);
    }

    @Override
    @Transactional
    public CollectionItemDto restore(String collectionKey, String slug, String updatedBy) {
        var item = requireOwnItem(collectionKey, slug);

        logger.info("Restoring collection item {}/{} by {}", item.getCollectionKey(), item.getSlug(), LogMasks.principal(updatedBy));

        if (item.isArchived()) {
            item.setArchived(false);
            item.setArchivedAt(null);
            item.setUpdatedBy(updatedBy);
            collectionItemDao.save(item);
        }

        return editable(item);
    }

    @Override
    @Transactional
    public void purge(String collectionKey, String slug, String purgedBy) {
        var key = requirePurgeable(collectionKey);
        var normalizedSlug = SlugNormalizer.normalizeBlockPath(slug);
        var item = requireWritable(key, normalizedSlug);

        if (!item.isArchived()) {
            throw new ConcurrencyConflictException(CmsMessages.PURGE_REQUIRES_ARCHIVED + key + "/" + normalizedSlug);
        }

        purgeItem(item);
        logger.info("Purged archived collection item {}/{} by {}", key, normalizedSlug, LogMasks.principal(purgedBy));
    }

    @Override
    @Transactional
    public PurgeResultDto purgeArchived(String collectionKey, String purgedBy) {
        var key = requirePurgeable(collectionKey);

        var archived = collectionItemDao.findByCollectionKeyAndArchivedTrue(key);
        archived.forEach(this::purgeItem);

        logger.info("Purged {} archived item(s) of collection {} by {}", archived.size(), key, LogMasks.principal(purgedBy));
        return new PurgeResultDto(archived.size());
    }

    private String requirePurgeable(String collectionKey) {
        var def = registry.resolve(collectionKey);
        if (providers.containsKey(def.key())) {
            throw new CmsValidationException(CmsMessages.COLLECTION_NOT_PURGEABLE + def.key()
                    + (def.restDeletePath() == null ? "" : CmsMessages.USE_REST_DELETE + def.restDeletePath()));
        }
        return def.key();
    }

    // Soft-delete kuralının bilinçli istisnası: arşiv çöp kutusudur, yalnız zaten arşivlenmiş
    // satır açık istekle kalıcı silinir. Alias ve taslaklar da gider ki slug gerçekten boşalsın
    // ve aynı başlıkla yeniden oluşturulan kayıt "-2" eki almasın.
    private void purgeItem(CollectionItem item) {
        slugAliasDao.deleteByCollectionKeyAndItemId(item.getCollectionKey(), item.getId());
        collectionDraftDao.deleteAll(collectionDraftDao.findByCollectionKeyAndSlug(item.getCollectionKey(), item.getSlug()));
        collectionItemDao.delete(item);
    }


    private String localeOfItem(String collectionKey, String slug, String requested) {
        return collectionItemDao.findByCollectionKeyAndSlug(collectionKey, slug)
                .map(CollectionItem::getLocale)
                .orElseGet(() -> localeResolver.resolveForRead(requested));
    }

    private CollectionItem requireOwnItem(String collectionKey, String slug) {
        var def = registry.resolve(collectionKey);
        var key = def.key();
        var normalizedSlug = SlugNormalizer.normalizeBlockPath(slug);

        if (providers.containsKey(key)) {
            throw new CmsValidationException(notArchivable(def));
        }

        return requireWritable(key, normalizedSlug);
    }

    // Silme sağlayıcıya iner ama geri yükleme inemez: satır kendi tablosunda soft-delete
    // edilmiştir, CMS arşivinde listelenmez. Hata nereye gidileceğini söylesin ki çağıran
    // (bot ya da panel) 400'ü okuyup doğru uca gidebilsin.
    private String notArchivable(CollectionRegistry.CollectionDefinition def) {
        var message = CmsMessages.COLLECTION_NOT_ARCHIVABLE + def.key();
        return def.restDeletePath() == null ? message : message + CmsMessages.USE_REST_DELETE + def.restDeletePath();
    }

    private CollectionItem requireWritable(String collectionKey, String slug) {
        var item = collectionItemDao.findByCollectionKeyAndSlug(collectionKey, slug).orElse(null);
        if (item == null) {
            requireNotAlias(collectionKey, slug);
            throw new ResourceNotFoundException(CmsMessages.COLLECTION_ITEM_NOT_FOUND + collectionKey + "/" + slug);
        }
        return item;
    }

    // Eski adrese yazma takip edilmez, reddedilir: yönlendirilen yazma çağıranın hiç
    // adını vermediği bir kayda iner ve adresinin bayatladığını asla öğrenemez.
    private void requireNotAlias(String collectionKey, String slug) {
        slugAliasDao.findByCollectionKeyAndSlug(collectionKey, slug).ifPresent(alias -> {
            var current = collectionItemDao.findById(alias.getItemId())
                    .map(CollectionItem::getSlug)
                    .orElse(slug);
            throw new SlugConflictException(CmsMessages.SLUG_MOVED + current,
                    SlugConflictException.REASON_MOVED, current);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public CollectionItemDto getBySlug(String collectionKey, String slug, String userId, String locale) {
        var def = registry.resolve(collectionKey);
        var key = def.key();
        var normalizedSlug = SlugNormalizer.normalizeBlockPath(slug);

        var resolvedLocale = localeResolver.resolveForRead(locale);
        var provider = providers.get(key);

        var isEditor = userId != null;

        CollectionItemDto dto;
        var draftLocale = resolvedLocale;
        if (provider != null) {
            dto = provider.getBySlug(normalizedSlug, resolvedLocale);
        } else {
            var found = (isEditor
                    ? collectionItemDao.findByCollectionKeyAndSlug(key, normalizedSlug)
                    : collectionItemDao.findByCollectionKeyAndSlugAndArchivedFalse(key, normalizedSlug))
                    .or(() -> resolveAlias(key, normalizedSlug, isEditor))
                    .orElseThrow(() -> new ResourceNotFoundException(
                            CmsMessages.COLLECTION_ITEM_NOT_FOUND + key + "/" + normalizedSlug));
            var item = resolveLocaleSibling(found, locale);
            dto = toDto(item);
            dto.setTranslations(siblingsOf(item));
            draftLocale = item.getLocale();
        }

        if (isEditor) {
            dto.setCanEdit(true);
            dto.setDraftData(resolveItemDraft(key, dto.getSlug(), userId, draftLocale, dto.getData()));
        }
        return dto;
    }


    private CollectionItem resolveLocaleSibling(CollectionItem item, String requested) {
        if (requested == null || requested.isBlank() || item.getTranslationGroupId() == null) {
            return item;
        }

        var normalized = CmsLocaleResolver.normalize(requested);
        if (normalized.equals(item.getLocale())) {
            return item;
        }

        return collectionItemDao
                .findByCollectionKeyAndTranslationGroupId(item.getCollectionKey(), item.getTranslationGroupId())
                .stream()
                .filter(sibling -> !sibling.isArchived())
                .filter(sibling -> normalized.equals(sibling.getLocale()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        CmsMessages.COLLECTION_ITEM_NOT_FOUND + item.getCollectionKey()
                                + "/" + item.getSlug() + " (" + normalized + ")"));
    }


    private void applyTranslations(String collectionKey, List<CollectionItemDto> dtos) {
        var groupIds = dtos.stream()
                .map(CollectionItemDto::getTranslationGroupId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (groupIds.isEmpty()) {
            return;
        }

        var byGroup = collectionItemDao
                .findByCollectionKeyAndTranslationGroupIdIn(collectionKey, groupIds)
                .stream()
                .filter(item -> !item.isArchived())
                .collect(Collectors.groupingBy(CollectionItem::getTranslationGroupId));

        for (var dto : dtos) {
            var group = byGroup.get(dto.getTranslationGroupId());
            if (group == null) continue;

            var siblings = group.stream()
                    .filter(sibling -> !sibling.getId().equals(dto.getId()))
                    .map(sibling -> new TranslationRefDto(sibling.getLocale(), sibling.getSlug()))
                    .toList();
            if (!siblings.isEmpty()) {
                dto.setTranslations(siblings);
            }
        }
    }

    private List<TranslationRefDto> siblingsOf(CollectionItem item) {
        if (item.getTranslationGroupId() == null) {
            return null;
        }
        var siblings = collectionItemDao
                .findByCollectionKeyAndTranslationGroupId(item.getCollectionKey(), item.getTranslationGroupId())
                .stream()
                .filter(candidate -> !candidate.getId().equals(item.getId()))
                .filter(candidate -> !candidate.isArchived())
                .map(candidate -> new TranslationRefDto(candidate.getLocale(), candidate.getSlug()))
                .toList();
        return siblings.isEmpty() ? null : siblings;
    }


    @Override
    @Transactional
    public CollectionItemDto upsert(String collectionKey, String slug,
                                    UpsertCollectionItemRequestDto request, String updatedBy,
                                    String locale, UUID translationGroup) {
        var def = registry.resolve(collectionKey);
        var key = def.key();
        var normalizedSlug = SlugNormalizer.normalizeBlockPath(slug);

        logger.info("Upserting collection item {}/{} by {}", key, normalizedSlug, LogMasks.principal(updatedBy));

        var validated = filePreviewEnricher.enrich(def.schema(),
                CollectionSchemaValidator.validateAndStrip(def.schema(), request.getData()));

        var provider = providers.get(key);
        if (provider != null) {
            var exists = provider.existsBySlug(normalizedSlug);
            if (!exists && def.slugSource() == SlugSource.AUTO_GENERATED) {
                throw new CmsValidationException(CmsMessages.AUTO_GENERATED_USE_POST);
            }
            if (exists && request.getVersion() == null) {
                throw new CmsValidationException(CmsMessages.VERSION_REQUIRED_FOR_EXISTING + key + "/" + normalizedSlug);
            }
            var resolvedLocale = localeResolver.resolveForRead(locale);
            var providedDto = provider.upsert(normalizedSlug, validated, request.getVersion(), resolvedLocale);
            deleteDrafts(key, normalizedSlug, updatedBy, resolvedLocale, false);
            providedDto.setCanEdit(true);
            return providedDto;
        }

        var item = collectionItemDao.findByCollectionKeyAndSlug(key, normalizedSlug).orElse(null);
        var isCreate = item == null;


        if (isCreate) {
            requireNotAlias(key, normalizedSlug);
        }


        var resolvedLocale = isCreate
                ? writeLocale(def, locale)
                : item.getLocale();

        if (isCreate) {
            if (def.slugSource() == SlugSource.AUTO_GENERATED) {
                throw new CmsValidationException(CmsMessages.AUTO_GENERATED_USE_POST);
            }
            item = CollectionItem.builder()
                    .collectionKey(key)
                    .slug(normalizedSlug)
                    .locale(resolvedLocale)
                    .translationGroupId(translationGroup != null ? translationGroup : UUID.randomUUID())
                    .data(validated)
                    .updatedBy(updatedBy)
                    .build();
        } else {
            // Arşivlenmiş kaydı sessizce diriltmek yerine reddediyoruz: editörün yapması
            // gereken şey önce geri yüklemek, sürüm çakışması gibi birleştirme ekranı değil.
            if (item.isArchived()) {
                throw new ArchivedException(key + "/" + normalizedSlug, item.getVersion());
            }
            if (request.getVersion() == null) {
                throw new CmsValidationException(CmsMessages.VERSION_REQUIRED_FOR_EXISTING + key + "/" + normalizedSlug);
            }
            if (request.getVersion() != item.getVersion()) {
                throw new ConcurrencyConflictException(CmsMessages.VERSION_CONFLICT);
            }
            item.setData(validated);
            touch(item, updatedBy);
        }

        var saved = collectionItemDao.save(item);

        collectionDraftDao.findOwnItemDraft(key, normalizedSlug, updatedBy, resolvedLocale)
                .ifPresent(collectionDraftDao::delete);
        if (isCreate) {
            collectionDraftDao.findOwnNewDraft(key, updatedBy, resolvedLocale)
                    .ifPresent(collectionDraftDao::delete);
        }

        return editable(saved);
    }

    @Override
    @Transactional
    public CollectionItemDto createWithAutoSlug(String collectionKey,
                                                CreateCollectionItemRequestDto request, String updatedBy,
                                                String locale, UUID translationGroup) {
        var def = registry.resolve(collectionKey);
        var key = def.key();

        if (def.slugSource() == SlugSource.USER_DEFINED) {
            throw new CmsValidationException(CmsMessages.USER_DEFINED_USE_PUT);
        }

        var resolvedLocale = writeLocale(def, locale);
        var validated = filePreviewEnricher.enrich(def.schema(),
                CollectionSchemaValidator.validateAndStrip(def.schema(), request.getData()));

        var source = validated.path(def.slugSourceField()).asText("");
        var base = SlugGenerator.slugify(source);
        if (base.isBlank()) {
            throw new CmsValidationException(CmsMessages.SLUG_SOURCE_FIELD_MISSING);
        }
        var provider = providers.get(key);
        if (provider != null) {
            var providedDto = provider.create(validated, resolvedLocale);
            deleteDrafts(key, null, updatedBy, resolvedLocale, true);
            providedDto.setCanEdit(true);
            logger.info("Created collection item {}/{} by {}", key, providedDto.getSlug(), LogMasks.principal(updatedBy));
            return providedDto;
        }

        requireOpenTranslationSlot(key, translationGroup, resolvedLocale);
        var slug = resolveUniqueSlug(key, base);

        logger.info("Creating collection item {}/{} by {}", key, slug, LogMasks.principal(updatedBy));

        var item = CollectionItem.builder()
                .collectionKey(key)
                .slug(slug)
                .locale(resolvedLocale)
                .translationGroupId(translationGroup != null ? translationGroup : UUID.randomUUID())
                .data(validated)
                .updatedBy(updatedBy)
                .build();

        var saved = collectionItemDao.save(item);

        collectionDraftDao.findOwnNewDraft(key, updatedBy, resolvedLocale)
                .ifPresent(collectionDraftDao::delete);

        return editable(saved);
    }

    @Override
    @Transactional
    public void saveItemDraft(String collectionKey, String slug, String userId, SaveDraftRequestDto request, String locale) {
        var def = registry.resolve(collectionKey);
        var key = def.key();
        var normalizedSlug = SlugNormalizer.normalizeBlockPath(slug);

        var item = collectionItemDao.findByCollectionKeyAndSlug(key, normalizedSlug).orElse(null);
        if (item == null) {
            requireNotAlias(key, normalizedSlug);
        }
        var resolvedLocale = item != null ? item.getLocale() : localeResolver.resolveForRead(locale);
        var validated = CollectionSchemaValidator.validateAndStrip(def.schema(), request.getData(), true);

        var draft = collectionDraftDao
                .findOwnItemDraft(key, normalizedSlug, userId, resolvedLocale)
                .orElseGet(() -> CollectionDraft.builder()
                        .collectionKey(key)
                        .slug(normalizedSlug)
                        .userId(userId)
                        .locale(resolvedLocale)
                        .build());

        draft.setPayload(validated);
        collectionDraftDao.save(draft);
    }

    @Override
    @Transactional
    public void saveNewDraft(String collectionKey, String userId, SaveNewDraftRequestDto request, String locale) {
        var def = registry.resolve(collectionKey);
        var key = def.key();

        var resolvedLocale = writeLocale(def, locale);
        var validated = CollectionSchemaValidator.validateAndStrip(def.schema(), request.getData(), true);

        var draft = collectionDraftDao
                .findOwnNewDraft(key, userId, resolvedLocale)
                .orElseGet(() -> CollectionDraft.builder()
                        .collectionKey(key)
                        .userId(userId)
                        .locale(resolvedLocale)
                        .forNewItem(true)
                        .build());

        draft.setPayload(validated);
        collectionDraftDao.save(draft);
    }

    @Override
    @Transactional
    public void deleteItemDraft(String collectionKey, String slug, String userId, String locale) {
        var key = registry.resolve(collectionKey).key();
        var normalizedSlug = SlugNormalizer.normalizeBlockPath(slug);

        collectionDraftDao
                .findOwnItemDraft(key, normalizedSlug, userId, localeOfItem(key, normalizedSlug, locale))
                .ifPresent(collectionDraftDao::delete);
    }

    @Override
    @Transactional
    public void deleteNewDraft(String collectionKey, String userId, String locale) {
        var def = registry.resolve(collectionKey);
        var key = def.key();

        collectionDraftDao
                .findOwnNewDraft(key, userId, writeLocale(def, locale))
                .ifPresent(collectionDraftDao::delete);
    }



    private void deleteDrafts(String collectionKey, String slug, String userId, String locale, boolean isCreate) {
        if (slug != null) {
            collectionDraftDao.findOwnItemDraft(collectionKey, slug, userId, locale)
                    .ifPresent(collectionDraftDao::delete);
        }
        if (isCreate) {
            collectionDraftDao.findOwnNewDraft(collectionKey, userId, locale)
                    .ifPresent(collectionDraftDao::delete);
        }
    }

    private Map<String, CollectionDraft> itemDrafts(String key, List<CollectionItemDto> items,
                                                    String userId, String locale) {
        var slugs = items.stream()
                .map(CollectionItemDto::getSlug)
                .filter(Objects::nonNull)
                .toList();

        if (slugs.isEmpty()) {
            return Map.of();
        }

        return collectionDraftDao.findOwnItemDrafts(key, slugs, userId, locale).stream()
                .collect(Collectors.toMap(CollectionDraft::getSlug, draft -> draft, (first, second) -> first));
    }

    private JsonNode draftFor(CollectionDraft draft, JsonNode publishedData) {
        if (draft == null || draft.getPayload() == null || draft.getPayload().equals(publishedData)) {
            return null;
        }
        return draft.getPayload();
    }

    private JsonNode resolveItemDraft(String collectionKey, String slug, String userId, String locale, JsonNode publishedData) {
        return collectionDraftDao
                .findOwnItemDraft(collectionKey, slug, userId, locale)
                .map(CollectionDraft::getPayload)
                .filter(payload -> !payload.equals(publishedData))
                .orElse(null);
    }

    // Henüz kaydedilmemiş yeni item taslağı gerçek bir satır değil, o yüzden `items` yerine
    // `virtualItems` altında duruyor. `data` boş: yazılmış tek şey taslağın kendisi.
    private List<VirtualItemDto> pendingVirtualItems(String collectionKey, String userId, String locale) {
        return collectionDraftDao.findOwnNewDraft(collectionKey, userId, locale)
                .filter(draft -> !isEffectivelyEmpty(draft.getPayload()))
                .map(draft -> List.of(VirtualItemDto.pending(
                        collectionKey, NODES.objectNode(), draft.getPayload(),
                        draft.getLocale(), null, draft.getUpdatedAt())))
                .orElse(null);
    }

    private boolean isEffectivelyEmpty(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return true;
        if (node.isTextual()) return node.asText().isBlank();
        if (node.isBoolean()) return !node.asBoolean();
        if (node.isNumber()) return node.decimalValue().signum() == 0;
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (!isEffectivelyEmpty(item)) return false;
            }
            return true;
        }
        if (node.isObject()) {
            for (JsonNode value : node) {
                if (!isEffectivelyEmpty(value)) return false;
            }
            return true;
        }
        return false;
    }


    private java.util.Optional<CollectionItem> resolveAlias(String collectionKey, String slug, boolean includeArchived) {
        return slugAliasDao.findByCollectionKeyAndSlug(collectionKey, slug)
                .flatMap(alias -> collectionItemDao.findById(alias.getItemId()))
                .filter(item -> includeArchived || !item.isArchived());
    }

    @Override
    @Transactional
    public CollectionItemDto renameSlug(String collectionKey, String slug, RenameSlugRequestDto request,
                                        boolean replaceAlias, String updatedBy) {
        var def = registry.resolve(collectionKey);
        var key = def.key();

        if (!def.slugEditable()) {
            throw new CmsValidationException(CmsMessages.SLUG_NOT_EDITABLE);
        }
        if (providers.containsKey(key)) {
            throw new CmsValidationException(CmsMessages.SLUG_RENAME_NOT_SUPPORTED);
        }

        var currentSlug = SlugNormalizer.normalizeBlockPath(slug);
        var item = requireWritable(key, currentSlug);

        if (item.getVersion() != request.getVersion()) {
            throw new ConcurrencyConflictException(CmsMessages.VERSION_CONFLICT);
        }

        var target = SlugGenerator.slugify(request.getSlug());
        if (target.isBlank()) {
            throw new CmsValidationException(CmsMessages.SLUG_REQUIRED);
        }
        if (target.equals(item.getSlug())) {
            return editable(item);
        }

        collectionItemDao.findByCollectionKeyAndSlug(key, target).ifPresent(holder -> {
            throw new SlugConflictException(CmsMessages.SLUG_TAKEN + target,
                    SlugConflictException.REASON_TAKEN, target);
        });

        slugAliasDao.findByCollectionKeyAndSlug(key, target).ifPresent(alias -> {
            if (alias.getItemId().equals(item.getId())) {
                slugAliasDao.delete(alias);
                return;
            }
            if (!replaceAlias) {
                var holder = collectionItemDao.findById(alias.getItemId());
                throw new SlugConflictException(CmsMessages.SLUG_HELD_BY_ALIAS + target,
                        SlugConflictException.REASON_ALIAS,
                        holder.map(CollectionItem::getSlug).orElse(target));
            }
            slugAliasDao.delete(alias);
        });

        var previousSlug = item.getSlug();

        item.setSlug(target);
        touch(item, updatedBy);
        var saved = collectionItemDao.save(item);

        slugAliasDao.save(CollectionSlugAlias.builder()
                .collectionKey(key)
                .slug(previousSlug)
                .itemId(saved.getId())
                .build());

        collectionDraftDao.findByCollectionKeyAndSlug(key, previousSlug)
                .forEach(draft -> draft.setSlug(target));

        logger.info("Renamed collection item {}/{} to {} by {}", key, previousSlug, target, LogMasks.principal(updatedBy));

        return editable(saved);
    }

    // Çeviri eklerken grup gerçek olmalı ve o dil boş olmalı; yoksa yetim grup ya da
    // aynı dilde iki kayıt oluşur ve TranslationChips ikisini de "kardeş" sanır.
    private void requireOpenTranslationSlot(String collectionKey, UUID translationGroup, String locale) {
        if (translationGroup == null) {
            return;
        }
        var siblings = collectionItemDao.findByCollectionKeyAndTranslationGroupId(collectionKey, translationGroup);
        if (siblings.isEmpty()) {
            throw new CmsValidationException(CmsMessages.TRANSLATION_GROUP_NOT_FOUND + translationGroup);
        }
        var taken = siblings.stream()
                .filter(sibling -> !sibling.isArchived())
                .anyMatch(sibling -> Objects.equals(sibling.getLocale(), locale));
        if (taken) {
            throw new ConcurrencyConflictException(CmsMessages.TRANSLATION_ALREADY_EXISTS + locale);
        }
    }

    private CollectionItemDto editable(CollectionItem item) {
        var dto = toDto(item);
        dto.setTranslations(siblingsOf(item));
        dto.setCanEdit(true);
        return dto;
    }

    @Override
    @Transactional
    public void deleteSlugAlias(String collectionKey, String slug) {
        var key = registry.resolve(collectionKey).key();
        var normalizedSlug = SlugNormalizer.normalizeBlockPath(slug);

        var alias = slugAliasDao.findByCollectionKeyAndSlug(key, normalizedSlug)
                .orElseThrow(() -> new ResourceNotFoundException(CmsMessages.ALIAS_NOT_FOUND + key + "/" + normalizedSlug));

        slugAliasDao.delete(alias);
        logger.info("Dropped slug alias {}/{}", key, normalizedSlug);
    }

    private String resolveUniqueSlug(String collectionKey, String base) {
        var candidate = base;
        int suffix = 2;
        while (collectionItemDao.existsByCollectionKeyAndSlug(collectionKey, candidate)
                || slugAliasDao.existsByCollectionKeyAndSlug(collectionKey, candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private void touch(CollectionItem item, String updatedBy) {
        item.setUpdatedBy(updatedBy);
        item.setVersion(item.getVersion() + 1);
    }

    private CollectionItemDto toDto(CollectionItem item) {
        var dto = new CollectionItemDto(
                item.getId(),
                item.getCollectionKey(),
                item.getSlug(),
                item.getData(),
                item.getVersion(),
                false,
                null);
        dto.setCreatedAt(item.getCreatedAt());
        dto.setUpdatedAt(item.getUpdatedAt());
        dto.setLocale(item.getLocale());
        dto.setTranslationGroupId(item.getTranslationGroupId());
        if (item.isArchived()) {
            dto.setIsArchived(true);
            dto.setArchivedAt(item.getArchivedAt());
        }
        return dto;
    }

    @Override
    @Transactional(readOnly = true)
    public CollectionLookupDto lookup(String collectionKey, String query, List<String> slugs,
                                      String locale, int limit) {
        var def = registry.resolve(collectionKey);
        var window = Math.max(1, Math.min(limit, LOOKUP_MAX_LIMIT));

        // ?slugs= seçili kaydın adını gösterir: aramada çıkmasa da çözülmeli.
        // Karşılığı olmayan slug yanıttan düşer, çağıran onu "bulunamadı" çizer.
        if (slugs != null && !slugs.isEmpty()) {
            var resolved = slugs.stream()
                    .distinct()
                    .filter(slug -> existsBySlug(def.key(), slug))
                    .map(slug -> getBySlug(def.key(), slug, null, locale))
                    .map(item -> new CollectionLookupDto.Item(item.getSlug(), labelOf(def, item)))
                    .toList();

            return new CollectionLookupDto(resolved, resolved.size());
        }

        var needle = query == null || query.isBlank() ? null : query.trim().toLowerCase(Locale.ROOT);
        if (needle != null && providers.containsKey(def.key())) {
            return lookupAcrossProvider(def, needle, locale, window);
        }

        var page = list(def.key(), null, null, null, false, locale, query, 0, window);

        var items = page.getItems().stream()
                .map(item -> new CollectionLookupDto.Item(item.getSlug(), labelOf(def, item)))
                .filter(item -> needle == null || item.label().toLowerCase(Locale.ROOT).contains(needle))
                .toList();

        // Süzgeç bellekte uygulandığında toplam da elde kalandır; aksi halde
        // seçici olmayan bir "daha var" sayısı gösterirdi.
        var total = needle == null ? page.getTotal() : items.size();
        return new CollectionLookupDto(items, total);
    }

    // Sağlayıcıya metin araması iletilmiyor; ilk sayfayı bellekte süzmek 20. personelden
    // sonrasını seçilemez yapıyordu. Sayfalar sonuna kadar taranır — seçici için ucuz.
    private CollectionLookupDto lookupAcrossProvider(CollectionRegistry.CollectionDefinition def,
                                                     String needle, String locale, int window) {
        var matches = new ArrayList<CollectionLookupDto.Item>();
        var offset = 0;
        long total;
        do {
            var page = list(def.key(), null, null, null, false, locale, null, offset, LOOKUP_MAX_LIMIT);
            page.getItems().stream()
                    .map(item -> new CollectionLookupDto.Item(item.getSlug(), labelOf(def, item)))
                    .filter(item -> item.label().toLowerCase(Locale.ROOT).contains(needle))
                    .forEach(matches::add);
            total = page.getTotal();
            offset += LOOKUP_MAX_LIMIT;
        } while (offset < total);
        return new CollectionLookupDto(matches.stream().limit(window).toList(), matches.size());
    }

    private String labelOf(CollectionRegistry.CollectionDefinition def, CollectionItemDto item) {
        var field = def.displayField();
        if (field == null || item.getData() == null) {
            return item.getSlug();
        }

        var value = item.getData().get(field);
        if (value == null || value.isNull()) {
            return item.getSlug();
        }

        var text = value.isTextual() ? value.stringValue() : value.toString();
        return text.isBlank() ? item.getSlug() : text;
    }

    // Varlık önce sorulur: getBySlug'ın fırlattığı ResourceNotFoundException
    // yakalansa bile REQUIRED transaction'ı rollback-only işaretler ve istek
    // UnexpectedRollbackException ile 500 döner.
    private boolean existsBySlug(String collectionKey, String slug) {
        var normalized = SlugNormalizer.normalizeBlockPath(slug);
        var provider = providers.get(collectionKey);

        return provider != null
                ? provider.existsBySlug(normalized)
                : collectionItemDao.findByCollectionKeyAndSlugAndArchivedFalse(collectionKey, normalized).isPresent();
    }
}
