package fu.hl.g64.motorflow.operation.service;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.operation.dto.NotificationResponse;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

/**
 * In-app notifications. Other features call the {@code notify...} methods inside their own transaction, so a
 * notification exists only if the business change that caused it was saved.
 */
public interface NotificationService {

    /** One notification to one user. */
    void notify(long recipientId, String type, String message, String refType, Long refId);

    /** Every active Branch Manager of a store. */
    void notifyStoreManagers(long storeId, String type, String message, String refType, Long refId);

    /** Every active Owner. */
    void notifyOwners(String type, String message, String refType, Long refId);

    /** UC-87: the signed-in user's notifications, newest first; {@code unreadOnly} hides those already read. */
    PageResponse<NotificationResponse> list(AuthenticatedUser user, boolean unreadOnly, int page, int size);

    /** UC-87: marks one of the user's own notifications read (MSG87 if it is not theirs or does not exist). */
    NotificationResponse markRead(AuthenticatedUser user, long id);

    /** UC-87: marks all of the user's notifications read. @return how many were unread */
    int markAllRead(AuthenticatedUser user);
}
