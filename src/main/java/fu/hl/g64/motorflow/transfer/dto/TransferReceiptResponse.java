package fu.hl.g64.motorflow.transfer.dto;

import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;
import fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType;

import java.time.LocalDateTime;
import java.util.List;

/** A receipt of a transfer order, at the destination or back at the source. */
public record TransferReceiptResponse(long id, long transferOrderId, ReceiptType receiptType,
                                      GoodsReceiptNoteStatus status, LocalDateTime arrivedAt, Long recordedBy,
                                      LocalDateTime submittedAt, String recountReason, Long confirmedBy,
                                      LocalDateTime confirmedAt, List<Line> lines) {

    /** {@code documentQty}: what the order still expected when the goods arrived; above it is excess (BR-68). */
    public record Line(long productId, String sku, String partName, String baseUnit, int documentQty,
                       int deliveredQty, int passedQty, int damagedQty, int excessQty) {
    }
}
