package fu.hl.g64.motorflow.stockcount.dto;

import fu.hl.g64.motorflow.inventory.entity.enums.CountType;
import fu.hl.g64.motorflow.inventory.entity.enums.ScopeType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * UC-91. The store is always the Branch Manager's own. {@code stockCountRequestId}: the Owner's request this cycle
 * count answers; scope, scope note and parts must then match it (SC-5).
 */
public record StockCountCreateRequest(
        @NotNull(message = "Vui lòng chọn loại kiểm kê.")
        CountType countType,

        @NotNull(message = "Vui lòng chọn ngày kiểm.")
        LocalDate countDate,

        @NotNull(message = "Vui lòng chọn phạm vi kiểm kê.")
        ScopeType scopeType,

        @Size(max = 255, message = "Ghi chú phạm vi tối đa 255 ký tự.")
        String scopeNote,

        List<@NotNull Long> productIds,

        Long stockCountRequestId) {
}
