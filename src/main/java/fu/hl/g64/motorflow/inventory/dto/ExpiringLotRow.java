package fu.hl.g64.motorflow.inventory.dto;

import java.time.LocalDate;

/** JOB-03: the available quantity of one lot in one warehouse, for an expiry notification (BR-76). */
public record ExpiringLotRow(long lotId, long storeId, String storeName, String sku, LocalDate expiryDate,
                             long quantity) {
}
