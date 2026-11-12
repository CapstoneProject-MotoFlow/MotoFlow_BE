package fu.hl.g64.motorflow.sales.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Sales figures of a period, both days included, for the dashboards (FE UI fixes 12/11). Amounts are in VND.
 * {@code total} adds up {@code stores}; a Branch Manager gets only their own store.
 */
public record SalesSummaryResponse(LocalDate from, LocalDate to, Figures total, List<StoreFigures> stores) {

    /**
     * What happened in the period at one store, or at all of them for {@code total}.
     *
     * @param completedOrders orders completed in the period
     * @param revenue         what those orders sold for: delivered quantity × actual unit price (BR-37)
     * @param collectedAtSale what the customers of those orders paid: verified payments − verified refunds, with
     *                        applied corrections (BR-49)
     * @param creditOrders    those of the completed orders handed over on credit
     * @param creditSales     what those orders left on credit: the original amount of their receivables (BR-51);
     *                        {@code revenue = collectedAtSale + creditSales}
     * @param debtCollected   debt payments verified in the period, the part allocated to the store's receivables
     *                        (UC-66), whatever day the debts were made
     * @param totalCollected  {@code collectedAtSale + debtCollected}
     */
    public record Figures(long completedOrders, long revenue, long collectedAtSale, long creditOrders,
                          long creditSales, long debtCollected, long totalCollected) {

        public static final Figures ZERO = new Figures(0, 0, 0, 0, 0, 0, 0);

        public Figures plus(Figures other) {
            return new Figures(completedOrders + other.completedOrders, revenue + other.revenue,
                    collectedAtSale + other.collectedAtSale, creditOrders + other.creditOrders,
                    creditSales + other.creditSales, debtCollected + other.debtCollected,
                    totalCollected + other.totalCollected);
        }
    }

    /** One store, A to Z by name. */
    public record StoreFigures(long storeId, String storeName, Figures figures) {
    }
}
