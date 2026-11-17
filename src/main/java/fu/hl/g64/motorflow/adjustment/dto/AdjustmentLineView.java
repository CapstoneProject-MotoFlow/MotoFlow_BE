package fu.hl.g64.motorflow.adjustment.dto;

import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;

import java.time.LocalDate;

/**
 * One stock change of a request and its effect (UC-84 step 2): {@code currentQty} is what the warehouse holds now of
 * the part and status (of the lot when one is named), {@code afterQty} what is left once applied. Cost figures are
 * shown to Owner only (BR-72); for a Branch Manager they are null.
 *
 * @param unitCost the moving average cost now (BR-74), at which the write-off will be valued
 */
public record AdjustmentLineView(
        Long productId,
        String sku,
        String partName,
        String baseUnit,
        Long lotId,
        String manufacturerLotNo,
        LocalDate expiryDate,
        StockStatus stockStatus,
        int quantityChange,
        int currentQty,
        int afterQty,
        Long unitCost,
        Long value) {
}
