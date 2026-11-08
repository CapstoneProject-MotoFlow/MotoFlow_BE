package fu.hl.g64.motorflow.transfer.dto;

import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;

import java.time.LocalDate;

/** UC-72 filters; {@code from} and {@code to} are created dates, both included. */
public record TransferOrderSearch(TransferOrderStatus status, Long sourceStoreId, Long destStoreId,
                                  Long replenishmentRequestId, LocalDate from, LocalDate to) {
}
