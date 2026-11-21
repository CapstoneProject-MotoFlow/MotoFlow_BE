package fu.hl.g64.motorflow.receiving.dto;

import fu.hl.g64.motorflow.receiving.entity.enums.PurchaseOrderStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One row of the PO list (UC-39, screen 6.1.1). {@code overdue}: still open after its expected arrival day.
 * {@code orderedValue} (ordered quantity times purchase price, summed) is shown to Owner only and is null for a
 * Branch Manager (BR-72). {@code openDiscrepancies}: supplier discrepancy cases of the PO not closed yet.
 * {@code discrepancy} is filled on the DISCREPANCY_OPEN tab only (GVHD 20/11, PR 6), null elsewhere.
 */
public record PurchaseOrderSummary(long id, PurchaseOrderStatus status, long supplierId,
                                   String supplierName, long storeId, String storeName, LocalDateTime createdAt,
                                   LocalDate expectedArrival, boolean overdue, long lineCount, Long orderedValue,
                                   long openDiscrepancies, Discrepancy discrepancy) {

    /**
     * The open discrepancy of the PO: {@code discrepancyProductCount} parts in an open case (a part with a case on a
     * replacement delivery too counts once), {@code openCaseCount} open cases, {@code lastDeliveredAt} the arrival of
     * its latest delivery, and the short, wrong-item and damaged units of the open cases.
     */
    public record Discrepancy(int discrepancyProductCount, int openCaseCount, LocalDateTime lastDeliveredAt,
                              int shortQty, int wrongItemQty, int damagedQty) {
    }
}
