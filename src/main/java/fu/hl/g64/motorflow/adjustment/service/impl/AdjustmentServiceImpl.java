package fu.hl.g64.motorflow.adjustment.service.impl;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.adjustment.dto.AdjustmentAmountView;
import fu.hl.g64.motorflow.adjustment.dto.AdjustmentDetail;
import fu.hl.g64.motorflow.adjustment.dto.AdjustmentDetail.Actions;
import fu.hl.g64.motorflow.adjustment.dto.AdjustmentDetail.HistoryEntry;
import fu.hl.g64.motorflow.adjustment.dto.AdjustmentLineView;
import fu.hl.g64.motorflow.adjustment.dto.AdjustmentPreview;
import fu.hl.g64.motorflow.adjustment.dto.AdjustmentSearch;
import fu.hl.g64.motorflow.adjustment.dto.AdjustmentSource;
import fu.hl.g64.motorflow.adjustment.dto.AdjustmentSummary;
import fu.hl.g64.motorflow.adjustment.dto.AdjustmentSummary.Person;
import fu.hl.g64.motorflow.adjustment.dto.CorrectionRequest;
import fu.hl.g64.motorflow.adjustment.dto.RejectAdjustmentRequest;
import fu.hl.g64.motorflow.adjustment.dto.StockLossRequest;
import fu.hl.g64.motorflow.adjustment.entity.AdjustmentRequest;
import fu.hl.g64.motorflow.adjustment.entity.AdjustmentRequest.Source;
import fu.hl.g64.motorflow.adjustment.entity.AdjustmentStockLine;
import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus;
import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentType;
import fu.hl.g64.motorflow.adjustment.helper.AdjustmentStock;
import fu.hl.g64.motorflow.adjustment.helper.AdjustmentStock.Change;
import fu.hl.g64.motorflow.adjustment.helper.DocumentCorrections;
import fu.hl.g64.motorflow.adjustment.helper.DocumentCorrections.Plan;
import fu.hl.g64.motorflow.adjustment.repository.AdjustmentAmountLineRepository;
import fu.hl.g64.motorflow.adjustment.repository.AdjustmentRequestRepository;
import fu.hl.g64.motorflow.adjustment.repository.AdjustmentStockLineRepository;
import fu.hl.g64.motorflow.adjustment.service.AdjustmentService;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyResolutionEvent;
import fu.hl.g64.motorflow.discrepancy.entity.enums.SourceType;
import fu.hl.g64.motorflow.discrepancy.helper.DiscrepancySettlement;
import fu.hl.g64.motorflow.discrepancy.helper.DiscrepancySettlement.Standing;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyCaseRepository;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyResolutionEventRepository;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.inventory.helper.InventoryPeriods;
import fu.hl.g64.motorflow.inventory.repository.StockMovementRepository;
import fu.hl.g64.motorflow.operation.dto.AttachmentUpload;
import fu.hl.g64.motorflow.operation.entity.enums.OwnerType;
import fu.hl.g64.motorflow.operation.service.AttachmentService;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNote;
import fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteRepository;
import fu.hl.g64.motorflow.receiving.repository.PurchaseOrderRepository;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.helper.StoreScope;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class AdjustmentServiceImpl implements AdjustmentService {

    /** audit_log.entity_type of adjustment requests and the actions of their history (UC-84 step 8). */
    public static final String AUDIT_ENTITY = "ADJUSTMENT_REQUEST";
    public static final String SUBMITTED = "ADJUSTMENT_SUBMITTED";
    public static final String APPROVED = "ADJUSTMENT_APPROVED";
    public static final String APPLIED = "ADJUSTMENT_APPLIED";
    public static final String REJECTED = "ADJUSTMENT_REJECTED";
    public static final String WITHDRAWN = "ADJUSTMENT_WITHDRAWN";
    private static final List<String> HISTORY = List.of(SUBMITTED, APPROVED, APPLIED, REJECTED, WITHDRAWN);

    /** Statuses a loss found in the warehouse may write off; goods still being inspected are not the store's yet. */
    private static final Set<StockStatus> WAREHOUSE_LOSS = EnumSet.of(StockStatus.AVAILABLE, StockStatus.WRONG_ITEM,
            StockStatus.DAMAGED);
    /** Held goods of a supplier delivery: wrong, damaged, or rejected and waiting to go back (UC-46). */
    private static final Set<StockStatus> HELD = EnumSet.of(StockStatus.WRONG_ITEM, StockStatus.DAMAGED,
            StockStatus.AWAITING_SUPPLIER_RETURN);

    /** The longest range of submission days one search may cover (MSG264). */
    private static final int MAX_RANGE_DAYS = 366;

    private final AdjustmentRequestRepository requests;
    private final AdjustmentStockLineRepository stockLines;
    private final AdjustmentAmountLineRepository amountLines;
    private final AdjustmentStock stock;
    private final DocumentCorrections corrections;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final UserAccountRepository users;
    private final TransferOrderRepository transferOrders;
    private final GoodsReceiptNoteRepository receipts;
    private final PurchaseOrderRepository purchaseOrders;
    private final DiscrepancyCaseRepository discrepancies;
    private final DiscrepancyResolutionEventRepository discrepancyEvents;
    private final DiscrepancySettlement settlement;
    private final StockMovementRepository movements;
    private final InventoryPeriods periods;
    private final AttachmentService attachments;
    private final NotificationService notifications;
    private final AuditLogRepository auditLog;
    private final TransactionTemplate transactions;
    private final StoreScope storeScope;
    private final Clock clock;

    // ---------------------------------------------------------------------------------------------- UC-83

    /** A stock loss checked against the stock now, before it is saved. */
    private record StockLoss(Store store, Source source, List<Change> changes) {
    }

    @Transactional(readOnly = true)
    @Override
    public AdjustmentPreview previewStockLoss(AuthenticatedUser user, StockLossRequest request) {
        StockLoss loss = checkStockLoss(user, request);
        boolean owner = user.role() == Role.OWNER;
        Long transferOrderId = transferOf(loss.source());
        List<AdjustmentLineView> lines = stock.views(loss.store().getId(), transferOrderId, loss.changes(), owner);
        return new AdjustmentPreview(AdjustmentType.STOCK_LOSS, loss.store().getId(), loss.store().getStoreName(),
                source(loss.source()), LocalDate.now(clock), lines, List.of(), owner ? total(lines) : null);
    }

    @Transactional
    @Override
    public AdjustmentDetail submitStockLoss(AuthenticatedUser user, StockLossRequest request) {
        StockLoss loss = checkStockLoss(user, request);
        if (request.attachments() == null || request.attachments().isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG255");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        AdjustmentRequest saved = requests.save(AdjustmentRequest.submit(AdjustmentType.STOCK_LOSS,
                loss.store().getId(), loss.source(), request.reason().strip(), user.id(), now));
        for (Change c : loss.changes()) {
            stockLines.save(AdjustmentStockLine.of(saved.getId(), loss.store().getId(), c.productId(), c.lotId(),
                    c.status(), c.change()));
        }
        submitted(user, saved, request.attachments(), now);
        return detail(user, saved);
    }

    /** Records the evidence and the submission, and tells Owner unless Owner raised it (UC-83 Step 7). */
    void submitted(AuthenticatedUser user, AdjustmentRequest saved,
                   List<AttachmentUpload> files, LocalDateTime now) {
        attachments.attach(OwnerType.ADJUSTMENT_REQUEST, saved.getId(), files, user.id());
        auditLog.save(AuditLog.record(AUDIT_ENTITY, saved.getId(), SUBMITTED, user.id(), saved.getReason(), now));
        if (user.role() != Role.OWNER) {
            notifications.notifyOwners("ADJUSTMENT_AWAITING_APPROVAL",
                    "Đề nghị điều chỉnh #%d của %s đang chờ bạn duyệt.".formatted(saved.getId(),
                            storeName(saved.getStoreId())), AUDIT_ENTITY, saved.getId());
        }
    }

    /** UC-83 step 4. */
    private StockLoss checkStockLoss(AuthenticatedUser user, StockLossRequest request) {
        Store store = request.transferOrderId() != null
                ? storeForTransitLoss(user, request.storeId(), request.transferOrderId())
                : storeFor(user, request.storeId());
        if (request.transferOrderId() != null && request.goodsReceiptNoteId() != null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG280");
        }
        List<Change> changes = request.lines().stream()
                .map(l -> new Change(l.productId(), l.lotId(), l.stockStatus(), -l.quantity())).toList();
        Source source = null;
        if (request.transferOrderId() != null) {
            source = new Source(Source.SourceType.TRANSFER_ORDER, request.transferOrderId());
        } else if (request.goodsReceiptNoteId() != null) {
            source = new Source(Source.SourceType.GOODS_RECEIPT_NOTE, request.goodsReceiptNoteId());
        }
        AdjustmentStock.requireDistinct(changes);
        checkStockLoss(store.getId(), source, changes, 0);
        return new StockLoss(store, source, changes);
    }

    /**
     * What a stock loss may take: the order's goods in transit, the delivery's held goods no decision covers yet, or
     * the warehouse's own stock; each after what other open requests already take. Run at submission and again when
     * Owner approves ({@code exceptRequestId}), as the stock may have moved since (UC-84 Step 4_Balances changed).
     */
    private void checkStockLoss(long storeId, Source source, List<Change> changes, long exceptRequestId) {
        Long transferOrderId = transferOf(source);
        if (transferOrderId != null) {
            transferOrders.findById(transferOrderId).filter(o -> o.getDestStoreId().equals(storeId))
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG279"));
            changes.stream().filter(c -> c.status() != StockStatus.IN_TRANSIT || c.lotId() != null).findFirst()
                    .ifPresent(c -> {
                        throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG259", c.status());
                    });
        } else if (source != null) {
            checkHeldGoods(storeId, source.id(), changes, exceptRequestId);
        } else {
            changes.stream().filter(c -> !WAREHOUSE_LOSS.contains(c.status())).findFirst().ifPresent(c -> {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG259", c.status());
            });
            stock.requireAvailable(storeId, null, changes, exceptRequestId, heldForCases(storeId, changes));
            return;
        }
        stock.requireAvailable(storeId, transferOrderId, changes, exceptRequestId);
    }

    /**
     * Review 17/11 R-06: wrong or damaged goods a supplier discrepancy case still holds without a decision are the
     * case's, not the warehouse's. A loss found in the warehouse leaves them alone; writing them off goes through
     * the delivery (goodsReceiptNoteId), so the case records APPROVED_LOSS. Keyed "productId:status".
     */
    private Map<String, Long> heldForCases(long storeId, List<Change> changes) {
        Set<Long> parts = changes.stream()
                .filter(c -> c.status() == StockStatus.WRONG_ITEM || c.status() == StockStatus.DAMAGED)
                .map(Change::productId).collect(Collectors.toSet());
        Map<String, Long> held = new HashMap<>();
        if (parts.isEmpty()) {
            return held;
        }
        List<DiscrepancyCase> open = discrepancies.findOpenSupplierCasesAtStore(storeId, parts);
        if (open.isEmpty()) {
            return held;
        }
        Map<Long, Standing> standings = settlement.standings(open);
        Map<String, Long> pendingByDelivery = heldWriteOffs(
                open.stream().map(DiscrepancyCase::getGoodsReceiptNoteId).collect(Collectors.toSet()), parts, 0);
        for (DiscrepancyCase found : open) {
            int undecided = standings.get(found.getId()).heldUndecided();
            long pending = pendingByDelivery.getOrDefault(
                    found.getGoodsReceiptNoteId() + ":" + found.getProductId(), 0L);
            long wrong = Math.max(Math.min(undecided, found.getWrongItemQty()) - pending, 0);
            long damaged = Math.max(Math.min(undecided, found.getDamagedQty()) - pending, 0);
            held.merge(found.getProductId() + ":" + StockStatus.WRONG_ITEM, wrong, Long::sum);
            held.merge(found.getProductId() + ":" + StockStatus.DAMAGED, damaged, Long::sum);
        }
        return held;
    }

    /** UC-46 Step 3_Goods cannot be returned: no more than the held goods of each case that wait for a decision. */
    private void checkHeldGoods(long storeId, long receiptId, List<Change> changes, long exceptRequestId) {
        GoodsReceiptNote receipt = receipts.findById(receiptId)
                .filter(r -> r.getReceiptType() == ReceiptType.SUPPLIER_DELIVERY)
                .filter(r -> purchaseOrders.findById(r.getPurchaseOrderId())
                        .map(o -> o.getStoreId().equals(storeId)).orElse(false))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG132"));
        changes.stream().filter(c -> !HELD.contains(c.status())).findFirst().ifPresent(c -> {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG259", c.status());
        });
        Map<Long, Integer> byPart = new LinkedHashMap<>();
        changes.forEach(c -> byPart.merge(c.productId(), -c.change(), Integer::sum));
        Map<Long, DiscrepancyCase> caseByPart = cases(receipt.getId());
        Map<Long, Standing> standings = settlement.standings(caseByPart.values().stream()
                .filter(c -> byPart.containsKey(c.getProductId()) && c.isOpen()).toList());
        Map<String, Long> pending = heldWriteOffs(List.of(receipt.getId()), byPart.keySet(), exceptRequestId);
        for (Map.Entry<Long, Integer> e : byPart.entrySet()) {
            DiscrepancyCase found = caseByPart.get(e.getKey());
            long free = found == null || !found.isOpen() ? 0
                    : standings.get(found.getId()).heldUndecided()
                    - pending.getOrDefault(receipt.getId() + ":" + e.getKey(), 0L);
            if (e.getValue() > free) {
                Product part = products.findById(e.getKey())
                        .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG57"));
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG257", part.getSku(), Math.max(free, 0));
            }
        }
    }

    /** Units other open requests write off from held goods, keyed "receiptId:productId". */
    private Map<String, Long> heldWriteOffs(Collection<Long> receiptIds, Collection<Long> productIds,
                                            long exceptRequestId) {
        Map<String, Long> pending = new HashMap<>();
        for (Object[] row : stockLines.findOpenHeldWriteOffs(receiptIds, productIds, exceptRequestId)) {
            pending.put(row[0] + ":" + row[1], ((Number) row[2]).longValue());
        }
        return pending;
    }

    private Map<Long, DiscrepancyCase> cases(long receiptId) {
        return discrepancies.findByGoodsReceiptNoteIdInOrderByIdAsc(List.of(receiptId)).stream()
                .filter(c -> c.getSourceType() == SourceType.SUPPLIER)
                .collect(Collectors.toMap(DiscrepancyCase::getProductId, Function.identity(), (a, b) -> a));
    }

    // ---------------------------------------------------------------------------------------------- UC-81

    @Transactional(readOnly = true)
    @Override
    public AdjustmentPreview previewCorrection(AuthenticatedUser user, CorrectionRequest request) {
        Plan plan = corrections.plan(user, request);
        boolean owner = user.role() == Role.OWNER;
        List<AdjustmentLineView> lines = stock.views(plan.storeId(), null, plan.stockChanges(), owner);
        List<AdjustmentAmountView> amounts = corrections.previewAmounts(plan);
        return new AdjustmentPreview(AdjustmentType.DOCUMENT_CORRECTION, plan.storeId(), storeName(plan.storeId()),
                source(plan.source()), LocalDate.now(clock), lines, amounts, owner ? total(lines) : null);
    }

    @Transactional
    @Override
    public AdjustmentDetail submitCorrection(AuthenticatedUser user, CorrectionRequest request) {
        Plan plan = corrections.plan(user, request);
        if (request.attachments() == null || request.attachments().isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG255");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        AdjustmentRequest saved = requests.save(AdjustmentRequest.submit(AdjustmentType.DOCUMENT_CORRECTION,
                plan.storeId(), plan.source(), request.reason().strip(), user.id(), now));
        for (Change c : plan.stockChanges()) {
            stockLines.save(AdjustmentStockLine.of(saved.getId(), plan.storeId(), c.productId(), c.lotId(),
                    c.status(), c.change()));
        }
        corrections.saved(saved, plan, user.id(), now);
        submitted(user, saved, request.attachments(), now);
        return detail(user, saved);
    }

    // ---------------------------------------------------------------------------------------------- UC-82

    @Transactional(readOnly = true)
    @Override
    public PageResponse<AdjustmentSummary> list(AuthenticatedUser user, AdjustmentSearch search, int page,
                                                int size) {
        if (search.from() != null && search.to() != null && (search.from().isAfter(search.to())
                || search.from().plusDays(MAX_RANGE_DAYS).isBefore(search.to()))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG264", MAX_RANGE_DAYS);
        }
        Long storeId = search.storeId();
        if (user.role() != Role.OWNER) {
            if (storeId != null && !storeId.equals(user.storeId())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
            }
            storeId = user.storeId();
        }
        Page<AdjustmentRequest> found = requests.findAll(matching(user, storeId, search), PageRequest.of(
                Math.max(page, 0), Math.clamp(size, 1, 100),
                Sort.by(Sort.Order.desc("submittedAt"), Sort.Order.desc("id"))));
        return PageResponse.of(found, summaries(user, found.getContent()));
    }

    private static Specification<AdjustmentRequest> matching(AuthenticatedUser user, Long storeId,
                                                             AdjustmentSearch search) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.notEqual(root.get("status"), AdjustmentRequestStatus.DRAFT));
            if (storeId != null && user.role() != Role.OWNER && search.storeId() == null) {
                // A Branch Manager's own store, and the transit losses they reported for others (R-05).
                where.add(cb.or(cb.equal(root.get("storeId"), storeId), cb.equal(root.get("requestedBy"), user.id())));
            } else if (storeId != null) {
                where.add(cb.equal(root.get("storeId"), storeId));
            }
            if (search.type() != null) {
                where.add(cb.equal(root.get("adjustmentType"), search.type()));
            }
            if (search.statuses() != null && !search.statuses().isEmpty()) {
                where.add(root.get("status").in(search.statuses()));
            }
            if (search.from() != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("submittedAt"), search.from().atStartOfDay()));
            }
            if (search.to() != null) {
                where.add(cb.lessThan(root.get("submittedAt"), search.to().plusDays(1).atStartOfDay()));
            }
            if (search.mine()) {
                where.add(cb.equal(root.get("requestedBy"), user.id()));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    private List<AdjustmentSummary> summaries(AuthenticatedUser user, List<AdjustmentRequest> page) {
        Set<Long> ids = page.stream().map(AdjustmentRequest::getId).collect(Collectors.toSet());
        Map<Long, List<AdjustmentStockLine>> linesByRequest = ids.isEmpty() ? Map.of()
                : stockLines.findByAdjustmentRequestIdIn(ids).stream()
                .collect(Collectors.groupingBy(AdjustmentStockLine::getAdjustmentRequestId));
        boolean owner = user.role() == Role.OWNER;
        Map<Long, Long> posted = owner && !ids.isEmpty() ? postedValues(ids) : Map.of();
        Map<Long, Product> parts = owner ? products.findAllById(linesByRequest.values().stream()
                        .flatMap(List::stream).map(AdjustmentStockLine::getProductId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Product::getId, Function.identity())) : Map.of();
        Map<Long, String> storeNames = storeNames(page.stream().map(AdjustmentRequest::getStoreId).toList());
        Map<Long, Person> people = people(page.stream()
                .flatMap(r -> Stream.of(r.getRequestedBy(), r.getDecidedBy())).toList());
        return page.stream().map(r -> {
            List<AdjustmentStockLine> lines = linesByRequest.getOrDefault(r.getId(), List.of());
            Long value = null;
            if (owner) {
                value = r.getStatus() == AdjustmentRequestStatus.APPLIED
                        ? posted.getOrDefault(r.getId(), 0L)
                        : lines.stream().mapToLong(l -> AdjustmentStock.unitCost(parts.get(l.getProductId()))
                        * l.getQuantityChange()).sum();
            }
            return new AdjustmentSummary(r.getId(), "#" + r.getId(), r.getAdjustmentType(), r.getStatus(),
                    r.getStoreId(), storeNames.get(r.getStoreId()), source(r.source()), r.getReason(),
                    lines.size(), value, people.get(r.getRequestedBy()), r.getSubmittedAt(),
                    people.get(r.getDecidedBy()), r.getDecidedAt(), r.getAppliedAt());
        }).toList();
    }

    // ---------------------------------------------------------------------------------------------- UC-84

    @Transactional(readOnly = true)
    @Override
    public AdjustmentDetail get(AuthenticatedUser user, long id) {
        return detail(user, requests.findById(id).filter(r -> visible(user, r)).orElseThrow(this::notFound));
    }

    @Override
    public AdjustmentDetail approve(AuthenticatedUser owner, long id) {
        boolean alreadyApplied = Boolean.TRUE.equals(transactions.execute(tx -> {
            AdjustmentRequest request = lock(owner, id);
            if (request.getStatus() == AdjustmentRequestStatus.APPLIED) {
                return true;
            }
            requireStatus(request, AdjustmentRequestStatus.PENDING);
            // UC-84 step 4: approving what cannot be applied any more leaves the request PENDING.
            recheck(request);
            LocalDateTime now = LocalDateTime.now(clock);
            request.approve(owner.id(), now);
            auditLog.save(AuditLog.record(AUDIT_ENTITY, id, APPROVED, owner.id(), null, now));
            return false;
        }));
        if (!alreadyApplied) {
            transactions.executeWithoutResult(tx -> applyApproved(owner, lock(owner, id)));
        }
        return transactions.execute(tx -> get(owner, id));
    }

    @Override
    public AdjustmentDetail apply(AuthenticatedUser owner, long id) {
        transactions.executeWithoutResult(tx -> {
            AdjustmentRequest request = lock(owner, id);
            if (request.getStatus() != AdjustmentRequestStatus.APPLIED) {
                requireStatus(request, AdjustmentRequestStatus.APPROVED);
                applyApproved(owner, request);
            }
        });
        return transactions.execute(tx -> get(owner, id));
    }

    /** UC-84 steps 7-9, once: posts the stock and amount lines on today's date and marks the request APPLIED. */
    private void applyApproved(AuthenticatedUser owner, AdjustmentRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        recheck(request);
        List<AdjustmentStockLine> lines = stockLines.findByAdjustmentRequestIdOrderByIdAsc(request.getId());
        stock.apply(request, lines, owner.id());
        if (request.getAdjustmentType() == AdjustmentType.STOCK_LOSS && request.getGoodsReceiptNoteId() != null) {
            settleHeldGoods(request, lines, owner.id(), now);
        }
        if (request.getAdjustmentType() == AdjustmentType.DOCUMENT_CORRECTION) {
            corrections.apply(request, owner.id(), now);
        }
        request.markApplied(now);
        if (request.getAdjustmentType() == AdjustmentType.DOCUMENT_CORRECTION) {
            corrections.applied(request, now);
        }
        auditLog.save(AuditLog.record(AUDIT_ENTITY, request.getId(), APPLIED, owner.id(), null, now));
        if (!request.isRequestedBy(owner.id())) {
            notifications.notify(request.getRequestedBy(), "ADJUSTMENT_APPLIED",
                    "Đề nghị điều chỉnh #%d đã được duyệt và áp dụng.".formatted(request.getId()), AUDIT_ENTITY,
                    request.getId());
        }
    }

    /** UC-84 step 4 again, inside the decision's transaction; a closed month is refused here too (MSG85). */
    private void recheck(AdjustmentRequest request) {
        List<AdjustmentStockLine> lines = stockLines.findByAdjustmentRequestIdOrderByIdAsc(request.getId());
        if (!lines.isEmpty()) {
            periods.openFor(LocalDate.now(clock));
        }
        if (request.getAdjustmentType() == AdjustmentType.STOCK_LOSS) {
            checkStockLoss(request.getStoreId(), request.source(), AdjustmentStock.changesOf(lines), request.getId());
        } else {
            corrections.recheck(request, lines);
        }
    }

    /** UC-46: the written-off held goods settle their discrepancy cases, which may close. */
    private void settleHeldGoods(AdjustmentRequest request, List<AdjustmentStockLine> lines, long ownerId,
                                 LocalDateTime now) {
        Map<Long, Integer> byPart = new LinkedHashMap<>();
        lines.forEach(l -> byPart.merge(l.getProductId(), -l.getQuantityChange(), Integer::sum));
        Map<Long, DiscrepancyCase> caseByPart = cases(request.getGoodsReceiptNoteId());
        for (Map.Entry<Long, Integer> e : byPart.entrySet()) {
            DiscrepancyCase found = discrepancies.findByIdForUpdate(caseByPart.get(e.getKey()).getId()).orElseThrow();
            discrepancyEvents.save(DiscrepancyResolutionEvent.heldWriteOff(found.getId(), e.getValue(),
                    request.getId(), request.getReason(), ownerId, now));
            discrepancyEvents.flush();
            settlement.closeIfSettled(found, ownerId, now);
        }
    }

    @Transactional
    @Override
    public AdjustmentDetail reject(AuthenticatedUser owner, long id, RejectAdjustmentRequest body) {
        AdjustmentRequest request = lock(owner, id);
        requireStatus(request, AdjustmentRequestStatus.PENDING);
        LocalDateTime now = LocalDateTime.now(clock);
        String reason = body.reason().strip();
        request.reject(owner.id(), reason, now);
        auditLog.save(AuditLog.record(AUDIT_ENTITY, id, REJECTED, owner.id(), reason, now));
        if (!request.isRequestedBy(owner.id())) {
            notifications.notify(request.getRequestedBy(), "ADJUSTMENT_REJECTED",
                    "Đề nghị điều chỉnh #%d đã bị từ chối: %s".formatted(id, reason), AUDIT_ENTITY, id);
        }
        return detail(owner, request);
    }

    @Transactional
    @Override
    public AdjustmentDetail withdraw(AuthenticatedUser user, long id) {
        AdjustmentRequest request = lock(user, id);
        if (!request.isRequestedBy(user.id())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        requireStatus(request, AdjustmentRequestStatus.PENDING);
        request.withdraw();
        auditLog.save(AuditLog.record(AUDIT_ENTITY, id, WITHDRAWN, user.id(), null, LocalDateTime.now(clock)));
        return detail(user, request);
    }

    // ---------------------------------------------------------------------------------------------- views

    AdjustmentDetail detail(AuthenticatedUser user, AdjustmentRequest r) {
        boolean owner = user.role() == Role.OWNER;
        List<AdjustmentStockLine> lines = stockLines.findByAdjustmentRequestIdOrderByIdAsc(r.getId());
        List<AdjustmentLineView> lineViews = stock.views(r.getStoreId(), r.getTransferOrderId(),
                AdjustmentStock.changesOf(lines), owner);
        List<AdjustmentAmountView> amounts = corrections.amountViews(r);
        Long total = null;
        if (owner) {
            total = r.getStatus() == AdjustmentRequestStatus.APPLIED
                    ? postedValues(List.of(r.getId())).getOrDefault(r.getId(), 0L) : total(lineViews);
        }
        List<AuditLog> entries = new ArrayList<>(auditLog.findByEntityTypeAndEntityIdAndActionInOrderByOccurredAtDescIdDesc(
                AUDIT_ENTITY, r.getId(), HISTORY));
        Collections.reverse(entries);
        Map<Long, Person> people = people(Stream.concat(Stream.of(r.getRequestedBy(), r.getDecidedBy()),
                entries.stream().map(AuditLog::getActorId)).toList());
        List<HistoryEntry> history = entries.stream().map(e -> new HistoryEntry(e.getAction(),
                people.get(e.getActorId()), e.getOccurredAt(), e.getReason())).toList();
        boolean pending = r.getStatus() == AdjustmentRequestStatus.PENDING;
        Actions actions = new Actions(owner && pending, owner && pending,
                owner && r.getStatus() == AdjustmentRequestStatus.APPROVED, pending && r.isRequestedBy(user.id()));
        LocalDate postingDate = r.getAppliedAt() != null ? r.getAppliedAt().toLocalDate()
                : EnumSet.of(AdjustmentRequestStatus.PENDING, AdjustmentRequestStatus.APPROVED).contains(r.getStatus())
                ? LocalDate.now(clock) : null;
        return new AdjustmentDetail(r.getId(), "#" + r.getId(), r.getAdjustmentType(), r.getStatus(), r.getStoreId(),
                storeName(r.getStoreId()), source(r.source()), r.getReason(), people.get(r.getRequestedBy()),
                r.getSubmittedAt(), people.get(r.getDecidedBy()), r.getDecidedAt(), r.getDecisionReason(),
                r.getAppliedAt(), postingDate, lineViews, amounts, total, corrections.correctionView(r),
                attachments.list(OwnerType.ADJUSTMENT_REQUEST, r.getId()), history, actions);
    }

    private static Long total(List<AdjustmentLineView> lines) {
        return lines.stream().map(AdjustmentLineView::value).filter(Objects::nonNull).mapToLong(Long::longValue)
                .sum();
    }

    /**
     * What an applied request changed: the value of the stock it posted, or for one that posted no stock (a corrected
     * payment or opening receivable) the money it changed. The same rule as the document search (UC-78, review 18/11
     * R-04).
     */
    private Map<Long, Long> postedValues(Collection<Long> ids) {
        Map<Long, Long> values = new HashMap<>();
        amountLines.sumAmountByAdjustmentRequest(ids)
                .forEach(row -> values.put((Long) row[0], ((Number) row[1]).longValue()));
        movements.sumValueByAdjustmentRequest(ids)
                .forEach(row -> values.put((Long) row[0], ((Number) row[1]).longValue()));
        return values;
    }

    static AdjustmentSource source(Source source) {
        return source == null ? null : new AdjustmentSource(source.type().name(), source.id(), "#" + source.id());
    }

    private static Long transferOf(Source source) {
        return source != null && source.type() == Source.SourceType.TRANSFER_ORDER ? source.id() : null;
    }

    private Map<Long, Person> people(Collection<Long> ids) {
        Set<Long> wanted = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        return users.findAllById(wanted).stream().collect(Collectors.toMap(UserAccount::getId,
                u -> new Person(u.getId(), u.getFullName())));
    }

    private Map<Long, String> storeNames(Collection<Long> ids) {
        return stores.findAllById(new HashSet<>(ids)).stream()
                .collect(Collectors.toMap(Store::getId, Store::getStoreName));
    }

    private String storeName(long storeId) {
        return stores.findById(storeId).map(Store::getStoreName).orElse(null);
    }

    // ---------------------------------------------------------------------------------------------- access

    /** A Branch Manager works in their own store; Owner names the store (MSG254). */
    Store storeFor(AuthenticatedUser user, Long storeId) {
        if (user.role() == Role.OWNER) {
            if (storeId == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG254");
            }
        } else if (storeId != null && !storeId.equals(user.storeId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        long id = storeId != null ? storeId : user.storeId();
        return stores.findById(id).orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG52"));
    }

    /**
     * UC-83 alternative flow, UC-76 (review 17/11 R-05): the coordinator, Owner or the Branch Manager of the central
     * warehouse, reports goods lost in transit for the receiving store of the transfer. Anyone else works as in
     * {@link #storeFor}. MSG279 when the order does not exist or the named store is not its destination.
     */
    Store storeForTransitLoss(AuthenticatedUser user, Long storeId, long transferOrderId) {
        if (user.role() != Role.BRANCH_MANAGER || !storeScope.seesEveryStore(user)) {
            return storeFor(user, storeId);
        }
        long destination = transferOrders.findById(transferOrderId)
                .filter(o -> storeId == null || o.getDestStoreId().equals(storeId))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG279")).getDestStoreId();
        return stores.findById(destination).orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG52"));
    }

    /**
     * Owner sees every request; a Branch Manager those of their store, and those they made for another store as the
     * transfer coordinator (UC-82, R-05).
     */
    private static boolean visible(AuthenticatedUser user, AdjustmentRequest request) {
        return request.getStatus() != AdjustmentRequestStatus.DRAFT
                && (user.role() == Role.OWNER || request.getStoreId().equals(user.storeId())
                || Objects.equals(request.getRequestedBy(), user.id()));
    }

    private AdjustmentRequest lock(AuthenticatedUser user, long id) {
        return requests.findByIdForUpdate(id).filter(r -> visible(user, r)).orElseThrow(this::notFound);
    }

    private static void requireStatus(AdjustmentRequest request, AdjustmentRequestStatus expected) {
        if (request.getStatus() != expected) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG281", request.getId(), request.getStatus());
        }
    }

    private BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "MSG253");
    }
}
