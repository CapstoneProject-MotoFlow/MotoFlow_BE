package fu.hl.g64.motorflow.inventory.dto;

import jakarta.validation.constraints.Size;

/** UC-50. Blank clears the value. */
public record StorageLocationRequest(
        @Size(max = 50, message = "Khu vực lưu kho tối đa 50 ký tự.")
        String storageArea,

        @Size(max = 50, message = "Vị trí kệ tối đa 50 ký tự.")
        String binLocation) {
}
