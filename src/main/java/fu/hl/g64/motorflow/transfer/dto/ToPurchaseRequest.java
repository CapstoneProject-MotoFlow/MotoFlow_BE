package fu.hl.g64.motorflow.transfer.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** BR-55: what no warehouse can supply, per part. Parts left out keep their quantity. */
public record ToPurchaseRequest(
        @NotEmpty(message = "Vui lòng thêm ít nhất một phụ tùng.")
        @Size(max = 200, message = "Tối đa 200 phụ tùng trong một yêu cầu.")
        List<@NotNull @Valid Line> lines) {

    public record Line(
            @NotNull(message = "Vui lòng chọn phụ tùng.")
            Long productId,

            @NotNull(message = "Vui lòng nhập số lượng.")
            @Min(value = 0, message = "Số lượng không được âm.")
            Integer toPurchaseQty) {
    }
}
