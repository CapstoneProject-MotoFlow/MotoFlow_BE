package fu.hl.g64.motorflow.adjustment.dto;

import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus;
import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentType;

import java.time.LocalDate;
import java.util.List;

/**
 * UC-82 filters; every one is optional. {@code from} and {@code to} are submission days, both included.
 * {@code statuses}: any of them (review 17/11 R-08: "?status=REJECTED&status=WITHDRAWN" for one tab).
 * {@code mine} keeps only the caller's own requests.
 */
public record AdjustmentSearch(AdjustmentType type, List<AdjustmentRequestStatus> statuses, Long storeId, LocalDate from,
                               LocalDate to, boolean mine) {
}
