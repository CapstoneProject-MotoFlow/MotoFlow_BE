package fu.hl.g64.motorflow.store.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** UC-05. The new store gets its own name, address and warehouse; its warehouse is never the central one (BR-04). */
public record CopyStoreRequest(
        @NotBlank(message = "Vui lòng nhập tên cửa hàng.")
        @Size(max = 150, message = "Tên cửa hàng tối đa 150 ký tự.")
        String storeName,

        @NotBlank(message = "Vui lòng nhập địa chỉ.")
        @Size(max = 255, message = "Địa chỉ tối đa 255 ký tự.")
        String address,

        @Pattern(regexp = "^$|^[0-9+ ]{8,20}$", message = "Số điện thoại gồm 8–20 chữ số.")
        String phone,

        @NotBlank(message = "Vui lòng nhập tên kho.")
        @Size(max = 150, message = "Tên kho tối đa 150 ký tự.")
        String warehouseName) {
}
