package fu.hl.g64.motorflow.receiving.dto;

import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * UC-39 for Staff: a supplier delivery whose inspection the user holds. {@code taskStatus} is ASSIGNED while the
 * result is still to be recorded, RESULT_SUBMITTED while it waits for the Branch Manager.
 */
public record AssignedReceiptResponse(long goodsReceiptNoteId, long purchaseOrderId, String supplierName,
                                      String deliveryDocumentNo, LocalDate deliveryDocumentDate,
                                      LocalDateTime arrivedAt, GoodsReceiptNoteStatus status, long lineCount,
                                      WarehouseTaskStatus taskStatus, LocalDateTime assignedAt) {
}
