package fu.hl.g64.motorflow.transfer.dto;

import jakarta.validation.constraints.Size;

/** GVHD 24/11: why the source's BM rejects a phiếu nhận hàng giao sai; required (MSG361). */
public record RejectWrongItemRequest(
        @Size(max = 255, message = "Lý do tối đa 255 ký tự.")
        String reason) {
}
