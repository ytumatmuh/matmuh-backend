package com.matmuh.matmuhsite.business.concretes;

import com.matmuh.matmuhsite.business.abstracts.ContentService;
import com.matmuh.matmuhsite.business.constants.CmsMessages;
import com.matmuh.matmuhsite.core.dtos.cms.request.SyncManifestRequestDto;
import com.matmuh.matmuhsite.core.dtos.cms.request.UpdatePageRequestDto;
import com.matmuh.matmuhsite.core.dtos.cms.response.BlockDto;
import com.matmuh.matmuhsite.core.dtos.cms.response.ContentBundleDto;
import com.matmuh.matmuhsite.core.dtos.cms.response.ContentResponseDto;
import com.matmuh.matmuhsite.core.dtos.cms.response.SyncResultDto;
import com.matmuh.matmuhsite.core.dtos.cms.response.UpdatePageResponseDto;
import com.matmuh.matmuhsite.core.exceptions.CmsValidationException;
import com.matmuh.matmuhsite.core.exceptions.ConcurrencyConflictException;
import com.matmuh.matmuhsite.core.helpers.CmsLocaleResolver;
import com.matmuh.matmuhsite.core.helpers.FileUrlRule;
import com.matmuh.matmuhsite.core.helpers.LogMasks;
import com.matmuh.matmuhsite.core.helpers.SlugNormalizer;
import com.matmuh.matmuhsite.dataAccess.abstracts.cms.ContentBlockDao;
import com.matmuh.matmuhsite.dataAccess.abstracts.cms.ContentDraftDao;
import com.matmuh.matmuhsite.entities.cms.BlockType;
import com.matmuh.matmuhsite.entities.cms.ContentBlock;
import com.matmuh.matmuhsite.entities.cms.ContentDraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ContentManager implements ContentService {

    // Son segmenti bununla başlayan slug bir rota değil, her sayfanın paylaştığı içerik (__global gibi).
    private static final String GLOBAL_SLUG_PREFIX = "__";

    private final Logger logger = LoggerFactory.getLogger(ContentManager.class);

    private final ContentBlockDao contentBlockDao;
    private final ContentDraftDao contentDraftDao;
    private final ObjectMapper objectMapper;
    private final CmsLocaleResolver localeResolver;

    public ContentManager(ContentBlockDao contentBlockDao,
                          ContentDraftDao contentDraftDao,
                          ObjectMapper objectMapper,
                          CmsLocaleResolver localeResolver) {
        this.contentBlockDao = contentBlockDao;
        this.contentDraftDao = contentDraftDao;
        this.objectMapper = objectMapper;
        this.localeResolver = localeResolver;
    }


    @Override
    @Transactional(readOnly = true)
    public ContentResponseDto getPublishedBySlug(String slug, String locale) {
        var normalizedSlug = SlugNormalizer.normalizeSlug(slug);
        var resolved = localeResolver.resolveForRead(locale);
        logger.debug("Fetching published content for slug: {} locale: {}", normalizedSlug, resolved);

        var blocks = contentBlockDao.findPublished(normalizedSlug, resolved);

        return new ContentResponseDto(normalizedSlug, resolved, toBlockDtos(blocks));
    }

    @Override
    @Transactional(readOnly = true)
    public ContentResponseDto getBySlugForEditor(String userId, String slug, String locale) {
        var normalizedSlug = SlugNormalizer.normalizeSlug(slug);
        var resolved = localeResolver.resolveForRead(locale);
        logger.debug("Fetching editor content for slug: {} locale: {} user: {}", normalizedSlug, resolved, LogMasks.principal(userId));

        var blocks = contentBlockDao.findPublished(normalizedSlug, resolved);
        var blockDtos = toBlockDtos(blocks);

        contentDraftDao.findOwn(normalizedSlug, userId, resolved).ifPresent(draft -> {
            var draftValues = parseDraftPayload(draft.getPayload());
            applyDraft(blockDtos, draftValues);
            logger.debug("Resolved draft with {} block(s) for slug: {}", draftValues.size(), normalizedSlug);
        });

        return new ContentResponseDto(normalizedSlug, resolved, blockDtos);
    }

    @Override
    @Transactional(readOnly = true)
    public ContentBundleDto getAllPublished(String locale) {
        var resolved = localeResolver.resolveForRead(locale);
        logger.debug("Fetching published content bundle for locale: {}", resolved);

        return toBundle(resolved, contentBlockDao.findPublishedByLocale(resolved), Map.of());
    }

    @Override
    @Transactional(readOnly = true)
    public ContentBundleDto getAllForEditor(String userId, String locale) {
        var resolved = localeResolver.resolveForRead(locale);
        logger.debug("Fetching editor content bundle for locale: {} user: {}", resolved, LogMasks.principal(userId));

        var draftsBySlug = contentDraftDao.findAllOwn(userId, resolved).stream()
                .collect(Collectors.toMap(ContentDraft::getSlug, draft -> parseDraftPayload(draft.getPayload())));

        return toBundle(resolved, contentBlockDao.findPublishedByLocale(resolved), draftsBySlug);
    }

    // `pages` sitenin rotaları değil, canlı bloğu olan slug'lar: blokları arşivlenmiş bir sayfa
    // hiç görünmez. Paylaşılan bloklar bu yüzden sayfalara katılmıyor, ayrı `global` altında dönüyor.
    private ContentBundleDto toBundle(String locale, List<ContentBlock> blocks,
                                      Map<String, Map<String, JsonNode>> draftsBySlug) {
        var blocksBySlug = blocks.stream()
                .collect(Collectors.groupingBy(ContentBlock::getSlug, LinkedHashMap::new, Collectors.toList()));

        var global = new ArrayList<ContentBundleDto.ContentPageDto>();
        var pages = new ArrayList<ContentBundleDto.ContentPageDto>();

        blocksBySlug.forEach((slug, pageBlocks) -> {
            var blockDtos = toBlockDtos(pageBlocks);
            applyDraft(blockDtos, draftsBySlug.getOrDefault(slug, Map.of()));
            (isGlobalSlug(slug) ? global : pages).add(new ContentBundleDto.ContentPageDto(slug, blockDtos));
        });

        return new ContentBundleDto(locale, global, pages);
    }

    private boolean isGlobalSlug(String slug) {
        return slug.substring(slug.lastIndexOf('/') + 1).startsWith(GLOBAL_SLUG_PREFIX);
    }

    private void applyDraft(List<BlockDto> blockDtos, Map<String, JsonNode> draftValues) {
        for (BlockDto dto : blockDtos) {
            var draftValue = draftValues.get(dto.getBlockPath());
            if (draftValue != null && !draftValue.equals(dto.getValue())) {
                dto.setDraftValue(draftValue);
            }
        }
    }


    @Override
    @Transactional
    public UpdatePageResponseDto updatePage(String userId, UpdatePageRequestDto request, String locale) {
        var normalizedSlug = SlugNormalizer.normalizeSlug(request.getSlug());
        var resolved = localeResolver.requireForWrite(locale);
        logger.info("Publishing {} block(s) for slug: {} by user: {}",
                request.getBlocks().size(), normalizedSlug, LogMasks.principal(userId));

        var paths = request.getBlocks().stream()
                .map(update -> SlugNormalizer.normalizeBlockPath(update.getBlockPath()))
                .toList();

        var blocksByPath = contentBlockDao
                .findForUpdate(normalizedSlug, paths, resolved)
                .stream()
                .collect(Collectors.toMap(ContentBlock::getBlockPath, Function.identity()));

        record Pending(ContentBlock block, JsonNode value) {}

        int unchanged = 0;
        var pending = new ArrayList<Pending>();
        var conflicts = new ArrayList<ConcurrencyConflictException.BlockConflict>();
        var invalid = new ArrayList<String>();

        for (var update : request.getBlocks()) {
            var blockPath = SlugNormalizer.normalizeBlockPath(update.getBlockPath());
            var block = blocksByPath.get(blockPath);
            if (block == null) {
                logger.warn("Ignoring unknown blockPath on publish: {}.{}", normalizedSlug, blockPath);
                continue;
            }

            if (update.getVersion() != null && update.getVersion() != block.getVersion()) {
                conflicts.add(new ConcurrencyConflictException.BlockConflict(
                        blockPath, block.getVersion(), update.getVersion()));
                continue;
            }

            if (Objects.equals(block.getValue(), update.getValue())) {
                unchanged++;
                continue;
            }

            if (block.getBlockType() == BlockType.FILE && !FileUrlRule.accepts(update.getValue())) {
                invalid.add("Block '" + blockPath + "': 'url' " + FileUrlRule.EXPECTATION + ".");
                continue;
            }

            pending.add(new Pending(block, update.getValue()));
        }

        if (!invalid.isEmpty()) {
            throw new CmsValidationException(invalid);
        }

        // Tek 409 bütün bayat blokları sayar; SDK yalnız listelenen kartları çakışma
        // durumuna alır, ilk uyuşmazlıkta kesmek kalanları gizlerdi.
        if (!conflicts.isEmpty()) {
            throw new ConcurrencyConflictException(CmsMessages.VERSION_CONFLICT, conflicts);
        }

        var toSave = new ArrayList<ContentBlock>();
        for (var change : pending) {
            change.block().setValue(change.value());
            touch(change.block(), userId);
            toSave.add(change.block());
        }
        int updated = toSave.size();

        contentBlockDao.saveAll(toSave);
        contentDraftDao.deleteOwn(normalizedSlug, userId, resolved);

        return new UpdatePageResponseDto(updated, unchanged);
    }

    @Override
    @Transactional
    public void saveDraft(String userId, UpdatePageRequestDto request, String locale) {
        var normalizedSlug = SlugNormalizer.normalizeSlug(request.getSlug());
        var resolved = localeResolver.requireForWrite(locale);
        logger.info("Saving draft for slug: {} locale: {} user: {}", normalizedSlug, resolved, LogMasks.principal(userId));

        var draft = contentDraftDao.findOwn(normalizedSlug, userId, resolved)
                .orElseGet(() -> ContentDraft.builder()
                        .slug(normalizedSlug)
                        .userId(userId)
                        .locale(resolved)
                        .build());

        draft.setPayload(mergeDraftPayload(draft.getPayload(), request.getBlocks()));
        contentDraftDao.save(draft);
    }

    // SDK autosave'i yalnız son kayıttan beri değişen blokları yollar; gövdeyi olduğu gibi
    // yazmak daha önce taslağa girmiş diğer blokları sessizce düşürüyordu.
    private JsonNode mergeDraftPayload(JsonNode existing, List<UpdatePageRequestDto.BlockUpdateDto> blocks) {
        var merged = new LinkedHashMap<String, JsonNode>();
        if (existing != null && existing.isArray()) {
            for (JsonNode node : existing) {
                var path = node.path("blockPath").asString(null);
                if (path != null && !path.isBlank()) {
                    merged.put(SlugNormalizer.normalizeBlockPath(path), node);
                }
            }
        }
        for (var block : blocks) {
            var path = SlugNormalizer.normalizeBlockPath(block.getBlockPath());
            ObjectNode node = objectMapper.valueToTree(block);
            node.put("blockPath", path);
            merged.put(path, node);
        }
        var payload = objectMapper.createArrayNode();
        merged.values().forEach(payload::add);
        return payload;
    }

    @Override
    @Transactional
    public void deleteDraft(String userId, String slug, String locale) {
        var normalizedSlug = SlugNormalizer.normalizeSlug(slug);
        var resolved = localeResolver.requireForWrite(locale);
        logger.info("Deleting draft for slug: {} locale: {} user: {}", normalizedSlug, resolved, LogMasks.principal(userId));

        contentDraftDao.deleteOwn(normalizedSlug, userId, resolved);
    }


    @Override
    @Transactional
    public SyncResultDto sync(List<SyncManifestRequestDto> manifests, List<String> locales, boolean reseed) {
        rejectUnsafeFileSeeds(manifests);

        var declared = localeResolver.replaceDeclared(locales);
        logger.info("Sync started with {} manifest(s), locales={}, reseed={}", manifests.size(), declared, reseed);


        var lanes = declared.isEmpty() ? Collections.<String>singletonList(null) : declared;

        var allBlocks = new ArrayList<>(contentBlockDao.findAll());
        adoptLegacyRows(allBlocks, declared);

        var blocksByKey = allBlocks.stream()
                .collect(Collectors.toMap(
                        b -> key(b.getSlug(), b.getBlockPath(), b.getLocale()),
                        Function.identity()));

        // Aşağıdaki döngü satırları değiştirmeden önce çalışmalı: sürüm şartlı UPDATE'ten önceki
        // otomatik flush eski değerli bir satırı yazıp araya giren publish'i ezmesin.
        var reseededIds = reseedUntouched(manifests, lanes, blocksByKey, reseed);

        var seenKeys = new HashSet<String>();
        var manifestSlugs = new HashSet<String>();
        var toSave = new ArrayList<ContentBlock>();
        var results = new ArrayList<SyncResultDto.SyncSlugResultDto>();

        for (var manifest : manifests) {
            var manifestSlug = SlugNormalizer.normalizeSlug(manifest.getSlug());
            manifestSlugs.add(manifestSlug);

            var manifestBlocks = manifest.getBlocks() == null
                    ? List.<SyncManifestRequestDto.ManifestBlockDto>of()
                    : manifest.getBlocks();

            int created = 0, restored = 0, unchanged = 0, reseeded = 0;

            int index = 0;
            for (var mb : manifestBlocks) {
                var blockPath = SlugNormalizer.normalizeBlockPath(mb.getBlockPath());
                var sortOrder = mb.getSortOrder() == null ? index : mb.getSortOrder();
                index++;


                for (var lane : lanes) {
                    var k = key(manifestSlug, blockPath, lane);
                    seenKeys.add(k);

                    var existing = blocksByKey.get(k);

                    if (existing == null) {

                        var block = ContentBlock.builder()
                                .slug(manifestSlug)
                                .blockPath(blockPath)
                                .locale(lane)
                                .blockType(mb.getBlockType())
                                .value(seed(mb.defaultFor(lane)))
                                .itemSchema(mb.getItemSchema())
                                .sortOrder(sortOrder)
                                .updatedBy(CmsMessages.SYNCED_BY_DEPLOY_PIPELINE)
                                .build();
                        toSave.add(block);
                        created++;
                        continue;
                    }

                    boolean changed = false;

                    if (existing.isArchived()) {
                        existing.setArchived(false);
                        existing.setArchivedAt(null);
                        changed = true;
                        restored++;
                    }

                    if (existing.getBlockType() != mb.getBlockType()
                            || existing.getSortOrder() != sortOrder
                            || !Objects.equals(existing.getItemSchema(), mb.getItemSchema())) {
                        existing.setBlockType(mb.getBlockType());
                        existing.setSortOrder(sortOrder);
                        existing.setItemSchema(mb.getItemSchema());
                        changed = true;
                    }

                    if (reseededIds.contains(existing.getId())) {
                        changed = true;
                        reseeded++;
                    }

                    // Sürüme dokunulmuyor: onu yalnız publish ilerletir. Aksi halde sayfası açık
                    // editörün bir sonraki publish'i, deploy yüzünden 409 alırdı.
                    if (changed) {
                        existing.setUpdatedBy(CmsMessages.SYNCED_BY_DEPLOY_PIPELINE);
                        toSave.add(existing);
                    } else {
                        unchanged++;
                    }
                }
            }

            results.add(new SyncResultDto.SyncSlugResultDto(manifestSlug, created, 0, unchanged, restored, reseeded));
        }

        var deletedBySlug = new HashMap<String, Integer>();
        var prunedSlugs = new TreeSet<String>();

        for (var block : allBlocks) {
            if (seenKeys.contains(key(block.getSlug(), block.getBlockPath(), block.getLocale()))) continue;
            if (block.isArchived()) continue;

            block.setArchived(true);
            block.setArchivedAt(Instant.now());
            block.setUpdatedBy(CmsMessages.SYNCED_BY_DEPLOY_PIPELINE);
            toSave.add(block);

            deletedBySlug.merge(block.getSlug(), 1, Integer::sum);
            if (!manifestSlugs.contains(block.getSlug())) {
                prunedSlugs.add(block.getSlug());
            }
        }

        for (var result : results) {
            result.setDeleted(deletedBySlug.getOrDefault(result.getSlug(), 0));
        }
        for (var entry : deletedBySlug.entrySet()) {
            if (!manifestSlugs.contains(entry.getKey())) {
                results.add(new SyncResultDto.SyncSlugResultDto(entry.getKey(), 0, entry.getValue(), 0, 0));
            }
        }

        contentBlockDao.saveAll(toSave);

        logger.info("Sync finished: {} slug result(s), {} pruned slug(s)", results.size(), prunedSlugs.size());

        return new SyncResultDto(results, new ArrayList<>(prunedSlugs));
    }


    private void rejectUnsafeFileSeeds(List<SyncManifestRequestDto> manifests) {
        var errors = new ArrayList<String>();
        for (var manifest : manifests) {
            if (manifest.getBlocks() == null) continue;
            for (var mb : manifest.getBlocks()) {
                if (mb.getBlockType() != BlockType.FILE) continue;
                var unsafeSeed = findUnsafeFileSeed(mb);
                if (unsafeSeed != null) {
                    errors.add("Block '" + manifest.getSlug() + "/" + mb.getBlockPath() + "': '" + unsafeSeed + ".url' "
                            + FileUrlRule.EXPECTATION + ".");
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new CmsValidationException(errors);
        }
    }

    private String findUnsafeFileSeed(SyncManifestRequestDto.ManifestBlockDto mb) {
        if (!FileUrlRule.accepts(mb.getDefaultValue())) {
            return "defaultValue";
        }
        if (mb.getDefaultValues() == null) {
            return null;
        }
        return mb.getDefaultValues().entrySet().stream()
                .filter(seed -> !FileUrlRule.accepts(seed.getValue()))
                .map(seed -> "defaultValues." + seed.getKey())
                .findFirst()
                .orElse(null);
    }

    // İki kural bir satırı tohumuna geri yazar. Her sync'te: dile özgü tohum sonradan eklendiyse
    // hâlâ dilsiz tohumu taşıyan satır onu alır. ?reseed=true ile ayrıca: kimsenin yayınlamadığı
    // (sürüm 1) ve o dilde kimsenin taslağında olmayan her satır. Sürüm korunur ki sonraki bir
    // reseed onu yine bulabilsin.
    private Set<UUID> reseedUntouched(List<SyncManifestRequestDto> manifests, List<String> lanes,
                                      Map<String, ContentBlock> blocksByKey, boolean reseed) {
        var drafted = reseed ? draftedKeys() : Set.<String>of();
        var reseeded = new HashSet<UUID>();

        for (var manifest : manifests) {
            if (manifest.getBlocks() == null) continue;
            var slug = SlugNormalizer.normalizeSlug(manifest.getSlug());

            for (var mb : manifest.getBlocks()) {
                var blockPath = SlugNormalizer.normalizeBlockPath(mb.getBlockPath());

                for (var lane : lanes) {
                    var k = key(slug, blockPath, lane);
                    var block = blocksByKey.get(k);
                    var target = seed(mb.defaultFor(lane));
                    if (block == null || Objects.equals(block.getValue(), target)) continue;

                    var adoptsLocaleSeed = mb.hasLocaleDefault(lane) && isUntouchedSeed(block, mb);
                    var neverPublished = reseed && block.getVersion() == 1 && !drafted.contains(k);
                    if (!adoptsLocaleSeed && !neverPublished) continue;

                    if (contentBlockDao.reseed(block.getId(), block.getVersion(), target,
                            CmsMessages.SYNCED_BY_DEPLOY_PIPELINE) == 0) {
                        throw new ConcurrencyConflictException(CmsMessages.RESEED_CONFLICT + slug + "/" + blockPath);
                    }
                    block.setValue(target);
                    reseeded.add(block.getId());
                }
            }
        }
        return reseeded;
    }

    private Set<String> draftedKeys() {
        var keys = new HashSet<String>();
        for (var draft : contentDraftDao.findAll()) {
            for (var blockPath : parseDraftPayload(draft.getPayload()).keySet()) {
                keys.add(key(draft.getSlug(), blockPath, draft.getLocale()));
            }
        }
        return keys;
    }

    private void adoptLegacyRows(List<ContentBlock> blocks, List<String> declared) {
        if (declared.isEmpty()) {
            return;
        }

        var first = declared.get(0);
        var taken = blocks.stream()
                .filter(block -> first.equals(block.getLocale()))
                .map(block -> key(block.getSlug(), block.getBlockPath(), first))
                .collect(Collectors.toSet());

        var adopted = new ArrayList<ContentBlock>();
        for (var block : blocks) {
            if (block.getLocale() != null) continue;
            if (taken.contains(key(block.getSlug(), block.getBlockPath(), first))) continue;

            block.setLocale(first);
            adopted.add(block);
        }

        if (!adopted.isEmpty()) {
            contentBlockDao.saveAll(adopted);
            logger.info("Adopted {} pre-localization block(s) into locale {}", adopted.size(), first);
        }
    }


    private void touch(ContentBlock block, String updatedBy) {
        block.setUpdatedBy(updatedBy);
        block.setVersion(block.getVersion() + 1);
    }

    private List<BlockDto> toBlockDtos(List<ContentBlock> blocks) {
        return blocks.stream()
                .map(b -> new BlockDto(
                        b.getBlockPath(),
                        b.getBlockType(),
                        b.getValue(),
                        b.getSortOrder(),
                        b.getVersion()))
                .collect(Collectors.toList());
    }

    private Map<String, JsonNode> parseDraftPayload(JsonNode payload) {
        var map = new HashMap<String, JsonNode>();
        if (payload != null && payload.isArray()) {
            for (JsonNode node : payload) {
                var path = node.path("blockPath").asString(null);
                if (path != null && !path.isBlank() && node.has("value")) {
                    map.put(SlugNormalizer.normalizeBlockPath(path), node.get("value"));
                }
            }
        }
        return map;
    }

    private JsonNode seed(JsonNode value) {
        return value == null ? objectMapper.nullNode() : value;
    }

    // Değer hâlâ dilsiz tohuma eşitse editör dokunmamıştır; yalnız o satır dile özgü
    // tohumu alır. Düzenlenmiş bir satır sync ile asla ezilmez.
    private boolean isUntouchedSeed(ContentBlock existing, SyncManifestRequestDto.ManifestBlockDto mb) {
        return Objects.equals(existing.getValue(), seed(mb.getDefaultValue()));
    }

    private String key(String slug, String blockPath, String locale) {
        return slug + "\u0000" + blockPath + "\u0000" + (locale == null ? "" : locale);
    }
}
