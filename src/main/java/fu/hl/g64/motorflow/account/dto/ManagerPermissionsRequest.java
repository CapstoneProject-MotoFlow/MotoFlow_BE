package fu.hl.g64.motorflow.account.dto;

import jakarta.validation.constraints.Size;

/** UC-38. The Branch Manager's two operation permissions after the change (BR-05, BR-08). */
public record ManagerPermissionsRequest(
        boolean catalogEdit,

        boolean retailPriceEdit,

        @Size(max = 255, message = "Lý do tối đa 255 ký tự.")
        String reason) {
}
