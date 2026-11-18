package fu.hl.g64.motorflow.stockcount.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * UC-96: what approving an opening count will post, by part and expiry date, at each part's initial unit cost.
 * {@code complete} is false while a part still has no cost; those parts are listed in {@code missingSkus}, and
 * {@code totalValue} then covers the priced parts only.
 */
public record OpeningCostsResponse(long stockCountId, boolean complete, List<String> missingSkus, long totalValue,
                                   List<Part> parts) {

    /**
     * @param initialUnitCost null while not entered
     * @param locked          true once an opening count with this part was approved: the cost can no longer change
     * @param value           quantity × initial unit cost, null while the cost is missing
     */
    public record Part(long productId, String sku, String partName, String baseUnit, Long initialUnitCost,
                       boolean locked, int quantity, Long value, List<Lot> lots) {
    }

    /** One opening lot to create: the quantity of one expiry date, damaged goods included. */
    public record Lot(LocalDate expiryDate, int quantity, int damagedQty, Long value) {
    }
}
