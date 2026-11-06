package fu.hl.g64.motorflow.account.service;

import fu.hl.g64.motorflow.account.dto.AccountResponse;
import fu.hl.g64.motorflow.account.dto.BranchManagerResponse;
import fu.hl.g64.motorflow.account.dto.ManagerPermissionsRequest;
import fu.hl.g64.motorflow.account.dto.PermissionHistoryResponse;
import fu.hl.g64.motorflow.account.dto.UpdatePermissionsRequest;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

import java.util.List;

/**
 * UC-09 Update Permissions and UC-12 View Permission History (Admin, BR-01); UC-37 Branch Manager permissions (Owner).
 * The history is kept in audit_log (entity USER_ACCOUNT) with {"permission_code", "value"} in old_data / new_data.
 */
public interface PermissionService {
    /**
     * Sets role, store and the catalog permission in one step; each change is kept in the history.
     * Applies from the user's next action (BR-03), so open sessions stay.
     */
    AccountResponse updatePermissions(AuthenticatedUser admin, long accountId, UpdatePermissionsRequest request);

    /** Newest first, with the name of the Admin (or Owner, BR-08) who made each change. */
    List<PermissionHistoryResponse> permissionHistory(long accountId);

    /** UC-37: Branch Managers (active or locked) of one store or of the whole chain, by store then name. */
    List<BranchManagerResponse> listBranchManagers(Long storeId);

    /**
     * UC-37: the Owner grants or revokes a Branch Manager's catalog and retail price permissions (BR-05, BR-08).
     * Each change is kept in the permission history; it applies from the manager's next action (BR-03).
     */
    BranchManagerResponse updateManagerPermissions(AuthenticatedUser owner, long managerId,
                                                   ManagerPermissionsRequest request);
}
