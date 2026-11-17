package fu.hl.g64.motorflow.adjustment.dto;

import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus;
import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentType;

import java.time.LocalDateTime;

/** A row of the adjustment request list (UC-82). {@code totalValue} is Owner only (BR-72). */
public record AdjustmentSummary(
        long id,
        String number,
        AdjustmentType adjustmentType,
        AdjustmentRequestStatus status,
        Long storeId,
        String storeName,
        AdjustmentSource source,
        String reason,
        int lineCount,
        Long totalValue,
        Person requestedBy,
        LocalDateTime submittedAt,
        Person decidedBy,
        LocalDateTime decidedAt,
        LocalDateTime appliedAt) {

    public record Person(long id, String fullName) {
    }
}
