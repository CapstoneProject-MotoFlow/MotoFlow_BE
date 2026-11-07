package fu.hl.g64.motorflow.operation.dto;

import java.time.LocalDateTime;

/**
 * One file attached to a record. {@code downloadUrl} opens the original and stops working after a few minutes
 * (motoflow.cloudinary.download-url-ttl); it is null while uploads are off (no Cloudinary account configured).
 */
public record AttachmentResponse(long id, String fileName, String contentType, long fileSize, Long uploadedById,
                                 String uploadedByName, LocalDateTime uploadedAt, String downloadUrl) {
}
