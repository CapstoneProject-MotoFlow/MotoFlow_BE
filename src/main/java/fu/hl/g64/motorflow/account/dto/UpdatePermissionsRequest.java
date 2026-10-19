package fu.hl.g64.motorflow.account.dto;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * UC-09. The whole new state: role, store and the catalog permission. The retail price permission is
 * granted by the Owner (BR-08), so it is not here; it is revoked when the account stops being a Branch Manager.
 * Send {@code releaseAssignedTasks = true} after the user agrees to MSG18.
 */
public record UpdatePermissionsRequest(
        @NotNull(message = "Vui lòng chọn vai trò.")
        Role role,

        Long storeId,

        boolean catalogEdit,

        @Size(max = 255, message = "Lý do tối đa 255 ký tự.")
        String reason,

        boolean releaseAssignedTasks) {
}
