package fu.hl.g64.motorflow.adjustment.dto;

import fu.hl.g64.motorflow.adjustment.dto.AdjustmentSummary.Person;
import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus;
import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentType;
import fu.hl.g64.motorflow.operation.dto.AttachmentResponse;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * A request with its evidence, source, proposed effects and decision history (UC-84 step 2).
 * {@code postingDate}: the day it was applied, or today while it can still be applied. Effects of an applied request
 * show the stock as it is now; the posted movements keep what was written off.
 */
public record AdjustmentDetail(
        long id,
        String number,
        AdjustmentType adjustmentType,
        AdjustmentRequestStatus status,
        Long storeId,
        String storeName,
        AdjustmentSource source,
        String reason,
        Person requestedBy,
        LocalDateTime submittedAt,
        Person decidedBy,
        LocalDateTime decidedAt,
        String decisionReason,
        LocalDateTime appliedAt,
        LocalDate postingDate,
        List<AdjustmentLineView> stockLines,
        List<AdjustmentAmountView> amountLines,
        Long totalValue,
        Map<String, Object> correction,
        List<AttachmentResponse> evidence,
        List<HistoryEntry> history,
        Actions actions) {

    /** One decision or step, oldest first (UC-84 step 8). */
    public record HistoryEntry(String action, Person actor, LocalDateTime occurredAt, String reason) {
    }

    /**
     * What the caller may do now: approve or reject (Owner, PENDING), apply again (Owner, APPROVED: UC-84 Step
     * 1_Approved but unapplied), withdraw (the requester, PENDING).
     */
    public record Actions(boolean canApprove, boolean canReject, boolean canApply, boolean canWithdraw) {
    }
}
