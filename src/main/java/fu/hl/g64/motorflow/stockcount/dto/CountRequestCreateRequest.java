package fu.hl.g64.motorflow.stockcount.dto;

import fu.hl.g64.motorflow.inventory.entity.enums.ScopeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * UC-105. {@code productIds}: at least one, no repeats, for PRODUCTS only. {@code scopeNote}: the storage area, required
 * for AREA.
 */
public record CountRequestCreateRequest(
        @NotNull(message = "Vui lòng chọn cửa hàng.")
        Long storeId,

        @NotNull(message = "Vui lòng chọn phạm vi kiểm kê.")
        ScopeType scopeType,

        @Size(max = 255, message = "Ghi chú phạm vi tối đa 255 ký tự.")
        String scopeNote,

        @NotBlank(message = "Vui lòng nhập lý do.")
        @Size(max = 255, message = "Lý do tối đa 255 ký tự.")
        String reason,

        List<@NotNull Long> productIds) {
}
