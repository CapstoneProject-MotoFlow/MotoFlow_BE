package fu.hl.g64.motorflow.catalog.dto;

import fu.hl.g64.motorflow.catalog.entity.enums.VehicleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** UC-19, UC-20. Without {@code parentId} the category is top-level. */
public record PartCategoryRequest(
        @NotBlank(message = "Vui lòng nhập tên nhóm hàng.")
        @Size(max = 100, message = "Tên nhóm hàng tối đa 100 ký tự.")
        String categoryName,

        @NotNull(message = "Vui lòng chọn loại xe.")
        VehicleType vehicleType,

        Long parentId,

        @Size(max = 500, message = "Mô tả tối đa 500 ký tự.")
        String description) {
}
