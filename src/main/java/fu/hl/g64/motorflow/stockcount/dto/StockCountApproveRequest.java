package fu.hl.g64.motorflow.stockcount.dto;

import jakarta.validation.constraints.Size;

/** UC-95: the Owner approves the differences. */
public record StockCountApproveRequest(
        @Size(max = 255, message = "Ghi chú duyệt tối đa 255 ký tự.")
        String decisionReason) {
}
