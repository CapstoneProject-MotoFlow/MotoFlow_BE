package fu.hl.g64.motorflow.stockcount.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/** UC-96, BR-74: initial unit costs, typed or read from Excel by the client; one per part, used for every warehouse. */
public record OpeningCostsSaveRequest(
        @NotNull(message = "Vui lòng gửi danh sách giá vốn.")
        List<@NotNull @Valid Cost> costs) {

    public record Cost(
            @NotNull(message = "Vui lòng chọn phụ tùng.")
            Long productId,

            @NotNull(message = "Vui lòng nhập giá vốn ban đầu.")
            @Positive(message = "Giá vốn ban đầu phải lớn hơn 0.")
            Long initialUnitCost) {
    }
}
