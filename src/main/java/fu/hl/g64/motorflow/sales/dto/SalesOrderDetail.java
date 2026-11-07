package fu.hl.g64.motorflow.sales.dto;

import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import fu.hl.g64.motorflow.sales.entity.enums.PriceType;
import fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus;
import fu.hl.g64.motorflow.sales.helper.PickingGuide.LotToPick;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * UC-54 View Sales Order Detail. A Branch Manager of the store and Owner see everything; the Staff member picking it
 * sees the parts, quantities, places and lots to pick, without prices, totals, contact details or history.
 *
 * <p>{@code quantityBasis} says which quantities the totals use: REQUESTED until the order is confirmed, DELIVERED
 * after (BR-14, BR-37). {@code discountThreshold} is the limit in effect now, for the form (screen 8.1.2).
 */
public record SalesOrderDetail(
        long id, int version, SalesOrderStatus status, long storeId, String storeName,
        Long customerId, String customerName, String customerPhone, String customerAddress,
        PriceType priceType, BigDecimal discountRate, Picker picker,
        Long createdById, String createdBy, LocalDateTime createdAt, LocalDateTime updatedAt,
        Long confirmedById, String confirmedBy, LocalDateTime confirmedAt,
        String quantityBasis, Long referenceTotal, Long discountAmount, Long total, BigDecimal invoiceDiscountRate,
        BigDecimal discountThreshold, List<Line> lines, Actions actions, List<HistoryEntry> history) {

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
    public record Actions(boolean canUpdate, boolean canAssignPicker, boolean canRecordPicking, boolean canConfirm) {
    }

    /**
     * One step: CREATED, VERSIONED (with its {@code cause}, UPDATE or DELIVERY), PICKER_ASSIGNED, PICKED, CONFIRMED.
     * {@code version} is the version the step belongs to.
     */
    public record HistoryEntry(String action, Integer version, String cause, Long actorId, String actorName,
                               LocalDateTime occurredAt) {
    }
}
