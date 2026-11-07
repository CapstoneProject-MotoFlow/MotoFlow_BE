package fu.hl.g64.motorflow.sales.dto;

import fu.hl.g64.motorflow.sales.entity.enums.PriceType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One version of a sales order as it was saved (UC-55 "retains version history"). {@code cause} is CREATED, UPDATE
 * (the seller changed it) or DELIVERY (delivered less than requested at confirmation). Totals use the delivered
 * quantities when the version has them.
 */
public record SalesOrderVersion(int version, String cause, Long savedById, String savedBy, LocalDateTime savedAt,
                                Long customerId, String customerName, String customerPhone, PriceType priceType,
                                BigDecimal discountRate, long referenceTotal, long total, List<Line> lines) {

    public record Line(long productId, String sku, String partName, int requestedQty, Integer deliveredQty,
                       long referencePrice, long actualUnitPrice) {
    }
}
