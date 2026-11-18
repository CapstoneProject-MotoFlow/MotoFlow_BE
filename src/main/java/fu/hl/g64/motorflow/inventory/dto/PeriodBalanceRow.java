package fu.hl.g64.motorflow.inventory.dto;

import java.math.BigDecimal;

/**
 * One part in one warehouse for a period (UC-99, BR-85): closing = opening + in − out, for quantity and value.
 * {@code inTransitQty} is the part of the closing quantity still on its way to this warehouse.
 * {@code periodAvgUnitCost} = (opening value + value in) / (opening quantity + quantity in), null when that is 0.
 */
public record PeriodBalanceRow(long storeId, String storeName, long productId, String sku, String partName,
                               String baseUnit, long openingQty, long openingValue, long inQty, long inValue,
                               long outQty, long outValue, long closingQty, long closingValue, long inTransitQty,
                               long inTransitValue, BigDecimal periodAvgUnitCost) {
}
