package fu.hl.g64.motorflow.document.repository;

import fu.hl.g64.motorflow.document.dto.DocumentType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * UC-78: the posted documents of every module in one list, read in SQL. Each type contributes the same columns: the
 * store it belongs to, the second store that also sees it (a transfer order is the source's and the destination's),
 * the date it took effect, the customer or supplier and its value.
 */
@Component
public class DocumentQuery {

    /** One found document before the store names and print forms are added. */
    public record Found(DocumentType type, long id, String subType, String status, LocalDateTime documentDate,
                        long storeId, String storeName, Long otherStoreId, String otherStoreName, String partnerName,
                        Long value) {
    }

    /** Where to look; null fields do not filter. {@code scopeStoreId}: a Branch Manager's store or Owner's filter. */
    public record Filter(Set<DocumentType> types, Long id, LocalDate from, LocalDate to, Long scopeStoreId) {
    }

    private static final String SALES = """
            SELECT 'SALES_ORDER' AS doc_type, o.id, o.completion_type AS sub_type, o.status,
                   COALESCE(o.completed_at, o.cancelled_at) AS doc_date, o.store_id,
                   CAST(NULL AS BIGINT) AS other_store_id, CAST(NULL AS BIGINT) AS scope_store_id,
                   o.customer_name_snapshot AS partner_name,
                   (SELECT CAST(SUM(CAST(COALESCE(l.delivered_qty, l.requested_qty) AS BIGINT) * l.actual_unit_price)
                           AS BIGINT)
                      FROM sales_order_line l WHERE l.sales_order_id = o.id) AS value
              FROM sales_order o
             WHERE o.status IN ('COMPLETED', 'CANCELLED')""";

    /**
     * A receipt belongs to the store that receives it (BR-03): the purchase order's store, the transfer's destination,
     * or its source for goods sent back. What was accepted into stock is valued at purchase prices, for Owner only.
     */
    private static final String RECEIPTS = """
            SELECT 'GOODS_RECEIPT', g.id, g.receipt_type, g.status, g.confirmed_at,
                   CASE g.receipt_type WHEN 'SUPPLIER_DELIVERY' THEN po.store_id
                                       WHEN 'TRANSFER_IN' THEN t.dest_store_id ELSE t.source_store_id END,
                   CASE g.receipt_type WHEN 'TRANSFER_IN' THEN t.source_store_id
                                       WHEN 'RETURN_TO_SOURCE' THEN t.dest_store_id END,
                   NULL, s.supplier_name,
                   CASE WHEN %s AND g.receipt_type = 'SUPPLIER_DELIVERY' THEN
                       (SELECT CAST(SUM(CAST(l.passed_qty - CASE WHEN l.excess_decision = 'ACCEPTED' THEN 0
                                                                  ELSE l.excess_qty END AS BIGINT) * l.unit_price)
                                    AS BIGINT)
                          FROM goods_receipt_note_line l WHERE l.goods_receipt_note_id = g.id) END
              FROM goods_receipt_note g
              LEFT JOIN purchase_order po ON po.id = g.purchase_order_id
              LEFT JOIN supplier s ON s.id = po.supplier_id
              LEFT JOIN transfer_order t ON t.id = g.transfer_order_id
             WHERE g.status = 'CONFIRMED'""";

    private static final String TRANSFERS = """
            SELECT 'TRANSFER_ORDER', t.id, NULL, t.status, t.dispatched_at, t.source_store_id, t.dest_store_id,
                   t.dest_store_id, NULL, NULL
              FROM transfer_order t
             WHERE t.status <> 'AWAITING_DISPATCH' AND t.dispatched_at IS NOT NULL""";

    /** A payment has no store of its own: an order's money is the order's store's, a debt payment its debts' store's. */
    private static final String PAYMENTS = """
            SELECT 'PAYMENT', p.id, p.payment_type, p.status, p.verified_at, COALESCE(so.store_id, debt.store_id),
                   NULL, NULL, COALESCE(so.customer_name_snapshot, debt.customer_name), p.amount
              FROM payment p
              LEFT JOIN sales_order so ON so.id = p.sales_order_id
              LEFT JOIN LATERAL (SELECT r.store_id, c.customer_name
                                   FROM payment_allocation a
                                   JOIN receivable r ON r.id = a.receivable_id
                                   JOIN customer c ON c.id = r.customer_id
                                  WHERE a.payment_id = p.id
                                  ORDER BY a.id LIMIT 1) debt ON TRUE
             WHERE p.status = 'VERIFIED'""";

    private static final String ADJUSTMENTS = """
            SELECT 'ADJUSTMENT', a.id, a.adjustment_type, a.status, a.submitted_at, a.store_id, NULL, NULL, NULL, NULL
              FROM adjustment_request a
             WHERE a.status <> 'DRAFT' AND a.submitted_at IS NOT NULL""";

    private final NamedParameterJdbcTemplate jdbc;

    public DocumentQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Newest first, {@code size} from {@code offset}. */
    public List<Found> find(Filter filter, boolean owner, int offset, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = "SELECT d.*, s1.store_name, s2.store_name AS other_store_name FROM (" + union(filter.types(), owner)
                + ") d JOIN store s1 ON s1.id = d.store_id LEFT JOIN store s2 ON s2.id = d.other_store_id"
                + where(filter, params) + " ORDER BY d.doc_date DESC, d.doc_type, d.id DESC LIMIT :size OFFSET :offset";
        params.addValue("size", size).addValue("offset", offset);
        return jdbc.query(sql, params, DocumentQuery::found);
    }

    public long count(Filter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = "SELECT count(*) FROM (" + union(filter.types(), false) + ") d" + where(filter, params);
        Long count = jdbc.queryForObject(sql, params, Long.class);
        return count == null ? 0 : count;
    }

    /** The document if it is posted, whatever its store. */
    public Optional<Found> findPosted(DocumentType type, long id, boolean owner) {
        return find(new Filter(EnumSet.of(type), id, null, null, null), owner, 0, 1).stream().findFirst();
    }

    private static String union(Set<DocumentType> types, boolean owner) {
        List<String> parts = new ArrayList<>();
        for (DocumentType type : types.isEmpty() ? EnumSet.allOf(DocumentType.class) : types) {
            parts.add(switch (type) {
                case SALES_ORDER -> SALES;
                case GOODS_RECEIPT -> RECEIPTS.formatted(owner ? "TRUE" : "FALSE");
                case TRANSFER_ORDER -> TRANSFERS;
                case PAYMENT -> PAYMENTS;
                case ADJUSTMENT -> ADJUSTMENTS;
            });
        }
        // The first part names the columns; the others only need the same order.
        if (!types.isEmpty() && !types.contains(DocumentType.SALES_ORDER)) {
            parts.addFirst(SALES + " AND FALSE");
        }
        return String.join("\nUNION ALL\n", parts);
    }

    private static String where(Filter filter, MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder(" WHERE d.store_id IS NOT NULL");
        if (filter.id() != null) {
            where.append(" AND d.id = :id");
            params.addValue("id", filter.id());
        }
        if (filter.from() != null) {
            where.append(" AND d.doc_date >= :from");
            params.addValue("from", filter.from().atStartOfDay());
        }
        if (filter.to() != null) {
            where.append(" AND d.doc_date < :toExclusive");
            params.addValue("toExclusive", filter.to().plusDays(1).atStartOfDay());
        }
        if (filter.scopeStoreId() != null) {
            where.append(" AND (d.store_id = :store OR d.scope_store_id = :store)");
            params.addValue("store", filter.scopeStoreId());
        }
        return where.toString();
    }

    private static Found found(ResultSet rs, int row) throws SQLException {
        return new Found(DocumentType.valueOf(rs.getString("doc_type")), rs.getLong("id"), rs.getString("sub_type"),
                rs.getString("status"), time(rs, "doc_date"), rs.getLong("store_id"), rs.getString("store_name"),
                longOrNull(rs, "other_store_id"), rs.getString("other_store_name"), rs.getString("partner_name"),
                longOrNull(rs, "value"));
    }

    // ---- detail and print reads ------------------------------------------------------------------------------------

    /** Rows of a query as column maps, for the few reads of one document. */
    public List<Map<String, Object>> rows(String sql, Map<String, ?> params) {
        return jdbc.queryForList(sql, params);
    }

    public Optional<Map<String, Object>> row(String sql, Map<String, ?> params) {
        return rows(sql, params).stream().findFirst();
    }

    /** History rows of a record, oldest first, with the actor's name. */
    public List<Map<String, Object>> history(String entityType, long id, Collection<String> skippedActions) {
        String sql = """
                SELECT a.action, a.actor_id, u.full_name, a.occurred_at, a.reason
                  FROM audit_log a LEFT JOIN user_account u ON u.id = a.actor_id
                 WHERE a.entity_type = :type AND a.entity_id = :id""";
        MapSqlParameterSource params = new MapSqlParameterSource("type", entityType).addValue("id", id);
        if (!skippedActions.isEmpty()) {
            sql += " AND a.action NOT IN (:skipped)";
            params.addValue("skipped", skippedActions);
        }
        return jdbc.queryForList(sql + " ORDER BY a.occurred_at, a.id", params);
    }

    private static LocalDateTime time(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
