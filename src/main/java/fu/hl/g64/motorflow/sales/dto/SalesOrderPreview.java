package fu.hl.g64.motorflow.sales.dto;

import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.sales.entity.enums.PriceType;

import java.math.BigDecimal;
import java.util.List;

/**
 * UC-53 step 5 and UC-55 step 4: prices, totals and stock of an order being filled in; nothing is saved. A part with
 * no retail price in effect has null prices and makes {@code priced} false. {@code withinThreshold} is BR-16 on the
 * requested quantities against the threshold in effect.
 */
public record SalesOrderPreview(long storeId, PriceType priceType, BigDecimal discountRate,
                                BigDecimal discountThreshold, boolean priced, boolean withinThreshold,
                                long referenceTotal, long discountAmount, long total, BigDecimal invoiceDiscountRate,
                                List<Line> lines) {

    public record Line(long productId, String sku, String partName, String baseUnit, ProductStatus productStatus,
                       int quantity, Long retailPrice, Long actualUnitPrice, Long lineTotal, int sellableQty,
                       int shortQty) {
    }
}
