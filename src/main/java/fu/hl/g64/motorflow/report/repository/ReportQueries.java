package fu.hl.g64.motorflow.report.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Read-only figures for the reports (UC-87, UC-102, UC-89). Every query takes the period [{@code from}, {@code to})
 * and an optional store; nothing is saved. {@code unit} is 'day' or 'month' for date_trunc.
 */
@Repository
@RequiredArgsConstructor
public class ReportQueries {

    private static final String STORE = "(CAST(:storeId AS BIGINT) IS NULL OR %s = :storeId)";

    private final NamedParameterJdbcTemplate jdbc;

    private static MapSqlParameterSource params(Long storeId, LocalDateTime from, LocalDateTime to) {
        return new MapSqlParameterSource().addValue("storeId", storeId)
                .addValue("from", Timestamp.valueOf(from)).addValue("to", Timestamp.valueOf(to));
    }

    /** [store_id, day, orders, revenue, credit_sales] of sales orders completed in the period. */
    public List<Map<String, Object>> sales(Long storeId, LocalDateTime from, LocalDateTime to, String unit) {
        return jdbc.queryForList("""
                SELECT o.store_id, CAST(date_trunc(:unit, o.completed_at) AS DATE) AS day, count(*) AS orders,
                       coalesce(sum(t.total), 0) AS revenue, coalesce(sum(r.original_amount), 0) AS credit_sales
                FROM sales_order o
                JOIN (SELECT l.sales_order_id,
                             sum(CAST(coalesce(l.delivered_qty, 0) AS BIGINT) * l.actual_unit_price) AS total
                      FROM sales_order_line l GROUP BY l.sales_order_id) t ON t.sales_order_id = o.id
                LEFT JOIN receivable r ON r.sales_order_id = o.id
                WHERE o.status = 'COMPLETED' AND o.completed_at >= :from AND o.completed_at < :to AND %s
                GROUP BY 1, 2""".formatted(STORE.formatted("o.store_id")),
                params(storeId, from, to).addValue("unit", unit));
    }

    /** [store_id, day, order_payments, refunds] of order payments and refunds verified in the period. */
    public List<Map<String, Object>> orderCollections(Long storeId, LocalDateTime from, LocalDateTime to,
                                                      String unit) {
        return jdbc.queryForList("""
                SELECT o.store_id, CAST(date_trunc(:unit, p.verified_at) AS DATE) AS day,
                       coalesce(sum(p.amount) FILTER (WHERE p.payment_type = 'ORDER_PAYMENT'), 0) AS order_payments,
                       coalesce(sum(p.amount) FILTER (WHERE p.payment_type = 'REFUND'), 0) AS refunds
                FROM payment p
                JOIN sales_order o ON o.id = p.sales_order_id
                WHERE p.status = 'VERIFIED' AND p.payment_type IN ('ORDER_PAYMENT', 'REFUND')
                  AND p.verified_at >= :from AND p.verified_at < :to AND %s
                GROUP BY 1, 2""".formatted(STORE.formatted("o.store_id")),
                params(storeId, from, to).addValue("unit", unit));
    }

    /** [store_id, day, debt_collected]: debt payments verified in the period, by the store of the debt paid. */
    public List<Map<String, Object>> debtCollections(Long storeId, LocalDateTime from, LocalDateTime to,
                                                     String unit) {
        return jdbc.queryForList("""
                SELECT r.store_id, CAST(date_trunc(:unit, p.verified_at) AS DATE) AS day,
                       coalesce(sum(a.allocated_amount), 0) AS debt_collected
                FROM payment p
                JOIN payment_allocation a ON a.payment_id = p.id
                JOIN receivable r ON r.id = a.receivable_id
                WHERE p.payment_type = 'DEBT_PAYMENT' AND p.status = 'VERIFIED'
                  AND p.verified_at >= :from AND p.verified_at < :to AND %s
                GROUP BY 1, 2""".formatted(STORE.formatted("r.store_id")),
                params(storeId, from, to).addValue("unit", unit));
    }

    /**
     * [store_id, day, revenue_corrections, collection_corrections, receivable_corrections] of the amount lines of
     * requests applied in the period. A sales order correction changes the order's money by its whole amount; a
     * change of a refund counts against the money collected.
     */
    public List<Map<String, Object>> amountCorrections(Long storeId, LocalDateTime from, LocalDateTime to,
                                                       String unit) {
        return jdbc.queryForList("""
                SELECT q.store_id, CAST(date_trunc(:unit, q.applied_at) AS DATE) AS day,
                       coalesce(sum(a.amount_change) FILTER (WHERE q.sales_order_id IS NOT NULL), 0)
                           AS revenue_corrections,
                       coalesce(sum(CASE WHEN p.payment_type = 'REFUND' THEN -a.amount_change ELSE a.amount_change END)
                           FILTER (WHERE a.payment_id IS NOT NULL), 0) AS collection_corrections,
                       coalesce(sum(a.amount_change) FILTER (WHERE a.receivable_id IS NOT NULL), 0)
                           AS receivable_corrections
                FROM adjustment_request q
                JOIN adjustment_amount_line a ON a.adjustment_request_id = q.id
                LEFT JOIN payment p ON p.id = a.payment_id
                WHERE q.status = 'APPLIED' AND q.applied_at >= :from AND q.applied_at < :to AND %s
                GROUP BY 1, 2""".formatted(STORE.formatted("q.store_id")),
                params(storeId, from, to).addValue("unit", unit));
    }

    /**
     * [store_id, remaining]: debt left at {@code to}: original amounts of the debts recognised before it, with the
     * corrections applied before it, less what verified debt payments allocated before it.
     */
    public List<Map<String, Object>> receivableAt(Long storeId, LocalDateTime to) {
        return jdbc.queryForList("""
                SELECT r.store_id,
                       coalesce(sum(r.original_amount + coalesce(c.change, 0) - coalesce(p.paid, 0)), 0) AS remaining
                FROM receivable r
                LEFT JOIN (SELECT a.receivable_id, sum(a.amount_change) AS change
                           FROM adjustment_amount_line a
                           JOIN adjustment_request q ON q.id = a.adjustment_request_id
                           WHERE q.status = 'APPLIED' AND q.applied_at < :to AND a.receivable_id IS NOT NULL
                           GROUP BY a.receivable_id) c ON c.receivable_id = r.id
                LEFT JOIN (SELECT a.receivable_id, sum(a.allocated_amount) AS paid
                           FROM payment_allocation a
                           JOIN payment p ON p.id = a.payment_id
                           WHERE p.status = 'VERIFIED' AND p.verified_at < :to
                           GROUP BY a.receivable_id) p ON p.receivable_id = r.id
                WHERE r.recognized_at < :to AND %s
                GROUP BY r.store_id""".formatted(STORE.formatted("r.store_id")),
                new MapSqlParameterSource().addValue("storeId", storeId).addValue("to", Timestamp.valueOf(to)));
    }

    /**
     * Gross margin per store and part: [store_id, product_id, quantity, revenue, cost, correction_revenue,
     * correction_cost, write_off_value]. Sales: orders completed in the period; cost: their SALE_ISSUE movements.
     * Corrections and write-offs: requests applied in the period. {@code categoryId} also takes its sub-categories.
     */
    public List<Map<String, Object>> margins(Long storeId, Long categoryId, Long productId, LocalDateTime from,
                                             LocalDateTime to) {
        String filter = STORE.formatted("x.store_id") + """
                 AND (CAST(:productId AS BIGINT) IS NULL OR x.product_id = :productId)
                 AND (CAST(:categoryId AS BIGINT) IS NULL OR x.product_id IN (
                      SELECT pr.id FROM product pr JOIN part_category c ON c.id = pr.category_id
                      WHERE c.id = :categoryId OR c.parent_id = :categoryId))""";
        return jdbc.queryForList("""
                WITH completed AS (
                    SELECT o.id, o.store_id FROM sales_order o
                    WHERE o.status = 'COMPLETED' AND o.completed_at >= :from AND o.completed_at < :to),
                sold AS (
                    SELECT o.store_id, l.product_id, sum(coalesce(l.delivered_qty, 0)) AS quantity,
                           sum(CAST(coalesce(l.delivered_qty, 0) AS BIGINT) * l.actual_unit_price) AS revenue
                    FROM completed o JOIN sales_order_line l ON l.sales_order_id = o.id
                    GROUP BY 1, 2),
                issued AS (
                    SELECT o.store_id, m.product_id, -sum(m.value) AS cost
                    FROM completed o JOIN stock_movement m ON m.sales_order_id = o.id
                    WHERE m.movement_type = 'SALE_ISSUE'
                    GROUP BY 1, 2),
                applied AS (
                    SELECT q.id, q.store_id, q.adjustment_type, q.sales_order_id FROM adjustment_request q
                    WHERE q.status = 'APPLIED' AND q.applied_at >= :from AND q.applied_at < :to),
                corrected AS (
                    SELECT q.store_id, m.product_id,
                           -sum(CAST(m.quantity_change AS BIGINT) * pr.price) AS correction_revenue,
                           -sum(m.value) AS correction_cost
                    FROM applied q
                    JOIN stock_movement m ON m.adjustment_request_id = q.id
                    JOIN (SELECT sales_order_id, product_id, max(actual_unit_price) AS price
                          FROM sales_order_line GROUP BY 1, 2) pr
                      ON pr.sales_order_id = q.sales_order_id AND pr.product_id = m.product_id
                    WHERE q.adjustment_type = 'DOCUMENT_CORRECTION'
                    GROUP BY 1, 2),
                written AS (
                    SELECT q.store_id, m.product_id, -sum(m.value) AS write_off_value
                    FROM applied q JOIN stock_movement m ON m.adjustment_request_id = q.id
                    WHERE q.adjustment_type = 'STOCK_LOSS'
                    GROUP BY 1, 2),
                keys AS (
                    SELECT store_id, product_id FROM sold UNION SELECT store_id, product_id FROM issued
                    UNION SELECT store_id, product_id FROM corrected UNION SELECT store_id, product_id FROM written)
                SELECT x.store_id, x.product_id,
                       coalesce(s.quantity, 0) AS quantity, coalesce(s.revenue, 0) AS revenue,
                       coalesce(i.cost, 0) AS cost, coalesce(c.correction_revenue, 0) AS correction_revenue,
                       coalesce(c.correction_cost, 0) AS correction_cost,
                       coalesce(w.write_off_value, 0) AS write_off_value
                FROM keys x
                LEFT JOIN sold s ON s.store_id = x.store_id AND s.product_id = x.product_id
                LEFT JOIN issued i ON i.store_id = x.store_id AND i.product_id = x.product_id
                LEFT JOIN corrected c ON c.store_id = x.store_id AND c.product_id = x.product_id
                LEFT JOIN written w ON w.store_id = x.store_id AND w.product_id = x.product_id
                WHERE %s""".formatted(filter),
                params(storeId, from, to).addValue("categoryId", categoryId).addValue("productId", productId));
    }

    // -------------------------------------------------------------------------------------------- UC-89 sheets

    /** Lines of the sales orders completed in the period, with their cost from the SALE_ISSUE movements. */
    public List<Map<String, Object>> salesLines(Long storeId, LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForList("""
                SELECT o.id AS order_id, o.completed_at, s.store_name,
                       coalesce(cu.customer_name, o.customer_name_snapshot) AS customer, o.completion_type,
                       p.sku, p.part_name, p.base_unit, coalesce(l.delivered_qty, 0) AS quantity,
                       l.actual_unit_price, CAST(coalesce(l.delivered_qty, 0) AS BIGINT) * l.actual_unit_price AS amount,
                       coalesce((SELECT -sum(m.value) FROM stock_movement m WHERE m.sales_order_id = o.id
                                 AND m.product_id = l.product_id AND m.movement_type = 'SALE_ISSUE'), 0) AS cost
                FROM sales_order o
                JOIN sales_order_line l ON l.sales_order_id = o.id
                JOIN store s ON s.id = o.store_id
                JOIN product p ON p.id = l.product_id
                LEFT JOIN customer cu ON cu.id = o.customer_id
                WHERE o.status = 'COMPLETED' AND o.completed_at >= :from AND o.completed_at < :to AND %s
                ORDER BY o.completed_at, o.id, l.line_no""".formatted(STORE.formatted("o.store_id")),
                params(storeId, from, to));
    }

    /** Lines of the supplier deliveries confirmed in the period: the quantity taken into stock and its price. */
    public List<Map<String, Object>> purchaseLines(Long storeId, LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForList("""
                SELECT g.id AS receipt_id, g.confirmed_at, s.store_name, su.supplier_name, g.purchase_order_id,
                       g.delivery_document_no, p.sku, p.part_name, p.base_unit,
                       l.passed_qty - CASE WHEN l.excess_decision = 'ACCEPTED' THEN 0 ELSE l.excess_qty END
                           AS quantity,
                       coalesce(l.unit_price, lo.purchase_unit_price) AS unit_price
                FROM goods_receipt_note g
                JOIN purchase_order po ON po.id = g.purchase_order_id
                JOIN store s ON s.id = po.store_id
                JOIN supplier su ON su.id = po.supplier_id
                JOIN goods_receipt_note_line l ON l.goods_receipt_note_id = g.id
                JOIN product p ON p.id = l.product_id
                LEFT JOIN lot lo ON lo.goods_receipt_note_line_id = l.id
                WHERE g.receipt_type = 'SUPPLIER_DELIVERY' AND g.status = 'CONFIRMED'
                  AND g.confirmed_at >= :from AND g.confirmed_at < :to AND %s
                ORDER BY g.confirmed_at, g.id, l.id""".formatted(STORE.formatted("po.store_id")),
                params(storeId, from, to));
    }

    /** Payments verified in the period: order payments, refunds and debt payments. */
    public List<Map<String, Object>> payments(Long storeId, LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForList("""
                SELECT p.id, p.verified_at, p.payment_type, p.sales_order_id, p.payment_method,
                       p.transaction_reference, p.amount,
                       coalesce(o.store_id, (SELECT min(r.store_id) FROM payment_allocation a
                                             JOIN receivable r ON r.id = a.receivable_id
                                             WHERE a.payment_id = p.id)) AS store_id,
                       coalesce(cu.customer_name, o.customer_name_snapshot,
                                (SELECT min(c2.customer_name) FROM payment_allocation a
                                 JOIN receivable r ON r.id = a.receivable_id
                                 JOIN customer c2 ON c2.id = r.customer_id WHERE a.payment_id = p.id)) AS customer
                FROM payment p
                LEFT JOIN sales_order o ON o.id = p.sales_order_id
                LEFT JOIN customer cu ON cu.id = o.customer_id
                WHERE p.status = 'VERIFIED' AND p.verified_at >= :from AND p.verified_at < :to
                ORDER BY p.verified_at, p.id""", params(storeId, from, to)).stream()
                .filter(row -> storeId == null || storeId.equals(number(row.get("store_id"))))
                .toList();
    }

    /** Adjustment requests applied in the period, with their money and stock value changes. */
    public List<Map<String, Object>> adjustments(Long storeId, LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForList("""
                SELECT q.id, q.applied_at, q.adjustment_type, s.store_name,
                       CASE WHEN q.sales_order_id IS NOT NULL THEN 'SALES_ORDER #' || q.sales_order_id
                            WHEN q.payment_id IS NOT NULL THEN 'PAYMENT #' || q.payment_id
                            WHEN q.goods_receipt_note_id IS NOT NULL THEN 'GOODS_RECEIPT_NOTE #' || q.goods_receipt_note_id
                            WHEN q.transfer_order_id IS NOT NULL THEN 'TRANSFER_ORDER #' || q.transfer_order_id
                            WHEN q.receivable_id IS NOT NULL THEN 'RECEIVABLE #' || q.receivable_id END AS source,
                       q.reason, u.full_name AS requested_by, d.full_name AS decided_by,
                       coalesce((SELECT sum(a.amount_change) FROM adjustment_amount_line a
                                 WHERE a.adjustment_request_id = q.id), 0) AS amount_change,
                       coalesce((SELECT sum(m.value) FROM stock_movement m
                                 WHERE m.adjustment_request_id = q.id), 0) AS stock_value_change
                FROM adjustment_request q
                JOIN store s ON s.id = q.store_id
                JOIN user_account u ON u.id = q.requested_by
                LEFT JOIN user_account d ON d.id = q.decided_by
                WHERE q.status = 'APPLIED' AND q.applied_at >= :from AND q.applied_at < :to AND %s
                ORDER BY q.applied_at, q.id""".formatted(STORE.formatted("q.store_id")),
                params(storeId, from, to));
    }

    public static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }
}
