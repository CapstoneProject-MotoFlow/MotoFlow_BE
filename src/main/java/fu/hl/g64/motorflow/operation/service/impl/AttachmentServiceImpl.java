package fu.hl.g64.motorflow.operation.service.impl;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.operation.dto.AttachmentResponse;
import fu.hl.g64.motorflow.operation.dto.AttachmentUpload;
import fu.hl.g64.motorflow.operation.entity.Attachment;
import fu.hl.g64.motorflow.operation.entity.enums.OwnerType;
import fu.hl.g64.motorflow.operation.repository.AttachmentRepository;
import fu.hl.g64.motorflow.operation.service.AttachmentService;
import fu.hl.g64.motorflow.upload.config.CloudinaryProperties;
import fu.hl.g64.motorflow.upload.dto.UploadPurpose;
import fu.hl.g64.motorflow.upload.service.CloudinaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AttachmentServiceImpl implements AttachmentService {

    /** Cloudinary's free plan stops images at 10 MB, so a larger file cannot have been uploaded. */
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;

    /** Content types the ATTACHMENT upload signature lets through, with the format Cloudinary stores them in. */
    private static final Map<String, String> FORMATS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "application/pdf", "pdf");

    private final AttachmentRepository attachments;
    private final UserAccountRepository users;
    private final CloudinaryService cloudinary;
    private final CloudinaryProperties cloudinaryProperties;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public void attach(OwnerType ownerType, long ownerId, List<AttachmentUpload> files, long uploadedBy) {
        if (files == null || files.isEmpty()) {
            return;
        }
        String folder = UploadPurpose.ATTACHMENT.folder() + "/";
        Set<String> keys = new HashSet<>();
        for (AttachmentUpload file : files) {
            String key = file.storageKey().strip();
            if (!key.startsWith(folder) || key.length() == folder.length() || format(file.contentType()) == null
                    || file.fileSize() > MAX_FILE_SIZE) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG142");
            }
            if (!keys.add(key)) {
                throw new BusinessException(HttpStatus.CONFLICT, "MSG143", file.fileName().strip());
            }
        }
        attachments.findByStorageKeyIn(keys).stream().findFirst().ifPresent(taken -> {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG143", taken.getFileName());
        });
        LocalDateTime now = LocalDateTime.now(clock);
        attachments.saveAll(files.stream().map(file -> Attachment.create(ownerType, ownerId, file.fileName().strip(),
                file.contentType().strip().toLowerCase(Locale.ROOT), file.fileSize(), file.storageKey().strip(),
                uploadedBy, now)).toList());
    }

    @Transactional(readOnly = true)
    @Override
    public List<AttachmentResponse> list(OwnerType ownerType, long ownerId) {
        return list(ownerType, List.of(ownerId)).getOrDefault(ownerId, List.of());
    }

    @Transactional(readOnly = true)
    @Override
    public Map<Long, List<AttachmentResponse>> list(OwnerType ownerType, Collection<Long> ownerIds) {
        Map<Long, List<AttachmentResponse>> byOwner = new HashMap<>();
        if (ownerIds.isEmpty()) {
            return byOwner;
        }
        List<Attachment> found = attachments.findByOwnerTypeAndOwnerIdInOrderByUploadedAtAscIdAsc(ownerType, ownerIds);
        Set<Long> uploaders = found.stream().map(Attachment::getUploadedBy).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> names = users.findAllById(uploaders).stream()
                .collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName));
        boolean links = cloudinaryProperties.configured();
        for (Attachment file : found) {
            String format = format(file.getContentType());
            String url = links && format != null ? cloudinary.privateDownloadUrl(file.getStorageKey(), format) : null;
            byOwner.computeIfAbsent(file.getOwnerId(), key -> new ArrayList<>()).add(new AttachmentResponse(
                    file.getId(), file.getFileName(), file.getContentType(), file.getFileSize(), file.getUploadedBy(),
                    names.get(file.getUploadedBy()), file.getUploadedAt(), url));
        }
        return byOwner;
    }

    private static String format(String contentType) {
        return contentType == null ? null : FORMATS.get(contentType.strip().toLowerCase(Locale.ROOT));
    }
}
