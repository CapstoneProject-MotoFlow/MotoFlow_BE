package fu.hl.g64.motorflow.inventory.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef.SourceType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Q8 (PM 23/11): a stock document is the stock movements of one source document in one warehouse in one direction.
 * Values and unit costs are only for the Owner (BR-72), left out for others.
 * <p>{@code code}: SO-{id} for a sale, PN-, DC-, KK-, DCH- (adjustment request) and TNCC- (supplier return) with the
 * id padded to 4 digits, so the code on screen is also what {@code q} finds. {@code discrepancyReceiptId} is the
 * supplier receipt of a supplier return's discrepancy case, for the link to its handling screen.</p>
 */
public final class StockDocument {

    private StockDocument() {
    }

    public enum Direction { IN, OUT }

    public enum Kind {
        SALE, SUPPLIER_RECEIPT, SUPPLIER_RETURN, TRANSFER_DISPATCH, TRANSFER_RECEIPT,
        TRANSFER_RETURN, TRANSFER_EXCESS_DISPATCH, ADJUSTMENT, STOCK_COUNT
    }

    /** Filters of the list; every one is optional, {@code q} is lower case and empty for none. */
    public record Search(Direction direction, SourceType type, Kind kind, LocalDate from, LocalDate to, String q) {
    }

    public record Summary(SourceType sourceType, long sourceId, String code, Kind kind, long storeId, String storeName,
                          Direction direction, LocalDateTime occurredAt, String performedByName,
                          long lineCount, long quantity, @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal value,
                          Long transferOrderId, Long discrepancyCaseId, Long discrepancyReceiptId) {
    }

    public record Detail(Summary document, List<Line> lines) {
    }

    public record Line(long productId, String sku, String partName, Long lotId, LocalDate expiryDate,
                       long quantity, @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal unitCost,
                       @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal value) {
    }
}
