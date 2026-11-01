package fu.hl.g64.motorflow.home.dto;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.operation.entity.Notification;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SCR-03 Home after sign-in (UC-01 step 7). The menu is built from the role and permissions of
 * {@code GET /api/v1/auth/me}. {@code notifications} is null for Admin, who receives none (Permission Matrix);
 * {@code shortcuts} lists every shortcut of the role in a fixed order, zero counts included.
 */
public record HomeResponse(Role role, Long storeId, String storeName, Notifications notifications,
                           List<Shortcut> shortcuts) {

    /** {@code latest} holds the 5 newest unread notifications. */
    public record Notifications(long unread, List<NotificationItem> latest) {
    }

    public record NotificationItem(long id, String type, String message, String refType, Long refId,
                                   LocalDateTime createdAt) {

        public static NotificationItem from(Notification notification) {
            return new NotificationItem(notification.getId(), notification.getNotificationType(),
                    notification.getMessage(), notification.getRefType(), notification.getRefId(),
                    notification.getCreatedAt());
        }
    }

    public record Shortcut(HomeShortcut key, long count) {
    }
}
