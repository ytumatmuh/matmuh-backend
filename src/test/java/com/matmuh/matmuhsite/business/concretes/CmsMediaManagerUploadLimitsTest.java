package com.matmuh.matmuhsite.business.concretes;

import com.matmuh.matmuhsite.core.exceptions.BusinessRuleException;
import com.matmuh.matmuhsite.core.exceptions.FileEmptyException;
import com.matmuh.matmuhsite.core.exceptions.FileSizeExceededException;
import com.matmuh.matmuhsite.core.exceptions.UnsupportedFileTypeException;
import com.matmuh.matmuhsite.core.helpers.StorageUrlResolver;
import com.matmuh.matmuhsite.core.helpers.UploadValidator;
import com.matmuh.matmuhsite.core.properties.StudentPortalProperties;
import com.matmuh.matmuhsite.core.properties.UploadProperties;
import com.matmuh.matmuhsite.core.utilities.preview.DocumentPreviewService;
import com.matmuh.matmuhsite.core.utilities.storage.FolderType;
import com.matmuh.matmuhsite.core.utilities.storage.StorageService;
import com.matmuh.matmuhsite.dataAccess.abstracts.FileDao;
import com.matmuh.matmuhsite.dataAccess.abstracts.ImageDao;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CmsMediaManagerUploadLimitsTest {

    private static final int MB = 1024 * 1024;

    private final StorageService storageService = mock(StorageService.class);
    private final StorageUrlResolver urlResolver = mock(StorageUrlResolver.class);
    private final StudentPortalProperties studentPortal = new StudentPortalProperties();
    private final CmsMediaManager manager = new CmsMediaManager(storageService, urlResolver,
            new UploadValidator(new UploadProperties()), mock(DocumentPreviewService.class),
            mock(FileDao.class), mock(ImageDao.class), studentPortal);

    private static MockMultipartFile file(String name, int bytes) {
        return new MockMultipartFile("file", name, "application/octet-stream", new byte[bytes]);
    }

    // CLAUDE.md §5: tür ve boyut tek yerde (UploadValidator), aşım 413 ve MB sınırını söyler.
    // CMS yolu yalnız türe bakıyordu; 50 MB'lık kendi eşiği servlet sınırıyla aynı olduğu
    // için hiç devreye girmiyordu.
    @Test
    void oversizedDocumentIsRejectedWithTheMbLimit() {
        var exception = assertThrows(FileSizeExceededException.class,
                () -> manager.upload(file("buyuk.pdf", 26 * MB), true));

        assertEquals("file.size.exceeded.limit", exception.getMessage());
        assertArrayEquals(new Object[]{25L}, exception.getMessageArguments());
        verify(storageService, never()).uploadFile(any(), anyString(), any(), any());
    }

    @Test
    void imagesUseTheImageLimit() {
        var exception = assertThrows(FileSizeExceededException.class,
                () -> manager.upload(file("buyuk.png", 11 * MB), true));

        assertArrayEquals(new Object[]{10L}, exception.getMessageArguments());
    }

    @Test
    void emptyAndDisallowedFilesAreRejected() {
        assertThrows(FileEmptyException.class, () -> manager.upload(file("bos.pdf", 0), true));
        assertThrows(UnsupportedFileTypeException.class, () -> manager.upload(file("betik.sh", 10), true));
    }

    @Test
    void publicAccessPicksTheAccessClass() {
        studentPortal.setEnabled(true);
        when(storageService.uploadFile(any(), anyString(), any(), any())).thenReturn("public/x.pdf");
        when(urlResolver.urlFor("public/x.pdf")).thenReturn("https://cdn/x.pdf");

        var response = manager.upload(file("form.pdf", 10), true);

        assertEquals("https://cdn/x.pdf", response.getData().getUrl());
        verify(storageService).uploadFile(any(), eq("form.pdf"), any(), eq(FolderType.PUBLIC_FILE));

        manager.upload(file("gizli.pdf", 10), false);
        verify(storageService).uploadFile(any(), eq("gizli.pdf"), any(), eq(FolderType.FILE));
    }

    // Öğrenci girişi kapalı sitede files/'ı ziyaretçi açamaz; giriş isteyen ek kovaya hiç yazılmaz.
    @Test
    void privateAttachmentIsRejectedWithoutTheStudentPortal() {
        var exception = assertThrows(BusinessRuleException.class, () -> manager.upload(file("gizli.pdf", 10), false));

        assertEquals("file.private.attachment.not.supported", exception.getMessage());
        verify(storageService, never()).uploadFile(any(), anyString(), any(), any());
    }

    @Test
    void publicAndImageUploadsStillWorkWithoutTheStudentPortal() {
        manager.upload(file("form.pdf", 10), true);
        verify(storageService).uploadFile(any(), eq("form.pdf"), any(), eq(FolderType.PUBLIC_FILE));

        manager.upload(file("afis.png", 10), false);
        verify(storageService).uploadFile(any(), eq("afis.png"), any(), eq(FolderType.IMAGE));
    }
}
