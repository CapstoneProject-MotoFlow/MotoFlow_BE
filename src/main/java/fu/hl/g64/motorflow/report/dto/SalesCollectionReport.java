package fu.hl.g64.motorflow.report.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * UC-87 sales and collection report, amounts in VND. Original figures stay as recorded; corrections applied in the
 * period show apart, on the day they were applied (BR-65, plan 6.4).
 *
 * @param periods one row per day or month of the period, every one listed even with nothing in it
 * @param stores  one row per store: the chosen one, or every active store and any other with figures
 */
public record SalesCollectionReport(LocalDate from, LocalDate to, ReportGrouping groupBy, Long storeId,
                                    Figures total, List<PeriodRow> periods, List<StoreRow> stores) {

    /**
     * @param completedOrders       sales orders completed in the period
     * @param revenue               their delivered quantity × actual unit price
     * @param orderPayments         order payments verified in the period
     * @param refunds               refunds verified in the period
     * @param debtCollected         debt payments verified in the period, the part allocated to the store's debts
     * @param totalCollected        orderPayments − refunds + debtCollected
     * @param creditSales           debt that arose from orders completed on credit in the period
     * @param revenueCorrections    sales order corrections applied in the period (change of the order's money)
     * @param collectionCorrections payment amount corrections applied in the period
     * @param receivableCorrections receivable corrections applied in the period
     * @param receivableAtEnd       debt left at the end of the period; null on a day or month row
     */
    public record Figures(long completedOrders, long revenue, long orderPayments, long refunds, long debtCollected,
                          long totalCollected, long creditSales, long revenueCorrections, long collectionCorrections,
                          long receivableCorrections, Long receivableAtEnd) {

        public static final Figures ZERO = new Figures(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null);

        public Figures plus(Figures o) {
            return new Figures(completedOrders + o.completedOrders, revenue + o.revenue,
                    orderPayments + o.orderPayments, refunds + o.refunds, debtCollected + o.debtCollected,
                    totalCollected + o.totalCollected, creditSales + o.creditSales,
                    revenueCorrections + o.revenueCorrections, collectionCorrections + o.collectionCorrections,
                    receivableCorrections + o.receivableCorrections,
                    receivableAtEnd == null && o.receivableAtEnd == null ? null
                            : (receivableAtEnd == null ? 0 : receivableAtEnd)
                            + (o.receivableAtEnd == null ? 0 : o.receivableAtEnd));
        }
    }

    /** {@code from}-{@code to}: the day, or the month's days inside the period. */
    public record PeriodRow(LocalDate from, LocalDate to, Figures figures) {
    }

    public record StoreRow(long storeId, String storeName, Figures figures) {
    }
}
