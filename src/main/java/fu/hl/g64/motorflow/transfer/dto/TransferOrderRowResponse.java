package fu.hl.g64.motorflow.transfer.dto;

import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;

import java.time.LocalDateTime;

/** UC-72: one transfer order in the list. */
public record TransferOrderRowResponse(long id, TransferOrderStatus status, long sourceStoreId, String sourceStoreName,
                                       long destStoreId, String destStoreName, Long replenishmentRequestId,
                                       long lineCount, long totalPlannedQty, String createdByName,
                                       LocalDateTime createdAt, LocalDateTime dispatchedAt, LocalDateTime closedAt) {
}
