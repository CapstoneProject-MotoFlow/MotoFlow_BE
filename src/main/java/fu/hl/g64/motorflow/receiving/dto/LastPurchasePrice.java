package fu.hl.g64.motorflow.receiving.dto;

import java.time.LocalDateTime;

/**
 * PM 17/11 Q6: the unit price of a part on the latest confirmed delivery from the supplier, for the "Giá nhập lần
 * trước" column of the PO form. Shown for reference only.
 */
public record LastPurchasePrice(long productId, long lastPrice, LocalDateTime receivedAt, long purchaseOrderId) {
}
