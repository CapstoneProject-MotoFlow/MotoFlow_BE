package fu.hl.g64.motorflow.account.service;

import fu.hl.g64.motorflow.account.dto.AccountResponse;
import fu.hl.g64.motorflow.account.dto.PermissionHistoryResponse;
import fu.hl.g64.motorflow.account.dto.UpdatePermissionsRequest;
import fu.hl.g64.motorflow.account.entity.PermissionHistory;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.UserPermission;
import fu.hl.g64.motorflow.account.entity.enums.AccountStatus;
import fu.hl.g64.motorflow.account.entity.enums.PermissionCode;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.PermissionHistoryRepository;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.account.repository.UserPermissionRepository;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** UC-09 Update Permissions and UC-12 View Permission History. Admin only (BR-01). */
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final UserAccountRepository users;
    private final StoreRepository stores;
    private final UserPermissionRepository userPermissions;
    private final PermissionHistoryRepository permissionHistory;
    private final WarehouseTaskRepository tasks;
    private final NotificationRepository notifications;
    private final Clock clock;

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
        if (role != oldRole || storeChanged) {
            releaseAssignedTasks(account, request.releaseAssignedTasks(), now);
            if (role != oldRole) {
                permissionHistory.save(PermissionHistory.roleChanged(accountId, oldRole, role, reason, admin.id(), now));
            }
            if (storeChanged) {
                permissionHistory.save(PermissionHistory.storeChanged(accountId, oldStoreId, storeId, reason, admin.id(), now));
            }
            account.changeRoleAndStore(role, storeId, now);
        }

        // BR-05, BR-08: operation permissions belong to Branch Managers only.
        Set<PermissionCode> kept = EnumSet.noneOf(PermissionCode.class);
        for (UserPermission permission : userPermissions.findByUserId(accountId)) {
            PermissionCode code = permission.getPermissionCode();
            if (role == Role.BRANCH_MANAGER && (code != PermissionCode.CATALOG_EDIT || request.catalogEdit())) {
                kept.add(code);
            } else {
                userPermissions.delete(permission);
                permissionHistory.save(PermissionHistory.permissionRevoked(accountId, code, reason, admin.id(), now));
            }
        }
        if (request.catalogEdit() && kept.add(PermissionCode.CATALOG_EDIT)) {
            userPermissions.save(UserPermission.grant(accountId, PermissionCode.CATALOG_EDIT, admin.id(), now));
            permissionHistory.save(PermissionHistory.permissionGranted(accountId, PermissionCode.CATALOG_EDIT, reason,
                    admin.id(), now));
        }
        return AccountResponse.from(account, store == null ? null : store.getStoreName(), kept, now);
    }

    /** Newest first, with the name of the Admin (or Owner, BR-08) who made each change. */
    @Transactional(readOnly = true)
    public List<PermissionHistoryResponse> permissionHistory(long accountId) {
        if (!users.existsById(accountId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "MSG48");
        }
        List<PermissionHistory> entries = permissionHistory.findByUserIdOrderByChangedAtDescIdDesc(accountId);
        Map<Long, String> names = users.findAllById(entries.stream().map(PermissionHistory::getChangedBy).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName));
        return entries.stream().map(entry -> PermissionHistoryResponse.from(entry, names.get(entry.getChangedBy()))).toList();
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
