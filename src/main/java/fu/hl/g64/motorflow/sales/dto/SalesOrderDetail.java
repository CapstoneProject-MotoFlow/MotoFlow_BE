package fu.hl.g64.motorflow.sales.dto;

import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.operation.dto.AttachmentResponse;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentMethod;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentStatus;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentType;
import fu.hl.g64.motorflow.sales.entity.enums.CompletionType;
import fu.hl.g64.motorflow.sales.entity.enums.CreditDecision;
import fu.hl.g64.motorflow.sales.entity.enums.PriceType;
import fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus;
import fu.hl.g64.motorflow.sales.helper.PickingGuide.LotToPick;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * UC-54 View Sales Order Detail. A Branch Manager of the store and Owner see everything; the Staff member picking it
 * sees the parts, quantities, places and lots to pick, without prices, totals, contact details or history.
 *
 * <p>{@code quantityBasis} says which quantities the totals use: REQUESTED until the order is confirmed, DELIVERED
 * after (BR-14, BR-37). {@code discountThreshold} is the limit in effect now, for the form (screen 8.1.2).
 * {@code money} holds the payments, refunds, credit and receivable of the order (UC-54, UC-60, UC-61) and
 * {@code closing} its completion or cancellation; both are null for Staff.
 */
public record SalesOrderDetail(
        long id, int version, SalesOrderStatus status, long storeId, String storeName,
        Long customerId, String customerName, String customerPhone, String customerAddress,
        PriceType priceType, BigDecimal discountRate, Picker picker,
        Long createdById, String createdBy, LocalDateTime createdAt, LocalDateTime updatedAt,
        Long confirmedById, String confirmedBy, LocalDateTime confirmedAt,
        String quantityBasis, Long referenceTotal, Long discountAmount, Long total, BigDecimal invoiceDiscountRate,
        BigDecimal discountThreshold, Money money, Closing closing, List<Line> lines, Actions actions,
        List<HistoryEntry> history) {

    /** Who holds the picking task and where it stands. */
    public record Picker(long id, String name, WarehouseTaskStatus taskStatus) {
    }

    /**
     * One part. {@code shortQty} is requested minus picked once picked, with the picker's reason. {@code sellableQty}
     * is what the store can hand over now (available, not expired); {@code stockShortQty} what it lacks for the
     * quantity still to deliver. {@code pickFirst} lists the lots to take, earliest expiry first (BR-75).
     */
    public record Line(long id, int lineNo, long productId, String sku, String partName, String baseUnit,
                       ProductStatus productStatus, List<String> barcodes, String storageArea, String binLocation,
                       int requestedQty, Integer pickedQty, Integer shortQty, String shortReason, Integer deliveredQty,
                       Long referencePrice, Long actualUnitPrice, Long lineTotal, int sellableQty, int stockShortQty,
                       List<LotToPick> pickFirst) {
    }

    /** What the signed-in user may do with the order now. */
    public record Actions(boolean canUpdate, boolean canAssignPicker, boolean canRecordPicking, boolean canConfirm,
                          boolean canRecordPayment, boolean canRefund, boolean canRecordCredit, boolean canComplete,
                          boolean canCancel) {
    }

    /**
     * Screen 8.2.2 and UC-60 step 2. {@code netCollected} = verified payments − verified refunds (BR-49);
     * {@code remainingPayable} is what is still to be paid or put on credit, {@code overpaidAmount} what must be
     * refunded before completion (BR-50), {@code refundable} what may still be refunded (BR-48) and
     * {@code payableNow} what may still be recorded as paid while unverified payments wait (BR-47). Unverified rows do
     * not count; while any waits, the order can be neither completed nor cancelled.
     */
    public record Money(long total, long verifiedPaid, long verifiedRefunded, long netCollected,
                        long remainingPayable, long overpaidAmount, long refundable, long payableNow,
                        int unverifiedCount, List<PaymentRow> payments, Credit credit, Debt receivable) {
    }

    /**
     * One payment or refund of the order, oldest first. {@code amount} includes applied corrections;
     * {@code changeGiven} is the cash returned when the customer handed over more (BR-47).
     */
    public record PaymentRow(long id, PaymentType type, PaymentMethod method, long amount, Long tenderedAmount,
                             Long changeGiven, String transactionReference, PaymentStatus status, Long recordedById,
                             String recordedBy, LocalDateTime recordedAt, Long verifiedById, String verifiedBy,
                             LocalDateTime verifiedAt, String rejectionReason, List<AttachmentResponse> evidence) {
    }

    /** UC-61: the credit allowed for the current version (BR-42). */
    public record Credit(CreditDecision decision, Long amount, Long decidedById, String decidedBy,
                         LocalDateTime decidedAt) {
    }

    /** The receivable an order completed on credit created, with what is still owed on it (BR-53). */
    public record Debt(long id, long originalAmount, long remainingAmount, LocalDate dueDate,
                       LocalDateTime settledAt) {
    }

    /** How the order ended: completed (PAID or CREDIT) or cancelled with its reason. */
    public record Closing(CompletionType completionType, Long completedById, String completedBy,
                          LocalDateTime completedAt, Long cancelledById, String cancelledBy, LocalDateTime cancelledAt,
                          String cancellationReason) {
    }

    /**
     * One step: CREATED, VERSIONED (with its {@code cause}, UPDATE or DELIVERY), PICKER_ASSIGNED, PICKED, CONFIRMED,
     * PAYMENT_REJECTED, COMPLETED, CANCELLED.
     * {@code version} is the version the step belongs to.
     */
    public record HistoryEntry(String action, Integer version, String cause, Long actorId, String actorName,
                               LocalDateTime occurredAt) {
    }
}
