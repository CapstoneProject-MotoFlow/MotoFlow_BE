package fu.hl.g64.motorflow.operation.service.impl;

import fu.hl.g64.motorflow.account.entity.enums.AccountStatus;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.operation.dto.NotificationResponse;
import fu.hl.g64.motorflow.operation.entity.Notification;
import fu.hl.g64.motorflow.operation.repository.NotificationRepository;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notifications;
    private final UserAccountRepository users;
    private final Clock clock;

    @Transactional
    @Override
    public void notify(long recipientId, String type, String message, String refType, Long refId) {
        notifications.save(Notification.create(recipientId, type, message, refType, refId, LocalDateTime.now(clock)));
    }

    @Transactional
    @Override
    public void notifyStoreManagers(long storeId, String type, String message, String refType, Long refId) {
        LocalDateTime now = LocalDateTime.now(clock);
        users.findByRoleAndStoreIdAndStatus(Role.BRANCH_MANAGER, storeId, AccountStatus.ACTIVE).forEach(manager ->
                notifications.save(Notification.create(manager.getId(), type, message, refType, refId, now)));
    }

    @Transactional
    @Override
    public void notifyOwners(String type, String message, String refType, Long refId) {
        LocalDateTime now = LocalDateTime.now(clock);
        users.findByRoleAndStatus(Role.OWNER, AccountStatus.ACTIVE).forEach(owner ->
                notifications.save(Notification.create(owner.getId(), type, message, refType, refId, now)));
    }

    // UC-87
    @Transactional(readOnly = true)
    @Override
    public PageResponse<NotificationResponse> list(AuthenticatedUser user, boolean unreadOnly, int page, int size) {
        PageRequest request = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<Notification> found = unreadOnly
                ? notifications.findByRecipientIdAndIsReadFalse(user.id(), request)
                : notifications.findByRecipientId(user.id(), request);
        return PageResponse.of(found, found.getContent().stream().map(NotificationResponse::from).toList());
    }

    // UC-87
    @Transactional
    @Override
    public NotificationResponse markRead(AuthenticatedUser user, long id) {
        Notification notification = notifications.findByIdAndRecipientId(id, user.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG87"));
        notification.markRead(LocalDateTime.now(clock));
        return NotificationResponse.from(notification);
    }

    // UC-87
    @Transactional
    @Override
    public int markAllRead(AuthenticatedUser user) {
        return notifications.markAllRead(user.id(), LocalDateTime.now(clock));
    }
}
