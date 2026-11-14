package fu.hl.g64.motorflow.adjustment.dto;

import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus;
import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentType;

import java.time.LocalDate;

/**
 * UC-82 filters; every one is optional. {@code from} and {@code to} are submission days, both included.
 * {@code mine} keeps only the caller's own requests.
 */
public record AdjustmentSearch(AdjustmentType type, AdjustmentRequestStatus status, Long storeId, LocalDate from,
                               LocalDate to, boolean mine) {
}
