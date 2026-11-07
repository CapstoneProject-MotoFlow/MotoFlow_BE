package fu.hl.g64.motorflow.transfer.dto;

import fu.hl.g64.motorflow.discrepancy.entity.enums.DiscrepancyCaseStatus;
import fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType;
import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;
import fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType;
import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * UC-73. For Staff the reconciliation figures are null and {@code discrepancies} is empty: they see only what to
 * prepare or receive. {@code unitCost} of a lot is for the Owner only (BR-72).
 */
public record TransferOrderDetailResponse(
        long id, TransferOrderStatus status, Long replenishmentRequestId,
        long sourceStoreId, String sourceStoreName, long destStoreId, String destStoreName,
        Person createdBy, LocalDateTime createdAt,
        Person dispatchedBy, LocalDateTime dispatchedAt,
        Person extraDispatchConfirmedBy, LocalDateTime extraDispatchConfirmedAt,
        Person closedBy, LocalDateTime closedAt,
        List<Line> lines, List<Lot> lots, List<Receipt> receipts, List<Discrepancy> discrepancies) {

    public record Person(long id, String fullName) {
    }

    public record Line(long productId, String sku, String partName, String baseUnit, int plannedQty,
                       Integer preparedQty, Integer dispatchedQty, Integer receivedQty, Integer returnedQty,
                       Integer lossQty, Integer extraReceivedQty, Integer extraDispatchedQty, Integer unresolvedQty,
                       Long makeupForCaseId) {
    }

    /** A lot taken out of the source on dispatch (BR-75). */
    public record Lot(long productId, long lotId, String manufacturerLotNo, LocalDate expiryDate, long quantity,
                      BigDecimal unitCost) {
    }

    public record Receipt(long id, ReceiptType receiptType, GoodsReceiptNoteStatus status, LocalDateTime arrivedAt,
                          LocalDateTime confirmedAt) {
    }

    public record Discrepancy(long id, long productId, int shortQty, DiscrepancyCaseStatus status,
                              LocalDateTime openedAt, LocalDateTime closedAt, List<Event> events) {
    }

    public record Event(long id, ResolutionType resolutionType, int quantity, Long goodsReceiptNoteId,
                        Long adjustmentRequestId, String reason, LocalDateTime occurredAt,
                        LocalDateTime confirmedAt) {
    }
}
