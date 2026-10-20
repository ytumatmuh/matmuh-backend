package com.matmuh.matmuhsite.webAPI.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.matmuh.matmuhsite.business.abstracts.CmsMediaService;
import com.matmuh.matmuhsite.business.abstracts.ContentService;
import com.matmuh.matmuhsite.core.helpers.CmsCacheHeaders;
import com.matmuh.matmuhsite.core.dtos.cms.request.SyncManifestRequestDto;
import com.matmuh.matmuhsite.core.dtos.cms.request.UpdatePageRequestDto;
import com.matmuh.matmuhsite.core.dtos.cms.response.ContentBundleDto;
import com.matmuh.matmuhsite.core.dtos.cms.response.ContentResponseDto;
import com.matmuh.matmuhsite.core.dtos.cms.response.SyncResultDto;
import com.matmuh.matmuhsite.core.dtos.cms.response.UpdatePageResponseDto;
import com.matmuh.matmuhsite.core.dtos.cms.response.UploadResponseDto;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "CMS Content", description = "inscribed CMS sayfa içeriği endpointleri")
@RestController
@RequestMapping("/api/cms")
public class CmsContentController {

    private final ContentService contentService;
    private final CmsMediaService cmsMediaService;


    public CmsContentController(ContentService contentService, CmsMediaService cmsMediaService) {
        this.contentService = contentService;
        this.cmsMediaService = cmsMediaService;
    }

    @Operation(summary = "Public içerik", description = "Yayınlanmış blokları döner (anonim).")
    @GetMapping("/data")
    public ContentResponseDto getData(@RequestParam String slug,
                                     @RequestParam(required = false) String locale,
                                     HttpServletResponse response) {
        CmsCacheHeaders.anonymous(response);
        return contentService.getPublishedBySlug(slug, locale);
    }

    @Operation(summary = "Public içerik (clientKey yolu)",
            description = "inscribed clientKey ile yapılandırıldığında anonim okumanın gittiği yol (SDK 4.4: .../content). "
                    + "Tek site olduğu için clientKey yok sayılır.")
    @GetMapping({"/public/{clientKey}/content", "/public/{clientKey}/data"})
    public ContentResponseDto getPublicData(@PathVariable String clientKey,
                                            @RequestParam String slug,
                                            @RequestParam(required = false) String locale,
                                            HttpServletResponse response) {
        CmsCacheHeaders.anonymous(response);
        return contentService.getPublishedBySlug(slug, locale);
    }

    @Operation(summary = "İçerik",
            description = "Editör (ADMIN) için published + draftValue; anonim çağrıda sadece published döner.")
    @GetMapping("/content")
    public ContentResponseDto getContent(@RequestParam String slug,
                                         @RequestParam(required = false) String locale,
                                         Authentication authentication,
                                         HttpServletResponse response) {
        if (isEditor(authentication)) {
            CmsCacheHeaders.editor(response);
            return contentService.getBySlugForEditor(authentication.getName(), slug, locale);
        }
        CmsCacheHeaders.anonymous(response);
        return contentService.getPublishedBySlug(slug, locale);
    }

    @Operation(summary = "Tüm site içeriği",
            description = "İstenen dildeki bütün sayfaları tek çağrıda { locale, global, pages } olarak döner (SDK 5.0 sunucu okuması). "
                    + "Son segmenti __ ile başlayan slug'lar (__global gibi) rota değil paylaşılan içeriktir ve global altında gelir. "
                    + "pages sitenin rotaları değil, canlı bloğu olan slug'lardır. draftValue yalnız editöre döner.")
    @GetMapping("/content/all")
    public ContentBundleDto getAllContent(@RequestParam(required = false) String locale,
                                          Authentication authentication,
                                          HttpServletResponse response) {
        if (isEditor(authentication)) {
            CmsCacheHeaders.editor(response);
            return contentService.getAllForEditor(authentication.getName(), locale);
        }
        CmsCacheHeaders.anonymous(response);
        return contentService.getAllPublished(locale);
    }

    @Operation(summary = "Tüm site içeriği (clientKey yolu)",
            description = "GET /content/all'un anonim hali. Tek site olduğu için clientKey yok sayılır.")
    @GetMapping("/public/{clientKey}/content/all")
    public ContentBundleDto getPublicAllContent(@PathVariable String clientKey,
                                                @RequestParam(required = false) String locale,
                                                HttpServletResponse response) {
        CmsCacheHeaders.anonymous(response);
        return contentService.getAllPublished(locale);
    }

    @Operation(summary = "Publish", description = "Blok değerlerini yayınlar, draft silinir; version uyuşmazsa 409 (ADMIN).")
    @PutMapping("/content")
    public UpdatePageResponseDto updatePage(@RequestBody @Valid UpdatePageRequestDto request,
                                            @RequestParam(required = false) String locale,
                                            Authentication authentication) {
        return contentService.updatePage(authentication.getName(), request, locale);
    }

    @Operation(summary = "Draft kaydet", description = "Kullanıcı bazlı draft autosave (ADMIN).")
    @PutMapping("/draft")
    public ResponseEntity<Void> saveDraft(@RequestBody @Valid UpdatePageRequestDto request,
                                          @RequestParam(required = false) String locale,
                                          Authentication authentication) {
        contentService.saveDraft(authentication.getName(), request, locale);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Draft sil",
            description = "Kullanıcının bu slug için tuttuğu draft'ı siler. Idempotent: draft yoksa da 204 döner.")
    @DeleteMapping("/draft")
    public ResponseEntity<Void> deleteDraft(@RequestParam String slug,
                                            @RequestParam(required = false) String locale,
                                            Authentication authentication) {
        contentService.deleteDraft(authentication.getName(), slug, locale);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Manifest sync",
            description = "cms-sync CLI manifestini reconcile eder. Sync hiçbir bloğun version'ını değiştirmez. "
                    + "reseed=true, hâlâ ilk sürümünde olan ve o dilde kimsenin taslağında bulunmayan satırları güncel "
                    + "tohuma (defaultValues[dil] ya da defaultValue) yeniden yazar; araya giren bir publish 409 döndürür "
                    + "ve sync'ten hiçbir şey uygulanmaz.")
    @PostMapping("/sync")
    public SyncResultDto sync(@RequestBody List<@Valid SyncManifestRequestDto> manifests,
                              @RequestParam(required = false) List<String> locales,
                              @RequestParam(required = false, defaultValue = "false") boolean reseed) {
        return contentService.sync(manifests, locales, reseed);
    }

    @Operation(summary = "Medya yükle", description = "CMS görseli veya belge eki yükler. Görseller images/ (CDN) altına gider. Belgeler publicAccess=true ise "
                    + "giriş istemeyen public/ altına, false ise giriş isteyen files/ altına yazılır; öğrenci girişi kapalı sitede "
                    + "false 400 döner. Ofis belgelerinde PDF önizlemesi "
                    + "üretilirse yanıt {data:{url, previewUrl}} taşır (ADMIN/EDITOR).")
    @PostMapping("/media")
    public UploadResponseDto upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam(required = false, defaultValue = "true") boolean publicAccess) {
        return cmsMediaService.upload(file, publicAccess);
    }

    private boolean isEditor(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority())
                        || "ROLE_EDITOR".equals(authority.getAuthority()));
    }
}
