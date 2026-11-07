package fu.hl.g64.motorflow.operation.entity;

import fu.hl.g64.motorflow.operation.entity.enums.OwnerType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Photo or document attached to a business record as evidence (NFR External Interfaces, NFR-S07). */
@Entity
@Table(name = "attachment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 40)
    private OwnerType ownerType;

    @Column(name = "owner_id", nullable = false)
    private long ownerId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "storage_key", nullable = false, unique = true, length = 500)
    private String storageKey;

    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    /** A file the browser already uploaded to Cloudinary, attached to one record. */
    public static Attachment create(OwnerType ownerType, long ownerId, String fileName, String contentType,
                                    long fileSize, String storageKey, long uploadedBy, LocalDateTime now) {
        Attachment attachment = new Attachment();
        attachment.ownerType = ownerType;
        attachment.ownerId = ownerId;
        attachment.fileName = fileName;
        attachment.contentType = contentType;
        attachment.fileSize = fileSize;
        attachment.storageKey = storageKey;
        attachment.uploadedBy = uploadedBy;
        attachment.uploadedAt = now;
        return attachment;
    }
}
