package fu.hl.g64.motorflow.store.dto;

import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** UC-06. The store id never changes; the name stays unique. {@code central} can only be switched on while the chain has no central warehouse. */
public record UpdateStoreRequest(
        @NotBlank(message = "Vui lòng nhập tên cửa hàng.")
        @Size(max = 150, message = "Tên cửa hàng tối đa 150 ký tự.")
        String storeName,

        @NotBlank(message = "Vui lòng nhập địa chỉ.")
        @Size(max = 255, message = "Địa chỉ tối đa 255 ký tự.")
        String address,

        @Pattern(regexp = "^$|^[0-9+ ]{8,20}$", message = "Số điện thoại gồm 8–20 chữ số.")
        String phone,

        @NotNull(message = "Vui lòng chọn trạng thái.")
        StoreStatus status,

        boolean central) {
}
