package fu.hl.g64.motorflow.operation.service;

import fu.hl.g64.motorflow.operation.dto.AttachmentResponse;
import fu.hl.g64.motorflow.operation.dto.AttachmentUpload;
import fu.hl.g64.motorflow.operation.entity.enums.OwnerType;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Evidence files of business records (table attachment, NFR-S05). The browser uploads a file straight to Cloudinary
 * as a private file, then the feature that owns the record calls {@link #attach} inside its own transaction, so a
 * file is attached only if the business change was saved. Download links are built only by {@link #list}, which the
 * owning feature calls after its own access check, and they expire after a few minutes.
 */
public interface AttachmentService {

    /**
     * Saves the uploaded files as attachments of one record. MSG142 for a file outside the attachment folder of
     * MotoFlow, of another type than JPG, PNG, WEBP or PDF, or larger than 10 MB; MSG143 for a file that is already
     * attached to a record. Nothing is saved when one file is refused.
     */
    void attach(OwnerType ownerType, long ownerId, List<AttachmentUpload> files, long uploadedBy);

    /** The files of one record, oldest first. */
    List<AttachmentResponse> list(OwnerType ownerType, long ownerId);

    /** The files of several records of one type, by record id; a record without files has an empty list. */
    Map<Long, List<AttachmentResponse>> list(OwnerType ownerType, Collection<Long> ownerIds);
}
