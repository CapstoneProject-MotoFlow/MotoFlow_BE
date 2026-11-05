package fu.hl.g64.motorflow.account.dto;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * UC-09. The whole new state: role and store. The catalog and retail price permissions are granted by the
 * Owner (BR-01, BR-08, UC-38), so they are not here; both are revoked when the account stops being a Branch Manager.
 * Send {@code releaseAssignedTasks = true} after the user agrees to MSG18.
 */
public record UpdatePermissionsRequest(
        @NotNull(message = "Vui lòng chọn vai trò.")
        Role role,

        Long storeId,

        @Size(max = 255, message = "Lý do tối đa 255 ký tự.")
        String reason,

        boolean releaseAssignedTasks) {
}
