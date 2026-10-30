package fu.hl.g64.motorflow.account.service;

import fu.hl.g64.motorflow.account.dto.AccountResponse;
import fu.hl.g64.motorflow.account.dto.PermissionHistoryResponse;
import fu.hl.g64.motorflow.account.dto.UpdatePermissionsRequest;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.AccountStatus;
import fu.hl.g64.motorflow.account.entity.enums.PermissionCode;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.operation.entity.Notification;
import fu.hl.g64.motorflow.operation.entity.WarehouseTask;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import fu.hl.g64.motorflow.operation.repository.NotificationRepository;
import fu.hl.g64.motorflow.operation.repository.WarehouseTaskRepository;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * UC-09 Update Permissions and UC-12 View Permission History. Admin only (BR-01).
 * The history is kept in audit_log (entity USER_ACCOUNT) with {"permission_code", "value"} in old_data / new_data.
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final UserAccountRepository users;
    private final StoreRepository stores;
    private final AuditLogRepository auditLog;
    private final WarehouseTaskRepository tasks;
    private final NotificationRepository notifications;
    private final Clock clock;

    /** audit_log actions that make up the permission history (BR-01). */
    static final Set<String> HISTORY_ACTIONS = Set.of("ACCOUNT_CREATED", "ROLE_CHANGED", "STORE_CHANGED",
            "PERMISSION_GRANTED", "PERMISSION_REVOKED");

    /**
     * Sets role, store and the catalog permission in one step; each change is kept in the history.
     * Applies from the user's next action (BR-03), so open sessions stay.
     */
    @Transactional
    public AccountResponse updatePermissions(AuthenticatedUser admin, long accountId, UpdatePermissionsRequest request) {
        if (accountId == admin.id()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG15");
        }
        UserAccount account = users.findById(accountId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG48"));
        Role role = request.role();
        Long storeId = request.storeId();
        if (role.requiresStore() != (storeId != null)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG16");
        }
        // A closed store takes no new staff, but people already there may stay.
        Store store = storeId == null ? null : stores.findById(storeId)
                .filter(s -> s.acceptsStaff() || storeId.equals(account.getStoreId()))
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "MSG46"));
        if (request.catalogEdit() && role != Role.BRANCH_MANAGER) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG17");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        String reason = StringUtils.hasText(request.reason()) ? request.reason().strip() : null;
        Role oldRole = account.getRole();
        Long oldStoreId = account.getStoreId();
        boolean storeChanged = !Objects.equals(oldStoreId, storeId);
        if (oldRole == Role.ADMIN && role != Role.ADMIN && account.getStatus() == AccountStatus.ACTIVE) {
            AccountService.requireAnotherActiveAdmin(users, account);
        }
        if (role != oldRole || storeChanged) {
            releaseAssignedTasks(account, request.releaseAssignedTasks(), now);
            if (role != oldRole) {
                auditLog.save(history(accountId, "ROLE_CHANGED", null, oldRole.name(), role.name(), reason, admin.id(), now));
            }
            if (storeChanged) {
                // Values are store ids (the store has no code since V7); null means no store.
                auditLog.save(history(accountId, "STORE_CHANGED", null, Objects.toString(oldStoreId, null),
                        Objects.toString(storeId, null), reason, admin.id(), now));
            }
            account.changeRoleAndStore(role, storeId, now);
        }

        // BR-05, BR-08: operation permissions belong to Branch Managers only; the retail price one is the Owner's to give.
        boolean manager = role == Role.BRANCH_MANAGER;
        setPermission(account, PermissionCode.CATALOG_EDIT, manager && request.catalogEdit(), reason, admin, now);
        setPermission(account, PermissionCode.RETAIL_PRICE_EDIT, manager && account.isCanEditRetailPrice(), reason, admin, now);
        return AccountResponse.from(account, store == null ? null : store.getStoreName(), now);
    }

    /** Newest first, with the name of the Admin (or Owner, BR-08) who made each change. */
    @Transactional(readOnly = true)
    public List<PermissionHistoryResponse> permissionHistory(long accountId) {
        if (!users.existsById(accountId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "MSG48");
        }
        List<AuditLog> entries = auditLog.findByEntityTypeAndEntityIdAndActionInOrderByOccurredAtDescIdDesc(
                "USER_ACCOUNT", accountId, HISTORY_ACTIONS);
        Map<Long, String> names = users.findAllById(entries.stream().map(AuditLog::getActorId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName));
        return entries.stream().map(entry -> PermissionHistoryResponse.from(entry, names.get(entry.getActorId()))).toList();
    }

    static AuditLog accountCreated(UserAccount account, Long changedBy, String reason, LocalDateTime now) {
        return history(account.getId(), "ACCOUNT_CREATED", null, null, account.getRole().name(), reason, changedBy, now);
    }

    private void setPermission(UserAccount account, PermissionCode code, boolean granted, String reason,
                               AuthenticatedUser admin, LocalDateTime now) {
        if (account.setPermission(code, granted, now)) {
            auditLog.save(history(account.getId(), granted ? "PERMISSION_GRANTED" : "PERMISSION_REVOKED", code, null,
                    null, reason, admin.id(), now));
        }
    }

    /** Same shape as the rows V11 moved over from permission_history; null values are left out. */
    private static AuditLog history(Long userId, String action, PermissionCode code, String oldValue, String newValue,
                                    String reason, Long changedBy, LocalDateTime now) {
        return AuditLog.record("USER_ACCOUNT", userId, action, changedBy, reason,
                data(code, oldValue), data(code, newValue), now);
    }

    private static Map<String, Object> data(PermissionCode code, String value) {
        Map<String, Object> data = new HashMap<>();
        if (code != null) {
            data.put("permission_code", code.name());
        }
        if (value != null) {
            data.put("value", value);
        }
        return data;
    }

    /**
     * Tasks without a result cannot follow the user to another role or store: after the Admin agrees (MSG18) they
     * are cancelled, their documents wait for a new assignee, and the Branch Managers of the old store are told.
     */
    private void releaseAssignedTasks(UserAccount account, boolean agreed, LocalDateTime now) {
        List<WarehouseTask> assigned = tasks.findByAssignedToAndStatus(account.getId(), WarehouseTaskStatus.ASSIGNED);
        if (assigned.isEmpty()) {
            return;
        }
        if (!agreed) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG18", assigned.size());
        }
        assigned.forEach(WarehouseTask::cancel);
        String message = "%s đã được đổi vai trò hoặc cửa hàng; %d chứng từ đã giao cho người này đang chờ giao việc lại."
                .formatted(account.getFullName(), assigned.size());
        users.findByRoleAndStoreIdAndStatus(Role.BRANCH_MANAGER, account.getStoreId(), AccountStatus.ACTIVE).stream()
                .filter(manager -> !manager.getId().equals(account.getId()))
                .forEach(manager -> notifications.save(Notification.create(manager.getId(), "TASKS_RELEASED", message,
                        "USER_ACCOUNT", account.getId(), now)));
    }
}
