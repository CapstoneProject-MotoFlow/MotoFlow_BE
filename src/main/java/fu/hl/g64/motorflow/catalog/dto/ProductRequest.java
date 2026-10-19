package fu.hl.g64.motorflow.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * UC-15, UC-16. {@code sku} is required on create and ignored on update, because it never changes.
 * Lists replace what the part had; images are URLs the browser has already uploaded (at most 3, first is main).
 */
public record ProductRequest(
        @Pattern(regexp = "^\\s*[A-Za-z0-9._-]{1,50}\\s*$",
                message = "Mã SKU dài 1–50 ký tự, chỉ gồm chữ không dấu, số và . _ -")
        String sku,

        @NotBlank(message = "Vui lòng nhập tên phụ tùng.")
        @Size(max = 200, message = "Tên phụ tùng tối đa 200 ký tự.")
        String partName,

        @NotNull(message = "Vui lòng chọn nhóm hàng.")
        Long categoryId,

        @NotBlank(message = "Vui lòng nhập thương hiệu.")
        @Size(max = 100, message = "Thương hiệu tối đa 100 ký tự.")
        String brand,

        @Size(max = 100, message = "Xuất xứ tối đa 100 ký tự.")
        String countryOfOrigin,

        @NotBlank(message = "Vui lòng nhập đơn vị tính cơ sở.")
        @Size(max = 20, message = "Đơn vị tính tối đa 20 ký tự.")
        String baseUnit,

        @Size(max = 5000, message = "Thông số kỹ thuật tối đa 5000 ký tự.")
        String specifications,

        List<@NotBlank(message = "Mã OEM không được để trống.") @Size(max = 100, message = "Mã OEM tối đa 100 ký tự.") String> oemCodes,

        List<@NotBlank(message = "Mã vạch không được để trống.") @Size(max = 100, message = "Mã vạch tối đa 100 ký tự.") String> barcodes,

        List<@NotNull Long> vehicleModelIds,

        @Size(max = 3, message = "Tối đa 3 ảnh.")
        List<@Pattern(regexp = "^https://\\S{1,492}$", message = "Ảnh phải là đường dẫn https.") String> imageUrls) {

    public ProductRequest {
        oemCodes = oemCodes == null ? List.of() : oemCodes;
        barcodes = barcodes == null ? List.of() : barcodes;
        vehicleModelIds = vehicleModelIds == null ? List.of() : vehicleModelIds;
        imageUrls = imageUrls == null ? List.of() : imageUrls;
    }
}
