package fu.hl.g64.motorflow.inventory.dto;

import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.LotSourceType;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Stock that comes in as a new lot: a goods receipt note line (one line = one lot), an opening count line or a count
 * surplus with no lot. {@code quantities} splits the lot by stock status (for a GRN line: AVAILABLE for the passed
 * quantity, WRONG_ITEM, DAMAGED). Only the AVAILABLE quantity enters the moving average.
 *
 * @param goodsReceiptNoteLineId set for {@link LotSourceType#RECEIPT}, otherwise null
 * @param supplierId             set for {@link LotSourceType#RECEIPT}, otherwise null
 * @param source                 the GRN for a receipt; the stock count for an opening or surplus lot
 * @param periodId               COUNT_ADJUSTMENT only: the period to post into instead of the current month's (BR-83),
 *                               must be OPEN; null together with {@code occurredAt} keeps the current month
 * @param occurredAt             COUNT_ADJUSTMENT only: the movement time, normally the end of the count day
 */
public record NewLotPosting(
        Long storeId,
        Long productId,
        LotSourceType sourceType,
        Long goodsReceiptNoteLineId,
        Long supplierId,
        LocalDate receivedDate,
        long purchaseUnitPrice,
        String manufacturerLotNo,
        LocalDate manufactureDate,
        LocalDate expiryDate,
        Map<StockStatus, Integer> quantities,
        MovementType movementType,
        SourceRef source,
        Long performedBy,
        String reason,
        Long periodId,
        LocalDateTime occurredAt) {

    /** The earlier signature: posts into the current month's period. */
    public NewLotPosting(Long storeId, Long productId, LotSourceType sourceType, Long goodsReceiptNoteLineId,
                         Long supplierId, LocalDate receivedDate, long purchaseUnitPrice, String manufacturerLotNo,
                         LocalDate manufactureDate, LocalDate expiryDate, Map<StockStatus, Integer> quantities,
                         MovementType movementType, SourceRef source, Long performedBy, String reason) {
        this(storeId, productId, sourceType, goodsReceiptNoteLineId, supplierId, receivedDate, purchaseUnitPrice,
                manufacturerLotNo, manufactureDate, expiryDate, quantities, movementType, source, performedBy, reason,
                null, null);
    }
}
