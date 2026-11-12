package fu.hl.g64.motorflow.document.service.impl;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus;
import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentType;
import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.document.dto.AdjustmentContent;
import fu.hl.g64.motorflow.document.dto.DocumentDetail;
import fu.hl.g64.motorflow.document.dto.DocumentDetail.Adjustment;
import fu.hl.g64.motorflow.document.dto.DocumentDetail.HistoryEntry;
import fu.hl.g64.motorflow.document.dto.DocumentDetail.Link;
import fu.hl.g64.motorflow.document.dto.DocumentPrint;
import fu.hl.g64.motorflow.document.dto.DocumentPrint.Line;
import fu.hl.g64.motorflow.document.dto.DocumentPrint.Party;
import fu.hl.g64.motorflow.document.dto.DocumentPrint.Totals;
import fu.hl.g64.motorflow.document.dto.DocumentRow;
import fu.hl.g64.motorflow.document.dto.DocumentSearch;
import fu.hl.g64.motorflow.document.dto.DocumentType;
import fu.hl.g64.motorflow.document.dto.PaymentContent;
import fu.hl.g64.motorflow.document.dto.PrintForm;
import fu.hl.g64.motorflow.document.repository.DocumentQuery;
import fu.hl.g64.motorflow.document.repository.DocumentQuery.Filter;
import fu.hl.g64.motorflow.document.repository.DocumentQuery.Found;
import fu.hl.g64.motorflow.document.service.DocumentService;
import fu.hl.g64.motorflow.operation.entity.enums.OwnerType;
import fu.hl.g64.motorflow.operation.service.AttachmentService;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentMethod;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentStatus;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentType;
import fu.hl.g64.motorflow.receiving.service.GoodsReceiptService;
import fu.hl.g64.motorflow.sales.service.SalesOrderService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.transfer.service.TransferOrderService;
import fu.hl.g64.motorflow.transfer.service.TransferReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    /** BR-01: "#15", "15" or " # 15 ". */
    private static final Pattern NUMBER = Pattern.compile("^\\s*#?\\s*(\\d{1,18})\\s*$");

    private final DocumentQuery documents;
    private final SalesOrderService salesOrders;
    private final GoodsReceiptService goodsReceipts;
    private final TransferReceiptService transferReceipts;
    private final TransferOrderService transferOrders;
    private final AttachmentService attachments;
    private final Clock clock;

    // UC-78
    @Transactional(readOnly = true)
    @Override
    public PageResponse<DocumentRow> search(AuthenticatedUser user, DocumentSearch search, int page, int size) {
        Long id = null;
        if (search.number() != null && !search.number().isBlank()) {
            Matcher number = NUMBER.matcher(search.number());
            if (!number.matches()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG02");
            }
            id = Long.parseLong(number.group(1));
        }
        if (search.from() != null && search.to() != null && search.from().isAfter(search.to())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG251");
        }
        Long scope = scopeOf(user, search.storeId());
        Set<DocumentType> types = search.type() == null ? EnumSet.noneOf(DocumentType.class)
                : EnumSet.of(search.type());
        Filter filter = new Filter(types, id, search.from(), search.to(), scope);
        int pageNo = Math.max(page, 0);
        int pageSize = Math.clamp(size, 1, 100);
        List<DocumentRow> rows = documents.find(filter, isOwner(user), pageNo * pageSize, pageSize).stream()
                .map(DocumentServiceImpl::row).toList();
        return new PageResponse<>(rows, documents.count(filter), pageNo, pageSize);
    }

    // UC-79
    @Transactional(readOnly = true)
    @Override
    public DocumentDetail detail(AuthenticatedUser user, DocumentType type, long id) {
        Found found = open(user, type, id);
        DocumentRow row = row(found);
        return switch (type) {
            case SALES_ORDER -> salesOrderDetail(user, row);
            case GOODS_RECEIPT -> receiptDetail(user, row);
            case TRANSFER_ORDER -> transferDetail(user, row);
            case PAYMENT -> paymentDetail(row);
            case ADJUSTMENT -> adjustmentDetail(row);
        };
    }

    // UC-80
    @Transactional(readOnly = true)
    @Override
    public DocumentPrint print(AuthenticatedUser user, DocumentType type, long id, PrintForm form) {
        Found found = open(user, type, id);
        List<PrintForm> forms = formsOf(found.type(), found.status());
        PrintForm chosen = form == null && !forms.isEmpty() ? forms.getFirst() : form;
        if (chosen == null || !forms.contains(chosen)) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG252");
        }
        DocumentRow row = row(found);
        return switch (chosen) {
            case SALES_INVOICE -> salesInvoice(user, row);
            case STOCK_ISSUE_NOTE -> type == DocumentType.SALES_ORDER ? salesIssueNote(user, row)
                    : transferIssueNote(user, row);
            case GOODS_RECEIPT_NOTE -> receiptNote(user, row);
            case PAYMENT_RECEIPT -> paymentReceipt(user, row);
            case ADJUSTMENT_NOTE -> adjustmentNote(user, row);
        };
    }

    // ---- access ------------------------------------------------------------------------------------------------------

    /** Owner sees every store, or the one asked for; a Branch Manager only their own store (BR-03). */
    private static Long scopeOf(AuthenticatedUser user, Long requestedStoreId) {
        if (isOwner(user)) {
            return requestedStoreId;
        }
        if (user.role() != Role.BRANCH_MANAGER || user.storeId() == null
                || (requestedStoreId != null && !requestedStoreId.equals(user.storeId()))) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        return user.storeId();
    }

    /** A posted document of the user's scope; a draft or unfinished one is not a document yet (MSG250). */
    private Found open(AuthenticatedUser user, DocumentType type, long id) {
        Long scope = scopeOf(user, null);
        Found found = documents.findPosted(type, id, isOwner(user))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG250"));
        boolean inScope = scope == null || scope == found.storeId()
                || (type == DocumentType.TRANSFER_ORDER && Objects.equals(scope, found.otherStoreId()));
        if (!inScope) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        return found;
    }

    private static boolean isOwner(AuthenticatedUser user) {
        return user.role() == Role.OWNER;
    }

    // ---- rows --------------------------------------------------------------------------------------------------------

    private static DocumentRow row(Found found) {
        return new DocumentRow(found.type(), found.id(), number(found.id()), found.subType(), found.status(),
                found.documentDate(), found.storeId(), found.storeName(), found.otherStoreId(),
                found.otherStoreName(), found.partnerName(), found.value(), formsOf(found.type(), found.status()));
    }

    private static List<PrintForm> formsOf(DocumentType type, String status) {
        return switch (type) {
            // A cancelled order sold nothing: there is no invoice or issue note to print.
            case SALES_ORDER -> "COMPLETED".equals(status)
                    ? List.of(PrintForm.SALES_INVOICE, PrintForm.STOCK_ISSUE_NOTE) : List.of();
            case GOODS_RECEIPT -> List.of(PrintForm.GOODS_RECEIPT_NOTE);
            case TRANSFER_ORDER -> List.of(PrintForm.STOCK_ISSUE_NOTE);
            case PAYMENT -> List.of(PrintForm.PAYMENT_RECEIPT);
            case ADJUSTMENT -> List.of(PrintForm.ADJUSTMENT_NOTE);
        };
    }

    private static String number(long id) {
        return "#" + id;
    }

    /** The document type UC-79 opens a linked record as, when it is a posted document. */
    private static DocumentType posted(String recordType, String status) {
        return switch (recordType) {
            case "SALES_ORDER" -> "COMPLETED".equals(status) || "CANCELLED".equals(status)
                    ? DocumentType.SALES_ORDER : null;
            case "GOODS_RECEIPT" -> "CONFIRMED".equals(status) ? DocumentType.GOODS_RECEIPT : null;
            case "TRANSFER_ORDER" -> "AWAITING_DISPATCH".equals(status) ? null : DocumentType.TRANSFER_ORDER;
            case "PAYMENT" -> "VERIFIED".equals(status) ? DocumentType.PAYMENT : null;
            case "ADJUSTMENT" -> "DRAFT".equals(status) ? null : DocumentType.ADJUSTMENT;
            default -> null;
        };
    }

    private static Link link(String recordType, Map<String, Object> row, String note) {
        long id = lng(row, "id");
        String status = str(row, "status");
        return new Link(recordType, id, number(id), status, posted(recordType, status), note);
    }

    // ---- UC-79 -------------------------------------------------------------------------------------------------------

    private DocumentDetail salesOrderDetail(AuthenticatedUser user, DocumentRow row) {
        Map<String, Long> id = Map.of("id", row.id());
        List<Link> related = new ArrayList<>();
        documents.rows("SELECT id, status, payment_type FROM payment WHERE sales_order_id = :id ORDER BY id", id)
                .forEach(payment -> related.add(link("PAYMENT", payment, str(payment, "payment_type"))));
        documents.rows("""
                SELECT r.id, CASE WHEN r.settled_at IS NULL THEN 'OPEN' ELSE 'SETTLED' END AS status
                  FROM receivable r WHERE r.sales_order_id = :id""", id)
                .forEach(debt -> related.add(link("RECEIVABLE", debt, null)));
        documents.rows("""
                SELECT DISTINCT p.id, p.status, p.payment_type
                  FROM payment_allocation a JOIN receivable r ON r.id = a.receivable_id JOIN payment p ON p.id = a.payment_id
                 WHERE r.sales_order_id = :id ORDER BY p.id""", id)
                .forEach(payment -> related.add(link("PAYMENT", payment, str(payment, "payment_type"))));
        return new DocumentDetail(row, salesOrders.get(user, row.id()), List.of(), related,
                adjustments("sales_order_id", row.id()), history("SALES_ORDER", row.id()));
    }

    private DocumentDetail receiptDetail(AuthenticatedUser user, DocumentRow row) {
        Map<String, Object> receipt = documents.row(
                "SELECT receipt_type, purchase_order_id, transfer_order_id FROM goods_receipt_note WHERE id = :id",
                Map.of("id", row.id())).orElseThrow();
        List<Link> sources = new ArrayList<>();
        Object content;
        Long transferOrderId = lng(receipt, "transfer_order_id");
        if (transferOrderId == null) {
            documents.row("SELECT id, status FROM purchase_order WHERE id = :id",
                    Map.of("id", lng(receipt, "purchase_order_id")))
                    .ifPresent(order -> sources.add(link("PURCHASE_ORDER", order, null)));
            content = goodsReceipts.get(user, row.id());
        } else {
            documents.row("SELECT id, status FROM transfer_order WHERE id = :id", Map.of("id", transferOrderId))
                    .ifPresent(order -> sources.add(link("TRANSFER_ORDER", order, null)));
            content = transferReceipts.receipt(user, transferOrderId, row.id());
        }
        List<Link> related = new ArrayList<>();
        documents.rows("SELECT id, status FROM discrepancy_case WHERE goods_receipt_note_id = :id ORDER BY id",
                Map.of("id", row.id())).forEach(found -> related.add(link("DISCREPANCY_CASE", found, null)));
        return new DocumentDetail(row, content, sources, related, adjustments("goods_receipt_note_id", row.id()),
                history("GOODS_RECEIPT_NOTE", row.id()));
    }

    private DocumentDetail transferDetail(AuthenticatedUser user, DocumentRow row) {
        Map<String, Long> id = Map.of("id", row.id());
        List<Link> sources = new ArrayList<>();
        documents.rows("""
                SELECT q.id, q.status FROM replenishment_request q
                  JOIN transfer_order t ON t.replenishment_request_id = q.id WHERE t.id = :id""", id)
                .forEach(request -> sources.add(link("REPLENISHMENT_REQUEST", request, null)));
        List<Link> related = new ArrayList<>();
        documents.rows("""
                SELECT id, status, receipt_type FROM goods_receipt_note WHERE transfer_order_id = :id ORDER BY id""", id)
                .forEach(receipt -> related.add(link("GOODS_RECEIPT", receipt, str(receipt, "receipt_type"))));
        documents.rows("SELECT id, status FROM discrepancy_case WHERE transfer_order_id = :id ORDER BY id", id)
                .forEach(found -> related.add(link("DISCREPANCY_CASE", found, null)));
        return new DocumentDetail(row, transferOrders.detail(user, row.id()), sources, related,
                adjustments("transfer_order_id", row.id()), history("TRANSFER_ORDER", row.id()));
    }

    private DocumentDetail paymentDetail(DocumentRow row) {
        PaymentContent payment = payment(row.id());
        List<Link> sources = new ArrayList<>();
        if (payment.salesOrderId() != null) {
            documents.row("SELECT id, status FROM sales_order WHERE id = :id", Map.of("id", payment.salesOrderId()))
                    .ifPresent(order -> sources.add(link("SALES_ORDER", order, null)));
        }
        for (PaymentContent.Allocation allocation : payment.allocations()) {
            documents.row("""
                    SELECT id, CASE WHEN settled_at IS NULL THEN 'OPEN' ELSE 'SETTLED' END AS status
                      FROM receivable WHERE id = :id""", Map.of("id", allocation.receivableId()))
                    .ifPresent(debt -> sources.add(link("RECEIVABLE", debt, null)));
            if (allocation.salesOrderId() != null) {
                documents.row("SELECT id, status FROM sales_order WHERE id = :id",
                        Map.of("id", allocation.salesOrderId()))
                        .ifPresent(order -> sources.add(link("SALES_ORDER", order, null)));
            }
        }
        // Recording and verifying are on the payment itself; the audit log adds what came later.
        List<HistoryEntry> history = new ArrayList<>();
        history.add(new HistoryEntry("RECORDED", payment.recordedById(), payment.recordedBy(), payment.recordedAt(),
                null));
        if (payment.verifiedAt() != null) {
            history.add(new HistoryEntry("VERIFIED", payment.verifiedById(), payment.verifiedBy(),
                    payment.verifiedAt(), null));
        }
        documents.history("PAYMENT", row.id(), List.of("DEBT_PAYMENT_RECORDED")).forEach(entry ->
                history.add(historyEntry(entry)));
        return new DocumentDetail(row, payment, sources, List.of(), adjustments("payment_id", row.id()), history);
    }

    private DocumentDetail adjustmentDetail(DocumentRow row) {
        Map<String, Object> request = documents.row("""
                SELECT sales_order_id, payment_id, goods_receipt_note_id, transfer_order_id, receivable_id
                  FROM adjustment_request WHERE id = :id""", Map.of("id", row.id())).orElseThrow();
        List<Link> sources = new ArrayList<>();
        linked(request, "sales_order_id", "SALES_ORDER", "SELECT id, status FROM sales_order WHERE id = :id", sources);
        linked(request, "payment_id", "PAYMENT", "SELECT id, status FROM payment WHERE id = :id", sources);
        linked(request, "goods_receipt_note_id", "GOODS_RECEIPT",
                "SELECT id, status FROM goods_receipt_note WHERE id = :id", sources);
        linked(request, "transfer_order_id", "TRANSFER_ORDER", "SELECT id, status FROM transfer_order WHERE id = :id",
                sources);
        linked(request, "receivable_id", "RECEIVABLE", """
                SELECT id, CASE WHEN settled_at IS NULL THEN 'OPEN' ELSE 'SETTLED' END AS status
                  FROM receivable WHERE id = :id""", sources);
        return new DocumentDetail(row, adjustment(row.id()), sources, List.of(), List.of(),
                history("ADJUSTMENT_REQUEST", row.id()));
    }

    private void linked(Map<String, Object> request, String column, String recordType, String sql,
                        List<Link> sources) {
        Long id = lng(request, column);
        if (id != null) {
            documents.row(sql, Map.of("id", id)).ifPresent(found -> sources.add(link(recordType, found, null)));
        }
    }

    private List<Adjustment> adjustments(String column, long id) {
        return documents.rows("""
                SELECT id, adjustment_type, status, reason, submitted_at, decided_at, applied_at
                  FROM adjustment_request
                 WHERE %s = :id AND status <> 'DRAFT' AND submitted_at IS NOT NULL
                 ORDER BY submitted_at, id""".formatted(column), Map.of("id", id)).stream()
                .map(found -> new Adjustment(lng(found, "id"), number(lng(found, "id")),
                        AdjustmentType.valueOf(str(found, "adjustment_type")),
                        AdjustmentRequestStatus.valueOf(str(found, "status")), str(found, "reason"),
                        time(found, "submitted_at"), time(found, "decided_at"), time(found, "applied_at")))
                .toList();
    }

    private List<HistoryEntry> history(String entityType, long id) {
        return documents.history(entityType, id, List.of()).stream().map(DocumentServiceImpl::historyEntry).toList();
    }

    private static HistoryEntry historyEntry(Map<String, Object> entry) {
        return new HistoryEntry(str(entry, "action"), lng(entry, "actor_id"), str(entry, "full_name"),
                time(entry, "occurred_at"), str(entry, "reason"));
    }

    private PaymentContent payment(long id) {
        Map<String, Object> payment = documents.row("""
                SELECT p.*, so.customer_id AS order_customer_id, so.customer_name_snapshot,
                       rec.full_name AS recorded_name, ver.full_name AS verified_name
                  FROM payment p
                  LEFT JOIN sales_order so ON so.id = p.sales_order_id
                  LEFT JOIN user_account rec ON rec.id = p.recorded_by
                  LEFT JOIN user_account ver ON ver.id = p.verified_by
                 WHERE p.id = :id""", Map.of("id", id)).orElseThrow();
        List<Map<String, Object>> allocated = documents.rows("""
                SELECT a.receivable_id, a.allocated_amount, r.sales_order_id, r.customer_id, c.customer_name
                  FROM payment_allocation a JOIN receivable r ON r.id = a.receivable_id
                  JOIN customer c ON c.id = r.customer_id
                 WHERE a.payment_id = :id ORDER BY a.id""", Map.of("id", id));
        Long customerId = lng(payment, "order_customer_id");
        String customerName = str(payment, "customer_name_snapshot");
        if (!allocated.isEmpty()) {
            customerId = lng(allocated.getFirst(), "customer_id");
            customerName = str(allocated.getFirst(), "customer_name");
        }
        long amount = lng(payment, "amount");
        Long tendered = lng(payment, "tendered_amount");
        return new PaymentContent(id, PaymentType.valueOf(str(payment, "payment_type")),
                PaymentMethod.valueOf(str(payment, "payment_method")), amount, tendered,
                tendered == null ? null : tendered - amount, str(payment, "transaction_reference"),
                PaymentStatus.valueOf(str(payment, "status")), lng(payment, "sales_order_id"), customerId,
                customerName, lng(payment, "recorded_by"), str(payment, "recorded_name"),
                time(payment, "recorded_at"), lng(payment, "verified_by"), str(payment, "verified_name"),
                time(payment, "verified_at"),
                allocated.stream().map(found -> new PaymentContent.Allocation(lng(found, "receivable_id"),
                        lng(found, "sales_order_id"), lng(found, "allocated_amount"))).toList(),
                attachments.list(OwnerType.PAYMENT, id));
    }

    private AdjustmentContent adjustment(long id) {
        Map<String, Object> request = documents.row("""
                SELECT a.*, req.full_name AS requested_name, dcd.full_name AS decided_name
                  FROM adjustment_request a
                  LEFT JOIN user_account req ON req.id = a.requested_by
                  LEFT JOIN user_account dcd ON dcd.id = a.decided_by
                 WHERE a.id = :id""", Map.of("id", id)).orElseThrow();
        List<AdjustmentContent.StockLine> stockLines = documents.rows("""
                SELECT s.store_id, s.product_id, p.sku, p.part_name, p.base_unit, s.lot_id, l.manufacturer_lot_no,
                       s.stock_status, s.quantity_change
                  FROM adjustment_stock_line s JOIN product p ON p.id = s.product_id
                  LEFT JOIN lot l ON l.id = s.lot_id
                 WHERE s.adjustment_request_id = :id ORDER BY s.id""", Map.of("id", id)).stream()
                .map(line -> new AdjustmentContent.StockLine(lng(line, "store_id"), lng(line, "product_id"),
                        str(line, "sku"), str(line, "part_name"), str(line, "base_unit"), lng(line, "lot_id"),
                        str(line, "manufacturer_lot_no"), str(line, "stock_status"),
                        lng(line, "quantity_change").intValue())).toList();
        List<AdjustmentContent.AmountLine> amountLines = documents.rows("""
                SELECT payment_id, receivable_id, amount_change FROM adjustment_amount_line
                 WHERE adjustment_request_id = :id ORDER BY id""", Map.of("id", id)).stream()
                .map(line -> new AdjustmentContent.AmountLine(lng(line, "payment_id"), lng(line, "receivable_id"),
                        lng(line, "amount_change"))).toList();
        return new AdjustmentContent(id, AdjustmentType.valueOf(str(request, "adjustment_type")),
                AdjustmentRequestStatus.valueOf(str(request, "status")), lng(request, "store_id"),
                str(request, "reason"), lng(request, "requested_by"), str(request, "requested_name"),
                time(request, "created_at"), time(request, "submitted_at"), lng(request, "decided_by"),
                str(request, "decided_name"), time(request, "decided_at"), str(request, "decision_reason"),
                time(request, "applied_at"), stockLines, amountLines,
                attachments.list(OwnerType.ADJUSTMENT_REQUEST, id));
    }

    // ---- UC-80 -------------------------------------------------------------------------------------------------------

    private DocumentPrint salesInvoice(AuthenticatedUser user, DocumentRow row) {
        Map<String, Object> order = salesOrder(row.id());
        List<Line> lines = new ArrayList<>();
        long reference = 0;
        long total = 0;
        for (Map<String, Object> found : documents.rows("""
                SELECT l.product_id, p.sku, p.part_name, p.base_unit, l.delivered_qty, l.reference_price,
                       l.actual_unit_price
                  FROM sales_order_line l JOIN product p ON p.id = l.product_id
                 WHERE l.sales_order_id = :id AND l.delivered_qty > 0 ORDER BY l.line_no""", Map.of("id", row.id()))) {
            int quantity = lng(found, "delivered_qty").intValue();
            long price = lng(found, "actual_unit_price");
            long amount = Math.multiplyExact(quantity, price);
            reference = Math.addExact(reference, Math.multiplyExact(quantity, lng(found, "reference_price")));
            total = Math.addExact(total, amount);
            lines.add(new Line(lines.size() + 1, lng(found, "product_id"), str(found, "sku"),
                    str(found, "part_name"), str(found, "base_unit"), null, null, quantity, price, amount));
        }
        Map<String, Object> money = documents.row("""
                SELECT COALESCE(SUM(CASE WHEN payment_type = 'REFUND' THEN -amount ELSE amount END), 0) AS paid
                  FROM payment WHERE sales_order_id = :id AND status = 'VERIFIED'""", Map.of("id", row.id()))
                .orElseThrow();
        Long credit = documents.row("SELECT original_amount FROM receivable WHERE sales_order_id = :id",
                Map.of("id", row.id())).map(debt -> lng(debt, "original_amount")).orElse(null);
        Totals totals = new Totals(reference, reference - total, (BigDecimal) order.get("discount_rate"), total,
                lng(money, "paid"), credit);
        return new DocumentPrint(PrintForm.SALES_INVOICE, row.type(), row.id(), row.number(), row.status(),
                row.documentDate(), now(), user.fullName(), store(row.storeId()), customer(order), lines, totals,
                null, str(order, "completion_type"));
    }

    private DocumentPrint salesIssueNote(AuthenticatedUser user, DocumentRow row) {
        Map<String, Object> order = salesOrder(row.id());
        List<Line> lines = issuedLots("""
                SELECT m.product_id, p.sku, p.part_name, p.base_unit, m.lot_id, l.manufacturer_lot_no,
                       -SUM(m.quantity_change) AS quantity
                  FROM stock_movement m JOIN product p ON p.id = m.product_id LEFT JOIN lot l ON l.id = m.lot_id
                 WHERE m.movement_type = 'SALE_ISSUE' AND m.sales_order_id = :id AND m.quantity_change < 0
                 GROUP BY m.product_id, p.sku, p.part_name, p.base_unit, m.lot_id, l.manufacturer_lot_no
                 ORDER BY p.sku, m.lot_id""", row.id());
        return new DocumentPrint(PrintForm.STOCK_ISSUE_NOTE, row.type(), row.id(), row.number(), row.status(),
                row.documentDate(), now(), user.fullName(), store(row.storeId()), customer(order), lines,
                null, null, null);
    }

    /** What left the source warehouse, lot by lot (BR-75), including an extra dispatch (UC-75). */
    private DocumentPrint transferIssueNote(AuthenticatedUser user, DocumentRow row) {
        List<Line> lines = issuedLots("""
                SELECT m.product_id, p.sku, p.part_name, p.base_unit, m.lot_id, l.manufacturer_lot_no,
                       -SUM(m.quantity_change) AS quantity
                  FROM stock_movement m JOIN product p ON p.id = m.product_id LEFT JOIN lot l ON l.id = m.lot_id
                  JOIN transfer_order t ON t.id = m.transfer_order_id
                 WHERE m.movement_type IN ('TRANSFER_DISPATCH', 'TRANSFER_EXTRA_DISPATCH') AND m.transfer_order_id = :id
                   AND m.store_id = t.source_store_id AND m.stock_status = 'AVAILABLE' AND m.quantity_change < 0
                 GROUP BY m.product_id, p.sku, p.part_name, p.base_unit, m.lot_id, l.manufacturer_lot_no
                 ORDER BY p.sku, m.lot_id""", row.id());
        return new DocumentPrint(PrintForm.STOCK_ISSUE_NOTE, row.type(), row.id(), row.number(), row.status(),
                row.documentDate(), now(), user.fullName(), store(row.storeId()), store(row.otherStoreId()), lines,
                null, null, null);
    }

    private List<Line> issuedLots(String sql, long id) {
        List<Line> lines = new ArrayList<>();
        for (Map<String, Object> found : documents.rows(sql, Map.of("id", id))) {
            lines.add(new Line(lines.size() + 1, lng(found, "product_id"), str(found, "sku"),
                    str(found, "part_name"), str(found, "base_unit"), lng(found, "lot_id"),
                    str(found, "manufacturer_lot_no"), lng(found, "quantity").intValue(), null, null));
        }
        return lines;
    }

    /**
     * What went into stock: passed goods, less excess Owner has not accepted (BR-67). Purchase prices for Owner only
     * (BR-72).
     */
    private DocumentPrint receiptNote(AuthenticatedUser user, DocumentRow row) {
        boolean owner = isOwner(user);
        Map<String, Object> receipt = documents.row("""
                SELECT g.delivery_document_no, g.delivery_document_date, s.id AS supplier_id, s.supplier_name,
                       s.address, s.phone, s.tax_code
                  FROM goods_receipt_note g
                  LEFT JOIN purchase_order po ON po.id = g.purchase_order_id
                  LEFT JOIN supplier s ON s.id = po.supplier_id
                 WHERE g.id = :id""", Map.of("id", row.id())).orElseThrow();
        List<Line> lines = new ArrayList<>();
        long total = 0;
        for (Map<String, Object> found : documents.rows("""
                SELECT gl.product_id, p.sku, p.part_name, p.base_unit, l.id AS lot_id, gl.manufacturer_lot_no,
                       gl.passed_qty - CASE WHEN gl.excess_decision = 'ACCEPTED' THEN 0 ELSE gl.excess_qty END AS quantity,
                       gl.unit_price
                  FROM goods_receipt_note_line gl JOIN product p ON p.id = gl.product_id
                  LEFT JOIN lot l ON l.goods_receipt_note_line_id = gl.id
                 WHERE gl.goods_receipt_note_id = :id ORDER BY gl.id""", Map.of("id", row.id()))) {
            int quantity = lng(found, "quantity").intValue();
            if (quantity <= 0) {
                continue;
            }
            Long price = owner ? lng(found, "unit_price") : null;
            Long amount = price == null ? null : Math.multiplyExact(quantity, price);
            total += amount == null ? 0 : amount;
            lines.add(new Line(lines.size() + 1, lng(found, "product_id"), str(found, "sku"),
                    str(found, "part_name"), str(found, "base_unit"), lng(found, "lot_id"),
                    str(found, "manufacturer_lot_no"), quantity, price, amount));
        }
        Party counterpart = lng(receipt, "supplier_id") == null ? store(row.otherStoreId())
                : new Party(lng(receipt, "supplier_id"), str(receipt, "supplier_name"), str(receipt, "address"),
                str(receipt, "phone"), str(receipt, "tax_code"));
        String note = str(receipt, "delivery_document_no");
        boolean priced = owner && lng(receipt, "supplier_id") != null;
        return new DocumentPrint(PrintForm.GOODS_RECEIPT_NOTE, row.type(), row.id(), row.number(), row.status(),
                row.documentDate(), now(), user.fullName(), store(row.storeId()), counterpart, lines,
                priced ? new Totals(null, null, null, total, null, null) : null, null, note);
    }

    private DocumentPrint paymentReceipt(AuthenticatedUser user, DocumentRow row) {
        PaymentContent payment = payment(row.id());
        Party customer = new Party(payment.customerId(), payment.customerName(), null, null, null);
        if (payment.salesOrderId() != null) {
            customer = customer(salesOrder(payment.salesOrderId()));
        } else if (payment.customerId() != null) {
            customer = documents.row("SELECT id, customer_name, phone, address, tax_code FROM customer WHERE id = :id",
                    Map.of("id", payment.customerId())).map(found -> new Party(lng(found, "id"),
                    str(found, "customer_name"), str(found, "address"), str(found, "phone"),
                    str(found, "tax_code"))).orElse(customer);
        }
        return new DocumentPrint(PrintForm.PAYMENT_RECEIPT, row.type(), row.id(), row.number(), row.status(),
                row.documentDate(), now(), user.fullName(), store(row.storeId()), customer, List.of(),
                new Totals(null, null, null, payment.amount(), null, null),
                new DocumentPrint.Payment(payment.paymentType(), payment.paymentMethod(), payment.amount(),
                        payment.tenderedAmount(), payment.changeGiven(), payment.transactionReference(),
                        payment.allocations()),
                payment.salesOrderId() == null ? null : number(payment.salesOrderId()));
    }

    private DocumentPrint adjustmentNote(AuthenticatedUser user, DocumentRow row) {
        AdjustmentContent adjustment = adjustment(row.id());
        List<Line> lines = new ArrayList<>();
        for (AdjustmentContent.StockLine line : adjustment.stockLines()) {
            lines.add(new Line(lines.size() + 1, line.productId(), line.sku(), line.partName(), line.baseUnit(),
                    line.lotId(), line.manufacturerLotNo(), line.quantityChange(), null, null));
        }
        Long amountChange = adjustment.amountLines().isEmpty() ? null
                : adjustment.amountLines().stream().mapToLong(AdjustmentContent.AmountLine::amountChange).sum();
        return new DocumentPrint(PrintForm.ADJUSTMENT_NOTE, row.type(), row.id(), row.number(), row.status(),
                row.documentDate(), now(), user.fullName(), store(row.storeId()), null, lines,
                amountChange == null ? null : new Totals(null, null, null, amountChange, null, null), null,
                adjustment.reason());
    }

    private Map<String, Object> salesOrder(long id) {
        return documents.row("""
                SELECT o.customer_id, o.customer_name_snapshot, o.customer_phone_snapshot, o.customer_address_snapshot,
                       o.discount_rate, o.completion_type, c.tax_code
                  FROM sales_order o LEFT JOIN customer c ON c.id = o.customer_id
                 WHERE o.id = :id""", Map.of("id", id)).orElseThrow();
    }

    /** The customer as the order recorded them (a walk-in customer may have no record). */
    private static Party customer(Map<String, Object> order) {
        return new Party(lng(order, "customer_id"), str(order, "customer_name_snapshot"),
                str(order, "customer_address_snapshot"), str(order, "customer_phone_snapshot"),
                str(order, "tax_code"));
    }

    private Party store(Long id) {
        if (id == null) {
            return null;
        }
        return documents.row("SELECT id, store_name, address, phone FROM store WHERE id = :id", Map.of("id", id))
                .map(found -> new Party(id, str(found, "store_name"), str(found, "address"), str(found, "phone"),
                        null)).orElse(null);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    // ---- column reads ------------------------------------------------------------------------------------------------

    private static Long lng(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? null : ((Number) value).longValue();
    }

    private static String str(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? null : value.toString();
    }

    private static LocalDateTime time(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? null : ((Timestamp) value).toLocalDateTime();
    }
}
