package fu.hl.g64.motorflow.transfer.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** UC-76 Step 3_Return to source: the parts and quantities coming back to the source warehouse. */
public record TransferReturnRequest(
        @NotEmpty(message = "Vui lòng thêm ít nhất một phụ tùng.")
        @Size(max = 200, message = "Tối đa 200 phụ tùng trong một phiếu chuyển kho.")
        List<@NotNull @Valid Line> lines) {

    public record Line(
            @NotNull(message = "Vui lòng chọn phụ tùng.")
            Long productId,

            @NotNull(message = "Vui lòng nhập số lượng.")
            @Min(value = 1, message = "Số lượng phải từ 1 trở lên.")
            Integer quantity) {
    }
}
