package fu.hl.g64.motorflow.account.service;

import fu.hl.g64.motorflow.account.dto.AccountResponse;
import fu.hl.g64.motorflow.account.dto.PermissionHistoryResponse;
import fu.hl.g64.motorflow.account.dto.UpdatePermissionsRequest;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

import java.util.List;

/**
 * UC-09 Update Permissions and UC-12 View Permission History. Admin only (BR-01).
 * The history is kept in audit_log (entity USER_ACCOUNT) with {"permission_code", "value"} in old_data / new_data.
 */
public interface PermissionService {
    /**
     * Sets role, store and the catalog permission in one step; each change is kept in the history.
     * Applies from the user's next action (BR-03), so open sessions stay.
     */
    public AccountResponse updatePermissions(AuthenticatedUser admin, long accountId, UpdatePermissionsRequest request);

    /** Newest first, with the name of the Admin (or Owner, BR-08) who made each change. */
    public List<PermissionHistoryResponse> permissionHistory(long accountId);
}
