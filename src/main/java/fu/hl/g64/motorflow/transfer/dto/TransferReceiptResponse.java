package fu.hl.g64.motorflow.transfer.dto;

import fu.hl.g64.motorflow.operation.dto.AttachmentResponse;
import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;
import fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A receipt of a transfer order, at the destination or back at the source. The delivery document fields,
 * {@code createdBy} and {@code documents} are set for a TRANSFER_IN receipt recorded with its delivery document.
 */
public record TransferReceiptResponse(long id, long transferOrderId, ReceiptType receiptType,
                                      GoodsReceiptNoteStatus status, String deliveryDocumentNo,
                                      LocalDate deliveryDocumentDate, String delivererName, String vehiclePlateNo,
                                      LocalDateTime arrivedAt, Long createdBy, String createdByName,
                                      Long recordedBy, LocalDateTime submittedAt, String recountReason,
                                      Long confirmedBy, LocalDateTime confirmedAt, List<Line> lines,
                                      List<AttachmentResponse> documents) {

    /** {@code documentQty}: what the order still expected when the goods arrived; above it is excess (BR-68). */
    public record Line(long productId, String sku, String partName, String baseUnit, int documentQty,
                       int deliveredQty, int passedQty, int damagedQty, int excessQty) {
    }
}
