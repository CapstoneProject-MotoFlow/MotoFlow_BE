package fu.hl.g64.motorflow.operation.dto;

import fu.hl.g64.motorflow.operation.entity.Notification;

import java.time.LocalDateTime;

/** One notification in the list (UC-87); refType + refId open the related document. */
public record NotificationResponse(long id, String type, String message, String refType, Long refId, boolean read,
                                   LocalDateTime createdAt, LocalDateTime readAt) {

    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(n.getId(), n.getNotificationType(), n.getMessage(), n.getRefType(),
                n.getRefId(), n.isRead(), n.getCreatedAt(), n.getReadAt());
    }
}
