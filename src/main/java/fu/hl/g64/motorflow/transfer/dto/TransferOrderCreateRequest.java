package fu.hl.g64.motorflow.transfer.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** UC-71: one source and one destination per order (BR-57). {@code replenishmentRequestId} is optional. */
public record TransferOrderCreateRequest(
        Long replenishmentRequestId,

        @NotNull(message = "Vui lòng chọn kho nguồn.")
        Long sourceStoreId,

        @NotNull(message = "Vui lòng chọn kho đích.")
        Long destStoreId,

        @NotEmpty(message = "Vui lòng thêm ít nhất một phụ tùng.")
        @Size(max = 200, message = "Tối đa 200 phụ tùng trong một phiếu chuyển kho.")
        List<@NotNull @Valid Line> lines) {

    /** {@code makeupForCaseId}: the transfer discrepancy case this line makes up for (BR-62). */
    public record Line(
            @NotNull(message = "Vui lòng chọn phụ tùng.")
            Long productId,

            @NotNull(message = "Vui lòng nhập số lượng.")
            @Min(value = 1, message = "Số lượng phải từ 1 trở lên.")
            Integer plannedQty,

            Long makeupForCaseId) {
    }
}
