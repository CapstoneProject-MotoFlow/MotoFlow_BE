package fu.hl.g64.motorflow.transfer.dto;

import fu.hl.g64.motorflow.operation.dto.AttachmentResponse;
import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;
import fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A receipt of a transfer order, at the destination or back at the source. Delivery document fields stay null for a
 * transfer arrival (E02); {@code documents} lists any photos attached when the goods arrived.
 */
public record TransferReceiptResponse(long id, long transferOrderId, ReceiptType receiptType,
                                      GoodsReceiptNoteStatus status, String deliveryDocumentNo,
                                      LocalDate deliveryDocumentDate, String delivererName, String vehiclePlateNo,
                                      LocalDateTime arrivedAt, Long createdBy, String createdByName,
                                      Long recordedBy, String recordedByName, LocalDateTime submittedAt,
                                      String inspectionNote, String recountReason,
                                      Long confirmedBy, LocalDateTime confirmedAt, List<Line> lines,
                                      List<AttachmentResponse> documents) {

    /**
     * {@code documentQty}: what the order still expected when the goods arrived; above it is excess (BR-68).
     * {@code deliveredQty} = passed + wrong item + damaged.
     */
    public record Line(long productId, String sku, String partName, String baseUnit, int documentQty,
                       int deliveredQty, int passedQty, int wrongItemQty, int damagedQty, int excessQty) {
    }
}
