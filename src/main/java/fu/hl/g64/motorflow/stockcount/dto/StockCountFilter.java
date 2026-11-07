package fu.hl.g64.motorflow.stockcount.dto;

import fu.hl.g64.motorflow.inventory.entity.enums.CountType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;

import java.time.LocalDate;

/** UC-90 filters; every one is optional. {@code from} and {@code to} bound the count date. */
public record StockCountFilter(Long storeId, CountType countType, StockCountStatus status, LocalDate from,
                               LocalDate to) {
}
