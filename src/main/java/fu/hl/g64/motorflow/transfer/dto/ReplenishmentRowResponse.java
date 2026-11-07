package fu.hl.g64.motorflow.transfer.dto;

import fu.hl.g64.motorflow.transfer.entity.enums.ReplenishmentRequestStatus;

import java.time.LocalDateTime;

/** UC-69: one row of the replenishment request list. */
public record ReplenishmentRowResponse(long id, long requestingStoreId, String storeName,
                                       ReplenishmentRequestStatus status, long lineCount, long totalRequestedQty,
                                       long createdBy, String createdByName, LocalDateTime createdAt,
                                       LocalDateTime closedAt) {
}
