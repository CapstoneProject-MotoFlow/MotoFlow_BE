package fu.hl.g64.motorflow.partner.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** UC-27, UC-29. Only the name is required, so a profile can be created quickly while taking an order. */
public record CustomerRequest(
        @NotBlank(message = "Vui lòng nhập tên khách hàng.")
        @Size(max = 150, message = "Tên khách hàng tối đa 150 ký tự.")
        String customerName,

        @Pattern(regexp = "^$|^[0-9+ ]{8,20}$", message = "Số điện thoại gồm 8–20 chữ số.")
        String phone,

        @Size(max = 255, message = "Địa chỉ tối đa 255 ký tự.")
        String address,

        @Pattern(regexp = "^$|^[0-9]{10}(-?[0-9]{3})?$", message = "Mã số thuế gồm 10 hoặc 13 chữ số.")
        String taxCode) {
}
