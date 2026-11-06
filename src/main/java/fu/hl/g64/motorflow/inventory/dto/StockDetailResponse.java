package fu.hl.g64.motorflow.inventory.dto;

import fu.hl.g64.motorflow.inventory.entity.enums.LotSourceType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * UC-47: one part in one warehouse, split by lot and stock status. Cost fields are filled for the Owner only (BR-72).
 */
public record StockDetailResponse(StockRowResponse stock, BigDecimal movingAvgCost, Integer expiryWarningDays,
                                  List<LotLine> lots) {

    public enum ExpiryState { NO_EXPIRY, OK, NEAR_EXPIRY, EXPIRED }

    public record LotLine(long lotId, LotSourceType sourceType, LocalDate receivedDate, Long supplierId,
                          String supplierName, String manufacturerLotNo, LocalDate expiryDate, ExpiryState expiryState,
                          StockStatus stockStatus, int quantity, Long purchaseUnitPrice) {
    }
}
