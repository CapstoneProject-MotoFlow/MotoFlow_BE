package fu.hl.g64.motorflow.transfer.dto;

import fu.hl.g64.motorflow.discrepancy.entity.enums.DiscrepancyCaseStatus;
import fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType;
import fu.hl.g64.motorflow.discrepancy.entity.enums.WrongItemReceiptStatus;
import fu.hl.g64.motorflow.operation.dto.AttachmentResponse;
import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;
import fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType;
import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * UC-73. For Staff the reconciliation figures are null and {@code discrepancies} is empty: they see only what to
 * prepare or receive. {@code unitCost} of a lot is for the Owner only (BR-72). {@code attachments}: evidence of the
 * dispatch (UC-74) and extra dispatch (UC-75), empty for Staff.
 */
public record TransferOrderDetailResponse(
        long id, TransferOrderStatus status, Long replenishmentRequestId,
        long sourceStoreId, String sourceStoreName, long destStoreId, String destStoreName,
        Person createdBy, LocalDateTime createdAt,
        Person dispatchedBy, LocalDateTime dispatchedAt,
        String delivererName, String vehiclePlateNo,
        Person extraDispatchConfirmedBy, LocalDateTime extraDispatchConfirmedAt,
        Person closedBy, LocalDateTime closedAt,
        Person discrepancyAcknowledgedBy, LocalDateTime discrepancyAcknowledgedAt,
        List<Line> lines, List<Lot> lots, List<Receipt> receipts, List<Discrepancy> discrepancies,
        List<AttachmentResponse> attachments) {

    public record Person(long id, String fullName) {
    }

    /**
     * PM 23/11: {@code receivedQty} passed (đạt), {@code shortageQty} thiếu (dispatched less passed, damaged,
     * returned, lost and not to be replaced; a wrong item never counts), {@code unresolvedQty} chờ quyết (shortage
     * with no late delivery or no replacement chosen), {@code noReplacementQty} không giao bù, {@code lossQty} mất,
     * {@code wrongItemQty} giao sai, {@code wrongPendingQty} giao sai without a phiếu nhận hàng giao sai,
     * {@code wrongAwaitingSourceQty} on phiếu awaiting the source's BM, {@code wrongConfirmedQty} on phiếu it
     * confirmed, {@code wrongReturnedQty} handed back to the source (GVHD 24/11), {@code latePendingQty} to come later and not arrived,
     * {@code receiverMiscounted} the Owner found the destination counted the line wrong (it then holds what was
     * dispatched and every other figure is 0).
     */
    public record Line(long productId, String sku, String partName, String baseUnit, int plannedQty,
                       Integer preparedQty, Integer dispatchedQty, Integer receivedQty, Integer returnedQty,
                       Integer lossQty, Integer extraReceivedQty, Integer extraDispatchedQty, Integer unresolvedQty,
                       Integer damagedQty, Integer shortageQty, Long makeupForCaseId, Integer wrongItemQty,
                       Integer wrongPendingQty, Integer wrongAwaitingSourceQty, Integer wrongConfirmedQty,
                       Integer wrongReturnedQty, Integer noReplacementQty, Integer latePendingQty,
                       Boolean receiverMiscounted) {
    }

    /** A lot taken out of the source on dispatch (BR-75). */
    public record Lot(long productId, long lotId, String manufacturerLotNo, LocalDate expiryDate, long quantity,
                      BigDecimal unitCost) {
    }

    /** The delivery document fields are set for a TRANSFER_IN receipt recorded with its delivery document. */
    public record Receipt(long id, ReceiptType receiptType, GoodsReceiptNoteStatus status, String deliveryDocumentNo,
                          LocalDate deliveryDocumentDate, String delivererName, String vehiclePlateNo,
                          LocalDateTime arrivedAt, LocalDateTime confirmedAt) {
    }

    public record Discrepancy(long id, long productId, int shortQty, int wrongItemQty, int damagedQty, DiscrepancyCaseStatus status,
                              LocalDateTime openedAt, LocalDateTime closedAt, List<Event> events) {
    }

    /**
     * PM 23/11: a phiếu nhận hàng giao sai (RECEIVED_AS_OTHER_PRODUCT) carries the part really received, its
     * expiry and {@code status}; {@code recordedBy} made the event.
     */
    public record Event(long id, ResolutionType resolutionType, int quantity, Long goodsReceiptNoteId,
                        Long adjustmentRequestId, String reason, LocalDateTime occurredAt,
                        LocalDateTime confirmedAt, Long receivedProductId, String receivedSku,
                        String receivedPartName, LocalDate receivedExpiryDate, WrongItemReceiptStatus status,
                        Person recordedBy) {
    }
}
