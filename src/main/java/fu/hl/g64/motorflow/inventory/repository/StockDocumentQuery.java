package fu.hl.g64.motorflow.inventory.repository;

import fu.hl.g64.motorflow.inventory.dto.StockDocument;
import fu.hl.g64.motorflow.inventory.dto.StockDocument.Direction;
import fu.hl.g64.motorflow.inventory.dto.StockDocument.Kind;
import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef.SourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Q8, G5: groups the stock movements that change a warehouse's stock into documents, in SQL. Status changes inside a
 * warehouse (return hold and release, dented variant, wrong item reclassified) and opening balances are left out.
 * {@code storeId} null means every warehouse.
 */
@Component
@RequiredArgsConstructor
public class StockDocumentQuery {

    // Dispatch records the goods in transit at the destination. Only the actual receipt creates its incoming document.
    private static final String MOVEMENTS = """
            WITH movements AS (
                SELECT m.*, p.sku, p.part_name, s.store_name, u.full_name, l.expiry_date, r.transfer_order_id AS receipt_transfer_order_id, r.receipt_type, e.discrepancy_case_id, c.goods_receipt_note_id AS case_receipt_id,
                       CASE WHEN m.sales_order_id IS NOT NULL THEN 'SALES_ORDER'
                            WHEN m.goods_receipt_note_id IS NOT NULL THEN 'GOODS_RECEIPT_NOTE'
                            WHEN m.transfer_order_id IS NOT NULL THEN 'TRANSFER_ORDER'
                            WHEN m.discrepancy_resolution_event_id IS NOT NULL THEN 'DISCREPANCY_RESOLUTION_EVENT'
                            WHEN m.adjustment_request_id IS NOT NULL THEN 'ADJUSTMENT_REQUEST'
                            ELSE 'STOCK_COUNT' END AS source_type,
                       COALESCE(m.sales_order_id, m.goods_receipt_note_id, m.transfer_order_id,
                                m.discrepancy_resolution_event_id, m.adjustment_request_id, m.stock_count_id) AS source_id
                FROM stock_movement m
                JOIN product p ON p.id = m.product_id
                JOIN store s ON s.id = m.store_id
                JOIN user_account u ON u.id = m.performed_by
                LEFT JOIN lot l ON l.id = m.lot_id
                LEFT JOIN goods_receipt_note r ON r.id = m.goods_receipt_note_id
                LEFT JOIN discrepancy_resolution_event e ON e.id = m.discrepancy_resolution_event_id
                LEFT JOIN discrepancy_case c ON c.id = e.discrepancy_case_id
                WHERE m.store_id = COALESCE(:store, m.store_id)%s AND (
                   (m.movement_type IN ('SALE_ISSUE', 'SUPPLIER_RETURN') AND m.quantity_change < 0)
                   OR (m.movement_type IN ('RECEIPT_CONFIRMED', 'EXCESS_ACCEPTED', 'EXCESS_REJECTED', 'TRANSFER_RECEIPT')
                       AND m.quantity_change > 0)
                   OR (m.movement_type = 'TRANSFER_DISPATCH' AND m.quantity_change < 0)
                   OR (m.stock_status <> 'IN_TRANSIT'
                       AND m.movement_type IN ('TRANSFER_EXTRA_DISPATCH', 'ADJUSTMENT', 'COUNT_ADJUSTMENT'))
                )
            ), lot_changes AS (
                SELECT movements.*,
                       CASE WHEN movement_type IN ('ADJUSTMENT', 'COUNT_ADJUSTMENT')
                            THEN SUM(quantity_change) OVER same_lot ELSE CAST(quantity_change AS bigint)
                       END AS document_quantity,
                       CASE WHEN movement_type IN ('ADJUSTMENT', 'COUNT_ADJUSTMENT')
                            THEN SUM(value) OVER same_lot ELSE CAST(value AS numeric)
                       END AS document_value,
                       CASE WHEN movement_type IN ('ADJUSTMENT', 'COUNT_ADJUSTMENT')
                            THEN ROW_NUMBER() OVER one_row ELSE 1 END AS lot_row
                FROM movements
                WINDOW same_lot AS (PARTITION BY source_type, source_id, store_id, product_id, lot_id),
                       one_row AS (same_lot ORDER BY occurred_at DESC, id DESC)
            ), physical AS (
                SELECT lot_changes.*, CASE WHEN document_quantity > 0 THEN 'IN' ELSE 'OUT' END AS direction
                FROM lot_changes WHERE lot_row = 1 AND document_quantity <> 0
            ), named AS (
                SELECT physical.*,
                       CASE source_type
                           WHEN 'SALES_ORDER' THEN 'SALE'
                           WHEN 'GOODS_RECEIPT_NOTE' THEN
                               CASE WHEN receipt_type = 'RETURN_TO_SOURCE' THEN 'TRANSFER_RETURN'
                                    WHEN receipt_type = 'TRANSFER_IN' AND direction = 'OUT' THEN 'TRANSFER_EXCESS_DISPATCH'
                                    WHEN receipt_type = 'TRANSFER_IN' THEN 'TRANSFER_RECEIPT'
                                    ELSE 'SUPPLIER_RECEIPT' END
                           WHEN 'TRANSFER_ORDER' THEN CASE WHEN direction = 'OUT' THEN 'TRANSFER_DISPATCH'
                                                          ELSE 'TRANSFER_RECEIPT' END
                           WHEN 'DISCREPANCY_RESOLUTION_EVENT' THEN 'SUPPLIER_RETURN'
                           WHEN 'ADJUSTMENT_REQUEST' THEN 'ADJUSTMENT'
                           ELSE 'STOCK_COUNT' END AS kind,
                       CASE source_type
                           WHEN 'SALES_ORDER' THEN 'SO-' || source_id
                           ELSE CASE source_type
                                    WHEN 'GOODS_RECEIPT_NOTE' THEN 'PN-'
                                    WHEN 'TRANSFER_ORDER' THEN 'DC-'
                                    WHEN 'STOCK_COUNT' THEN 'KK-'
                                    WHEN 'ADJUSTMENT_REQUEST' THEN 'DCH-'
                                    ELSE 'TNCC-' END
                                || LPAD(CAST(source_id AS text), GREATEST(4, LENGTH(CAST(source_id AS text))), '0')
                       END AS code
                FROM physical
            )
            """;

    private static final String GROUPS = """
            , documents AS (
                SELECT source_type, source_id, code, store_id, store_name, direction, MAX(kind) AS kind,
                       MAX(occurred_at) AS occurred_at,
                       MAX(receipt_transfer_order_id) AS receipt_transfer_order_id,
                       MAX(discrepancy_case_id) AS discrepancy_case_id,
                       MAX(case_receipt_id) AS discrepancy_receipt_id,
                       (ARRAY_AGG(full_name ORDER BY occurred_at DESC, id DESC))[1] AS performed_by_name,
                       COUNT(*) AS line_count, SUM(ABS(document_quantity)) AS quantity,
                       SUM(ABS(document_value)) AS total_value
                FROM named
                GROUP BY source_type, source_id, code, store_id, store_name, direction
                HAVING BOOL_OR(:q = '' OR STRPOS(LOWER(code), :q) > 0 OR STRPOS(LOWER(sku), :q) > 0
                     OR STRPOS(LOWER(part_name), :q) > 0)
            )
            """;

    private static final String FILTER = """
            FROM documents WHERE (CAST(:direction AS text) IS NULL OR direction = :direction)
              AND (CAST(:type AS text) IS NULL OR source_type = :type)
              AND (CAST(:kind AS text) IS NULL OR kind = :kind)
              AND (CAST(:fromDate AS timestamp) IS NULL OR occurred_at >= :fromDate)
              AND (CAST(:untilDate AS timestamp) IS NULL OR occurred_at < :untilDate)
            """;

    private final JdbcClient jdbc;

    /**
     * One page of the list and the number of rows of the whole list, from one query: {@code COUNT(*) OVER ()} counts
     * the filtered rows before the LIMIT. {@code total} is -1 when the page has no rows, since there is then no row to
     * read it from; the service counts in that case.
     */
    public record Page(List<StockDocument.Summary> rows, long total) {
    }

    public Page page(Long storeId, StockDocument.Search search, boolean withValue, int page, int size) {
        List<Counted> found = filtered(MOVEMENTS.formatted("") + GROUPS + " SELECT *, COUNT(*) OVER () AS total_items "
                + FILTER + """
                ORDER BY occurred_at DESC, source_type, source_id DESC, store_id, direction
                LIMIT :size OFFSET :offset
                """, storeId, search).param("size", size).param("offset", (long) page * size)
                .query((rs, row) -> new Counted(summary(rs, withValue), rs.getLong("total_items"))).list();
        if (found.isEmpty()) {
            return new Page(List.of(), -1);
        }
        return new Page(found.stream().map(Counted::summary).toList(), found.get(0).total());
    }

    private record Counted(StockDocument.Summary summary, long total) {
    }

    public long count(Long storeId, StockDocument.Search search) {
        return filtered(MOVEMENTS.formatted("") + GROUPS + " SELECT COUNT(*) " + FILTER, storeId, search)
                .query(Long.class).single();
    }

    public Optional<StockDocument.Summary> find(long storeId, SourceType type, long sourceId, Direction direction,
                                                boolean withValue) {
        return jdbc.sql(movementsOf(type) + GROUPS + " SELECT * FROM documents" + ONE)
                .param("store", storeId).param("q", "").param("type", type.name()).param("id", sourceId)
                .param("direction", direction.name()).query((rs, row) -> summary(rs, withValue)).optional();
    }

    public List<StockDocument.Line> lines(long storeId, SourceType type, long sourceId, Direction direction,
                                          boolean withValue) {
        return jdbc.sql(movementsOf(type) + " SELECT * FROM named" + ONE + " ORDER BY id")
                .param("store", storeId).param("type", type.name()).param("id", sourceId)
                .param("direction", direction.name()).query((rs, row) -> new StockDocument.Line(rs.getLong("product_id"),
                        rs.getString("sku"), rs.getString("part_name"), rs.getObject("lot_id", Long.class),
                        rs.getObject("expiry_date", LocalDate.class), Math.abs(rs.getLong("document_quantity")),
                        withValue ? rs.getBigDecimal("unit_cost") : null,
                        withValue ? rs.getBigDecimal("document_value").abs() : null)).list();
    }

    private static final String ONE = " WHERE source_type = :type AND source_id = :id AND direction = :direction";

    /**
     * The movements CTE narrowed to one source. Each movement has exactly one source column set (CHECK
     * {@code num_nonnulls(...) = 1} in V1), so {@code m.<column> = :id} keeps the same movements as
     * {@code source_type = ... AND source_id = :id} does in {@link #ONE}, and the index on the column is used. The
     * windows of the CTE partition by source, so each document keeps all its lines. The column name comes from this
     * switch only, never from input.
     */
    private static String movementsOf(SourceType type) {
        String column = switch (type) {
            case SALES_ORDER -> "sales_order_id";
            case GOODS_RECEIPT_NOTE -> "goods_receipt_note_id";
            case TRANSFER_ORDER -> "transfer_order_id";
            case DISCREPANCY_RESOLUTION_EVENT -> "discrepancy_resolution_event_id";
            case ADJUSTMENT_REQUEST -> "adjustment_request_id";
            case STOCK_COUNT -> "stock_count_id";
        };
        return MOVEMENTS.formatted(" AND m." + column + " = :id");
    }

    private JdbcClient.StatementSpec filtered(String sql, Long storeId, StockDocument.Search search) {
        return jdbc.sql(sql).param("store", storeId).param("q", search.q())
                .param("direction", search.direction() == null ? null : search.direction().name())
                .param("type", search.type() == null ? null : search.type().name())
                .param("kind", search.kind() == null ? null : search.kind().name())
                .param("fromDate", search.from() == null ? null : search.from().atStartOfDay())
                .param("untilDate", search.to() == null ? null : search.to().plusDays(1).atStartOfDay());
    }

    private static StockDocument.Summary summary(ResultSet rs, boolean withValue) throws SQLException {
        return new StockDocument.Summary(SourceType.valueOf(rs.getString("source_type")), rs.getLong("source_id"),
                rs.getString("code"), Kind.valueOf(rs.getString("kind")), rs.getLong("store_id"),
                rs.getString("store_name"), Direction.valueOf(rs.getString("direction")),
                rs.getObject("occurred_at", LocalDateTime.class), rs.getString("performed_by_name"),
                rs.getLong("line_count"), rs.getLong("quantity"), withValue ? rs.getBigDecimal("total_value") : null,
                rs.getObject("receipt_transfer_order_id", Long.class), rs.getObject("discrepancy_case_id", Long.class),
                rs.getObject("discrepancy_receipt_id", Long.class));
    }
}
