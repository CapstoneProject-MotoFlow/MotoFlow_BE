package fu.hl.g64.motorflow.transfer.service.impl;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.AccountStatus;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyResolutionEvent;
import fu.hl.g64.motorflow.discrepancy.entity.enums.ClosureType;
import fu.hl.g64.motorflow.discrepancy.entity.enums.ResolutionType;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyCaseRepository;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyResolutionEventRepository;
import fu.hl.g64.motorflow.inventory.dto.LotQuantity;
import fu.hl.g64.motorflow.inventory.dto.SplitMovePosting;
import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.inventory.service.InventoryPostingService;
import fu.hl.g64.motorflow.operation.entity.enums.OwnerType;
import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import fu.hl.g64.motorflow.operation.repository.WarehouseTaskRepository;
import fu.hl.g64.motorflow.operation.service.AttachmentService;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.operation.service.WarehouseTaskService;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNote;
import fu.hl.g64.motorflow.receiving.entity.GoodsReceiptNoteLine;
import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptNoteStatus;
import fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteLineRepository;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptNoteRepository;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.helper.StoreScope;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import fu.hl.g64.motorflow.transfer.dto.RecountRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferReceiptRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferReceiptResponse;
import fu.hl.g64.motorflow.transfer.dto.TransferReceiptResultRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferReturnRequest;
import fu.hl.g64.motorflow.transfer.entity.TransferOrder;
import fu.hl.g64.motorflow.transfer.entity.TransferOrderLine;
import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;
import fu.hl.g64.motorflow.transfer.helper.TransferReconciliation;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderLineRepository;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderRepository;
import fu.hl.g64.motorflow.transfer.service.TransferReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TransferReceiptServiceImpl implements TransferReceiptService {

    private static final String ENTITY = "TRANSFER_ORDER";
    private static final String RECEIPT_ENTITY = "GOODS_RECEIPT_NOTE";
    private static final String RECEIPT_CREATED = "GOODS_RECEIPT_CREATED";

    private final TransferOrderRepository orders;
    private final TransferOrderLineRepository orderLines;
    private final GoodsReceiptNoteRepository receipts;
    private final GoodsReceiptNoteLineRepository receiptLines;
    private final DiscrepancyCaseRepository cases;
    private final DiscrepancyResolutionEventRepository events;
    private final ProductRepository products;
    private final StoreRepository stores;
    private final StoreScope storeScope;
    private final UserAccountRepository users;
    private final TransferReconciliation reconciliation;
    private final InventoryPostingService posting;
    private final WarehouseTaskRepository tasks;
    private final WarehouseTaskService warehouseTasks;
    private final AuditLogRepository auditLog;
    private final NotificationService notifications;
    private final AttachmentService attachments;
    private final Clock clock;

    // UC-106
    @Transactional
    @Override
    public TransferReceiptResponse arrive(AuthenticatedUser user, long transferOrderId,
                                          TransferReceiptRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime arrivedAt = request.arrivedAt() != null ? request.arrivedAt() : now;
        if (request.deliveryDocumentDate().isAfter(now.toLocalDate()) || arrivedAt.isAfter(now)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG138");
        }
        TransferOrder order = lockOrder(transferOrderId);
        // Lecturer feedback 12/11: whoever holds the transfer's receiving task records it, a Branch Manager too.
        warehouseTasks.requireAssignee(user, TaskType.TRANSFER_RECEIVING, transferOrderId);
        TransferReconciliation.Arrival arrival = reconciliation.arrival(order);
        if (arrival.blockedCode() != null) {
            throw new BusinessException(HttpStatus.CONFLICT, arrival.blockedCode());
        }
        String documentNo = request.deliveryDocumentNo().strip();
        if (receipts.existsByTransferOrderIdAndDeliveryDocumentNoIgnoreCase(transferOrderId, documentNo)) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG28");
        }
        GoodsReceiptNote receipt = receipts.save(GoodsReceiptNote.transferIn(transferOrderId, documentNo,
                request.deliveryDocumentDate(), text(request.delivererName()), text(request.vehiclePlateNo()),
                arrivedAt));
        saveExpected(receipt, arrival.uncovered());
        attachments.attach(OwnerType.GOODS_RECEIPT_NOTE, receipt.getId(), request.documents(), user.id());
        auditLog.save(AuditLog.record(ENTITY, transferOrderId, "TRANSFER_RECEIPT_ARRIVED", user.id(), null, null,
                Map.of("goods_receipt_note_id", receipt.getId()), now));
        auditLog.save(AuditLog.record(RECEIPT_ENTITY, receipt.getId(), RECEIPT_CREATED, user.id(), null, now));
        warehouseTasks.handOverToInspection(user, TaskType.TRANSFER_RECEIVING, transferOrderId, receipt.getId());
        return view(receipt);
    }

    // UC-76 Step 3_Return to source
    @Transactional
    @Override
    public TransferReceiptResponse arriveReturn(AuthenticatedUser user, long transferOrderId,
                                                TransferReturnRequest body) {
        TransferOrder order = lockOrder(transferOrderId);
        requireManager(user, order.getSourceStoreId());
        if (order.getStatus() != TransferOrderStatus.RECONCILING) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG29");
        }
        List<TransferOrderLine> parts = orderLines.findByTransferOrderIdOrderByProductId(transferOrderId);
        Map<Long, Integer> uncovered = reconciliation.uncovered(transferOrderId, parts);
        Map<Long, Product> catalog = products.findAllById(uncovered.keySet()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        // The source returns only what a coordinator decided (R-06), less the returns it already opened. Every
        // decision was capped by uncovered and is subtracted from it, so allowed never exceeds what is unresolved.
        Map<Long, Long> decided = new HashMap<>();
        orderLines.pendingReturnDecisions(transferOrderId)
                .forEach(row -> decided.merge(row.getProductId(), row.getQty(), Long::sum));
        Map<Long, Long> opened = new HashMap<>();
        orderLines.openReturnQty(transferOrderId).forEach(row -> opened.merge(row.getProductId(), row.getQty(), Long::sum));
        Map<Long, Integer> coming = new LinkedHashMap<>();
        for (int i = 0; i < body.lines().size(); i++) {
            TransferReturnRequest.Line line = body.lines().get(i);
            if (!uncovered.containsKey(line.productId()) || coming.putIfAbsent(line.productId(), line.quantity()) != null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG166", i + 1);
            }
            String sku = catalog.get(line.productId()).getSku();
            long decidedQty = decided.getOrDefault(line.productId(), 0L);
            if (decidedQty == 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG226", sku);
            }
            long allowed = decidedQty - opened.getOrDefault(line.productId(), 0L);
            if (line.quantity() > allowed) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG217", sku, Math.max(allowed, 0));
            }
        }
        return open(user, ReceiptType.RETURN_TO_SOURCE, transferOrderId, coming);
    }

    /** A receipt of the order waiting for inspection, each part expecting the given quantity. */
    private TransferReceiptResponse open(AuthenticatedUser user, ReceiptType type, long transferOrderId,
                                         Map<Long, Integer> expected) {
        LocalDateTime now = LocalDateTime.now(clock);
        GoodsReceiptNote receipt = receipts.save(GoodsReceiptNote.transfer(type, transferOrderId, now));
        saveExpected(receipt, expected);
        auditLog.save(AuditLog.record(ENTITY, transferOrderId, type == ReceiptType.TRANSFER_IN
                ? "TRANSFER_RECEIPT_ARRIVED" : "TRANSFER_RETURN_ARRIVED", user.id(), null, null,
                Map.of("goods_receipt_note_id", receipt.getId()), now));
        return view(receipt);
    }

    @Transactional(readOnly = true)
    @Override
    public TransferReceiptResponse receipt(AuthenticatedUser user, long transferOrderId, long receiptId) {
        TransferOrder order = orders.findById(transferOrderId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG175"));
        GoodsReceiptNote receipt = receipts.findById(receiptId)
                .filter(found -> Objects.equals(found.getTransferOrderId(), transferOrderId))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG132"));
        boolean allowed = user.role() == Role.STAFF
                ? tasks.findHistory(TaskType.INSPECTION, receiptId).stream().anyMatch(task ->
                task.getAssignedTo() == user.id() && task.getStatus() != WarehouseTaskStatus.CANCELLED)
                : storeScope.seesEveryStore(user) || isManagerOf(user, receivingStore(order, receipt));
        if (!allowed) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        return view(receipt);
    }

    // UC-43
    @Transactional
    @Override
    public TransferReceiptResponse recordResult(AuthenticatedUser user, long transferOrderId, long receiptId,
                                                TransferReceiptResultRequest body) {
        requireNotClosed(lockOrder(transferOrderId));
        GoodsReceiptNote receipt = lockReceipt(transferOrderId, receiptId);
        warehouseTasks.requireAssignee(user, TaskType.INSPECTION, receiptId);
        if (!receipt.awaitsResult()) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG29");
        }
        List<GoodsReceiptNoteLine> lines = receiptLines.findByGoodsReceiptNoteIdOrderByIdAsc(receiptId);
        Map<Long, GoodsReceiptNoteLine> byProduct = lines.stream()
                .collect(Collectors.toMap(GoodsReceiptNoteLine::getProductId, Function.identity()));
        Map<Long, Product> parts = parts(lines);
        Map<Long, TransferReceiptResultRequest.Line> sent = new LinkedHashMap<>();
        for (int i = 0; i < body.lines().size(); i++) {
            TransferReceiptResultRequest.Line line = body.lines().get(i);
            if (!byProduct.containsKey(line.productId()) || sent.putIfAbsent(line.productId(), line) != null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG166", i + 1);
            }
        }
        String missing = lines.stream().filter(line -> !sent.containsKey(line.getProductId()))
                .map(line -> parts.get(line.getProductId()).getSku()).collect(Collectors.joining(", "));
        if (!missing.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG167", missing);
        }
        // An open return, a return decision or a pending loss already claims what is still missing: a late receipt
        // may not count more.
        Map<Long, Long> claimed = new HashMap<>();
        if (receipt.getReceiptType() == ReceiptType.TRANSFER_IN) {
            orderLines.openReturnQty(transferOrderId)
                    .forEach(row -> claimed.merge(row.getProductId(), row.getQty(), Long::sum));
            reconciliation.pendingClaims(transferOrderId)
                    .forEach((productId, qty) -> claimed.merge(productId, qty, Long::sum));
        }
        for (GoodsReceiptNoteLine line : lines) {
            TransferReceiptResultRequest.Line result = sent.get(line.getProductId());
            // Damaged goods fill what is expected first, so excess is always goods that passed (excess <= passed).
            if (result.damagedQty() > line.getDocumentQty()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG212", parts.get(line.getProductId()).getSku(),
                        line.getDocumentQty());
            }
            int delivered = result.passedQty() + result.damagedQty();
            // Goods coming back can only be goods that left (BR-61): nothing above what is expected.
            if (receipt.getReceiptType() == ReceiptType.RETURN_TO_SOURCE && delivered > line.getDocumentQty()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG220", parts.get(line.getProductId()).getSku(),
                        line.getDocumentQty());
            }
            if (claimed.getOrDefault(line.getProductId(), 0L) > 0 && delivered > line.getDocumentQty()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG224", parts.get(line.getProductId()).getSku(),
                        line.getDocumentQty());
            }
            line.record(line.getDocumentQty(), delivered, 0, result.damagedQty(), null, null, null);
            line.settleTransferExcess(Math.max(delivered - line.getDocumentQty(), 0));
        }
        LocalDateTime now = LocalDateTime.now(clock);
        receipt.submitResult(user.id(), null, now);
        warehouseTasks.submitResult(user, TaskType.INSPECTION, receiptId);
        return view(receipt);
    }

    // UC-44 Step 3_Return for recount
    @Transactional
    @Override
    public TransferReceiptResponse returnForRecount(AuthenticatedUser user, long transferOrderId, long receiptId,
                                                    RecountRequest body) {
        TransferOrder order = requireNotClosed(lockOrder(transferOrderId));
        GoodsReceiptNote receipt = lockReceipt(transferOrderId, receiptId);
        requireManager(user, receivingStore(order, receipt));
        if (receipt.getStatus() != GoodsReceiptNoteStatus.AWAITING_CONFIRMATION) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG29");
        }
        receipt.returnForRecount(body.reason().strip());
        long recorder = warehouseTasks.returnForRecount(TaskType.INSPECTION, receiptId);
        notifications.notify(recorder, "TRANSFER_RECEIPT_RECOUNT", "Phiếu nhận hàng #%d của phiếu chuyển kho #%d cần "
                .formatted(receiptId, transferOrderId) + "kiểm lại: " + body.reason().strip(), "GOODS_RECEIPT_NOTE",
                receiptId);
        return view(receipt);
    }

    // UC-44
    @Transactional
    @Override
    public TransferReceiptResponse confirm(AuthenticatedUser user, long transferOrderId, long receiptId) {
        TransferOrder order = requireNotClosed(lockOrder(transferOrderId));
        GoodsReceiptNote receipt = lockReceipt(transferOrderId, receiptId);
        long receivingStore = receivingStore(order, receipt);
        requireManager(user, receivingStore);
        if (receipt.getStatus() == GoodsReceiptNoteStatus.CONFIRMED) {
            return view(receipt);
        }
        if (receipt.getStatus() != GoodsReceiptNoteStatus.AWAITING_CONFIRMATION) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG29");
        }
        warehouseTasks.confirmResult(user, TaskType.INSPECTION, receiptId);
        List<GoodsReceiptNoteLine> lines = receiptLines.findByGoodsReceiptNoteIdOrderByIdAsc(receiptId).stream()
                .sorted(Comparator.comparing(GoodsReceiptNoteLine::getProductId)).toList();
        for (GoodsReceiptNoteLine line : lines) {
            int arrived = line.getDeliveredQty() - line.getExcessQty();
            if (arrived == 0) {
                continue;
            }
            Map<StockStatus, Integer> into = new EnumMap<>(StockStatus.class);
            into.put(StockStatus.AVAILABLE, line.getPassedQty() - line.getExcessQty());
            into.put(StockStatus.DAMAGED, line.getDamagedQty());
            posting.moveToStatuses(new SplitMovePosting(line.getProductId(), order.getDestStoreId(),
                    StockStatus.IN_TRANSIT, receivingStore, into, transitLots(order, line.getProductId(), arrived),
                    MovementType.TRANSFER_RECEIPT, SourceRef.goodsReceiptNote(receiptId), user.id(), null));
        }
        LocalDateTime now = LocalDateTime.now(clock);
        receipt.confirm(user.id(), now);
        receiptLines.flush();
        afterConfirmation(user, order, receipt, lines, now);
        return view(receipt);
    }

    /**
     * BR-68 excess goes to the source to confirm; BR-59 what is still missing opens the part's case for the
     * coordinators; the coordinator who created the order is told it can be reconciled and closed.
     */
    private void afterConfirmation(AuthenticatedUser user, TransferOrder order, GoodsReceiptNote receipt,
                                   List<GoodsReceiptNoteLine> lines, LocalDateTime now) {
        long id = order.getId();
        List<TransferOrderLine> parts = orderLines.findByTransferOrderIdOrderByProductId(id);
        Map<Long, Product> catalog = products.findAllById(parts.stream().map(TransferOrderLine::getProductId).toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, TransferReconciliation.Part> figures = reconciliation.byProduct(id, parts);
        String excess = lines.stream().filter(line -> line.getExcessQty() > 0)
                .map(line -> catalog.get(line.getProductId()).getSku() + " x" + line.getExcessQty())
                .collect(Collectors.joining(", "));
        if (!excess.isEmpty()) {
            notifications.notifyStoreManagers(order.getSourceStoreId(), "TRANSFER_EXCESS_RECEIVED",
                    "Phiếu chuyển kho #%d nhận thừa %s, cần xác nhận xuất thêm".formatted(id, excess), ENTITY, id);
        }
        Map<Long, DiscrepancyCase> opened = orders.findCases(id).stream()
                .collect(Collectors.toMap(DiscrepancyCase::getProductId, Function.identity()));
        // UC-76: what a later receipt or a return brought settles the part's case (BR-59, BR-61).
        ResolutionType settled = receipt.getReceiptType() == ReceiptType.TRANSFER_IN
                ? ResolutionType.LATE_DELIVERY_RECEIVED : ResolutionType.RECEIVED_BACK_AT_SOURCE;
        for (GoodsReceiptNoteLine line : lines) {
            DiscrepancyCase shortage = opened.get(line.getProductId());
            int arrived = line.getDeliveredQty() - line.getExcessQty();
            if (shortage == null || arrived <= 0) {
                continue;
            }
            int remaining = arrived;
            if (settled == ResolutionType.RECEIVED_BACK_AT_SOURCE) {
                // R-06: the coordinator's pending decisions are consumed, oldest first; a partial return splits one.
                for (DiscrepancyResolutionEvent decision : events.findPendingReturnDecisionsForUpdate(id,
                        line.getProductId())) {
                    if (remaining == 0) {
                        break;
                    }
                    if (decision.getQuantity() <= remaining) {
                        remaining -= decision.getQuantity();
                        decision.settle(receipt.getId(), user.id(), now);
                    } else {
                        decision.reduceTo(decision.getQuantity() - remaining);
                        events.save(confirmed(decision.getDiscrepancyCaseId(), settled, remaining, receipt, user, now));
                        remaining = 0;
                    }
                }
            }
            // Without a decision (a return opened before decisions were required) the receipt itself is the record.
            if (remaining > 0) {
                events.save(confirmed(shortage.getId(), settled, remaining, receipt, user, now));
            }
        }
        List<String> missing = new ArrayList<>();
        for (TransferOrderLine part : parts) {
            int unresolved = figures.get(part.getProductId()).unresolved();
            if (unresolved > 0 && !opened.containsKey(part.getProductId())) {
                cases.save(DiscrepancyCase.transfer(id, part.getProductId(), unresolved, now));
                missing.add(catalog.get(part.getProductId()).getSku() + " x" + unresolved);
            }
        }
        opened.values().stream().filter(DiscrepancyCase::isOpen)
                .filter(shortage -> figures.get(shortage.getProductId()).unresolved() == 0)
                .forEach(shortage -> shortage.close(ClosureType.ALL_SETTLED, null, user.id(), now));
        if (!missing.isEmpty()) {
            String message = "Phiếu chuyển kho #%d còn thiếu %s chưa có kết quả, cần xử lý chênh lệch"
                    .formatted(id, String.join(", ", missing));
            coordinators().stream().filter(coordinator -> coordinator.getId() != user.id()).forEach(coordinator ->
                    notifications.notify(coordinator.getId(), "TRANSFER_DISCREPANCY", message, ENTITY, id));
        }
        if (receipt.getReceiptType() == ReceiptType.TRANSFER_IN && order.getStatus() == TransferOrderStatus.IN_TRANSIT) {
            order.startReconciling();
            if (order.getCreatedBy() != user.id()) {
                notifications.notify(order.getCreatedBy(), "TRANSFER_RECEIVED",
                        "Phiếu chuyển kho #%d đã nhận hàng, cần đối soát và đóng phiếu".formatted(id), ENTITY, id);
            }
        }
        auditLog.save(AuditLog.record(ENTITY, id, "TRANSFER_RECEIPT_CONFIRMED", user.id(), null, null,
                Map.of("goods_receipt_note_id", receipt.getId(), "receipt_type", receipt.getReceiptType().name()),
                now));
    }

    private static DiscrepancyResolutionEvent confirmed(long caseId, ResolutionType type, int quantity,
                                                        GoodsReceiptNote receipt, AuthenticatedUser user,
                                                        LocalDateTime now) {
        DiscrepancyResolutionEvent event = DiscrepancyResolutionEvent.pending(caseId, type, quantity, receipt.getId(),
                null, user.id(), now);
        event.confirm(quantity, user.id(), now);
        return event;
    }

    /** BR-56: the Owners and the central warehouse Branch Managers. */
    private List<UserAccount> coordinators() {
        List<UserAccount> found = new ArrayList<>(users.findByRoleAndStatus(Role.OWNER, AccountStatus.ACTIVE));
        stores.findByIsCentralTrue().ifPresent(central -> found.addAll(users.findByRoleAndStoreIdAndStatus(
                Role.BRANCH_MANAGER, central.getId(), AccountStatus.ACTIVE)));
        return found;
    }

    /** The order's own lots in transit, FEFO then FIFO, covering {@code quantity} (BR-75). */
    private List<LotQuantity> transitLots(TransferOrder order, long productId, int quantity) {
        List<LotQuantity> taken = new ArrayList<>();
        int left = quantity;
        for (TransferOrderRepository.TransitLot lot : orders.findTransitLots(order.getId(), order.getDestStoreId(),
                productId)) {
            if (left == 0) {
                break;
            }
            int take = (int) Math.min(left, lot.getQuantity());
            taken.add(new LotQuantity(lot.getLotId(), take));
            left -= take;
        }
        if (left > 0) {
            throw new IllegalStateException("transfer order " + order.getId() + " has " + (quantity - left) + " of part "
                    + productId + " in transit, not " + quantity);
        }
        return taken;
    }

    private TransferOrder lockOrder(long id) {
        return orders.findByIdForUpdate(id).orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG175"));
    }

    private static TransferOrder requireNotClosed(TransferOrder order) {
        if (order.getStatus() == TransferOrderStatus.CLOSED) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG29");
        }
        return order;
    }

    private GoodsReceiptNote lockReceipt(long transferOrderId, long receiptId) {
        return receipts.findByIdForUpdate(receiptId)
                .filter(found -> Objects.equals(found.getTransferOrderId(), transferOrderId))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG132"));
    }

    private static long receivingStore(TransferOrder order, GoodsReceiptNote receipt) {
        return receipt.getReceiptType() == ReceiptType.RETURN_TO_SOURCE ? order.getSourceStoreId()
                : order.getDestStoreId();
    }

    private static void requireManager(AuthenticatedUser user, long storeId) {
        if (!isManagerOf(user, storeId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
    }

    private void saveExpected(GoodsReceiptNote receipt, Map<Long, Integer> expected) {
        receiptLines.saveAll(expected.entrySet().stream().filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> GoodsReceiptNoteLine.transfer(receipt.getId(), entry.getKey(), entry.getValue())).toList());
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static boolean isManagerOf(AuthenticatedUser user, long storeId) {
        return user.role() == Role.BRANCH_MANAGER && Objects.equals(user.storeId(), storeId);
    }

    private Map<Long, Product> parts(List<GoodsReceiptNoteLine> lines) {
        return products.findAllById(lines.stream().map(GoodsReceiptNoteLine::getProductId).toList()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
    }

    private TransferReceiptResponse view(GoodsReceiptNote receipt) {
        List<GoodsReceiptNoteLine> lines = receiptLines.findByGoodsReceiptNoteIdOrderByIdAsc(receipt.getId());
        Map<Long, Product> parts = parts(lines);
        Long createdBy = auditLog.findByEntityTypeAndEntityIdAndActionInOrderByOccurredAtDescIdDesc(RECEIPT_ENTITY,
                receipt.getId(), List.of(RECEIPT_CREATED)).stream().findFirst().map(AuditLog::getActorId).orElse(null);
        String createdByName = createdBy == null ? null
                : users.findById(createdBy).map(UserAccount::getFullName).orElse(null);
        return new TransferReceiptResponse(receipt.getId(), receipt.getTransferOrderId(), receipt.getReceiptType(),
                receipt.getStatus(), receipt.getDeliveryDocumentNo(), receipt.getDeliveryDocumentDate(),
                receipt.getDelivererName(), receipt.getVehiclePlateNo(), receipt.getArrivedAt(), createdBy,
                createdByName, receipt.getRecordedBy(), receipt.getSubmittedAt(),
                receipt.getRecountReason(), receipt.getConfirmedBy(), receipt.getConfirmedAt(),
                lines.stream().map(line -> {
                    Product part = parts.get(line.getProductId());
                    return new TransferReceiptResponse.Line(part.getId(), part.getSku(), part.getPartName(),
                            part.getBaseUnit(), line.getDocumentQty(), line.getDeliveredQty(), line.getPassedQty(),
                            line.getDamagedQty(), line.getExcessQty());
                }).toList(), attachments.list(OwnerType.GOODS_RECEIPT_NOTE, receipt.getId()));
    }
}
