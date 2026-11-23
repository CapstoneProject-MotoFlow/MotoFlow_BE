package fu.hl.g64.motorflow.transfer.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.time.LocalDate;

/**
 * UC-43 for a transfer: one line per part of the receipt. Delivered = passed + wrong item + damaged; a wrong item is
 * another part sent in place of this one and is left out when 0 (review FE PR #44 R-01).
 */
public record TransferReceiptResultRequest(
        @NotEmpty(message = "Vui lòng ghi kết quả cho mọi phụ tùng.")
        @Size(max = 200, message = "Tối đa 200 phụ tùng trong một phiếu chuyển kho.")
        List<@NotNull @Valid Line> lines,

        @Size(max = 500, message = "Ghi chú kiểm nhận tối đa 500 ký tự.")
        String inspectionNote) {

    public record Line(
            @NotNull(message = "Vui lòng chọn phụ tùng.")
            Long productId,

            @NotNull(message = "Vui lòng nhập số đạt.")
            @Min(value = 0, message = "Số lượng không được âm.")
            Integer passedQty,

            @Min(value = 0, message = "Số lượng không được âm.")
            Integer wrongItemQty,

            @NotNull(message = "Vui lòng nhập số hỏng.")
            @Min(value = 0, message = "Số lượng không được âm.")
            Integer damagedQty, LocalDate expiryDate) {
        public Line(Long productId, Integer passedQty, Integer wrongItemQty, Integer damagedQty) {
            this(productId, passedQty, wrongItemQty, damagedQty, null);
        }
    }
}
