package fu.hl.g64.motorflow.receiving.service.impl;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyResolutionEvent;
import fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType;
import fu.hl.g64.motorflow.discrepancy.entity.enums.SourceType;
import fu.hl.g64.motorflow.discrepancy.helper.DiscrepancySettlement;
import fu.hl.g64.motorflow.discrepancy.helper.DiscrepancySettlement.Progress;
import fu.hl.g64.motorflow.discrepancy.helper.DiscrepancySettlement.Standing;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyCaseRepository;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyResolutionEventRepository;
import fu.hl.g64.motorflow.inventory.dto.LotReceiptPosting;
import fu.hl.g64.motorflow.inventory.dto.NewLotPosting;
import fu.hl.g64.motorflow.inventory.entity.Lot;
import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.LotSourceType;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.inventory.repository.LotRepository;
import fu.hl.g64.motorflow.inventory.service.InventoryPostingService;
import fu.hl.g64.motorflow.operation.entity.WarehouseTask;
import fu.hl.g64.motorflow.operation.entity.enums.OwnerType;
import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import fu.hl.g64.motorflow.operation.repository.WarehouseTaskRepository;
import fu.hl.g64.motorflow.operation.service.AttachmentService;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.operation.service.WarehouseTaskService;
import fu.hl.g64.motorflow.partner.entity.Supplier;
import fu.hl.g64.motorflow.partner.repository.SupplierRepository;
import fu.hl.g64.motorflow.receiving.dto.AssignedReceiptResponse;
import fu.hl.g64.motorflow.receiving.dto.ExcessDecisionRequest;
import fu.hl.g64.motorflow.receiving.dto.ExcessDecisionRequest.Choice;
import fu.hl.g64.motorflow.receiving.dto.GoodsReceiptDetail;
import fu.hl.g64.motorflow.receiving.dto.GoodsReceiptRequest;
import fu.hl.g64.motorflow.receiving.dto.GoodsReceiptSearch;
import fu.hl.g64.motorflow.receiving.dto.GoodsReceiptSummary;
import fu.hl.g64.motorflow.receiving.dto.InspectionResultRequest;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNote;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNoteLine;
import fu.hl.g64.motorflow.receiving.entity.PurchaseOrder;
import fu.hl.g64.motorflow.receiving.entity.PurchaseOrderLine;
import fu.hl.g64.motorflow.receiving.entity.enums.ExcessDecision;
import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;
import fu.hl.g64.motorflow.receiving.entity.enums.PurchaseOrderStatus;
import fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType;
import fu.hl.g64.motorflow.receiving.helper.GoodsReceiptViews;
import fu.hl.g64.motorflow.receiving.helper.PurchaseOrderReceiving;
import fu.hl.g64.motorflow.receiving.helper.ReceivingLedger;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteLineRepository;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteRepository;
import fu.hl.g64.motorflow.receiving.repository.PurchaseOrderLineRepository;
import fu.hl.g64.motorflow.receiving.repository.PurchaseOrderRepository;
import fu.hl.g64.motorflow.receiving.service.GoodsReceiptService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
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
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GoodsReceiptServiceImpl implements GoodsReceiptService {

    /** notification.ref_type of a GRN, so that opening the notification opens it. */
    private static final String REF_TYPE = "GOODS_RECEIPT_NOTE";
    /** audit_log.entity_type and actions of the GRN steps that have no column of their own. */
    private static final String AUDIT_ENTITY = "GOODS_RECEIPT_NOTE";
    private static final String CREATED = "GOODS_RECEIPT_CREATED";
    private static final String RECOUNT = "GOODS_RECEIPT_RECOUNT";

    private final WarehouseTaskRepository tasks;
    private final GoodsReceiptNoteRepository receipts;
    private final GoodsReceiptNoteLineRepository receiptLines;
    private final PurchaseOrderRepository orders;
    private final PurchaseOrderLineRepository orderLines;
    private final SupplierRepository suppliers;
    private final DiscrepancyCaseRepository discrepancies;
    private final DiscrepancyResolutionEventRepository events;
    private final LotRepository lots;
    private final WarehouseTaskService warehouseTasks;
    private final InventoryPostingService posting;
    private final AttachmentService attachments;
    private final NotificationService notifications;
    private final AuditLogRepository auditLog;
    private final ReceivingLedger ledger;
    private final PurchaseOrderReceiving purchaseOrderReceiving;
    private final DiscrepancySettlement settlement;
    private final GoodsReceiptViews views;
    private final Clock clock;

    // UC-39 (Staff)
    @Transactional(readOnly = true)
    @Override
    public PageResponse<AssignedReceiptResponse> assignedToMe(AuthenticatedUser user, int page, int size) {
        Page<WarehouseTask> found = tasks.findOpenSupplierInspections(user.id(), PageRequest.of(Math.max(page, 0),
                Math.clamp(size, 1, 100), Sort.by("assignedAt", "id")));
        if (found.isEmpty()) {
            return PageResponse.of(found, List.of());
        }
        List<Long> receiptIds = found.stream().map(WarehouseTask::getGoodsReceiptNoteId).toList();
        Map<Long, GoodsReceiptNote> receiptById = byId(receipts.findAllById(receiptIds), GoodsReceiptNote::getId);
        Map<Long, PurchaseOrder> orderById = byId(orders.findAllById(receiptById.values().stream()
                .map(GoodsReceiptNote::getPurchaseOrderId).collect(Collectors.toSet())), PurchaseOrder::getId);
        Map<Long, String> supplierNames = suppliers.findAllById(orderById.values().stream()
                        .map(PurchaseOrder::getSupplierId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Supplier::getId, Supplier::getSupplierName));
        Map<Long, Long> lineCounts = receiptLines.countByReceipt(receiptIds).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));

        return PageResponse.of(found, found.stream().map(task -> {
            GoodsReceiptNote receipt = receiptById.get(task.getGoodsReceiptNoteId());
            PurchaseOrder order = orderById.get(receipt.getPurchaseOrderId());
            return new AssignedReceiptResponse(receipt.getId(), order.getId(), supplierNames.get(order.getSupplierId()),
                    receipt.getDeliveryDocumentNo(), receipt.getDeliveryDocumentDate(), receipt.getArrivedAt(),
                    receipt.getStatus(), lineCounts.getOrDefault(receipt.getId(), 0L), task.getStatus(),
                    task.getAssignedAt());
        }).toList());
    }

    @Transactional(readOnly = true)
    @Override
    public PageResponse<GoodsReceiptSummary> list(AuthenticatedUser user, GoodsReceiptSearch search, int page,
                                                  int size) {
        Long storeId = search.storeId();
        if (!isOwner(user)) {
            if (storeId != null && !storeId.equals(user.storeId())) {
                throw forbidden();
            }
            storeId = user.storeId();
        }
        Page<GoodsReceiptNote> found = receipts.findAll(matching(storeId, search), PageRequest.of(Math.max(page, 0),
                Math.clamp(size, 1, 100), Sort.by(Sort.Order.desc("arrivedAt"), Sort.Order.desc("id"))));
        return PageResponse.of(found, views.summaries(found.getContent()));
    }

    @Transactional(readOnly = true)
    @Override
    public GoodsReceiptDetail get(AuthenticatedUser user, long id) {
        GoodsReceiptNote receipt = receipts.findById(id).filter(GoodsReceiptServiceImpl::fromSupplier)
                .orElseThrow(GoodsReceiptServiceImpl::notFound);
        PurchaseOrder order = orders.findById(receipt.getPurchaseOrderId()).orElseThrow();
        boolean allowed = switch (user.role()) {
            case OWNER -> true;
            case BRANCH_MANAGER -> Objects.equals(user.storeId(), order.getStoreId());
            // Staff sees the deliveries they inspect or inspected (BR-32), not the others of the store.
            case STAFF -> tasks.findHistory(TaskType.INSPECTION, id).stream().anyMatch(task ->
                    task.getAssignedTo() == user.id() && task.getStatus() != WarehouseTaskStatus.CANCELLED);
            default -> false;
        };
        if (!allowed) {
            throw forbidden();
        }
        return views.detail(user, receipt, order);
    }

    // UC-106
    @Transactional
    @Override
    public GoodsReceiptDetail create(AuthenticatedUser user, long purchaseOrderId, GoodsReceiptRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime arrivedAt = request.arrivedAt() != null ? request.arrivedAt() : now;
        if (request.deliveryDocumentDate().isAfter(now.toLocalDate()) || arrivedAt.isAfter(now)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG138");
        }
        // BR-21: the PO lock keeps a change or cancellation of the PO from passing this new GRN.
        PurchaseOrder order = orders.findByIdForUpdate(purchaseOrderId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG131"));
        // Lecturer feedback 12/11: whoever holds the PO's receiving task records the GRN, a Branch Manager too.
        warehouseTasks.requireAssignee(user, TaskType.SUPPLIER_RECEIVING, order.getId());
        String documentNo = request.deliveryDocumentNo().strip();
        Set<Long> caseIds = new LinkedHashSet<>(request.replacementForCaseIds() == null ? List.of()
                : request.replacementForCaseIds());
        Map<Long, Long> priceByProduct = orderLines.findByPurchaseOrderIdOrderByIdAsc(order.getId()).stream()
                .collect(Collectors.toMap(PurchaseOrderLine::getProductId, PurchaseOrderLine::getPurchasePrice,
                        (a, b) -> a, LinkedHashMap::new));

        // Expected parts in line order, and for a replacement delivery what each case may still receive.
        List<Long> expectedParts;
        Map<Long, Integer> replacementByCase = new LinkedHashMap<>();
        if (caseIds.isEmpty()) {
            if (order.getStatus() != PurchaseOrderStatus.AWAITING_DELIVERY
                    && order.getStatus() != PurchaseOrderStatus.RECEIVING) {
                throw changed();
            }
            ReceivingLedger.Ledger standing = ledger.of(order.getId());
            expectedParts = priceByProduct.keySet().stream().filter(productId -> standing.outstanding(productId) > 0)
                    .toList();
            if (expectedParts.isEmpty()) {
                throw changed();
            }
        } else {
            List<DiscrepancyCase> cases = discrepancies.findAllByIdForUpdate(caseIds);
            Set<Long> ofThisOrder = receipts.findByPurchaseOrderIdOrderByArrivedAtAscIdAsc(order.getId()).stream()
                    .map(GoodsReceiptNote::getId).collect(Collectors.toSet());
            if (cases.size() != caseIds.size() || cases.stream().anyMatch(found -> found.getSourceType()
                    != SourceType.SUPPLIER || !ofThisOrder.contains(found.getGoodsReceiptNoteId()))) {
                throw new BusinessException(HttpStatus.NOT_FOUND, "MSG133");
            }
            Map<Long, Progress> progress = settlement.progress(caseIds);
            Map<Long, DiscrepancyCase> caseById = byId(cases, DiscrepancyCase::getId);
            Set<Long> parts = new LinkedHashSet<>();
            for (Long caseId : caseIds) {
                DiscrepancyCase found = caseById.get(caseId);
                int open = new Standing(found, progress.get(caseId)).openReplacement();
                if (!found.isOpen() || open == 0) {
                    throw new BusinessException(HttpStatus.CONFLICT, "MSG141", "#" + caseId);
                }
                replacementByCase.put(caseId, open);
                parts.add(found.getProductId());
            }
            expectedParts = List.copyOf(parts);
        }
        if (receipts.existsByPurchaseOrderIdAndDeliveryDocumentNoIgnoreCase(order.getId(), documentNo)) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG28");
        }

        GoodsReceiptNote receipt = receipts.save(GoodsReceiptNote.supplierDelivery(order.getId(), documentNo,
                request.deliveryDocumentDate(), text(request.delivererName()), text(request.vehiclePlateNo()),
                arrivedAt));
        receiptLines.saveAll(expectedParts.stream().map(productId -> GoodsReceiptNoteLine.expected(receipt.getId(),
                productId, priceByProduct.get(productId))).toList());
        events.saveAll(replacementByCase.entrySet().stream().map(e -> DiscrepancyResolutionEvent.pending(e.getKey(),
                ResolutionType.REPLACEMENT_RECEIVED, e.getValue(), receipt.getId(), null, user.id(), now)).toList());
        order.startReceiving(now);
        attachments.attach(OwnerType.GOODS_RECEIPT_NOTE, receipt.getId(), orEmpty(request.documents()), user.id());
        auditLog.save(AuditLog.record(AUDIT_ENTITY, receipt.getId(), CREATED, user.id(), null, now));
        warehouseTasks.handOverToInspection(user, TaskType.SUPPLIER_RECEIVING, order.getId(), receipt.getId());
        return views.detail(user, receipt, order);
    }

    // UC-43
    @Transactional
    @Override
    public GoodsReceiptDetail recordResult(AuthenticatedUser user, long id, InspectionResultRequest request) {
        GoodsReceiptNote receipt = lockReceipt(id);
        PurchaseOrder order = orders.findById(receipt.getPurchaseOrderId()).orElseThrow();
        if (!receipt.awaitsResult()) {
            throw changed();
        }
        // BR-32: only the person holding the inspection, and only until they sent its result.
        warehouseTasks.requireAssignee(user, TaskType.INSPECTION, id);
        LocalDateTime now = LocalDateTime.now(clock);
        if (request.arrivedAt() != null && request.arrivedAt().isAfter(now)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG138");
        }

        List<GoodsReceiptNoteLine> lines = receiptLines.findByGoodsReceiptNoteIdOrderByIdAsc(id);
        Set<Long> lineIds = lines.stream().map(GoodsReceiptNoteLine::getId).collect(Collectors.toSet());
        Map<Long, InspectionResultRequest.Line> given = new HashMap<>();
        boolean anything = false;
        for (int i = 0; i < request.lines().size(); i++) {
            InspectionResultRequest.Line in = request.lines().get(i);
            int n = i + 1;
            if (!lineIds.contains(in.lineId()) || given.putIfAbsent(in.lineId(), in) != null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG136", n);
            }
            // BR-24: delivered = passed + wrong item + damaged, and passed cannot be negative.
            if (orZero(in.wrongItemQty()) + orZero(in.damagedQty()) > in.deliveredQty()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG27", n);
            }
            if (in.manufactureDate() != null && in.expiryDate() != null
                    && in.expiryDate().isBefore(in.manufactureDate())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG137", n);
            }
            anything |= in.documentQty() > 0 || in.deliveredQty() > 0;
        }
        if (!anything) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG140");
        }

        for (GoodsReceiptNoteLine line : lines) {
            InspectionResultRequest.Line in = given.get(line.getId());
            if (in == null) {
                // A part the delivery did not bring and its document did not list.
                line.record(0, 0, 0, 0, null, null, null);
            } else {
                line.record(in.documentQty(), in.deliveredQty(), orZero(in.wrongItemQty()), orZero(in.damagedQty()),
                        text(in.manufacturerLotNo()), in.manufactureDate(), in.expiryDate());
            }
        }
        receipt.submitResult(user.id(), request.arrivedAt(), now);
        warehouseTasks.submitResult(user, TaskType.INSPECTION, id);
        attachments.attach(OwnerType.GOODS_RECEIPT_NOTE, id, orEmpty(request.evidence()), user.id());
        notifications.notifyStoreManagers(order.getStoreId(), "GOODS_RECEIPT_SUBMITTED",
                "Phiếu nhận hàng %s của yêu cầu nhập hàng #%d đã có kết quả kiểm nhận và đang chờ xác nhận."
                        .formatted(label(receipt), order.getId()), REF_TYPE, id);
        return views.detail(user, receipt, order);
    }

    // UC-44
    @Transactional
    @Override
    public GoodsReceiptDetail confirm(AuthenticatedUser manager, long id) {
        // Lock order: PO, GRN, discrepancy cases, then stock. The PO lock keeps two deliveries of the same PO from
        // both filling the same outstanding quantity.
        long orderId = receipts.findPurchaseOrderIdOf(id).orElseThrow(GoodsReceiptServiceImpl::notFound);
        PurchaseOrder order = orders.findByIdForUpdate(orderId).orElseThrow();
        requireManager(manager, order);
        GoodsReceiptNote receipt = lockReceipt(id);
        if (receipt.getStatus() != GoodsReceiptNoteStatus.AWAITING_CONFIRMATION) {
            throw changed();
        }
        // BR-26: the confirmer did not record the result (the task check covers a Branch Manager who took it).
        if (Objects.equals(receipt.getRecordedBy(), manager.id())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG32");
        }
        warehouseTasks.confirmResult(manager, TaskType.INSPECTION, id);
        LocalDateTime now = LocalDateTime.now(clock);

        List<GoodsReceiptNoteLine> lines = receiptLines.findByGoodsReceiptNoteIdOrderByIdAsc(id).stream()
                .sorted(Comparator.comparing(GoodsReceiptNoteLine::getProductId)).toList();
        List<DiscrepancyResolutionEvent> replacements =
                events.findByGoodsReceiptNoteIdAndResolutionTypeOrderByIdAsc(id, ResolutionType.REPLACEMENT_RECEIVED);
        boolean replacement = !replacements.isEmpty();
        List<DiscrepancyCase> replaced = replacement ? discrepancies.findAllByIdForUpdate(replacements.stream()
                .map(DiscrepancyResolutionEvent::getDiscrepancyCaseId).toList()) : List.of();
        // Read after the case locks, so that no waiver or other replacement can change what is still owed.
        Map<Long, Integer> expected = views.expected(order, replacements);

        boolean anyExcess = false;
        List<DiscrepancyCase> opened = new ArrayList<>();
        Map<Long, Integer> toStockByProduct = new HashMap<>();
        for (GoodsReceiptNoteLine line : lines) {
            // BR-67: passed goods above what is still expected are excess and wait for Owner.
            int stillExpected = expected.getOrDefault(line.getProductId(), 0);
            int toStock = Math.min(line.getPassedQty(), stillExpected);
            line.settleExcess(line.getPassedQty() - toStock);
            anyExcess |= line.getExcessQty() > 0;
            toStockByProduct.merge(line.getProductId(), toStock, Integer::sum);

            Map<StockStatus, Integer> quantities = new EnumMap<>(StockStatus.class);
            putPositive(quantities, StockStatus.AVAILABLE, toStock);
            putPositive(quantities, StockStatus.WRONG_ITEM, line.getWrongItemQty());
            putPositive(quantities, StockStatus.DAMAGED, line.getDamagedQty());
            if (!quantities.isEmpty()) {
                // BR-73: one lot per line, at the purchase price Owner agreed on the PO.
                posting.receiveNewLot(new NewLotPosting(order.getStoreId(), line.getProductId(),
                        LotSourceType.RECEIPT, line.getId(), order.getSupplierId(), receipt.getArrivedAt().toLocalDate(),
                        line.getUnitPrice(), line.getManufacturerLotNo(), line.getManufactureDate(),
                        line.getExpiryDate(), quantities, MovementType.RECEIPT_CONFIRMED,
                        SourceRef.goodsReceiptNote(id), manager.id(), null));
            }
            // BR-27, BR-67: the supplier owes no more than the PO still lacks once this receipt is stocked. Units the
            // document lists beyond the PO are only recorded on the line. What a replacement delivery fails to
            // bring stays owed on the original case, so its own case only tracks the goods to send back.
            int lacking = replacement ? 0 : Math.max(stillExpected - toStock, 0);
            int shortQty = Math.min(line.shortQty(), lacking);
            int limit = Math.min(shortQty + line.getWrongItemQty() + line.getDamagedQty(), lacking);
            if (shortQty + line.getWrongItemQty() + line.getDamagedQty() > 0) {
                opened.add(DiscrepancyCase.supplier(id, line.getProductId(), shortQty, line.getWrongItemQty(),
                        line.getDamagedQty(), limit, now));
            }
        }
        discrepancies.saveAll(opened);

        if (replacement) {
            // UC-44 Step 1_Supplier replacement GRN: the accepted replacements settle the cases in case order.
            Map<Long, DiscrepancyCase> caseById = byId(replaced, DiscrepancyCase::getId);
            Map<Long, Progress> progress = settlement.progress(caseById.keySet());
            for (DiscrepancyResolutionEvent pending : replacements.stream()
                    .sorted(Comparator.comparing(DiscrepancyResolutionEvent::getDiscrepancyCaseId)).toList()) {
                DiscrepancyCase found = caseById.get(pending.getDiscrepancyCaseId());
                int owed = new Standing(found, progress.get(found.getId())).expectedFrom(pending);
                int left = toStockByProduct.getOrDefault(found.getProductId(), 0);
                int settled = Math.min(owed, left);
                toStockByProduct.put(found.getProductId(), left - settled);
                pending.confirm(settled, manager.id(), now);
            }
            replaced.forEach(found -> settlement.closeIfSettled(found, manager.id(), now));
        }

        receipt.confirm(manager.id(), now);
        // BR-22: the PO is fully received once no line is outstanding; a replacement covers none of it.
        if (!replacement && order.getStatus() == PurchaseOrderStatus.RECEIVING
                && !ledger.of(order.getId()).anyOutstanding()) {
            order.completeReceiving(now);
        }
        if (!purchaseOrderReceiving.of(order).any()) {
            warehouseTasks.cancelForDocument(TaskType.SUPPLIER_RECEIVING, order.getId());
        }
        if (anyExcess) {
            notifications.notifyOwners("EXCESS_AWAITING_DECISION",
                    "Phiếu nhận hàng %s của yêu cầu nhập hàng #%d có hàng giao thừa đang chờ quyết định nhận hoặc không nhận."
                            .formatted(label(receipt), order.getId()), REF_TYPE, id);
        }
        if (!opened.isEmpty()) {
            notifications.notifyOwners("SUPPLIER_DISCREPANCY_OPENED",
                    "Phiếu nhận hàng %s của yêu cầu nhập hàng #%d có %d phụ tùng thiếu, giao sai hoặc hư hỏng cần xử lý với nhà cung cấp."
                            .formatted(label(receipt), order.getId(), opened.size()), REF_TYPE, id);
        }
        return views.detail(manager, receipt, order);
    }

    // UC-44 Step 3_Return for recount
    @Transactional
    @Override
    public GoodsReceiptDetail returnForRecount(AuthenticatedUser manager, long id, String reason) {
        GoodsReceiptNote receipt = lockReceipt(id);
        PurchaseOrder order = orders.findById(receipt.getPurchaseOrderId()).orElseThrow();
        requireManager(manager, order);
        if (receipt.getStatus() != GoodsReceiptNoteStatus.AWAITING_CONFIRMATION) {
            throw changed();
        }
        String why = reason.strip();
        LocalDateTime now = LocalDateTime.now(clock);
        receipt.returnForRecount(why);
        long recorder = warehouseTasks.returnForRecount(TaskType.INSPECTION, id);
        // recount_reason keeps only the latest reason; the log keeps every one of them.
        auditLog.save(AuditLog.record(AUDIT_ENTITY, id, RECOUNT, manager.id(), why, now));
        if (recorder != manager.id()) {
            notifications.notify(recorder, "GOODS_RECEIPT_RECOUNT", "Phiếu nhận hàng %s được trả về để kiểm lại. Lý do: %s"
                    .formatted(label(receipt), why), REF_TYPE, id);
        }
        return views.detail(manager, receipt, order);
    }

    // UC-45
    @Transactional
    @Override
    public GoodsReceiptDetail decideExcess(AuthenticatedUser owner, long id, ExcessDecisionRequest request) {
        // Lock order: GRN, discrepancy cases, then stock.
        GoodsReceiptNote receipt = lockReceipt(id);
        PurchaseOrder order = orders.findById(receipt.getPurchaseOrderId()).orElseThrow();
        if (receipt.getStatus() != GoodsReceiptNoteStatus.CONFIRMED) {
            throw changed();
        }
        List<GoodsReceiptNoteLine> lines = receiptLines.findByGoodsReceiptNoteIdOrderByIdAsc(id);
        Map<Long, GoodsReceiptNoteLine> lineById = byId(lines, GoodsReceiptNoteLine::getId);
        Map<Long, Choice> decisions = new HashMap<>();
        for (int i = 0; i < request.lines().size(); i++) {
            ExcessDecisionRequest.Line in = request.lines().get(i);
            GoodsReceiptNoteLine line = lineById.get(in.lineId());
            if (line == null || decisions.putIfAbsent(line.getId(), in.decision()) != null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG136", i + 1);
            }
            // UC-45 Step 4_Incomplete or changed decision: excess already decided is never posted again.
            if (line.getExcessDecision() != ExcessDecision.AWAITING_OWNER) {
                throw changed();
            }
        }
        List<GoodsReceiptNoteLine> waiting = lines.stream()
                .filter(line -> line.getExcessDecision() == ExcessDecision.AWAITING_OWNER)
                .sorted(Comparator.comparing(GoodsReceiptNoteLine::getProductId)).toList();
        if (waiting.stream().anyMatch(line -> !decisions.containsKey(line.getId()))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG139");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        Map<Long, DiscrepancyCase> caseByProduct = discrepancies.findByReceiptForUpdate(id).stream()
                .collect(Collectors.toMap(DiscrepancyCase::getProductId, Function.identity()));
        Map<Long, Lot> lotByLine = byLine(lots.findByGoodsReceiptNoteLineIdIn(waiting.stream()
                .map(GoodsReceiptNoteLine::getId).toList()));
        for (GoodsReceiptNoteLine line : waiting) {
            boolean accepted = decisions.get(line.getId()) == Choice.ACCEPTED;
            // BR-73: accepted excess joins the lot of its line; BR-67: rejected excess waits to go back.
            StockStatus status = accepted ? StockStatus.AVAILABLE : StockStatus.AWAITING_SUPPLIER_RETURN;
            MovementType type = accepted ? MovementType.EXCESS_ACCEPTED : MovementType.EXCESS_REJECTED;
            Map<StockStatus, Integer> quantities = Map.of(status, line.getExcessQty());
            Lot lot = lotByLine.get(line.getId());
            if (lot != null) {
                posting.receiveIntoLot(new LotReceiptPosting(order.getStoreId(), lot.getId(), quantities, type,
                        SourceRef.goodsReceiptNote(id), owner.id(), null));
            } else {
                // Every passed unit of the line was excess, so the confirmation created no lot for it.
                posting.receiveNewLot(new NewLotPosting(order.getStoreId(), line.getProductId(), LotSourceType.RECEIPT,
                        line.getId(), order.getSupplierId(), receipt.getArrivedAt().toLocalDate(), line.getUnitPrice(),
                        line.getManufacturerLotNo(), line.getManufactureDate(), line.getExpiryDate(), quantities, type,
                        SourceRef.goodsReceiptNote(id), owner.id(), null));
            }
            line.decideExcess(accepted ? ExcessDecision.ACCEPTED : ExcessDecision.REJECTED);
            if (!accepted) {
                DiscrepancyCase found = caseByProduct.computeIfAbsent(line.getProductId(), productId ->
                        discrepancies.save(DiscrepancyCase.supplier(id, productId, 0, 0, 0, 0, now)));
                found.addRejectedExcess(line.getExcessQty());
            }
        }
        receipt.recordExcessDecision(owner.id(), now);
        // The excess may have been all that kept a case open.
        caseByProduct.values().forEach(found -> settlement.closeIfSettled(found, owner.id(), now));
        notifications.notifyStoreManagers(order.getStoreId(), "EXCESS_DECIDED",
                "Owner đã quyết định phần hàng thừa của phiếu nhận hàng %s.".formatted(label(receipt)), REF_TYPE, id);
        return views.detail(owner, receipt, order);
    }

    /** The supplier delivery, locked until the end of the transaction (MSG132 if there is none). */
    private GoodsReceiptNote lockReceipt(long id) {
        return receipts.findByIdForUpdate(id).filter(GoodsReceiptServiceImpl::fromSupplier)
                .orElseThrow(GoodsReceiptServiceImpl::notFound);
    }

    /** The Branch Manager of the store the PO delivers to (MSG05 otherwise). */
    private static void requireManager(AuthenticatedUser user, PurchaseOrder order) {
        if (user.role() != Role.BRANCH_MANAGER || !Objects.equals(user.storeId(), order.getStoreId())) {
            throw forbidden();
        }
    }

    private static Specification<GoodsReceiptNote> matching(Long storeId, GoodsReceiptSearch search) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("receiptType"), ReceiptType.SUPPLIER_DELIVERY));
            if (storeId != null) {
                Subquery<Long> ofStore = query.subquery(Long.class);
                Root<PurchaseOrder> order = ofStore.from(PurchaseOrder.class);
                ofStore.select(order.get("id")).where(cb.equal(order.get("storeId"), storeId));
                where.add(root.get("purchaseOrderId").in(ofStore));
            }
            if (search.purchaseOrderId() != null) {
                where.add(cb.equal(root.get("purchaseOrderId"), search.purchaseOrderId()));
            }
            if (search.status() != null) {
                where.add(cb.equal(root.get("status"), search.status()));
            }
            if (Boolean.TRUE.equals(search.excessAwaiting())) {
                Subquery<Long> waiting = query.subquery(Long.class);
                Root<GoodsReceiptNoteLine> line = waiting.from(GoodsReceiptNoteLine.class);
                waiting.select(line.get("goodsReceiptNoteId"))
                        .where(cb.equal(line.get("excessDecision"), ExcessDecision.AWAITING_OWNER));
                where.add(root.get("id").in(waiting));
            }
            if (Boolean.TRUE.equals(search.unassigned())) {
                // Same rule as the Home count: waiting for inspection and nobody holds or held it.
                Subquery<Long> held = query.subquery(Long.class);
                Root<WarehouseTask> task = held.from(WarehouseTask.class);
                held.select(task.get("goodsReceiptNoteId"))
                        .where(cb.notEqual(task.get("status"), WarehouseTaskStatus.CANCELLED),
                                cb.isNotNull(task.get("goodsReceiptNoteId")));
                where.add(cb.equal(root.get("status"), GoodsReceiptNoteStatus.AWAITING_INSPECTION));
                where.add(cb.not(root.get("id").in(held)));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    /** How a GRN is named in notifications: its number. The table has no separate code. */
    private static String label(GoodsReceiptNote receipt) {
        return "#" + receipt.getId();
    }

    private static boolean fromSupplier(GoodsReceiptNote receipt) {
        return receipt.getReceiptType() == ReceiptType.SUPPLIER_DELIVERY;
    }

    private static void putPositive(Map<StockStatus, Integer> quantities, StockStatus status, int quantity) {
        if (quantity > 0) {
            quantities.put(status, quantity);
        }
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String text(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private static boolean isOwner(AuthenticatedUser user) {
        return user.role() == Role.OWNER;
    }

    private static BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "MSG132");
    }

    private static BusinessException changed() {
        return new BusinessException(HttpStatus.CONFLICT, "MSG29");
    }

    private static BusinessException forbidden() {
        return new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
    }

    private static Map<Long, Lot> byLine(List<Lot> found) {
        return found.stream().collect(Collectors.toMap(Lot::getGoodsReceiptNoteLineId, Function.identity()));
    }

    private static <T> Map<Long, T> byId(java.util.Collection<T> rows, Function<T, Long> id) {
        return rows.stream().collect(Collectors.toMap(id, Function.identity()));
    }
}
