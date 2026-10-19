package com.matmuh.matmuhsite.business.concretes;

import com.matmuh.matmuhsite.business.abstracts.CmsMediaService;
import com.matmuh.matmuhsite.business.constants.FileMessages;
import com.matmuh.matmuhsite.core.dtos.cms.response.UploadResponseDto;
import com.matmuh.matmuhsite.core.exceptions.BusinessRuleException;
import com.matmuh.matmuhsite.core.helpers.StorageUrlResolver;
import com.matmuh.matmuhsite.core.helpers.UploadValidator;
import com.matmuh.matmuhsite.core.properties.StudentPortalProperties;
import com.matmuh.matmuhsite.core.utilities.preview.DocumentPreviewService;
import com.matmuh.matmuhsite.core.utilities.storage.FolderType;
import com.matmuh.matmuhsite.core.utilities.storage.StorageService;
import com.matmuh.matmuhsite.dataAccess.abstracts.FileDao;
import com.matmuh.matmuhsite.dataAccess.abstracts.ImageDao;
import com.matmuh.matmuhsite.entities.File;
import com.matmuh.matmuhsite.entities.Image;
import com.matmuh.matmuhsite.entities.Media;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

@Service
public class CmsMediaManager implements CmsMediaService {

    private final Logger logger = LoggerFactory.getLogger(CmsMediaManager.class);

    private final StorageService storageService;
    private final StorageUrlResolver storageUrlResolver;
    private final UploadValidator uploadValidator;
    private final DocumentPreviewService documentPreviewService;
    private final FileDao fileDao;
    private final ImageDao imageDao;
    private final StudentPortalProperties studentPortal;

    public CmsMediaManager(StorageService storageService,
                           StorageUrlResolver storageUrlResolver,
                           UploadValidator uploadValidator,
                           DocumentPreviewService documentPreviewService,
                           FileDao fileDao,
                           ImageDao imageDao,
                           StudentPortalProperties studentPortal) {
        this.storageService = storageService;
        this.storageUrlResolver = storageUrlResolver;
        this.uploadValidator = uploadValidator;
        this.documentPreviewService = documentPreviewService;
        this.fileDao = fileDao;
        this.imageDao = imageDao;
        this.studentPortal = studentPortal;
    }

    @Override
    @Transactional
    public UploadResponseDto upload(MultipartFile file, boolean publicAccess) {
        var folderType = folderTypeFor(file, publicAccess);
        uploadValidator.validate(file, folderType, FileMessages.FILE_EMPTY_ERROR, FileMessages.FILE_SIZE_LIMIT);

        try {
            var bytes = file.getBytes();
            var key = storageService.uploadFile(bytes, file.getOriginalFilename(), file.getContentType(), folderType);
            var previewKey = documentPreviewService.createPdfPreview(bytes, file.getOriginalFilename(), folderType);

            record(file, key, previewKey, folderType);
            logger.info("CMS media uploaded: {} (preview: {})", key, previewKey != null);

            return UploadResponseDto.of(storageUrlResolver.urlFor(key), storageUrlResolver.urlFor(previewKey));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }


    private void record(MultipartFile file, String key, String previewKey, FolderType folderType) {
        Media media = folderType == FolderType.IMAGE ? new Image() : new File();
        media.setFileName(file.getOriginalFilename());
        media.setFileType(file.getContentType());
        media.setFileUrl(key);
        media.setFileSize(file.getSize());
        media.setPreviewUrl(previewKey);
        if (media instanceof Image image) {
            imageDao.save(image);
        } else {
            fileDao.save((File) media);
        }
    }

    private FolderType folderTypeFor(MultipartFile file, boolean publicAccess) {
        if (uploadValidator.isAllowed(file, FolderType.IMAGE)) {
            return FolderType.IMAGE;
        }
        if (publicAccess) {
            return FolderType.PUBLIC_FILE;
        }
        // Öğrenci girişi kapalı sitede files/'ı yalnız yöneticiler açabilir; ziyaretçiye
        // görünmeyecek bir eki sessizce kabul etmek yerine reddediyoruz.
        if (!studentPortal.isEnabled()) {
            throw new BusinessRuleException(FileMessages.PRIVATE_ATTACHMENT_NOT_SUPPORTED);
        }
        return FolderType.FILE;
    }
}
