package fu.hl.g64.motorflow.receiving.dto;

import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One row of the GRN list. {@code replacement}: a replacement delivery for discrepancy cases;
 * {@code assigneeId}/{@code assigneeName}: who holds the inspection now (null when nobody does).
 */
public record GoodsReceiptSummary(long id, GoodsReceiptNoteStatus status, boolean replacement, long purchaseOrderId,
                                  long supplierId, String supplierName, long storeId, String storeName,
                                  String deliveryDocumentNo, LocalDate deliveryDocumentDate, LocalDateTime arrivedAt,
                                  Long assigneeId, String assigneeName, long lineCount, boolean excessAwaiting,
                                  long openDiscrepancies, LocalDateTime submittedAt, LocalDateTime confirmedAt) {
}
