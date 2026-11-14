package fu.hl.g64.motorflow.report.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * UC-102 gross margin report for Owner, amounts in VND. Revenue and cost of goods sold come from the sales orders
 * completed in the period (cost = the SALE_ISSUE movements at the moving average, BR-74). Corrections of those sales
 * and stock write-offs applied in the period show apart (BR-65, plan 6.4).
 */
public record GrossMarginReport(LocalDate from, LocalDate to, MarginGrouping groupBy, Long storeId, Long categoryId,
                                Long productId, Figures total, List<Row> rows) {

    /**
     * @param quantity           delivered quantity
     * @param grossMargin        revenue − cost
     * @param marginRate         grossMargin / revenue in percent, two decimals; null without revenue
     * @param correctionRevenue  change of revenue by sales corrections applied in the period
     * @param correctionCost     change of cost of goods sold by those corrections
     * @param adjustedMargin     grossMargin + correctionRevenue − correctionCost
     * @param writeOffValue      value of stock lost or damaged and written off in the period (UC-83), not in the margin
     */
    public record Figures(long quantity, long revenue, long cost, long grossMargin, BigDecimal marginRate,
                          long correctionRevenue, long correctionCost, long adjustedMargin, long writeOffValue) {
    }

    /** {@code id}: store, category or part id; {@code code}: the part's SKU, null otherwise. */
    public record Row(long id, String code, String name, Figures figures) {
    }
}
