package fu.hl.g64.motorflow.inventory.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef.SourceType;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1/stock-documents")
@PreAuthorize("hasAnyRole('OWNER', 'BRANCH_MANAGER', 'STAFF')")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockDocumentController {

    public enum Direction { IN, OUT }

    public enum Kind {
        SALE, SUPPLIER_RECEIPT, SUPPLIER_RETURN, TRANSFER_DISPATCH, TRANSFER_RECEIPT,
        TRANSFER_RETURN, TRANSFER_EXCESS_DISPATCH, ADJUSTMENT, STOCK_COUNT
    }

    // Dispatch records the goods in transit at the destination. Only the actual receipt creates its incoming document.
    private static final String MOVEMENTS = """
            WITH movements AS (
                SELECT m.*, p.sku, p.part_name, s.store_name, u.full_name, l.expiry_date, r.transfer_order_id AS receipt_transfer_order_id, r.receipt_type, e.discrepancy_case_id,
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
                WHERE m.store_id = COALESCE(:store, m.store_id) AND (
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
                           WHEN 'GOODS_RECEIPT_NOTE' THEN 'PN-' || LPAD(CAST(source_id AS text),
                                    GREATEST(4, LENGTH(CAST(source_id AS text))), '0')
                           WHEN 'TRANSFER_ORDER' THEN 'DC-' || LPAD(CAST(source_id AS text),
                                    GREATEST(4, LENGTH(CAST(source_id AS text))), '0')
                           ELSE source_type || '-' || source_id END AS code
                FROM physical
            )
            """;

    private static final String GROUPS = """
            , documents AS (
                SELECT source_type, source_id, code, store_id, store_name, direction, MAX(kind) AS kind,
                       MAX(occurred_at) AS occurred_at,
                       MAX(receipt_transfer_order_id) AS receipt_transfer_order_id,
                       MAX(discrepancy_case_id) AS discrepancy_case_id,
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

    @GetMapping
    public PageResponse<Summary> list(@AuthenticationPrincipal AuthenticatedUser user,
                                      @RequestParam(required = false) Long storeId,
                                      @RequestParam(required = false) Direction direction,
                                      @RequestParam(required = false) SourceType type,
                                      @RequestParam(required = false) Kind kind,
                                      @RequestParam(required = false) LocalDate from,
                                      @RequestParam(required = false) LocalDate to,
                                      @RequestParam(defaultValue = "") String q,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG02");
        }
        Long scope = store(user, storeId);
        int offsetPage = Math.max(0, page);
        int pageSize = Math.clamp(size, 1, 100);
        String search = q.strip().toLowerCase(Locale.ROOT);
        var until = to == null ? null : to.plusDays(1).atStartOfDay();
        var start = from == null ? null : from.atStartOfDay();
        String dir = direction == null ? null : direction.name();
        String source = type == null ? null : type.name();
        var items = jdbc.sql(MOVEMENTS + GROUPS + " SELECT * " + FILTER + """
                ORDER BY occurred_at DESC, source_type, source_id DESC, store_id, direction
                LIMIT :size OFFSET :offset
                """).param("store", scope).param("q", search).param("direction", dir).param("type", source)
                .param("kind", kind == null ? null : kind.name()).param("fromDate", start).param("untilDate", until)
                .param("size", pageSize).param("offset", (long) offsetPage * pageSize)
                .query((rs, row) -> summary(rs, user.role() == Role.OWNER)).list();
        long count = jdbc.sql(MOVEMENTS + GROUPS + " SELECT COUNT(*) " + FILTER)
                .param("store", scope).param("q", search).param("direction", dir).param("type", source)
                .param("kind", kind == null ? null : kind.name()).param("fromDate", start).param("untilDate", until).query(Long.class).single();
        return new PageResponse<>(items, count, offsetPage, pageSize);
    }

    @GetMapping("/{type}/{sourceId}")
    public Detail detail(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable SourceType type,
                         @PathVariable long sourceId, @RequestParam(required = false) Long storeId,
                         @RequestParam Direction direction) {
        Long scope = store(user, storeId);
        if (scope == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG02");
        }
        boolean owner = user.role() == Role.OWNER;
        String filter = " WHERE source_type = :type AND source_id = :id AND direction = :direction";
        Summary summary = jdbc.sql(MOVEMENTS + GROUPS + " SELECT * FROM documents" + filter)
                .param("store", scope).param("q", "").param("type", type.name()).param("id", sourceId)
                .param("direction", direction.name()).query((rs, row) -> summary(rs, owner)).optional()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG70"));
        List<Line> lines = jdbc.sql(MOVEMENTS + " SELECT * FROM named" + filter + " ORDER BY id")
                .param("store", scope).param("type", type.name()).param("id", sourceId)
                .param("direction", direction.name()).query((rs, row) -> new Line(rs.getLong("product_id"),
                        rs.getString("sku"), rs.getString("part_name"), rs.getObject("lot_id", Long.class),
                        rs.getObject("expiry_date", LocalDate.class), Math.abs(rs.getLong("document_quantity")),
                        owner ? rs.getBigDecimal("unit_cost") : null,
                        owner ? rs.getBigDecimal("document_value").abs() : null)).list();
        return new Detail(summary, lines);
    }

    private static Long store(AuthenticatedUser user, Long requested) {
        if (user.role() == Role.OWNER) {
            return requested;
        }
        if (user.storeId() == null || (requested != null && !requested.equals(user.storeId()))) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        return user.storeId();
    }

    private static Summary summary(java.sql.ResultSet rs, boolean owner) throws java.sql.SQLException {
        return new Summary(SourceType.valueOf(rs.getString("source_type")), rs.getLong("source_id"),
                rs.getString("code"), Kind.valueOf(rs.getString("kind")), rs.getLong("store_id"), rs.getString("store_name"),
                Direction.valueOf(rs.getString("direction")), rs.getObject("occurred_at", LocalDateTime.class),
                rs.getString("performed_by_name"), rs.getLong("line_count"), rs.getLong("quantity"),
                owner ? rs.getBigDecimal("total_value") : null,
                rs.getObject("receipt_transfer_order_id", Long.class), rs.getObject("discrepancy_case_id", Long.class));
    }

    public record Summary(SourceType sourceType, long sourceId, String code, Kind kind, long storeId, String storeName,
                          Direction direction, LocalDateTime occurredAt, String performedByName,
                          long lineCount, long quantity, @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal value, Long transferOrderId, Long discrepancyCaseId) {
    }

    public record Detail(Summary document, List<Line> lines) {
    }

    public record Line(long productId, String sku, String partName, Long lotId, LocalDate expiryDate,
                       long quantity, @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal unitCost,
                       @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal value) {
    }
}
