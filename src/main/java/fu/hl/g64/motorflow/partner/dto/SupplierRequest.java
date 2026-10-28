package fu.hl.g64.motorflow.partner.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** UC-31, UC-33. {@code supplierCode} is required on create and ignored on update, because it never changes. */
public record SupplierRequest(
        @Pattern(regexp = "^\\s*[A-Za-z0-9._-]{1,20}\\s*$",
                message = "Mã nhà cung cấp dài 1–20 ký tự, chỉ gồm chữ không dấu, số và . _ -")
        String supplierCode,

        @NotBlank(message = "Vui lòng nhập tên nhà cung cấp.")
        @Size(max = 150, message = "Tên nhà cung cấp tối đa 150 ký tự.")
        String supplierName,

        @Size(max = 100, message = "Tên người liên hệ tối đa 100 ký tự.")
        String contactName,

        @Pattern(regexp = "^$|^[0-9+ ]{8,20}$", message = "Số điện thoại gồm 8–20 chữ số.")
        String phone,

        @Email(message = "Email không đúng định dạng.")
        @Size(max = 100, message = "Email tối đa 100 ký tự.")
        String email,

        @Size(max = 255, message = "Địa chỉ tối đa 255 ký tự.")
        String address,

        @Pattern(regexp = "^$|^[0-9]{10}(-?[0-9]{3})?$", message = "Mã số thuế gồm 10 hoặc 13 chữ số.")
        String taxCode) {
}
