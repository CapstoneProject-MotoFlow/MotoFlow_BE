package fu.hl.g64.motorflow.discrepancy.service.impl;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.discrepancy.dto.DiscrepancyCaseResponse;
import fu.hl.g64.motorflow.discrepancy.dto.DiscrepancyCaseResponse.Event;
import fu.hl.g64.motorflow.discrepancy.dto.DiscrepancyHandlingRequest;
import fu.hl.g64.motorflow.discrepancy.dto.DiscrepancySearch;
import fu.hl.g64.motorflow.discrepancy.dto.SupplierReturnRequest;
import fu.hl.g64.motorflow.discrepancy.dto.WaiveReplacementRequest;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyResolutionEvent;
import fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType;
import fu.hl.g64.motorflow.discrepancy.entity.enums.SourceType;
import fu.hl.g64.motorflow.discrepancy.helper.DiscrepancySettlement;
import fu.hl.g64.motorflow.discrepancy.helper.DiscrepancySettlement.Progress;
import fu.hl.g64.motorflow.discrepancy.helper.DiscrepancySettlement.Standing;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyCaseRepository;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyResolutionEventRepository;
import fu.hl.g64.motorflow.discrepancy.service.DiscrepancyService;
import fu.hl.g64.motorflow.inventory.dto.LotQuantity;
import fu.hl.g64.motorflow.inventory.dto.MovePosting;
import fu.hl.g64.motorflow.inventory.dto.OutPosting;
import fu.hl.g64.motorflow.inventory.entity.Lot;
import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.inventory.repository.LotRepository;
import fu.hl.g64.motorflow.inventory.service.InventoryPostingService;
import fu.hl.g64.motorflow.operation.dto.AttachmentResponse;
import fu.hl.g64.motorflow.operation.entity.enums.OwnerType;
import fu.hl.g64.motorflow.operation.service.AttachmentService;
import fu.hl.g64.motorflow.partner.entity.Supplier;
import fu.hl.g64.motorflow.partner.repository.SupplierRepository;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNote;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNoteLine;
import fu.hl.g64.motorflow.receiving.entity.PurchaseOrder;
import fu.hl.g64.motorflow.receiving.entity.enums.ExcessDecision;
import fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType;
import fu.hl.g64.motorflow.receiving.helper.ReceivingLedger;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteLineRepository;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteRepository;
import fu.hl.g64.motorflow.receiving.repository.PurchaseOrderRepository;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DiscrepancyServiceImpl implements DiscrepancyService {

    /** audit_log.entity_type and action of a saved handling (UC-46 step 7). */
    private static final String AUDIT_ENTITY = "DISCREPANCY_CASE";
    private static final String HANDLING_SAVED = "DISCREPANCY_HANDLING_SAVED";

    private final DiscrepancyCaseRepository discrepancies;
    private final DiscrepancyResolutionEventRepository events;
    private final GoodsReceiptNoteRepository receipts;
    private final GoodsReceiptNoteLineRepository receiptLines;
    private final PurchaseOrderRepository orders;
    private final SupplierRepository suppliers;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final UserAccountRepository users;
    private final LotRepository lots;
    private final InventoryPostingService posting;
    private final AttachmentService attachments;
    private final AuditLogRepository auditLog;
    private final ReceivingLedger ledger;
    private final DiscrepancySettlement settlement;
    private final Clock clock;

    @Transactional(readOnly = true)
    @Override
    public PageResponse<DiscrepancyCaseResponse> list(AuthenticatedUser user, DiscrepancySearch search, int page,
                                                      int size) {
        Long storeId = search.storeId();
        if (user.role() != Role.OWNER) {
            if (storeId != null && !storeId.equals(user.storeId())) {
                throw forbidden();
            }
            storeId = user.storeId();
        }
        Page<DiscrepancyCase> found = discrepancies.findAll(matching(storeId, search), PageRequest.of(
                Math.max(page, 0), Math.clamp(size, 1, 100),
                Sort.by(Sort.Order.desc("openedAt"), Sort.Order.desc("id"))));
        return PageResponse.of(found, responses(user, found.getContent(), false));
    }

    @Transactional(readOnly = true)
    @Override
    public DiscrepancyCaseResponse get(AuthenticatedUser user, long id) {
        DiscrepancyCase found = discrepancies.findById(id)
                .filter(c -> c.getSourceType() == SourceType.SUPPLIER)
                .orElseThrow(DiscrepancyServiceImpl::notFound);
        requireHandler(user, orderOf(found.getGoodsReceiptNoteId()));
        return responses(user, List.of(found), true).getFirst();
    }

    // UC-46 steps 3-7
    @Transactional
    @Override
    public List<DiscrepancyCaseResponse> saveHandling(AuthenticatedUser handler, long receiptId,
                                                      DiscrepancyHandlingRequest request) {
        GoodsReceiptNote receipt = receipts.findById(receiptId)
                .filter(r -> r.getReceiptType() == ReceiptType.SUPPLIER_DELIVERY)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG132"));
        PurchaseOrder order = orders.findById(receipt.getPurchaseOrderId()).orElseThrow();
        requireHandler(handler, order);
        // Lock order: cases, then stock.
        List<DiscrepancyCase> cases = discrepancies.findByReceiptForUpdate(receiptId);
        Map<Long, DiscrepancyCase> caseById = byId(cases, DiscrepancyCase::getId);
        Map<Long, Integer> wanted = new LinkedHashMap<>();
        for (int i = 0; i < request.lines().size(); i++) {
            DiscrepancyHandlingRequest.Line line = request.lines().get(i);
            int n = i + 1;
            DiscrepancyCase found = caseById.get(line.caseId());
            if (found == null || wanted.putIfAbsent(found.getId(), line.returnRequiredQty()) != null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG136", n);
            }
            if (!found.isOpen()) {
                throw changed();
            }
            // UC-46 Step 5_Invalid or stale quantities. Goods already moved to awaiting return stay to be returned.
            if (line.returnRequiredQty() > found.heldQty()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG35", n);
            }
            if (line.returnRequiredQty() < found.getReturnRequiredQty()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG134", n, found.getReturnRequiredQty());
            }
        }

        boolean replacementDelivery = ledger.replacementReceiptIds(List.of(receiptId)).contains(receiptId);
        String note = text(request.note());
        LocalDateTime now = LocalDateTime.now(clock);
        List<DiscrepancyCase> handled = cases.stream().filter(found -> wanted.containsKey(found.getId()))
                .sorted(Comparator.comparing(DiscrepancyCase::getProductId)).toList();
        for (DiscrepancyCase found : handled) {
            int before = found.getReturnRequiredQty();
            int after = wanted.get(found.getId());
            if (after == before) {
                continue;
            }
            // Rejected excess is already awaiting return (UC-45); the rest comes from the wrong items first, then
            // from the damaged goods.
            int movedBefore = before - found.getRejectedExcessQty();
            int movedAfter = after - found.getRejectedExcessQty();
            int wrongBefore = Math.min(movedBefore, found.getWrongItemQty());
            int wrongAfter = Math.min(movedAfter, found.getWrongItemQty());
            long lotId = lotOf(receiptId, found.getProductId());
            hold(found, StockStatus.WRONG_ITEM, wrongAfter - wrongBefore, lotId, order.getStoreId(), handler.id(),
                    note, now);
            hold(found, StockStatus.DAMAGED, (movedAfter - wrongAfter) - (movedBefore - wrongBefore), lotId,
                    order.getStoreId(), handler.id(), note, now);
            Map<String, Object> old = obligations(found);
            found.agreeReturn(after, !replacementDelivery);
            auditLog.save(AuditLog.record(AUDIT_ENTITY, found.getId(), HANDLING_SAVED, handler.id(), note, old,
                    obligations(found), now));
        }
        handled.forEach(found -> settlement.closeIfSettled(found, handler.id(), now));
        return responses(handler, cases, true);
    }

    // UC-46 Step 1_Actual supplier return
    @Transactional
    @Override
    public DiscrepancyCaseResponse recordSupplierReturn(AuthenticatedUser manager, long id,
                                                        SupplierReturnRequest request) {
        long receiptId = discrepancies.findReceiptIdOf(id).orElseThrow(DiscrepancyServiceImpl::notFound);
        PurchaseOrder order = orderOf(receiptId);
        // Actual returns are recorded only by the receiving Branch Manager (UC-46 preconditions).
        if (manager.role() != Role.BRANCH_MANAGER || !Objects.equals(manager.storeId(), order.getStoreId())) {
            throw forbidden();
        }
        DiscrepancyCase found = discrepancies.findAllByIdForUpdate(List.of(id)).getFirst();
        if (!found.isOpen()) {
            throw changed();
        }
        int left = settlement.standing(found).outstandingReturn();
        if (left == 0) {
            throw changed();
        }
        if (request.quantity() > left) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG135", left);
        }
        int quantity = request.quantity();
        String note = text(request.note());
        LocalDateTime now = LocalDateTime.now(clock);
        DiscrepancyResolutionEvent event = events.save(DiscrepancyResolutionEvent.done(id,
                ResolutionType.RETURNED_TO_SUPPLIER, quantity, note, manager.id(), now));
        // The goods leave the store from awaiting return to supplier, valued at the average (BR-74).
        long lotId = lotOf(receiptId, found.getProductId());
        posting.takeOut(new OutPosting(order.getStoreId(), found.getProductId(), StockStatus.AWAITING_SUPPLIER_RETURN,
                quantity, List.of(new LotQuantity(lotId, quantity)), MovementType.SUPPLIER_RETURN,
                SourceRef.discrepancyResolutionEvent(event.getId()), manager.id(), note));
        attachments.attach(OwnerType.DISCREPANCY_RESOLUTION_EVENT, event.getId(),
                request.evidence() == null ? List.of() : request.evidence(), manager.id());
        settlement.closeIfSettled(found, manager.id(), now);
        return responses(manager, List.of(found), true).getFirst();
    }

    // UC-46 Step 3_Shortage settled without replacement
    @Transactional
    @Override
    public DiscrepancyCaseResponse waiveReplacement(AuthenticatedUser owner, long id,
                                                    WaiveReplacementRequest request) {
        if (owner.role() != Role.OWNER) {
            throw forbidden();
        }
        discrepancies.findReceiptIdOf(id).orElseThrow(DiscrepancyServiceImpl::notFound);
        DiscrepancyCase found = discrepancies.findAllByIdForUpdate(List.of(id)).getFirst();
        if (!found.isOpen()) {
            throw changed();
        }
        // Replacement a replacement delivery is already bringing is settled by that delivery's confirmation.
        int open = settlement.standing(found).openReplacement();
        if (open == 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG141", "#" + id);
        }
        int quantity = request.quantity() == null ? open : request.quantity();
        if (quantity > open) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG144", open);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        events.save(DiscrepancyResolutionEvent.done(id, ResolutionType.CLOSED_BY_AGREEMENT, quantity,
                request.reason().strip(), owner.id(), now));
        settlement.closeIfSettled(found, owner.id(), now);
        return responses(owner, List.of(found), true).getFirst();
    }

    /** Moves goods of the case to awaiting return to supplier, with the event the movements point to. */
    private void hold(DiscrepancyCase found, StockStatus from, int quantity, long lotId, long storeId, long by,
                      String note, LocalDateTime now) {
        if (quantity <= 0) {
            return;
        }
        DiscrepancyResolutionEvent event = events.save(DiscrepancyResolutionEvent.done(found.getId(),
                ResolutionType.MOVED_TO_AWAITING_RETURN, quantity, note, by, now));
        posting.move(new MovePosting(found.getProductId(), storeId, from, storeId,
                StockStatus.AWAITING_SUPPLIER_RETURN, quantity, List.of(new LotQuantity(lotId, quantity)),
                MovementType.RETURN_HOLD, SourceRef.discrepancyResolutionEvent(event.getId()), by, note));
    }

    /** The lot the receipt line of the part created; held, rejected and returned goods all stay in it. */
    private long lotOf(long receiptId, long productId) {
        List<Long> lineIds = receiptLines.findByGoodsReceiptNoteIdOrderByIdAsc(receiptId).stream()
                .filter(line -> line.getProductId() == productId).map(GoodsReceiptNoteLine::getId).toList();
        return lots.findByGoodsReceiptNoteLineIdIn(lineIds).stream().map(Lot::getId).findFirst()
                .orElseThrow(() -> new IllegalStateException("no lot for part " + productId + " on GRN " + receiptId));
    }

    private PurchaseOrder orderOf(long receiptId) {
        GoodsReceiptNote receipt = receipts.findById(receiptId).orElseThrow();
        return orders.findById(receipt.getPurchaseOrderId()).orElseThrow();
    }

    /** Owner, or a Branch Manager of the store that received the goods (MSG05 otherwise). */
    private static void requireHandler(AuthenticatedUser user, PurchaseOrder order) {
        boolean manager = user.role() == Role.BRANCH_MANAGER && Objects.equals(user.storeId(), order.getStoreId());
        if (user.role() != Role.OWNER && !manager) {
            throw forbidden();
        }
    }

    private List<DiscrepancyCaseResponse> responses(AuthenticatedUser user, List<DiscrepancyCase> cases,
                                                    boolean withEvents) {
        if (cases.isEmpty()) {
            return List.of();
        }
        Set<Long> receiptIds = cases.stream().map(DiscrepancyCase::getGoodsReceiptNoteId).collect(Collectors.toSet());
        Map<Long, GoodsReceiptNote> receiptById = byId(receipts.findAllById(receiptIds), GoodsReceiptNote::getId);
        Map<Long, PurchaseOrder> orderById = byId(orders.findAllById(receiptById.values().stream()
                .map(GoodsReceiptNote::getPurchaseOrderId).collect(Collectors.toSet())), PurchaseOrder::getId);
        Map<Long, Supplier> supplierById = byId(suppliers.findAllById(orderById.values().stream()
                .map(PurchaseOrder::getSupplierId).collect(Collectors.toSet())), Supplier::getId);
        Map<Long, String> storeNames = stores.findAllById(orderById.values().stream().map(PurchaseOrder::getStoreId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Store::getId, Store::getStoreName));
        Map<Long, Product> parts = byId(products.findAllById(cases.stream().map(DiscrepancyCase::getProductId)
                .collect(Collectors.toSet())), Product::getId);
        List<Long> caseIds = cases.stream().map(DiscrepancyCase::getId).toList();
        Map<Long, Progress> progress = settlement.progress(caseIds);
        Set<Long> replacements = ledger.replacementReceiptIds(receiptIds);
        Set<String> excessAwaiting = receiptLines.findByGoodsReceiptNoteIdInAndExcessDecision(receiptIds,
                        ExcessDecision.AWAITING_OWNER).stream()
                .map(line -> line.getGoodsReceiptNoteId() + ":" + line.getProductId()).collect(Collectors.toSet());

        Map<Long, List<DiscrepancyResolutionEvent>> eventsByCase = withEvents
                ? events.findByDiscrepancyCaseIdInOrderByOccurredAtAscIdAsc(caseIds).stream()
                .collect(Collectors.groupingBy(DiscrepancyResolutionEvent::getDiscrepancyCaseId))
                : Map.of();
        Map<Long, List<AttachmentResponse>> evidence = eventsByCase.isEmpty() ? Map.of()
                : attachments.list(OwnerType.DISCREPANCY_RESOLUTION_EVENT, eventsByCase.values().stream()
                .flatMap(List::stream).map(DiscrepancyResolutionEvent::getId).toList());
        Set<Long> people = new HashSet<>();
        cases.forEach(found -> people.add(found.getClosedBy()));
        eventsByCase.values().forEach(list -> list.forEach(event -> people.add(event.getRecordedBy())));
        people.remove(null);
        // A HashMap, so that the closer of a case still open (a null id) gives a null name.
        Map<Long, String> names = new HashMap<>();
        if (!people.isEmpty()) {
            users.findAllById(people).forEach(person -> names.put(person.getId(), person.getFullName()));
        }
        boolean owner = user.role() == Role.OWNER;

        List<DiscrepancyCaseResponse> rows = new ArrayList<>();
        for (DiscrepancyCase found : cases) {
            GoodsReceiptNote receipt = receiptById.get(found.getGoodsReceiptNoteId());
            PurchaseOrder order = orderById.get(receipt.getPurchaseOrderId());
            Supplier supplier = supplierById.get(order.getSupplierId());
            Product part = parts.get(found.getProductId());
            Standing standing = new Standing(found, progress.get(found.getId()));
            Progress done = standing.progress();
            boolean manager = user.role() == Role.BRANCH_MANAGER
                    && Objects.equals(user.storeId(), order.getStoreId());
            boolean open = found.isOpen();
            List<Event> history = eventsByCase.getOrDefault(found.getId(), List.of()).stream()
                    .map(event -> new Event(event.getId(), event.getResolutionType(), event.getQuantity(),
                            event.getGoodsReceiptNoteId(), event.getReason(), event.getRecordedBy(),
                            names.get(event.getRecordedBy()), event.getOccurredAt(), event.isConfirmed(),
                            event.getConfirmedAt(), evidence.getOrDefault(event.getId(), List.of())))
                    .toList();
            rows.add(new DiscrepancyCaseResponse(found.getId(), receipt.getId(), replacements.contains(receipt.getId()),
                    order.getId(), supplier.getId(), supplier.getSupplierName(), order.getStoreId(),
                    storeNames.get(order.getStoreId()), part.getId(), part.getSku(), part.getPartName(),
                    part.getBaseUnit(), found.getShortQty(), found.getWrongItemQty(), found.getDamagedQty(),
                    found.getRejectedExcessQty(), found.getReturnRequiredQty(), found.getReplacementRequiredQty(),
                    done.returned(), done.writtenOff(), done.replaced(), done.waived(), done.pendingReplacement(),
                    standing.outstandingReturn(), standing.outstandingReplacement(), standing.openReplacement(),
                    standing.heldUndecided(), excessAwaiting.contains(receipt.getId() + ":" + found.getProductId()),
                    found.getStatus(), found.getOpenedAt(), found.getClosureType(), found.getClosureReason(),
                    found.getClosedBy(), names.get(found.getClosedBy()), found.getClosedAt(),
                    open && (owner || manager) && found.heldQty() > found.getReturnRequiredQty(),
                    open && manager && standing.outstandingReturn() > 0,
                    open && owner && standing.openReplacement() > 0,
                    open && manager && standing.openReplacement() > 0, history));
        }
        return rows;
    }

    private static Map<String, Object> obligations(DiscrepancyCase found) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("return_required_qty", found.getReturnRequiredQty());
        values.put("replacement_required_qty", found.getReplacementRequiredQty());
        return values;
    }

    private static Specification<DiscrepancyCase> matching(Long storeId, DiscrepancySearch search) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("sourceType"), SourceType.SUPPLIER));
            if (search.status() != null) {
                where.add(cb.equal(root.get("status"), search.status()));
            }
            if (search.goodsReceiptNoteId() != null) {
                where.add(cb.equal(root.get("goodsReceiptNoteId"), search.goodsReceiptNoteId()));
            }
            if (search.purchaseOrderId() != null) {
                Subquery<Long> ofOrder = query.subquery(Long.class);
                Root<GoodsReceiptNote> receipt = ofOrder.from(GoodsReceiptNote.class);
                ofOrder.select(receipt.get("id"))
                        .where(cb.equal(receipt.get("purchaseOrderId"), search.purchaseOrderId()));
                where.add(root.get("goodsReceiptNoteId").in(ofOrder));
            }
            if (storeId != null) {
                Subquery<Long> ordersOfStore = query.subquery(Long.class);
                Root<PurchaseOrder> order = ordersOfStore.from(PurchaseOrder.class);
                ordersOfStore.select(order.get("id")).where(cb.equal(order.get("storeId"), storeId));
                Subquery<Long> ofStore = query.subquery(Long.class);
                Root<GoodsReceiptNote> receipt = ofStore.from(GoodsReceiptNote.class);
                ofStore.select(receipt.get("id")).where(receipt.get("purchaseOrderId").in(ordersOfStore));
                where.add(root.get("goodsReceiptNoteId").in(ofStore));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    private static String text(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private static BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "MSG133");
    }

    private static BusinessException changed() {
        return new BusinessException(HttpStatus.CONFLICT, "MSG29");
    }

    private static BusinessException forbidden() {
        return new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
    }

    private static <T> Map<Long, T> byId(Collection<T> rows, Function<T, Long> id) {
        return rows.stream().collect(Collectors.toMap(id, Function.identity()));
    }
}
