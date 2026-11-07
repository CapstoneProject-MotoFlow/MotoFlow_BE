package fu.hl.g64.motorflow.transfer.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** UC-43 for a transfer: one line per part of the receipt. Delivered = passed + damaged; nothing is a wrong item. */
public record TransferReceiptResultRequest(
        @NotEmpty(message = "Vui lòng ghi kết quả cho mọi phụ tùng.")
        @Size(max = 200, message = "Tối đa 200 phụ tùng trong một phiếu chuyển kho.")
        List<@NotNull @Valid Line> lines) {

    public record Line(
            @NotNull(message = "Vui lòng chọn phụ tùng.")
            Long productId,

            @NotNull(message = "Vui lòng nhập số đạt.")
            @Min(value = 0, message = "Số lượng không được âm.")
            Integer passedQty,

            @NotNull(message = "Vui lòng nhập số hỏng.")
            @Min(value = 0, message = "Số lượng không được âm.")
            Integer damagedQty) {
    }
}
