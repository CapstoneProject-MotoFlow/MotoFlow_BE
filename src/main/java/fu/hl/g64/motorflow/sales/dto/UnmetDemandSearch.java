package fu.hl.g64.motorflow.sales.dto;

import java.time.LocalDate;

/**
 * UC-59 filters, all optional. The days are the completion or cancellation days, the last 30 days up to today by
 * default. {@code q} matches the SKU, part name, customer name or phone. A Branch Manager sees their own store only.
 */
public record UnmetDemandSearch(Long storeId, Long productId, Long customerId, String q, LocalDate from,
                                LocalDate to) {
}
