package fu.hl.g64.motorflow.catalog.dto;

import fu.hl.g64.motorflow.catalog.entity.enums.VehicleType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** UC-23, UC-24. Years are optional; when both are given, {@code yearFrom <= yearTo}. */
public record VehicleModelRequest(
        @NotBlank(message = "Vui lòng nhập hãng xe.")
        @Size(max = 100, message = "Hãng xe tối đa 100 ký tự.")
        String manufacturer,

        @NotBlank(message = "Vui lòng nhập tên dòng xe.")
        @Size(max = 100, message = "Tên dòng xe tối đa 100 ký tự.")
        String modelName,

        @NotNull(message = "Vui lòng chọn loại xe.")
        VehicleType vehicleType,

        @Min(value = 1900, message = "Năm không hợp lệ.") @Max(value = 2100, message = "Năm không hợp lệ.")
        Short yearFrom,

        @Min(value = 1900, message = "Năm không hợp lệ.") @Max(value = 2100, message = "Năm không hợp lệ.")
        Short yearTo) {
}
