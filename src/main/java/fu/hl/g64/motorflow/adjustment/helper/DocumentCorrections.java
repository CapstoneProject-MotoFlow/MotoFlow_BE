package fu.hl.g64.motorflow.adjustment.helper;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.adjustment.dto.AdjustmentAmountView;
import fu.hl.g64.motorflow.adjustment.dto.CorrectionRequest;
import fu.hl.g64.motorflow.adjustment.dto.CorrectionRequest.CorrectedDocument;
import fu.hl.g64.motorflow.adjustment.entity.AdjustmentAmountLine;
import fu.hl.g64.motorflow.adjustment.entity.AdjustmentRequest;
import fu.hl.g64.motorflow.adjustment.entity.AdjustmentRequest.Source;
import fu.hl.g64.motorflow.adjustment.entity.AdjustmentStockLine;
import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus;
import fu.hl.g64.motorflow.adjustment.helper.AdjustmentStock.Change;
import fu.hl.g64.motorflow.adjustment.repository.AdjustmentAmountLineRepository;
import fu.hl.g64.motorflow.adjustment.repository.AdjustmentRequestRepository;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.entity.Lot;
import fu.hl.g64.motorflow.inventory.entity.StockMovement;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.inventory.repository.LotRepository;
import fu.hl.g64.motorflow.inventory.repository.StockMovementRepository;
import fu.hl.g64.motorflow.receivable.entity.Payment;
import fu.hl.g64.motorflow.receivable.entity.PaymentAllocation;
import fu.hl.g64.motorflow.receivable.entity.Receivable;
import fu.hl.g64.motorflow.receivable.entity.enums.OriginType;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentStatus;
import fu.hl.g64.motorflow.receivable.entity.enums.PaymentType;
import fu.hl.g64.motorflow.receivable.helper.DebtLedger.Debt;
import fu.hl.g64.motorflow.receivable.repository.PaymentAllocationRepository;
import fu.hl.g64.motorflow.receivable.repository.PaymentRepository;
import fu.hl.g64.motorflow.receivable.helper.PaymentReallocation;
import fu.hl.g64.motorflow.receivable.repository.ReceivableRepository;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNote;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNoteLine;
import fu.hl.g64.motorflow.receiving.entity.enums.ExcessDecision;
import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;
import fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteLineRepository;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteRepository;
import fu.hl.g64.motorflow.receiving.repository.PurchaseOrderRepository;
import fu.hl.g64.motorflow.sales.entity.SalesOrder;
import fu.hl.g64.motorflow.sales.entity.SalesOrderLine;
import fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus;
import fu.hl.g64.motorflow.sales.repository.SalesOrderLineRepository;
import fu.hl.g64.motorflow.sales.repository.SalesOrderRepository;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Corrections of posted documents (UC-81, plan 6.3). The original document is never changed (BR-65): a correction is
 * an adjustment request whose stock lines post on the day it is applied and whose amount lines count from then on:
 * <ul>
 *     <li>a completed sale's delivered quantity: stock out of (or back into) the store, the money difference on the
 *     order's receivable when it was sold on credit, otherwise on its latest verified payment;</li>
 *     <li>a confirmed supplier delivery's quantity put into stock: plus or minus on the line's lot;</li>
 *     <li>a debt payment's amount: the payment changes, and the corrected amount is allocated again over the same
 *     receivables in BR-70 order (BR-71), one line per receivable with the opposite of the change of what it pays;</li>
 *     <li>an opening debt's amount.</li>
 * </ul>
 * The values before and after are kept in the submission's audit entry (UC-84 step 8).
 */
@Component
@RequiredArgsConstructor
public class DocumentCorrections {

    private final AdjustmentRequestRepository requests;
    private final AdjustmentAmountLineRepository amountLines;
    private final AdjustmentStock stock;
    private final SalesOrderRepository salesOrders;
    private final SalesOrderLineRepository salesOrderLines;
    private final GoodsReceiptNoteRepository receipts;
    private final GoodsReceiptNoteLineRepository receiptLines;
    private final PurchaseOrderRepository purchaseOrders;
    private final PaymentRepository payments;
    private final PaymentAllocationRepository allocations;
    private final ReceivableRepository receivables;
    private final PaymentReallocation reallocation;
    private final LotRepository lots;
    private final StockMovementRepository movements;
    private final ProductRepository products;
    private final AuditLogRepository auditLog;

    /** An amount change before it is saved: of a payment or of what a receivable still asks for. */
    public record AmountChange(Long paymentId, Long receivableId, String label, long change) {
    }

    /**
     * A checked correction: the store, the corrected document, the stock and amount changes, and the values before
     * and after for the history.
     */
    public record Plan(long storeId, Source source, List<Change> stockChanges, List<AmountChange> amountChanges,
                       Map<String, Object> before, Map<String, Object> after) {
    }

    /** UC-81 steps 2-5: checks the proposal and its dependencies; saves nothing. */
    public Plan plan(AuthenticatedUser user, CorrectionRequest request) {
        Plan plan = switch (request.documentType()) {
            case SALES_ORDER -> salesOrder(user, request);
            case GOODS_RECEIPT_NOTE -> receipt(user, request);
            case DEBT_PAYMENT -> debtPayment(user, request);
            case OPENING_RECEIVABLE -> openingDebt(user, request);
        };
        Source source = plan.source();
        List<Long> open = requests.findOpenCorrections(id(source, Source.SourceType.SALES_ORDER),
                id(source, Source.SourceType.GOODS_RECEIPT_NOTE), id(source, Source.SourceType.PAYMENT),
                id(source, Source.SourceType.RECEIVABLE));
        if (!open.isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG283", open.getFirst());
        }
        AdjustmentStock.requireDistinct(plan.stockChanges());
        stock.requireAvailable(plan.storeId(), null, plan.stockChanges(), 0);
        return plan;
    }

    /** Saves the amount lines of a submitted correction and its values before and after (UC-81 step 7). */
    public void saved(AdjustmentRequest request, Plan plan, long userId, LocalDateTime now) {
        for (AmountChange a : plan.amountChanges()) {
            amountLines.save(a.paymentId() != null
                    ? AdjustmentAmountLine.ofPayment(request.getId(), a.paymentId(), a.change())
                    : AdjustmentAmountLine.ofReceivable(request.getId(), a.receivableId(), a.change()));
        }
        auditLog.save(AuditLog.record(AUDIT_ENTITY, request.getId(), CORRECTION_VALUES, userId, null,
                plan.before(), plan.after(), now));
    }

    /** audit_log entry holding the corrected values: before in old_data, after in new_data. */
    public static final String AUDIT_ENTITY = "ADJUSTMENT_REQUEST";
    public static final String CORRECTION_VALUES = "CORRECTION_VALUES";

    /**
     * UC-84 Step 4_Balances or dependencies changed: the stock can still give up what the correction takes, and no
     * receivable or payment would go below zero (MSG286).
     */
    public void recheck(AdjustmentRequest request, List<AdjustmentStockLine> lines) {
        stock.requireAvailable(request.getStoreId(), null, AdjustmentStock.changesOf(lines), request.getId());
        for (AdjustmentAmountView view : amountViews(request)) {
            if (view.after() < 0 || view.paymentId() != null && view.after() <= 0) {
                throw new BusinessException(HttpStatus.CONFLICT, "MSG286");
            }
        }
    }

    /**
     * UC-84 step 9 for the amount side: the amount lines count from now on (they are read with the APPLIED status);
     * the receivables they touch are marked settled or owed again. Called before the request is marked APPLIED, so
     * the remaining amounts are worked out with this request's lines added.
     */
    public void apply(AdjustmentRequest request, long ownerId, LocalDateTime now) {
        List<AdjustmentAmountLine> lines = amountLines.findByAdjustmentRequestIdOrderByIdAsc(request.getId());
        Set<Long> touched = new HashSet<>();
        Map<Long, Long> change = new HashMap<>();
        for (AdjustmentAmountLine line : lines) {
            if (line.getReceivableId() != null) {
                touched.add(line.getReceivableId());
                change.merge(line.getReceivableId(), line.getAmountChange(), Long::sum);
            }
        }
        if (touched.isEmpty()) {
            return;
        }
        for (Object[] row : receivables.findWithRemainingByIds(touched)) {
            Receivable receivable = (Receivable) row[0];
            long remaining = ((Number) row[1]).longValue() + change.getOrDefault(receivable.getId(), 0L);
            if (remaining <= 0) {
                receivable.settle(now);
            } else {
                receivable.reopen();
            }
        }
    }

    /**
     * UC-84 step 9 for a debt payment, once the request is APPLIED: its active allocation rows are reversed and the
     * corrected amount is allocated again over the same receivables (BR-71).
     */
    public void applied(AdjustmentRequest request, LocalDateTime now) {
        Long paymentId = request.getPaymentId();
        if (paymentId == null) {
            return;
        }
        Payment payment = payments.findById(paymentId).orElseThrow(DocumentCorrections::notFound);
        if (payment.getPaymentType() != PaymentType.DEBT_PAYMENT) {
            return;
        }
        reallocation.reallocate(payment, paymentAmounts(List.of(paymentId)).get(paymentId), request.getId(), now);
    }

    /**
     * The amount lines with the amounts now: before and after this request while it can still be applied; once
     * applied, the amounts now are the "after".
     */
    public List<AdjustmentAmountView> amountViews(AdjustmentRequest request) {
        List<AdjustmentAmountLine> lines = amountLines.findByAdjustmentRequestIdOrderByIdAsc(request.getId());
        if (lines.isEmpty()) {
            return List.of();
        }
        boolean applied = request.getStatus() == AdjustmentRequestStatus.APPLIED;
        Map<Long, Long> remaining = remaining(lines.stream().map(AdjustmentAmountLine::getReceivableId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, Long> paymentAmounts = paymentAmounts(lines.stream().map(AdjustmentAmountLine::getPaymentId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        List<AdjustmentAmountView> views = new ArrayList<>();
        for (AdjustmentAmountLine line : lines) {
            boolean payment = line.getPaymentId() != null;
            long now = payment ? paymentAmounts.getOrDefault(line.getPaymentId(), 0L)
                    : remaining.getOrDefault(line.getReceivableId(), 0L);
            long before = applied ? now - line.getAmountChange() : now;
            views.add(new AdjustmentAmountView(line.getPaymentId(), line.getReceivableId(),
                    label(line.getPaymentId(), line.getReceivableId()), before,
                    line.getAmountChange(), before + line.getAmountChange()));
        }
        return views;
    }

    /** The amount changes of a plan with the amounts now, before anything is saved (UC-81 step 5). */
    public List<AdjustmentAmountView> previewAmounts(Plan plan) {
        Map<Long, Long> remaining = remaining(plan.amountChanges().stream().map(AmountChange::receivableId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, Long> paymentAmounts = paymentAmounts(plan.amountChanges().stream().map(AmountChange::paymentId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return plan.amountChanges().stream().map(a -> {
            boolean payment = a.paymentId() != null;
            long before = payment ? paymentAmounts.getOrDefault(a.paymentId(), 0L)
                    : remaining.getOrDefault(a.receivableId(), 0L);
            return new AdjustmentAmountView(a.paymentId(), a.receivableId(), label(a.paymentId(), a.receivableId()),
                    before, a.change(), before + a.change());
        }).toList();
    }

    private static String label(Long paymentId, Long receivableId) {
        return paymentId != null ? "Số tiền khoản thu #" + paymentId : "Số còn nợ của khoản nợ #" + receivableId;
    }

    /** The corrected document's values before and after, from the submission (null for a stock loss). */
    public Map<String, Object> correctionView(AdjustmentRequest request) {
        return auditLog.findByEntityTypeAndEntityIdAndActionInOrderByOccurredAtDescIdDesc(AUDIT_ENTITY,
                request.getId(), List.of(CORRECTION_VALUES)).stream().findFirst().map(entry -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("before", entry.getOldData());
            view.put("after", entry.getNewData());
            return view;
        }).orElse(null);
    }

    // ---------------------------------------------------------------------------------------------- documents

    /** A completed sale's delivered quantities. */
    private Plan salesOrder(AuthenticatedUser user, CorrectionRequest request) {
        SalesOrder order = salesOrders.findById(request.documentId())
                .filter(o -> o.getStatus() == SalesOrderStatus.COMPLETED && visible(user, o.getStoreId()))
                .orElseThrow(DocumentCorrections::notFound);
        Map<Long, SalesOrderLine> byId = salesOrderLines.findBySalesOrderIdOrderByLineNoAsc(order.getId()).stream()
                .collect(Collectors.toMap(SalesOrderLine::getId, Function.identity()));
        Map<Long, Integer> corrected = correctedLines(request, byId.keySet());
        Map<Long, Product> parts = parts(byId.values().stream().map(SalesOrderLine::getProductId).toList());
        List<Change> changes = new ArrayList<>();
        List<Map<String, Object>> before = new ArrayList<>();
        List<Map<String, Object>> after = new ArrayList<>();
        long money = 0;
        for (Map.Entry<Long, Integer> e : corrected.entrySet()) {
            SalesOrderLine line = byId.get(e.getKey());
            int delivered = line.getDeliveredQty() == null ? 0 : line.getDeliveredQty();
            int diff = e.getValue() - delivered;
            if (diff == 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG282");
            }
            money = Math.addExact(money, Math.multiplyExact((long) diff, line.getActualUnitPrice()));
            if (diff > 0) {
                changes.add(new Change(line.getProductId(), null, StockStatus.AVAILABLE, -diff));
            } else {
                changes.addAll(backIntoSoldLots(order.getId(), line.getProductId(), -diff));
            }
            Product part = parts.get(line.getProductId());
            before.add(lineValue(line.getId(), part, delivered, line.getActualUnitPrice()));
            after.add(lineValue(line.getId(), part, e.getValue(), line.getActualUnitPrice()));
        }
        List<AmountChange> amounts = new ArrayList<>();
        if (money != 0) {
            amounts.add(orderMoney(order, money));
        }
        return new Plan(order.getStoreId(), new Source(Source.SourceType.SALES_ORDER, order.getId()), changes,
                amounts, values("SALES_ORDER", order.getId(), "deliveredQty", before),
                values("SALES_ORDER", order.getId(), "deliveredQty", after));
    }

    /** Units that were not really delivered go back into the lots the sale took them from, latest first. */
    private List<Change> backIntoSoldLots(long orderId, long productId, int quantity) {
        Map<Long, Integer> byLot = new LinkedHashMap<>();
        int left = quantity;
        for (StockMovement issue : movements.findSaleIssues(orderId, productId)) {
            if (left == 0) {
                break;
            }
            int take = Math.min(left, -issue.getQuantityChange());
            byLot.merge(issue.getLotId(), take, Integer::sum);
            left -= take;
        }
        if (left > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG286");
        }
        return byLot.entrySet().stream()
                .map(e -> new Change(productId, e.getKey(), StockStatus.AVAILABLE, e.getValue())).toList();
    }

    /**
     * The money difference of a corrected sale: on its receivable when sold on credit (never below what was paid on
     * it, MSG284), otherwise on its latest verified payment.
     */
    private AmountChange orderMoney(SalesOrder order, long difference) {
        List<Object[]> debt = receivables.findWithRemainingBySalesOrderId(order.getId());
        if (!debt.isEmpty()) {
            Receivable receivable = (Receivable) debt.getFirst()[0];
            long remaining = ((Number) debt.getFirst()[1]).longValue();
            if (remaining + difference < 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG284",
                        money(amountOf(receivable) - remaining));
            }
            return new AmountChange(null, receivable.getId(), null, difference);
        }
        List<Payment> paid = payments.findBySalesOrderIdOrderByRecordedAtAscIdAsc(order.getId()).stream()
                .filter(p -> p.getPaymentType() == PaymentType.ORDER_PAYMENT
                        && p.getStatus() == PaymentStatus.VERIFIED).toList();
        if (paid.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG285", order.getId());
        }
        Payment latest = paid.getLast();
        long amount = paymentAmounts(List.of(latest.getId())).get(latest.getId());
        if (amount + difference <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG285", order.getId());
        }
        return new AmountChange(latest.getId(), null, null, difference);
    }

    /** A confirmed supplier delivery's quantities put into stock, on each line's lot. */
    private Plan receipt(AuthenticatedUser user, CorrectionRequest request) {
        GoodsReceiptNote receipt = receipts.findById(request.documentId())
                .filter(r -> r.getReceiptType() == ReceiptType.SUPPLIER_DELIVERY
                        && r.getStatus() == GoodsReceiptNoteStatus.CONFIRMED)
                .orElseThrow(DocumentCorrections::notFound);
        long storeId = purchaseOrders.findById(receipt.getPurchaseOrderId()).orElseThrow().getStoreId();
        if (!visible(user, storeId)) {
            throw notFound();
        }
        Map<Long, GoodsReceiptNoteLine> byId = receiptLines.findByGoodsReceiptNoteIdOrderByIdAsc(receipt.getId()).stream()
                .collect(Collectors.toMap(GoodsReceiptNoteLine::getId, Function.identity()));
        Map<Long, Integer> corrected = correctedLines(request, byId.keySet());
        Map<Long, Lot> lotByLine = lots.findByGoodsReceiptNoteLineIdIn(corrected.keySet()).stream()
                .collect(Collectors.toMap(Lot::getGoodsReceiptNoteLineId, Function.identity()));
        Map<Long, Product> parts = parts(byId.values().stream().map(GoodsReceiptNoteLine::getProductId).toList());
        List<Change> changes = new ArrayList<>();
        List<Map<String, Object>> before = new ArrayList<>();
        List<Map<String, Object>> after = new ArrayList<>();
        int position = 0;
        for (Map.Entry<Long, Integer> e : corrected.entrySet()) {
            position++;
            GoodsReceiptNoteLine line = byId.get(e.getKey());
            int inStock = line.acceptedQty()
                    + (line.getExcessDecision() == ExcessDecision.ACCEPTED ? line.getExcessQty() : 0);
            int diff = e.getValue() - inStock;
            if (diff == 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG282");
            }
            Lot lot = lotByLine.get(line.getId());
            if (lot == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG290", position);
            }
            changes.add(new Change(line.getProductId(), lot.getId(), StockStatus.AVAILABLE, diff));
            Product part = parts.get(line.getProductId());
            before.add(lineValue(line.getId(), part, inStock, null));
            after.add(lineValue(line.getId(), part, e.getValue(), null));
        }
        return new Plan(storeId, new Source(Source.SourceType.GOODS_RECEIPT_NOTE, receipt.getId()), changes,
                List.of(), values("GOODS_RECEIPT_NOTE", receipt.getId(), "stockQty", before),
                values("GOODS_RECEIPT_NOTE", receipt.getId(), "stockQty", after));
    }

    /** BR-71: a verified debt payment's amount, allocated again over the same receivables in BR-70 order. */
    private Plan debtPayment(AuthenticatedUser user, CorrectionRequest request) {
        Payment payment = payments.findById(request.documentId())
                .filter(p -> p.getPaymentType() == PaymentType.DEBT_PAYMENT && p.getStatus() == PaymentStatus.VERIFIED)
                .orElseThrow(DocumentCorrections::notFound);
        List<PaymentAllocation> allocated = allocations.findByPaymentIdInAndReversedAtIsNull(List.of(payment.getId()));
        Map<Long, Long> paysOn = new HashMap<>();
        allocated.forEach(a -> paysOn.merge(a.getReceivableId(), a.getAllocatedAmount(), Long::sum));
        // What each receivable could take from this payment: what is still owed plus what the payment pays on it now.
        List<Debt> debts = paysOn.isEmpty() ? List.of() : receivables.findWithRemainingByIds(paysOn.keySet()).stream()
                .map(row -> new Debt((Receivable) row[0], ((Number) row[1]).longValue()
                        + paysOn.get(((Receivable) row[0]).getId())))
                .toList();
        if (debts.isEmpty()) {
            throw notFound();
        }
        long storeId = debts.getFirst().receivable().getStoreId();
        if (!visible(user, storeId)) {
            throw notFound();
        }
        long current = paymentAmounts(List.of(payment.getId())).get(payment.getId());
        long corrected = amount(request, current);
        long capacity = debts.stream().mapToLong(Debt::remaining).sum();
        if (corrected > capacity) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG288", money(capacity));
        }
        // The receivables are allocated again when the request is applied (PaymentReallocation, BR-71).
        List<AmountChange> amounts = List.of(new AmountChange(payment.getId(), null, null, corrected - current));
        return new Plan(storeId, new Source(Source.SourceType.PAYMENT, payment.getId()), List.of(), amounts,
                Map.of("documentType", "DEBT_PAYMENT", "documentId", payment.getId(), "amount", current),
                Map.of("documentType", "DEBT_PAYMENT", "documentId", payment.getId(), "amount", corrected));
    }

    /** An opening debt's amount, never below what has been paid on it (MSG284). */
    private Plan openingDebt(AuthenticatedUser user, CorrectionRequest request) {
        Receivable receivable = receivables.findById(request.documentId())
                .filter(r -> r.getOriginType() == OriginType.OPENING_BALANCE && visible(user, r.getStoreId()))
                .orElseThrow(DocumentCorrections::notFound);
        long current = amountOf(receivable);
        long corrected = amount(request, current);
        long paid = current - remaining(List.of(receivable.getId())).get(receivable.getId());
        if (corrected < paid) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG284", money(paid));
        }
        return new Plan(receivable.getStoreId(), new Source(Source.SourceType.RECEIVABLE, receivable.getId()),
                List.of(), List.of(new AmountChange(null, receivable.getId(), null, corrected - current)),
                Map.of("documentType", "OPENING_RECEIVABLE", "documentId", receivable.getId(), "amount", current),
                Map.of("documentType", "OPENING_RECEIVABLE", "documentId", receivable.getId(), "amount", corrected));
    }

    // ---------------------------------------------------------------------------------------------- helpers

    /** The corrected line quantities by line id, in the order given (MSG289 for a foreign or repeated line). */
    private static Map<Long, Integer> correctedLines(CorrectionRequest request, Set<Long> lineIds) {
        if (request.lines() == null || request.lines().isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG282");
        }
        Map<Long, Integer> corrected = new LinkedHashMap<>();
        for (int i = 0; i < request.lines().size(); i++) {
            CorrectionRequest.Line line = request.lines().get(i);
            if (!lineIds.contains(line.lineId()) || corrected.put(line.lineId(), line.correctedQty()) != null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG289", i + 1);
            }
        }
        return corrected;
    }

    /** The corrected amount of a payment or debt (MSG282 when missing or unchanged). */
    private static long amount(CorrectionRequest request, long current) {
        if (request.correctedAmount() == null || request.correctedAmount() == current) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG282");
        }
        return request.correctedAmount();
    }

    /** A receivable's amount with its applied amount corrections. */
    private long amountOf(Receivable receivable) {
        return receivable.getOriginalAmount() + receivables.sumCorrections(List.of(receivable.getId())).stream()
                .mapToLong(row -> ((Number) row[1]).longValue()).sum();
    }

    private Map<Long, Long> remaining(Collection<Long> receivableIds) {
        if (receivableIds.isEmpty()) {
            return Map.of();
        }
        return receivables.findWithRemainingByIds(receivableIds).stream().collect(Collectors.toMap(
                row -> ((Receivable) row[0]).getId(), row -> ((Number) row[1]).longValue()));
    }

    /** Each payment's amount with its applied corrections (BR-49). */
    private Map<Long, Long> paymentAmounts(Collection<Long> paymentIds) {
        if (paymentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> amounts = payments.findAllById(paymentIds).stream()
                .collect(Collectors.toMap(Payment::getId, Payment::getAmount));
        payments.sumCorrections(paymentIds)
                .forEach(row -> amounts.merge((Long) row[0], ((Number) row[1]).longValue(), Long::sum));
        return amounts;
    }

    private Map<Long, Product> parts(Collection<Long> ids) {
        return products.findAllById(new HashSet<>(ids)).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
    }

    private static Map<String, Object> lineValue(long lineId, Product part, int quantity, Long unitPrice) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("lineId", lineId);
        value.put("productId", part.getId());
        value.put("sku", part.getSku());
        value.put("partName", part.getPartName());
        value.put("quantity", quantity);
        if (unitPrice != null) {
            value.put("unitPrice", unitPrice);
        }
        return value;
    }

    private static Map<String, Object> values(String type, long id, String field, List<Map<String, Object>> lines) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("documentType", type);
        values.put("documentId", id);
        values.put("field", field);
        values.put("lines", lines);
        return values;
    }

    private static boolean visible(AuthenticatedUser user, Long storeId) {
        return user.role() == Role.OWNER || Objects.equals(storeId, user.storeId());
    }

    private static Long id(Source source, Source.SourceType type) {
        return source.type() == type ? source.id() : null;
    }

    private static String money(long amount) {
        return String.format(java.util.Locale.ROOT, "%,d", amount).replace(',', '.');
    }

    private static BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "MSG287");
    }
}
