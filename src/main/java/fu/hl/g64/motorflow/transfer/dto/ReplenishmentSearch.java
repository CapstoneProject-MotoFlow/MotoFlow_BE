package fu.hl.g64.motorflow.transfer.dto;

import fu.hl.g64.motorflow.transfer.entity.enums.ReplenishmentRequestStatus;

import java.time.LocalDate;

/**
 * UC-69 filters; {@code from} and {@code to} are created dates, both included. {@code q} is free text: a request code
 * (YBS-0001 or its id) or part of the requesting store's name.
 */
public record ReplenishmentSearch(ReplenishmentRequestStatus status, Long storeId, LocalDate from, LocalDate to,
                                  String q) {
}
