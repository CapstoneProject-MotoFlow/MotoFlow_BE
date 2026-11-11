package fu.hl.g64.motorflow.receiving.service.impl;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.entity.enums.ProductStatus;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import fu.hl.g64.motorflow.discrepancy.entity.enums.DiscrepancyCaseStatus;
import fu.hl.g64.motorflow.discrepancy.helper.DiscrepancySettlement;
import fu.hl.g64.motorflow.discrepancy.helper.DiscrepancySettlement.Progress;
import fu.hl.g64.motorflow.discrepancy.helper.DiscrepancySettlement.Standing;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyCaseRepository;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.partner.entity.Supplier;
import fu.hl.g64.motorflow.partner.entity.enums.SupplierStatus;
import fu.hl.g64.motorflow.partner.repository.SupplierRepository;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderDetail;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderDetail.Discrepancy;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderDetail.Line;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderDetail.Receipt;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderDetail.ReceiptLine;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderLineRequest;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderRequest;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderSearch;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderSummary;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderUpdateRequest;
import fu.hl.g64.motorflow.receiving.dto.PurchaseOrderView;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNote;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNoteLine;
import fu.hl.g64.motorflow.receiving.entity.PurchaseOrder;
import fu.hl.g64.motorflow.receiving.entity.PurchaseOrderLine;
import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;
import fu.hl.g64.motorflow.receiving.entity.enums.PurchaseOrderStatus;
import fu.hl.g64.motorflow.receiving.helper.PurchaseOrderHistory;
import fu.hl.g64.motorflow.receiving.helper.PurchaseOrderHistory.LineValues;
import fu.hl.g64.motorflow.receiving.helper.ReceivingLedger;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteLineRepository;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteRepository;
import fu.hl.g64.motorflow.receiving.repository.PurchaseOrderLineRepository;
import fu.hl.g64.motorflow.receiving.repository.PurchaseOrderRepository;
import fu.hl.g64.motorflow.receiving.service.PurchaseOrderService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PurchaseOrderServiceImpl implements PurchaseOrderService {

    /** notification.ref_type of a PO, so that opening the notification opens it. */
    private static final String REF_TYPE = "PURCHASE_ORDER";
    /** The tabs other than "Đang mở" show the POs created in the last 30 days by default (screen 6.1.1). */
    private static final int DEFAULT_DAYS = 30;

    private final PurchaseOrderRepository orders;
    private final PurchaseOrderLineRepository orderLines;
    private final GoodsReceiptNoteRepository receipts;
    private final GoodsReceiptNoteLineRepository receiptLines;
    private final DiscrepancyCaseRepository discrepancies;
    private final SupplierRepository suppliers;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final UserAccountRepository users;
    private final AuditLogRepository auditLog;
    private final NotificationService notifications;
    private final ReceivingLedger ledger;
    private final DiscrepancySettlement settlement;
    private final Clock clock;

    // UC-39
    @Transactional(readOnly = true)
    @Override
    public PageResponse<PurchaseOrderSummary> list(AuthenticatedUser user, PurchaseOrderSearch search, int page,
                                                   int size) {
        Long storeId = search.storeId();
        if (!isOwner(user)) {
            if (storeId != null && !storeId.equals(user.storeId())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
            }
            storeId = user.storeId();
        }
        LocalDate today = LocalDate.now(clock);
        PurchaseOrderView view = search.view() == null ? PurchaseOrderView.OPEN : search.view();
        LocalDate from = search.createdFrom();
        LocalDate to = search.createdTo();
        if (view != PurchaseOrderView.OPEN) {
            to = to != null ? to : today;
            from = from != null ? from : to.minusDays(DEFAULT_DAYS - 1);
        }
        Page<PurchaseOrder> found = orders.findAll(matching(search.q(), storeId, search.supplierId(), view, from, to),
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100),
                        Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return PageResponse.of(found, summaries(user, found.getContent(), today));
    }

    // UC-41
    @Transactional(readOnly = true)
    @Override
    public PurchaseOrderDetail get(AuthenticatedUser user, long id) {
        PurchaseOrder order = orders.findById(id).orElseThrow(PurchaseOrderServiceImpl::notFound);
        if (!isOwner(user) && !Objects.equals(order.getStoreId(), user.storeId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        return detail(user, order);
    }

    // UC-40
    @Transactional
    @Override
    public PurchaseOrderDetail create(AuthenticatedUser owner, PurchaseOrderRequest request) {
        Supplier supplier = suppliers.findById(request.supplierId())
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "MSG73"));
        if (supplier.getStatus() != SupplierStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG129", supplier.getSupplierName());
        }
        Store store = stores.findById(request.storeId()).filter(s -> s.getStatus() == StoreStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "MSG46"));
        checkLines(request.lines(), Set.of());
        LocalDateTime now = LocalDateTime.now(clock);
        checkArrival(request.expectedArrival(), null, now.toLocalDate());

        String note = text(request.note());
        PurchaseOrder order = orders.save(PurchaseOrder.create(supplier.getId(), store.getId(),
                request.expectedArrival(), note, owner.id(), now));
        orderLines.saveAll(request.lines().stream().map(line -> PurchaseOrderLine.create(order.getId(),
                line.productId(), line.quantity(), line.purchasePrice())).toList());
        auditLog.save(AuditLog.record(PurchaseOrderHistory.ENTITY, order.getId(), PurchaseOrderHistory.CREATED,
                owner.id(), null, null, PurchaseOrderHistory.snapshot(order.getExpectedArrival(), note,
                        values(request.lines())), now));
        notifications.notifyStoreManagers(store.getId(), "PURCHASE_ORDER_CREATED",
                "Có yêu cầu nhập hàng mới %s từ nhà cung cấp %s.".formatted(label(order), supplier.getSupplierName()),
                REF_TYPE, order.getId());
        return detail(owner, order);
    }

    // UC-42
    @Transactional
    @Override
    public PurchaseOrderDetail update(AuthenticatedUser owner, long id, PurchaseOrderUpdateRequest request) {
        PurchaseOrder order = lockChangeable(id);
        List<PurchaseOrderLine> current = orderLines.findByPurchaseOrderIdOrderByIdAsc(id);
        Map<Long, PurchaseOrderLine> byProduct = current.stream()
                .collect(Collectors.toMap(PurchaseOrderLine::getProductId, Function.identity()));
        checkLines(request.lines(), byProduct.keySet());
        checkArrival(request.expectedArrival(), order.getExpectedArrival(), LocalDate.now(clock));

        String note = text(request.note());
        Map<String, Object> before = PurchaseOrderHistory.snapshot(order.getExpectedArrival(), order.getNote(),
                current.stream().map(line -> new LineValues(line.getProductId(), line.getOrderedQty(),
                        line.getPurchasePrice())).toList());
        Map<String, Object> after = PurchaseOrderHistory.snapshot(request.expectedArrival(), note,
                values(request.lines()));

        Set<Long> kept = request.lines().stream().map(PurchaseOrderLineRequest::productId).collect(Collectors.toSet());
        List<PurchaseOrderLine> removed = current.stream().filter(line -> !kept.contains(line.getProductId())).toList();
        List<PurchaseOrderLine> added = new ArrayList<>();
        boolean linesChanged = !removed.isEmpty();
        for (PurchaseOrderLineRequest line : request.lines()) {
            PurchaseOrderLine existing = byProduct.get(line.productId());
            if (existing == null) {
                added.add(PurchaseOrderLine.create(id, line.productId(), line.quantity(), line.purchasePrice()));
            } else if (existing.getOrderedQty() != line.quantity() || existing.getPurchasePrice() != line.purchasePrice()) {
                existing.change(line.quantity(), line.purchasePrice());
                linesChanged = true;
            }
        }
        boolean deliveryChanged = !Objects.equals(order.getExpectedArrival(), request.expectedArrival())
                || !Objects.equals(order.getNote(), note);
        if (!linesChanged && added.isEmpty() && !deliveryChanged) {
            return detail(owner, order);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        String reason = request.reason().strip();
        orderLines.deleteAll(removed);
        orderLines.saveAll(added);
        order.changeDelivery(request.expectedArrival(), note, now);
        auditLog.save(AuditLog.record(PurchaseOrderHistory.ENTITY, id, PurchaseOrderHistory.UPDATED, owner.id(),
                reason, before, after, now));
        notifications.notifyStoreManagers(order.getStoreId(), "PURCHASE_ORDER_UPDATED",
                "Yêu cầu nhập hàng %s đã được cập nhật. Lý do: %s".formatted(label(order), reason), REF_TYPE, id);
        return detail(owner, order);
    }

    // UC-42 Cancel PO
    @Transactional
    @Override
    public PurchaseOrderDetail cancel(AuthenticatedUser owner, long id, String reason) {
        PurchaseOrder order = lockChangeable(id);
        String why = reason.strip();
        LocalDateTime now = LocalDateTime.now(clock);
        // No PO is linked to a replenishment request yet (creating a PO from one comes with UC-69..71), so there is
        // no purchasing need to give back.
        order.cancel(owner.id(), why, now);
        auditLog.save(AuditLog.record(PurchaseOrderHistory.ENTITY, id, PurchaseOrderHistory.CANCELLED, owner.id(),
                why, now));
        notifications.notifyStoreManagers(order.getStoreId(), "PURCHASE_ORDER_CANCELLED",
                "Yêu cầu nhập hàng %s đã bị hủy. Lý do: %s".formatted(label(order), why), REF_TYPE, id);
        return detail(owner, order);
    }

    /**
     * The PO, locked until the end of the transaction, if it may still change (BR-21): MSG131 if it does not exist,
     * MSG130 once cancelled, MSG25 once a GRN has been recorded for it.
     */
    private PurchaseOrder lockChangeable(long id) {
        PurchaseOrder order = orders.findByIdForUpdate(id).orElseThrow(PurchaseOrderServiceImpl::notFound);
        if (order.getStatus() == PurchaseOrderStatus.CANCELLED) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG130");
        }
        if (order.getStatus() != PurchaseOrderStatus.AWAITING_DELIVERY || receipts.existsByPurchaseOrderId(id)) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG25");
        }
        return order;
    }

    /**
     * UC-40 step 6: MSG20 with the line number for a quantity or price not greater than 0 or a part already on an
     * earlier line, MSG57 for an unknown part, MSG21 for a discontinued part that is not already on the PO (BR-07).
     */
    private void checkLines(List<PurchaseOrderLineRequest> lines, Set<Long> alreadyOnOrder) {
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            PurchaseOrderLineRequest line = lines.get(i);
            if (line.quantity() <= 0 || line.purchasePrice() <= 0 || !seen.add(line.productId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG20", i + 1);
            }
        }
        Map<Long, Product> parts = byId(products.findAllById(seen), Product::getId);
        for (PurchaseOrderLineRequest line : lines) {
            Product part = parts.get(line.productId());
            if (part == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG57");
            }
            if (part.getStatus() == ProductStatus.DISCONTINUED && !alreadyOnOrder.contains(part.getId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG21", part.getSku());
            }
        }
    }

    private List<PurchaseOrderSummary> summaries(AuthenticatedUser user, List<PurchaseOrder> page, LocalDate today) {
        if (page.isEmpty()) {
            return List.of();
        }
        List<Long> ids = page.stream().map(PurchaseOrder::getId).toList();
        Map<Long, Supplier> supplierById = byId(suppliers.findAllById(page.stream().map(PurchaseOrder::getSupplierId)
                .collect(Collectors.toSet())), Supplier::getId);
        Map<Long, String> storeNames = stores.findAllById(page.stream().map(PurchaseOrder::getStoreId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Store::getId, Store::getStoreName));
        Map<Long, Object[]> totals = orderLines.summarizeByRequest(ids).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], Function.identity()));
        Map<Long, Long> openCases = discrepancies.countOpenByPurchaseOrder(ids).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
        boolean owner = isOwner(user);

        return page.stream().map(order -> {
            Supplier supplier = supplierById.get(order.getSupplierId());
            Object[] total = totals.get(order.getId());
            return new PurchaseOrderSummary(order.getId(), order.getStatus(), order.getSupplierId(),
                    supplier.getSupplierCode(), supplier.getSupplierName(), order.getStoreId(),
                    storeNames.get(order.getStoreId()), order.getCreatedAt(), order.getExpectedArrival(),
                    overdue(order, today), total == null ? 0 : (Long) total[1],
                    owner ? (total == null ? 0L : ((Number) total[2]).longValue()) : null,
                    openCases.getOrDefault(order.getId(), 0L));
        }).toList();
    }

    private PurchaseOrderDetail detail(AuthenticatedUser user, PurchaseOrder order) {
        boolean owner = isOwner(user);
        long id = order.getId();
        List<PurchaseOrderLine> lines = orderLines.findByPurchaseOrderIdOrderByIdAsc(id);
        List<GoodsReceiptNote> deliveries = receipts.findByPurchaseOrderIdOrderByArrivedAtAscIdAsc(id);
        List<Long> deliveryIds = deliveries.stream().map(GoodsReceiptNote::getId).toList();
        List<GoodsReceiptNoteLine> results = deliveryIds.isEmpty() ? List.of()
                : receiptLines.findByGoodsReceiptNoteIdInOrderByIdAsc(deliveryIds);
        List<DiscrepancyCase> cases = deliveryIds.isEmpty() ? List.of()
                : discrepancies.findByGoodsReceiptNoteIdInOrderByIdAsc(deliveryIds);
        List<AuditLog> steps = auditLog.findByEntityTypeAndEntityIdAndActionInOrderByOccurredAtDescIdDesc(
                PurchaseOrderHistory.ENTITY, id, PurchaseOrderHistory.ACTIONS);

        Set<Long> productIds = new HashSet<>(PurchaseOrderHistory.productIds(steps));
        lines.forEach(line -> productIds.add(line.getProductId()));
        results.forEach(line -> productIds.add(line.getProductId()));
        cases.forEach(found -> productIds.add(found.getProductId()));
        Map<Long, Product> parts = byId(products.findAllById(productIds), Product::getId);
        Map<Long, String> names = names(order, deliveries, steps);
        Supplier supplier = suppliers.findById(order.getSupplierId()).orElseThrow();
        String storeName = stores.findById(order.getStoreId()).map(Store::getStoreName).orElse(null);

        Set<Long> replacements = ledger.replacementReceiptIds(deliveryIds);
        ReceivingLedger.Ledger standing = ReceivingLedger.compute(lines, deliveries, results, replacements);
        Map<Long, List<ReceiptLine>> resultsByDelivery = new HashMap<>();
        for (GoodsReceiptNoteLine result : results) {
            Product part = parts.get(result.getProductId());
            resultsByDelivery.computeIfAbsent(result.getGoodsReceiptNoteId(), key -> new ArrayList<>())
                    .add(new ReceiptLine(result.getProductId(), part.getSku(), part.getPartName(),
                            result.getDocumentQty(), result.getDeliveredQty(), result.getPassedQty(),
                            result.getWrongItemQty(), result.getDamagedQty(), result.getExcessQty(),
                            result.getExcessDecision()));
        }

        long orderedValue = 0;
        List<Line> lineRows = new ArrayList<>();
        for (PurchaseOrderLine line : lines) {
            Product part = parts.get(line.getProductId());
            long lineValue = (long) line.getOrderedQty() * line.getPurchasePrice();
            orderedValue += lineValue;
            ReceivingLedger.Part got = standing.parts().get(line.getProductId());
            lineRows.add(new Line(line.getId(), line.getProductId(), part.getSku(), part.getPartName(),
                    part.getBaseUnit(), part.getStatus(), line.getOrderedQty(), owner ? line.getPurchasePrice() : null,
                    owner ? lineValue : null, got.receivedQty(), got.acceptedExcessQty(), got.outstandingQty()));
        }
        List<Receipt> receiptRows = deliveries.stream().map(r -> new Receipt(r.getId(), r.getDeliveryDocumentNo(),
                r.getDeliveryDocumentDate(), r.getArrivedAt(), r.getStatus(), replacements.contains(r.getId()),
                r.getRecordedBy(),
                names.get(r.getRecordedBy()), r.getSubmittedAt(), r.getConfirmedBy(), names.get(r.getConfirmedBy()),
                r.getConfirmedAt(), r.getStatus() == GoodsReceiptNoteStatus.AWAITING_INSPECTION ? List.of()
                : resultsByDelivery.getOrDefault(r.getId(), List.of()))).toList();
        Map<Long, Progress> progress = settlement.progress(cases.stream().map(DiscrepancyCase::getId).toList());
        List<Discrepancy> caseRows = cases.stream().map(c -> {
            Product part = parts.get(c.getProductId());
            Standing done = new Standing(c, progress.get(c.getId()));
            return new Discrepancy(c.getId(), c.getGoodsReceiptNoteId(), c.getProductId(), part.getSku(),
                    part.getPartName(), c.getShortQty(), c.getWrongItemQty(), c.getDamagedQty(),
                    c.getRejectedExcessQty(), c.getReturnRequiredQty(), c.getReplacementRequiredQty(),
                    done.outstandingReturn(), done.outstandingReplacement(), c.isOpen() ? done.openReplacement() : 0,
                    c.getStatus(), c.getOpenedAt(), c.getClosedAt());
        }).toList();
        long openCases = cases.stream().filter(c -> c.getStatus() == DiscrepancyCaseStatus.OPEN).count();
        boolean changeable = owner && order.getStatus() == PurchaseOrderStatus.AWAITING_DELIVERY
                && deliveries.isEmpty();
        // UC-106: only the Branch Manager of the receiving store records deliveries.
        boolean receiving = user.role() == Role.BRANCH_MANAGER && Objects.equals(user.storeId(), order.getStoreId());
        boolean canCreateReceipt = receiving && (order.getStatus() == PurchaseOrderStatus.AWAITING_DELIVERY
                || order.getStatus() == PurchaseOrderStatus.RECEIVING) && standing.anyOutstanding();
        boolean canCreateReplacement = receiving && caseRows.stream().anyMatch(c -> c.openReplacementQty() > 0);

        return new PurchaseOrderDetail(id, order.getStatus(), supplier.getId(), supplier.getSupplierCode(),
                supplier.getSupplierName(), order.getStoreId(), storeName, order.getExpectedArrival(),
                overdue(order, LocalDate.now(clock)), order.getNote(), order.getReplenishmentRequestId(),
                order.getCreatedBy(), names.get(order.getCreatedBy()), order.getCreatedAt(), order.getUpdatedAt(),
                order.getCancelledBy(), names.get(order.getCancelledBy()), order.getCancelledAt(),
                order.getCancellationReason(), owner ? orderedValue : null, openCases, changeable, changeable,
                canCreateReceipt, canCreateReplacement, lineRows, receiptRows, caseRows,
                steps.stream().map(step -> PurchaseOrderHistory.entry(step, names.get(step.getActorId()), parts, owner))
                        .toList());
    }

    /** Full names of everyone the detail mentions. */
    private Map<Long, String> names(PurchaseOrder order, List<GoodsReceiptNote> deliveries, List<AuditLog> steps) {
        Set<Long> ids = new HashSet<>();
        ids.add(order.getCreatedBy());
        ids.add(order.getCancelledBy());
        deliveries.forEach(r -> {
            ids.add(r.getRecordedBy());
            ids.add(r.getConfirmedBy());
        });
        steps.forEach(step -> ids.add(step.getActorId()));
        ids.remove(null);
        return users.findAllById(ids).stream().collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName));
    }

    private static List<LineValues> values(List<PurchaseOrderLineRequest> lines) {
        return lines.stream().map(line -> new LineValues(line.productId(), line.quantity(), line.purchasePrice()))
                .toList();
    }

    private static Specification<PurchaseOrder> matching(String q, Long storeId, Long supplierId,
                                                         PurchaseOrderView view, LocalDate from, LocalDate to) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(root.get("status").in(view.statuses()));
            if (storeId != null) {
                where.add(cb.equal(root.get("storeId"), storeId));
            }
            if (supplierId != null) {
                where.add(cb.equal(root.get("supplierId"), supplierId));
            }
            if (from != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from.atStartOfDay()));
            }
            if (to != null) {
                where.add(cb.lessThan(root.get("createdAt"), to.plusDays(1).atStartOfDay()));
            }
            if (StringUtils.hasText(q)) {
                String needle = q.strip().toLowerCase(Locale.ROOT);
                String like = "%" + needle + "%";
                Subquery<Long> bySupplier = query.subquery(Long.class);
                Root<Supplier> supplier = bySupplier.from(Supplier.class);
                bySupplier.select(supplier.get("id")).where(cb.or(
                        cb.like(cb.lower(supplier.get("supplierName")), like),
                        cb.like(cb.lower(supplier.get("supplierCode")), like)));
                Predicate supplierMatches = root.get("supplierId").in(bySupplier);
                Long number = orderNumber(needle);
                where.add(number == null ? supplierMatches : cb.or(cb.equal(root.get("id"), number), supplierMatches));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    /** "12" or "#12" read as PO number 12; anything else is no PO number. */
    private static Long orderNumber(String needle) {
        String digits = needle.startsWith("#") ? needle.substring(1).strip() : needle;
        if (digits.isEmpty() || digits.length() > 18 || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return null;
        }
        return Long.parseLong(digits);
    }

    /** Still open after the day the supplier was expected to deliver (screen 6.1.1 warning). */
    private static boolean overdue(PurchaseOrder order, LocalDate today) {
        return order.isOpen() && order.getExpectedArrival() != null && order.getExpectedArrival().isBefore(today);
    }

    /** How a PO is named in notifications and messages: its number. The table has no separate code. */
    private static String label(PurchaseOrder order) {
        return "#" + order.getId();
    }

    private static String text(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private static boolean isOwner(AuthenticatedUser user) {
        return user.role() == Role.OWNER;
    }

    private static BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "MSG131");
    }

    private static <T> Map<Long, T> byId(Collection<T> rows, Function<T, Long> id) {
        return rows.stream().collect(Collectors.toMap(id, Function.identity()));
    }

    /**
     * MSG248: an expected arrival before today. A PO already late may keep the date it has, so that its other details
     * can still be changed; only a newly chosen date is checked.
     */
    private static void checkArrival(LocalDate arrival, LocalDate current, LocalDate today) {
        if (arrival != null && arrival.isBefore(today) && !arrival.equals(current)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG248");
        }
    }
}
