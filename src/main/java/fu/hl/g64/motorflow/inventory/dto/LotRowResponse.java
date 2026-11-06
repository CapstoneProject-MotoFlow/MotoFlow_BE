package fu.hl.g64.motorflow.inventory.dto;

import fu.hl.g64.motorflow.inventory.entity.enums.LotSourceType;

import java.time.LocalDate;

/**
 * UC-97: one lot in one warehouse. Carries no price (BR-72). {@code daysToExpiry} is negative once expired and null
 * for a lot without expiry; {@code remainingQty} leaves out stock in transit.
 */
public record LotRowResponse(long lotId, long storeId, String storeName, long productId, String sku, String partName,
                             String baseUnit, LotSourceType sourceType, Long supplierId, String supplierName,
                             String manufacturerLotNo, LocalDate manufactureDate, LocalDate expiryDate,
                             Integer daysToExpiry, int remainingQty, LocalDate receivedDate) {
}
