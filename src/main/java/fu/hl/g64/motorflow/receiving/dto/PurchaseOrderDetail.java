package fu.hl.g64.motorflow.receiving.dto;

import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.discrepancy.entity.enums.DiscrepancyCaseStatus;
import fu.hl.g64.motorflow.receiving.entity.enums.ExcessDecision;
import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;
import fu.hl.g64.motorflow.receiving.entity.enums.PurchaseOrderStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * UC-41: a PO with its lines, its deliveries (GRNs) and their discrepancies, and its history. Purchase prices and
 * values are null for a Branch Manager (BR-72). {@code canUpdate} and {@code canCancel} tell whether the signed-in
 * user may still change or cancel it (Owner, no GRN yet: BR-21). {@code canCreateReceipt}: the receiving Branch
 * Manager may record a delivery for the outstanding lines (UC-106); {@code canCreateReplacement}: they may record a
 * replacement delivery for a discrepancy with replacement still to come.
 */
public record PurchaseOrderDetail(
        long id, PurchaseOrderStatus status,
        long supplierId, String supplierCode, String supplierName,
        long storeId, String storeName,
        LocalDate expectedArrival, boolean overdue, String note, Long replenishmentRequestId,
        long createdById, String createdByName, LocalDateTime createdAt, LocalDateTime updatedAt,
        Long cancelledById, String cancelledByName, LocalDateTime cancelledAt, String cancellationReason,
        Long orderedValue, long openDiscrepancies, boolean canUpdate, boolean canCancel,
        boolean canCreateReceipt, boolean canCreateReplacement,
        List<Line> lines, List<Receipt> receipts, List<Discrepancy> discrepancies, List<HistoryEntry> history) {

    /**
     * A PO line. {@code receivedQty} is what confirmed deliveries, replacements included, took into available stock
     * against this line (passed minus excess); {@code acceptedExcessQty} is excess Owner accepted on top of it.
     * {@code outstandingQty} is what the PO still expects in a delivery of its own: a confirmed delivery covers what
     * its document listed (or what came, if more), and its shortage, wrong items and damaged goods are owed through
     * the discrepancy cases instead (BR-22, BR-27).
     */
    public record Line(long id, long productId, String sku, String partName, String baseUnit,
                       ProductStatus productStatus, int orderedQty, Long purchasePrice, Long lineValue,
                       int receivedQty, int acceptedExcessQty, int outstandingQty) {
    }

    /**
     * One delivery of the PO; {@code replacement} for a replacement delivery of discrepancy cases. {@code lines} stays
     * empty until the inspection result has been recorded.
     */
    public record Receipt(long id, String deliveryDocumentNo, LocalDate deliveryDocumentDate, LocalDateTime arrivedAt,
                          GoodsReceiptNoteStatus status, boolean replacement, Long recordedById, String recordedByName,
                          LocalDateTime submittedAt, Long confirmedById, String confirmedByName,
                          LocalDateTime confirmedAt, List<ReceiptLine> lines) {
    }

    /** The inspection result of one part in a delivery. */
    public record ReceiptLine(long productId, String sku, String partName, int documentQty, int deliveredQty,
                              int passedQty, int wrongItemQty, int damagedQty, int excessQty,
                              ExcessDecision excessDecision) {
    }

    /**
     * A supplier discrepancy case opened on one of the PO's deliveries. {@code openReplacementQty} is the replacement
     * still owed that no replacement delivery is bringing yet (what a replacement GRN may be created for).
     */
    public record Discrepancy(long id, long goodsReceiptNoteId, long productId, String sku, String partName,
                              int shortQty, int wrongItemQty, int damagedQty, int rejectedExcessQty,
                              int returnRequiredQty, int replacementRequiredQty, int outstandingReturnQty,
                              int outstandingReplacementQty, int openReplacementQty, DiscrepancyCaseStatus status,
                              LocalDateTime openedAt, LocalDateTime closedAt) {
    }

    /**
     * One step of the PO history, newest first: CREATED, UPDATED or CANCELLED, with the reason. An UPDATED step lists
     * what changed: {@code delivery} is null when the delivery details stayed the same.
     */
    public record HistoryEntry(String action, Long actorId, String actorName, LocalDateTime at, String reason,
                               DeliveryChange delivery, List<LineChange> lineChanges) {
    }

    public record DeliveryChange(LocalDate oldExpectedArrival, LocalDate newExpectedArrival, String oldNote,
                                 String newNote) {
    }

    /**
     * A part added ({@code oldQty} null), removed ({@code newQty} null) or changed. Prices are null for a Branch
     * Manager, who does not see a change of price alone either (BR-72).
     */
    public record LineChange(long productId, String sku, String partName, Integer oldQty, Integer newQty,
                             Long oldPrice, Long newPrice) {
    }
}
