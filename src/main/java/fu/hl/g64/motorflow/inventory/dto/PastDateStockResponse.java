package fu.hl.g64.motorflow.inventory.dto;

import fu.hl.g64.motorflow.common.dto.PageResponse;

import java.time.LocalDate;

/** Closing stock of one warehouse at the end of {@code date}; {@code basedOnPeriod} is yyyy-MM or null (UC-103). */
public record PastDateStockResponse(LocalDate date, long storeId, String storeName, String basedOnPeriod,
                                    PageResponse<PastDateStockRow> page) {
}
