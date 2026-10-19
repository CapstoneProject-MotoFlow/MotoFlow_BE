package fu.hl.g64.motorflow.store.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** UC-04. The store and its only warehouse are created together (BR-04); the warehouse uses the store's address and phone. */
public record CreateStoreRequest(
        @NotBlank(message = "Vui lòng nhập mã cửa hàng.")
        @Pattern(regexp = "^\s*[A-Za-z0-9_-]{2,20}\s*$",
                message = "Mã cửa hàng dài 2–20 ký tự, chỉ gồm chữ không dấu, số và _ -")
        String storeCode,

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
        String warehouseName,

        boolean central) {
}
