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

    /**
     * GVHD 20/11: {@code sellableQty} is what the store can sell now, less what other open orders hold
     * ({@code heldByOthersQty}); {@code suggestedNowQty} = min(quantity, sellableQty), the default "lấy ngay".
     * {@code variant} (PR 5) is the part's dented-goods variant when the store can sell some of it ("Có N hàng lỗi"),
     * otherwise null; it is sold as a part of its own.
     */
    public record Line(long productId, String sku, String partName, String baseUnit, ProductStatus productStatus,
                       int quantity, Long retailPrice, Long actualUnitPrice, Long lineTotal, int sellableQty,
                       int shortQty, int heldByOthersQty, int suggestedNowQty, Variant variant) {
    }

    /** GVHD 20/11 (PR 5): the dented-goods variant of a part, with what the store can sell of it and its price. */
    public record Variant(long productId, String sku, int sellableQty, Long retailPrice) {
    }
}
