package fu.hl.g64.motorflow.account.dto;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * UC-08. The system generates the temporary password and returns it once in the response
 * (NFR-S04). {@code storeId} is required for Branch Manager and Staff only.
 */
public record CreateAccountRequest(
        @NotBlank(message = "Vui lòng nhập tên đăng nhập.")
        @Pattern(regexp = "^\\s*[A-Za-z0-9._-]{3,50}\\s*$",
                message = "Tên đăng nhập dài 3–50 ký tự, chỉ gồm chữ không dấu, số và . _ -")
        String username,


        @NotBlank(message = "Vui lòng nhập họ tên.")
        @Size(max = 100, message = "Họ tên tối đa 100 ký tự.")
        String fullName,

        @Pattern(regexp = "^$|^[0-9+ ]{8,20}$", message = "Số điện thoại gồm 8–20 chữ số.")
        String phone,

        @Email(message = "Email không đúng định dạng.")
        @Size(max = 100, message = "Email tối đa 100 ký tự.")
        String email,

        @NotNull(message = "Vui lòng chọn vai trò.")
        Role role,

        Long storeId) {
}
