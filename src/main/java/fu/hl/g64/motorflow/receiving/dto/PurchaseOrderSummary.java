package fu.hl.g64.motorflow.receiving.dto;

import fu.hl.g64.motorflow.receiving.entity.enums.PurchaseOrderStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One row of the PO list (UC-39, screen 6.1.1). {@code overdue}: still open after its expected arrival day.
 * {@code orderedValue} (ordered quantity times purchase price, summed) is shown to Owner only and is null for a
 * Branch Manager (BR-72). {@code openDiscrepancies}: supplier discrepancy cases of the PO not closed yet.
 */
public record PurchaseOrderSummary(long id, PurchaseOrderStatus status, long supplierId, String supplierCode,
                                   String supplierName, long storeId, String storeName, LocalDateTime createdAt,
                                   LocalDate expectedArrival, boolean overdue, long lineCount, Long orderedValue,
                                   long openDiscrepancies) {
}
