package fu.hl.g64.motorflow.transfer.service.impl;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.AccountStatus;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyCase;
import fu.hl.g64.motorflow.discrepancy.entity.DiscrepancyResolutionEvent;
import fu.hl.g64.motorflow.discrepancy.entity.enums.SourceType;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyCaseRepository;
import fu.hl.g64.motorflow.inventory.dto.MovePosting;
import fu.hl.g64.motorflow.inventory.dto.LotQuantity;
import fu.hl.g64.motorflow.inventory.dto.OutPosting;
import fu.hl.g64.motorflow.discrepancy.entity.enums.ClosureType;
import fu.hl.g64.motorflow.inventory.dto.StockAvailabilityResponse;
import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.inventory.service.InventoryPostingService;
import fu.hl.g64.motorflow.inventory.service.InventoryService;
import fu.hl.g64.motorflow.operation.entity.WarehouseTask;
import fu.hl.g64.motorflow.operation.entity.enums.OwnerType;
import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import fu.hl.g64.motorflow.operation.repository.WarehouseTaskRepository;
import fu.hl.g64.motorflow.operation.service.AttachmentService;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.operation.service.WarehouseTaskService;
import fu.hl.g64.motorflow.sales.helper.PickingGuide;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import fu.hl.g64.motorflow.store.helper.StoreScope;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import fu.hl.g64.motorflow.transfer.dto.ClosingPreviewResponse;
import fu.hl.g64.motorflow.transfer.dto.DispatchPreviewResponse;
import fu.hl.g64.motorflow.transfer.dto.ExtraDispatchRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferDispatchRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderCreateRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderDetailResponse;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderDetailResponse.Person;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderRowResponse;
import fu.hl.g64.motorflow.transfer.dto.TransferOrderSearch;
import fu.hl.g64.motorflow.transfer.entity.ReplenishmentRequest;
import fu.hl.g64.motorflow.transfer.entity.ReplenishmentRequestLine;
import fu.hl.g64.motorflow.transfer.entity.TransferOrder;
import fu.hl.g64.motorflow.transfer.entity.TransferOrderLine;
import fu.hl.g64.motorflow.transfer.entity.enums.ReplenishmentRequestStatus;
import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;
import fu.hl.g64.motorflow.transfer.helper.RequestedParts;
import fu.hl.g64.motorflow.transfer.helper.TransferReconciliation;
import fu.hl.g64.motorflow.transfer.helper.TransferExcessAcceptance;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyResolutionEventRepository;
import fu.hl.g64.motorflow.transfer.repository.ReplenishmentRequestLineRepository;
import fu.hl.g64.motorflow.transfer.repository.ReplenishmentRequestRepository;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderLineRepository;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderRepository;
import fu.hl.g64.motorflow.transfer.service.ReplenishmentRequestService;
import fu.hl.g64.motorflow.transfer.service.TransferOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TransferOrderServiceImpl implements TransferOrderService {

    /** audit_log.entity_type and notification.ref_type of a transfer order. */
    private static final String ENTITY = "TRANSFER_ORDER";

    private final TransferOrderRepository orders;
    private final TransferOrderLineRepository lines;
    private final ReplenishmentRequestRepository requests;
    private final ReplenishmentRequestLineRepository requestLines;
    private final DiscrepancyCaseRepository cases;
    private final StoreRepository stores;
    private final StoreScope storeScope;
    private final RequestedParts requestedParts;
    private final TransferReconciliation reconciliation;
    private final ProductRepository products;
    private final InventoryService inventory;
    private final UserAccountRepository users;
    private final AuditLogRepository auditLog;
    private final NotificationService notifications;
    private final InventoryPostingService posting;
    private final WarehouseTaskRepository tasks;
    private final WarehouseTaskService warehouseTasks;
    private final PickingGuide guide;
    private final AttachmentService attachments;
    private final StockBalanceRepository balances;
    private final DiscrepancyResolutionEventRepository events;
    private final TransferExcessAcceptance excessAcceptance;
    private final ReplenishmentRequestService replenishments;
    private final Clock clock;

    // UC-71
    @Transactional
    @Override
    public TransferOrderDetailResponse create(AuthenticatedUser user, TransferOrderCreateRequest body) {
        requireCoordinator(user);
        if (body.sourceStoreId().equals(body.destStoreId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG200");
        }
        activeStore(body.sourceStoreId(), false);
        Store destination = activeStore(body.destStoreId(), user.role() == Role.OWNER);
        Map<Long, Product> parts = requestedParts.requireActive(body.lines().stream()
                .map(TransferOrderCreateRequest.Line::productId).toList());
        checkMakeups(body, parts);
        ReplenishmentRequest request = body.replenishmentRequestId() == null ? null : coveredRequest(body, parts);
        // GVHD 20/11: the source's stock rows are locked after the request (document first, then stock by part id),
        // so a sales order taking the same goods waits for this check.
        balances.findByStoreForUpdate(body.sourceStoreId(), parts.keySet().stream().sorted().toList());
        checkSellable(user, body, parts);

        LocalDateTime now = LocalDateTime.now(clock);
        TransferOrder order = orders.save(TransferOrder.create(body.replenishmentRequestId(), body.sourceStoreId(),
                body.destStoreId(), user.id(), now));
        List<TransferOrderLine> saved = lines.saveAll(body.lines().stream().map(line -> TransferOrderLine.create(
                order.getId(), line.productId(), line.plannedQty(), line.makeupForCaseId())).toList());
        if (request != null) {
            request.startCoordination();
        }
        auditLog.save(AuditLog.record(ENTITY, order.getId(), "TRANSFER_ORDER_CREATED", user.id(), null, null,
                Map.of("source_store_id", order.getSourceStoreId(), "dest_store_id", order.getDestStoreId(),
                        "lines", saved.stream().map(line -> Map.of("product_id", line.getProductId(),
                                "planned_qty", line.getPlannedQty())).toList()), now));
        String message = "Có phiếu chuyển kho #%d cần chuẩn bị hàng gửi tới %s"
                .formatted(order.getId(), destination.getStoreName());
        users.findByRoleAndStoreIdAndStatus(Role.BRANCH_MANAGER, order.getSourceStoreId(), AccountStatus.ACTIVE).stream()
                .filter(manager -> manager.getId() != user.id())
                .forEach(manager -> notifications.notify(manager.getId(), "TRANSFER_CREATED", message, ENTITY,
                        order.getId()));
        return detail(user, order);
    }

    // UC-72
    @Transactional(readOnly = true)
    @Override
    public PageResponse<TransferOrderRowResponse> list(AuthenticatedUser user, TransferOrderSearch search, int page,
                                                       int size) {
        Long staffId = user.role() == Role.STAFF ? user.id() : null;
        Long storeId = staffId == null && !storeScope.seesEveryStore(user) ? user.storeId() : null;
        Page<TransferOrderRepository.OrderRow> found = orders.search(search.status(), search.sourceStoreId(),
                search.destStoreId(), search.replenishmentRequestId(), storeId, staffId, search.from(), search.to(),
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100)));
        return PageResponse.of(found, found.stream().map(row -> {
            TransferOrder order = row.getTransferOrder();
            return new TransferOrderRowResponse(order.getId(), order.getStatus(), order.getSourceStoreId(),
                    row.getSourceStoreName(), order.getDestStoreId(), row.getDestStoreName(),
                    order.getReplenishmentRequestId(), row.getLineCount(), row.getTotalPlannedQty(),
                    row.getCreatedByName(), order.getCreatedAt(), order.getDispatchedAt(), order.getClosedAt());
        }).toList());
    }

    // UC-73
    @Transactional(readOnly = true)
    @Override
    public TransferOrderDetailResponse detail(AuthenticatedUser user, long id) {
        TransferOrder order = orders.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG175"));
        boolean allowed = user.role() == Role.STAFF ? orders.staffWorksOn(id, user.id())
                : storeScope.seesEveryStore(user) || Objects.equals(user.storeId(), order.getSourceStoreId())
                || Objects.equals(user.storeId(), order.getDestStoreId());
        if (!allowed) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        return detail(user, order);
    }

    // UC-74
    @Transactional(readOnly = true)
    @Override
    public DispatchPreviewResponse dispatchPreview(AuthenticatedUser user, long id) {
        TransferOrder order = orders.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG175"));
        boolean sourceManager = isManagerOf(user, order.getSourceStoreId());
        if (!sourceManager && !storeScope.seesEveryStore(user)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        List<TransferOrderLine> orderLines = lines.findByTransferOrderIdOrderByProductId(id);
        Map<Long, Product> parts = products.findAllById(orderLines.stream().map(TransferOrderLine::getProductId)
                .toList()).stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        boolean open = order.getStatus() == TransferOrderStatus.AWAITING_DISPATCH;
        Map<Long, PickingGuide.Part> stock = open
                ? guide.parts(order.getSourceStoreId(), parts.keySet(), LocalDate.now(clock)) : Map.of();
        Optional<WarehouseTask> preparer = tasks.findHistory(TaskType.DISPATCH_PREPARATION, id).stream()
                .filter(task -> task.getStatus() != WarehouseTaskStatus.CANCELLED).findFirst();
        boolean ready = open && preparer.filter(task -> task.getStatus() == WarehouseTaskStatus.RESULT_SUBMITTED)
                .isPresent();
        boolean canConfirm = ready && sourceManager;
        return new DispatchPreviewResponse(id, order.getStatus(), preparer.map(WarehouseTask::getAssignedTo).orElse(null),
                ready, canConfirm, orderLines.stream().map(line -> {
                    Product part = parts.get(line.getProductId());
                    PickingGuide.Part held = stock.get(line.getProductId());
                    int needed = line.getPreparedQty() != null ? line.getPreparedQty() : line.getPlannedQty();
                    return new DispatchPreviewResponse.Line(part.getId(), part.getSku(), part.getPartName(),
                            part.getBaseUnit(), line.getPlannedQty(), line.getPreparedQty(),
                            line.getPreparedQty() == null ? null : line.getPlannedQty() - line.getPreparedQty(),
                            held == null ? 0 : held.sellableQty(), held == null ? List.of() : held.pickFirst(needed));
                }).toList());
    }

    // UC-74
    @Transactional
    @Override
    public TransferOrderDetailResponse dispatch(AuthenticatedUser user, long id, TransferDispatchRequest body) {
        TransferOrder order = orders.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG175"));
        if (!isManagerOf(user, order.getSourceStoreId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        if (order.getDispatchedAt() != null) {
            return detail(user, order);
        }
        boolean submitted = tasks.findOpenForUpdate(TaskType.DISPATCH_PREPARATION, id)
                .filter(task -> task.getStatus() == WarehouseTaskStatus.RESULT_SUBMITTED).isPresent();
        if (!submitted) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG207");
        }
        warehouseTasks.confirmResult(user, TaskType.DISPATCH_PREPARATION, id);

        List<TransferOrderLine> orderLines = lines.findByTransferOrderIdOrderByProductId(id);
        Map<Long, Product> parts = products.findAllById(orderLines.stream().map(TransferOrderLine::getProductId)
                .toList()).stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, Integer> sent = sentQuantities(body, orderLines, parts);
        LocalDateTime now = LocalDateTime.now(clock);
        List<MovePosting> moves = new ArrayList<>();
        for (TransferOrderLine line : orderLines) {
            int quantity = sent.get(line.getProductId());
            if (quantity > 0) {
                moves.add(new MovePosting(line.getProductId(), order.getSourceStoreId(), StockStatus.AVAILABLE,
                        order.getDestStoreId(), StockStatus.IN_TRANSIT, quantity, null, MovementType.TRANSFER_DISPATCH,
                        SourceRef.transferOrder(id), user.id(), null));
            }
        }
        posting.moveAll(moves);
        List<Map<String, Object>> recorded = new ArrayList<>();
        for (TransferOrderLine line : orderLines) {
            int quantity = sent.get(line.getProductId());
            line.recordDispatched(quantity);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("product_id", line.getProductId());
            row.put("prepared_qty", line.getPreparedQty());
            row.put("dispatched_qty", quantity);
            recorded.add(row);
        }
        order.dispatch(user.id(), text(body.delivererName()), text(body.vehiclePlateNo()), now);
        List<?> evidence = body.evidence() == null ? List.of() : body.evidence();
        attachments.attach(OwnerType.TRANSFER_ORDER, id, body.evidence() == null ? List.of() : body.evidence(),
                user.id(), "DISPATCH");
        Map<String, Object> dispatched = new LinkedHashMap<>();
        dispatched.put("lines", recorded);
        dispatched.put("deliverer_name", text(body.delivererName()));
        dispatched.put("vehicle_plate_no", text(body.vehiclePlateNo()));
        dispatched.put("photo_count", evidence.size());
        auditLog.save(AuditLog.record(ENTITY, id, "TRANSFER_ORDER_DISPATCHED", user.id(), null, null, dispatched, now));
        String sourceName = stores.findById(order.getSourceStoreId()).orElseThrow().getStoreName();
        notifications.notifyStoreManagers(order.getDestStoreId(), "TRANSFER_DISPATCHED",
                "Phiếu chuyển kho #%d đã xuất từ %s, chờ nhận hàng".formatted(id, sourceName), ENTITY, id);
        return detail(user, order);
    }

    // UC-75
    @Transactional
    @Override
    public TransferOrderDetailResponse confirmExtraDispatch(AuthenticatedUser user, long id, ExtraDispatchRequest body) {
        TransferOrder order = orders.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG175"));
        if (!isManagerOf(user, order.getSourceStoreId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        if (order.getStatus() != TransferOrderStatus.RECONCILING) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG29");
        }
        List<TransferOrderLine> orderLines = lines.findByTransferOrderIdOrderByProductId(id);
        Map<Long, Product> parts = products.findAllById(orderLines.stream().map(TransferOrderLine::getProductId)
                .toList()).stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, TransferReconciliation.Part> figures = reconciliation.byProduct(id, orderLines);
        Map<Long, TransferOrderLine> byProduct = orderLines.stream()
                .collect(Collectors.toMap(TransferOrderLine::getProductId, Function.identity()));
        // What was received above the dispatch and not yet confirmed: confirming twice cannot go beyond it.
        Map<Long, Integer> pending = orderLines.stream().collect(Collectors.toMap(TransferOrderLine::getProductId,
                line -> Math.max(figures.get(line.getProductId()).legacyExcess() - line.getExtraDispatchedQty(), 0)));
        if (pending.values().stream().allMatch(quantity -> quantity == 0)) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG213");
        }
        Map<Long, Integer> sent = new LinkedHashMap<>();
        for (int i = 0; i < body.lines().size(); i++) {
            ExtraDispatchRequest.Line line = body.lines().get(i);
            if (!byProduct.containsKey(line.productId()) || sent.putIfAbsent(line.productId(), line.extraQty()) != null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG166", i + 1);
            }
            if (line.extraQty() > pending.get(line.productId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG214", parts.get(line.productId()).getSku(),
                        pending.get(line.productId()));
            }
        }
        String reason = StringUtils.hasText(body.reason()) ? body.reason().strip() : null;
        if (reason == null && sent.values().stream().anyMatch(quantity -> quantity == 0)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG215");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<Map<String, Object>> recorded = new ArrayList<>();
        sent.forEach((productId, quantity) -> {
            byProduct.get(productId).confirmExtraDispatch(quantity);
            recorded.add(Map.of("product_id", productId, "pending_qty", pending.get(productId), "extra_qty", quantity));
        });
        order.confirmExtraDispatch(user.id(), now);
        auditLog.save(AuditLog.record(ENTITY, id, "TRANSFER_EXTRA_DISPATCH_CONFIRMED", user.id(), reason, null,
                Map.of("lines", recorded), now));
        attachments.attach(OwnerType.TRANSFER_ORDER, id, body.evidence() == null ? List.of() : body.evidence(),
                user.id(), "EXTRA_DISPATCH");
        if (order.getCreatedBy() != user.id()) {
            notifications.notify(order.getCreatedBy(), "TRANSFER_EXTRA_DISPATCH_CONFIRMED",
                    "Phiếu chuyển kho #%d: kho nguồn đã xác nhận phần xuất thêm, có thể đối soát để đóng phiếu"
                            .formatted(id), ENTITY, id);
        }
        return detail(user, order);
    }

    @Transactional
    @Override
    public TransferOrderDetailResponse acknowledgeDiscrepancy(AuthenticatedUser user, long id) {
        TransferOrder order = orders.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG175"));
        if (!isManagerOf(user, order.getDestStoreId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        if (order.getStatus() != TransferOrderStatus.RECONCILING || !lines.openReceiptQty(id).isEmpty()
                || hasPendingClaims(id)) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG29");
        }
        // Only a transfer with a short, damaged or wrong item part is acknowledged; a clean one closes as before.
        if (order.getDiscrepancyAcknowledgedAt() == null && !hasDiscrepancy(closingLines(order))) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG29");
        }
        if (order.getDiscrepancyAcknowledgedAt() == null) {
            LocalDateTime now = LocalDateTime.now(clock);
            order.acknowledgeDiscrepancy(user.id(), now);
            auditLog.save(AuditLog.record(ENTITY, id, "TRANSFER_DISCREPANCY_ACKNOWLEDGED", user.id(), null,
                    null, Map.of("destination_store_id", order.getDestStoreId()), now));
            // PM 23/11 case 10: the Owner learns now what closing will write off as lost.
            String toLoss = closingLines(order).stream().filter(row -> row.toLossQty() > 0)
                    .map(row -> row.sku() + " x" + row.toLossQty()).collect(Collectors.joining(", "));
            notifications.notifyOwners("TRANSFER_DISCREPANCY_ACKNOWLEDGED",
                    "Kho nhận đã xác nhận xử lý sai lệch phiếu chuyển kho #%d".formatted(id)
                            + (toLoss.isEmpty() ? "" : ". Sẽ tính là mất khi đóng: " + toLoss), ENTITY, id);
        }
        return detail(user, order);
    }

    // UC-77
    @Transactional(readOnly = true)
    @Override
    public ClosingPreviewResponse closingPreview(AuthenticatedUser user, long id) {
        requireCoordinator(user);
        TransferOrder order = orders.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG175"));
        List<ClosingPreviewResponse.Line> rows = closingLines(order);
        Map<String, Object[]> blocked = order.getStatus() == TransferOrderStatus.RECONCILING
                ? blockers(order, rows, lines.openReceiptQty(id)) : Map.of();
        return new ClosingPreviewResponse(id, order.getStatus(),
                order.getStatus() == TransferOrderStatus.RECONCILING && blocked.isEmpty()
                        && (order.getDiscrepancyAcknowledgedAt() == null || user.role() == Role.OWNER),
                List.copyOf(blocked.keySet()), rows);
    }

    // UC-77
    @Transactional
    @Override
    public TransferOrderDetailResponse close(AuthenticatedUser user, long id) {
        requireCoordinator(user);
        TransferOrder order = orders.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG175"));
        if (order.getStatus() == TransferOrderStatus.CLOSED) {
            return detail(user, order);
        }
        if (order.getStatus() != TransferOrderStatus.RECONCILING) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG29");
        }
        List<ClosingPreviewResponse.Line> rows = closingLines(order);
        Map<String, Object[]> blocked = blockers(order, rows, lines.openReceiptQty(id));
        if (order.getDiscrepancyAcknowledgedAt() != null && user.role() != Role.OWNER) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        if (!blocked.isEmpty()) {
            Map.Entry<String, Object[]> first = blocked.entrySet().iterator().next();
            throw new BusinessException(HttpStatus.CONFLICT, first.getKey(), first.getValue());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> productIds = rows.stream().map(ClosingPreviewResponse.Line::productId).sorted().toList();
        balances.findByStoreForUpdate(Math.min(order.getSourceStoreId(), order.getDestStoreId()), productIds);
        balances.findByStoreForUpdate(Math.max(order.getSourceStoreId(), order.getDestStoreId()), productIds);
        List<MovePosting> moves = new ArrayList<>();
        List<Map<String, Object>> posted = new ArrayList<>();
        // BR-68: the confirmed excess leaves the source's available stock for the destination's, FEFO then FIFO.
        for (ClosingPreviewResponse.Line row : rows) {
            if (row.extraDispatchedQty() > 0) {
                moves.add(new MovePosting(row.productId(), order.getSourceStoreId(), StockStatus.AVAILABLE,
                        order.getDestStoreId(), StockStatus.AVAILABLE, row.extraDispatchedQty(), null,
                        MovementType.TRANSFER_EXTRA_DISPATCH, SourceRef.transferOrder(id), user.id(), null));
                posted.add(Map.of("product_id", row.productId(), "extra_qty", row.extraDispatchedQty()));
            }
        }
        posting.moveAll(moves);
        // PM 23/11 B-7(a): the excess still awaiting the Owner is accepted.
        excessAcceptance.accept(order, excessAcceptance.pending(id), user.id(), "Tự chấp nhận hàng thừa khi đóng phiếu");

        // PM 23/11 B-7(b), (c): of what is still in transit at the destination, the part not to be replaced goes back
        // to the source's available stock, lot by lot, and the rest is lost.
        Map<Long, List<LotQuantity>> transit = new HashMap<>();
        orders.findTransitLots(id, order.getDestStoreId()).forEach(lot -> transit.computeIfAbsent(lot.getProductId(),
                key -> new ArrayList<>()).add(new LotQuantity(lot.getLotId(), Math.toIntExact(lot.getQuantity()))));
        List<MovePosting> back = new ArrayList<>();
        List<Map<String, Object>> returned = new ArrayList<>();
        for (DiscrepancyResolutionEvent decision : events.findPendingNoReplacementForUpdate(id)) {
            long productId = cases.findById(decision.getDiscrepancyCaseId()).orElseThrow().getProductId();
            List<LotQuantity> lots = take(transit.getOrDefault(productId, new ArrayList<>()), decision.getQuantity());
            if (lots.stream().mapToInt(LotQuantity::quantity).sum() != decision.getQuantity()) {
                throw new BusinessException(HttpStatus.CONFLICT, "MSG337");
            }
            back.add(new MovePosting(productId, order.getDestStoreId(), StockStatus.IN_TRANSIT,
                    order.getSourceStoreId(), StockStatus.AVAILABLE, decision.getQuantity(), lots,
                    MovementType.TRANSFER_SHORT_RETURNED, SourceRef.discrepancyResolutionEvent(decision.getId()),
                    user.id(), "Không cần giao bù, trả lại kho gửi"));
            decision.confirmNoReplacement(user.id(), now);
            returned.add(Map.of("product_id", productId, "quantity", decision.getQuantity()));
        }
        if (!back.isEmpty()) {
            posting.moveAll(back);
        }
        List<OutPosting> lost = new ArrayList<>();
        List<Map<String, Object>> written = new ArrayList<>();
        for (ClosingPreviewResponse.Line row : rows) {
            List<LotQuantity> lots = transit.getOrDefault(row.productId(), List.of()).stream()
                    .filter(lot -> lot.quantity() > 0).toList();
            int quantity = lots.stream().mapToInt(LotQuantity::quantity).sum();
            if (quantity != row.toLossQty()) {
                throw new BusinessException(HttpStatus.CONFLICT, "MSG337");
            }
            if (quantity > 0) {
                lost.add(new OutPosting(order.getDestStoreId(), row.productId(), StockStatus.IN_TRANSIT,
                        quantity, lots, MovementType.ADJUSTMENT, SourceRef.transferOrder(id), user.id(),
                        "Mất khi điều chuyển"));
                written.add(Map.of("product_id", row.productId(), "quantity", quantity));
            }
        }
        if (!lost.isEmpty()) {
            posting.takeOutAll(lost);
        }
        orders.findCases(id).stream().filter(DiscrepancyCase::isOpen)
                .forEach(item -> item.close(ClosureType.CLOSED_BY_AGREEMENT,
                        order.getDiscrepancyAcknowledgedAt() != null
                                ? "Kho nhận đã xác nhận xử lý, Owner đã đóng phiếu."
                                : "Đã đóng phiếu chuyển kho.", user.id(), now));
        order.close(user.id(), now);
        warehouseTasks.cancelForDocument(TaskType.TRANSFER_RECEIVING, id);
        auditLog.save(AuditLog.record(ENTITY, id, "TRANSFER_ORDER_CLOSED", user.id(), null, null,
                Map.of("extra_dispatch", posted, "to_source", returned, "lost", written), now));
        String message = "Phiếu chuyển kho #%d đã đóng".formatted(id);
        notifications.notifyStoreManagers(order.getSourceStoreId(), "TRANSFER_CLOSED", message, ENTITY, id);
        notifications.notifyStoreManagers(order.getDestStoreId(), "TRANSFER_CLOSED", message, ENTITY, id);
        if (order.getReplenishmentRequestId() != null) {
            replenishments.closeIfDone(user, order.getReplenishmentRequestId(), "TRANSFER_ORDER_CLOSED");
        }
        return detail(user, order);
    }

    private List<ClosingPreviewResponse.Line> closingLines(TransferOrder order) {
        List<TransferOrderLine> orderLines = lines.findByTransferOrderIdOrderByProductId(order.getId());
        Map<Long, Product> parts = products.findAllById(orderLines.stream().map(TransferOrderLine::getProductId)
                .toList()).stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, TransferReconciliation.Part> figures = reconciliation.byProduct(order.getId(), orderLines);
        Map<Long, PickingGuide.Part> source = guide.parts(order.getSourceStoreId(), parts.keySet(),
                LocalDate.now(clock));
        return orderLines.stream().map(line -> {
            Product part = parts.get(line.getProductId());
            TransferReconciliation.Part figure = figures.get(line.getProductId());
            PickingGuide.Part held = source.get(line.getProductId());
            return new ClosingPreviewResponse.Line(part.getId(), part.getSku(), part.getPartName(), part.getBaseUnit(),
                    figure.dispatched(), figure.passed(), figure.returned(), figure.lost(), figure.unresolved(),
                    figure.extraReceived(), line.getExtraDispatchedQty(), held == null ? 0 : held.sellableQty(),
                    figure.damaged(), figure.legacyExcess(), figure.pendingExcess(), figure.shortage(),
                    figure.awaitingDecision(), figure.toSource(), figure.toLoss(), figure.pendingExcess(),
                    figure.rules().wrongItem(), figure.wrongPending(), figure.rules().wrongAwaitingSource(),
                    figure.rules().wrongConfirmed(), figure.rules().wrongReturned(), figure.rules().miscounted(),
                    figure.rules().latePending());
        }).toList();
    }

    /** Takes {@code quantity} from the lots in their order, lowering what is left in them. */
    private static List<LotQuantity> take(List<LotQuantity> lots, int quantity) {
        List<LotQuantity> taken = new ArrayList<>();
        int left = quantity;
        for (int i = 0; i < lots.size() && left > 0; i++) {
            LotQuantity lot = lots.get(i);
            int part = Math.min(left, lot.quantity());
            if (part > 0) {
                taken.add(new LotQuantity(lot.lotId(), part));
                lots.set(i, new LotQuantity(lot.lotId(), lot.quantity() - part));
                left -= part;
            }
        }
        return taken;
    }

    private boolean hasPendingClaims(long id) {
        return reconciliation.pendingClaims(id).values().stream().anyMatch(qty -> qty > 0);
    }

    /**
     * Q4, PM 23/11 B-6: a part still short, received damaged or with wrong items needs the destination's
     * acknowledgement. What is not to be replaced is the destination's own choice and needs none; a line the Owner
     * corrected as miscounted has none of these left.
     */
    private static boolean hasDiscrepancy(List<ClosingPreviewResponse.Line> rows) {
        return rows.stream().anyMatch(row -> row.shortageQty() > 0 || row.damagedQty() > 0 || row.wrongItemQty() > 0);
    }

    /** UC-77 step 2: each reason the order cannot close, with its message arguments, in the order checked. */
    private Map<String, Object[]> blockers(TransferOrder order, List<ClosingPreviewResponse.Line> rows,
                                           List<TransferOrderLineRepository.ProductQty> open) {
        Map<String, Object[]> blocked = new LinkedHashMap<>();
        String shortage = rows.stream().filter(row -> row.shortageQty() > 0)
                .map(row -> row.sku() + " x" + row.shortageQty()).collect(Collectors.joining(", "));
        if (order.getDiscrepancyAcknowledgedAt() == null && hasDiscrepancy(rows)) {
            blocked.put("MSG336", new Object[]{shortage});
        }
        // PM 23/11 B-7(d): every wrong item needs a phiếu nhận hàng giao sai the Owner confirmed.
        String wrong = rows.stream().filter(row -> row.wrongPendingQty() + row.wrongAwaitingSourceQty() > 0)
                .map(row -> row.sku() + " x" + (row.wrongPendingQty() + row.wrongAwaitingSourceQty()))
                .collect(Collectors.joining(", "));
        if (!wrong.isEmpty()) {
            blocked.put("MSG342", new Object[]{wrong});
        }
        if (hasPendingClaims(order.getId())) {
            blocked.put("MSG340", new Object[0]);
        }
        // PM Q1 23/11: what the destination chose to receive later must arrive before the order closes.
        String late = rows.stream().filter(row -> row.latePendingQty() > 0)
                .map(row -> row.sku() + " x" + row.latePendingQty()).collect(Collectors.joining(", "));
        if (!late.isEmpty()) {
            blocked.put("MSG348", new Object[]{late});
        }
        // PM 23/11 B-7(a): the excess awaiting the Owner is accepted on closing, so the source must be able to give it.
        TransferExcessAcceptance.Refusal excess = excessAcceptance.check(order, excessAcceptance.pending(order.getId()));
        if (excess != null) {
            blocked.put(excess.code(), excess.args());
        }
        String unconfirmed = rows.stream().filter(row -> row.legacyExcessQty() > row.extraDispatchedQty())
                .map(row -> row.sku() + " x" + (row.legacyExcessQty() - row.extraDispatchedQty()))
                .collect(Collectors.joining(", "));
        if (!unconfirmed.isEmpty()) {
            blocked.put("MSG222", new Object[]{unconfirmed});
        }
        String shortAtSource = rows.stream().filter(row -> row.extraDispatchedQty() > row.sourceSellableQty())
                .map(row -> row.sku() + " cần " + row.extraDispatchedQty() + ", còn " + row.sourceSellableQty())
                .collect(Collectors.joining(", "));
        if (!shortAtSource.isEmpty()) {
            blocked.put("MSG223", new Object[]{shortAtSource});
        }
        // A receipt is only ever opened with lines expecting at least one unit, so any open receipt shows up here.
        Map<Long, Long> expected = open.stream().collect(Collectors.toMap(
                TransferOrderLineRepository.ProductQty::getProductId, TransferOrderLineRepository.ProductQty::getQty));
        String opened = rows.stream().filter(row -> expected.getOrDefault(row.productId(), 0L) > 0)
                .map(row -> row.sku() + " x" + expected.get(row.productId())).collect(Collectors.joining(", "));
        if (!opened.isEmpty()) {
            blocked.put("MSG225", new Object[]{opened});
        }
        return blocked;
    }

    /** UC-74 step 3: one line per part of the order, each equal to what was prepared, not all zero. */
    private static Map<Long, Integer> sentQuantities(TransferDispatchRequest body, List<TransferOrderLine> orderLines,
                                                     Map<Long, Product> parts) {
        Map<Long, TransferOrderLine> byProduct = orderLines.stream()
                .collect(Collectors.toMap(TransferOrderLine::getProductId, Function.identity()));
        Map<Long, Integer> sent = new LinkedHashMap<>();
        for (int i = 0; i < body.lines().size(); i++) {
            TransferDispatchRequest.Line line = body.lines().get(i);
            if (!byProduct.containsKey(line.productId()) || sent.putIfAbsent(line.productId(), line.dispatchedQty()) != null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG166", i + 1);
            }
        }
        String missing = orderLines.stream().filter(line -> !sent.containsKey(line.getProductId()))
                .map(line -> parts.get(line.getProductId()).getSku()).collect(Collectors.joining(", "));
        if (!missing.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG167", missing);
        }
        for (TransferOrderLine line : orderLines) {
            if (!sent.get(line.getProductId()).equals(line.getPreparedQty())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG208", parts.get(line.getProductId()).getSku(),
                        line.getPreparedQty());
            }
        }
        if (sent.values().stream().allMatch(quantity -> quantity == 0)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG209");
        }
        return sent;
    }

    private static boolean isManagerOf(AuthenticatedUser user, long storeId) {
        return user.role() == Role.BRANCH_MANAGER && Objects.equals(user.storeId(), storeId);
    }

    private TransferOrderDetailResponse detail(AuthenticatedUser user, TransferOrder order) {
        long id = order.getId();
        boolean staff = user.role() == Role.STAFF;
        boolean owner = user.role() == Role.OWNER;
        List<TransferOrderLine> orderLines = lines.findByTransferOrderIdOrderByProductId(id);
        Map<Long, Product> parts = products.findAllById(orderLines.stream().map(TransferOrderLine::getProductId)
                .toList()).stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, TransferReconciliation.Part> figures = reconciliation.byProduct(id, orderLines);
        Map<Long, String> storeNames = stores.findAllById(List.of(order.getSourceStoreId(), order.getDestStoreId()))
                .stream().collect(Collectors.toMap(Store::getId, Store::getStoreName));
        Map<Long, String> names = users.findAllById(Stream.of(order.getCreatedBy(), order.getDispatchedBy(),
                        order.getExtraDispatchConfirmedBy(), order.getClosedBy(), order.getDiscrepancyAcknowledgedBy()).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName));
        List<TransferOrderDetailResponse.Discrepancy> discrepancies = staff ? List.of() : discrepancies(id);
        return new TransferOrderDetailResponse(id, order.getStatus(), order.getReplenishmentRequestId(),
                order.getSourceStoreId(), storeNames.get(order.getSourceStoreId()), order.getDestStoreId(),
                storeNames.get(order.getDestStoreId()),
                person(order.getCreatedBy(), names), order.getCreatedAt(),
                person(order.getDispatchedBy(), names), order.getDispatchedAt(), order.getDelivererName(),
                order.getVehiclePlateNo(), person(order.getExtraDispatchConfirmedBy(), names),
                order.getExtraDispatchConfirmedAt(),
                person(order.getClosedBy(), names), order.getClosedAt(),
                person(order.getDiscrepancyAcknowledgedBy(), names), order.getDiscrepancyAcknowledgedAt(),
                orderLines.stream().map(line -> {
                    Product part = parts.get(line.getProductId());
                    TransferReconciliation.Part figure = figures.get(line.getProductId());
                    return new TransferOrderDetailResponse.Line(part.getId(), part.getSku(), part.getPartName(),
                            part.getBaseUnit(), line.getPlannedQty(), line.getPreparedQty(), line.getDispatchedQty(),
                            staff ? null : figure.passed(), staff ? null : figure.returned(),
                            staff ? null : figure.lost(), staff ? null : figure.extraReceived(),
                            staff ? null : line.getExtraDispatchedQty(),
                            staff ? null : figure.awaitingDecision(),
                            staff ? null : figure.damaged(), staff ? null : figure.shortage(),
                            line.getMakeupForCaseId(), staff ? null : figure.rules().wrongItem(),
                            staff ? null : figure.wrongPending(), staff ? null : figure.rules().wrongAwaitingSource(),
                            staff ? null : figure.rules().wrongConfirmed(),
                            staff ? null : figure.rules().wrongReturned(),
                            staff ? null : figure.rules().noReplacement(), staff ? null : figure.rules().latePending(),
                            staff ? null : figure.rules().miscounted());
                }).toList(),
                orders.findDispatchedLots(id).stream().map(lot -> new TransferOrderDetailResponse.Lot(
                        lot.getProductId(), lot.getLotId(), lot.getManufacturerLotNo(), lot.getExpiryDate(),
                        lot.getQuantity(), owner ? lot.getUnitCost() : null)).toList(),
                orders.findReceipts(id).stream().map(receipt -> {
                    boolean incoming = receipt.getReceiptType() == fu.hl.g64.motorflow.receiving.entity.enums.ReceiptType.TRANSFER_IN;
                    return new TransferOrderDetailResponse.Receipt(
                        receipt.getId(), receipt.getReceiptType(), receipt.getStatus(),
                        receipt.getDeliveryDocumentNo(), receipt.getDeliveryDocumentDate(),
                        incoming ? order.getDelivererName() : receipt.getDelivererName(),
                        incoming ? order.getVehiclePlateNo() : receipt.getVehiclePlateNo(), receipt.getArrivedAt(),
                        receipt.getConfirmedAt());
                }).toList(),
                discrepancies, staff ? List.of() : attachments.list(OwnerType.TRANSFER_ORDER, id));
    }

    private List<TransferOrderDetailResponse.Discrepancy> discrepancies(long transferOrderId) {
        List<DiscrepancyCase> found = orders.findCases(transferOrderId);
        if (found.isEmpty()) {
            return List.of();
        }
        List<DiscrepancyResolutionEvent> all = orders.findEvents(found.stream().map(DiscrepancyCase::getId).toList());
        Map<Long, List<DiscrepancyResolutionEvent>> events = all.stream()
                .collect(Collectors.groupingBy(DiscrepancyResolutionEvent::getDiscrepancyCaseId));
        Map<Long, Product> received = products.findAllById(all.stream()
                        .map(DiscrepancyResolutionEvent::getReceivedProductId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, String> names = users.findAllById(all.stream().map(DiscrepancyResolutionEvent::getRecordedBy)
                        .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName));
        return found.stream().map(shortage -> new TransferOrderDetailResponse.Discrepancy(shortage.getId(),
                shortage.getProductId(), shortage.getShortQty(), shortage.getWrongItemQty(), shortage.getDamagedQty(),
                shortage.getStatus(), shortage.getOpenedAt(), shortage.getClosedAt(),
                events.getOrDefault(shortage.getId(), List.of()).stream().map(event -> {
                    Product part = event.getReceivedProductId() == null ? null : received.get(event.getReceivedProductId());
                    return new TransferOrderDetailResponse.Event(event.getId(), event.getResolutionType(),
                            event.getQuantity(), event.getGoodsReceiptNoteId(), event.getAdjustmentRequestId(),
                            event.getReason(), event.getOccurredAt(), event.getConfirmedAt(),
                            event.getReceivedProductId(), part == null ? null : part.getSku(),
                            part == null ? null : part.getPartName(), event.getReceivedExpiryDate(), event.getStatus(),
                            person(event.getRecordedBy(), names));
                }).toList()))
                .toList();
    }

    private static Person person(Long id, Map<Long, String> names) {
        return id == null ? null : new Person(id, names.get(id));
    }

    /** BR-56: the Owner or the central warehouse Branch Manager. */
    private void requireCoordinator(AuthenticatedUser user) {
        if (!storeScope.seesEveryStore(user)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
    }

    /** BR-64: only the Owner may stock a store that has not opened yet. */
    private Store activeStore(long id, boolean plannedAllowed) {
        return stores.findById(id)
                .filter(store -> store.getStatus() == StoreStatus.ACTIVE
                        || (plannedAllowed && store.getStatus() == StoreStatus.PLANNED))
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "MSG46"));
    }

    /** BR-62: a make-up line replaces goods missing on another order to the same store, up to the shortage. */
    private void checkMakeups(TransferOrderCreateRequest body, Map<Long, Product> parts) {
        List<TransferOrderCreateRequest.Line> makeups = body.lines().stream()
                .filter(line -> line.makeupForCaseId() != null).toList();
        if (makeups.isEmpty()) {
            return;
        }
        Map<Long, DiscrepancyCase> byCase = cases.findAllById(makeups.stream()
                        .map(TransferOrderCreateRequest.Line::makeupForCaseId).toList()).stream()
                .collect(Collectors.toMap(DiscrepancyCase::getId, Function.identity()));
        Map<Long, TransferOrder> byOrder = orders.findAllById(byCase.values().stream()
                        .map(DiscrepancyCase::getTransferOrderId).filter(Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(TransferOrder::getId, Function.identity()));
        for (TransferOrderCreateRequest.Line line : makeups) {
            DiscrepancyCase found = byCase.get(line.makeupForCaseId());
            TransferOrder original = found == null || found.getTransferOrderId() == null ? null
                    : byOrder.get(found.getTransferOrderId());
            boolean valid = found != null && found.getSourceType() == SourceType.TRANSFER && found.isOpen()
                    && found.getProductId().equals(line.productId()) && line.plannedQty() <= found.getShortQty()
                    && original != null && original.getDestStoreId().equals(body.destStoreId());
            if (!valid) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG206", parts.get(line.productId()).getSku());
            }
        }
    }

    /**
     * UC-71 step 4, locked so that two orders for one request cannot both take what remains. Make-up lines stand in for
     * goods already planned, so they neither need nor use up the demand.
     */
    private ReplenishmentRequest coveredRequest(TransferOrderCreateRequest body, Map<Long, Product> parts) {
        ReplenishmentRequest request = requests.findByIdForUpdate(body.replenishmentRequestId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG95"));
        if (request.getStatus() == ReplenishmentRequestStatus.CLOSED) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG29");
        }
        if (!request.getRequestingStoreId().equals(body.destStoreId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG203");
        }
        Map<Long, ReplenishmentRequestLine> requested = requestLines
                .findByReplenishmentRequestIdOrderById(request.getId()).stream()
                .collect(Collectors.toMap(ReplenishmentRequestLine::getProductId, Function.identity()));
        Map<Long, Long> planned = lines.plannedByProduct(request.getId()).stream()
                .collect(Collectors.toMap(TransferOrderLineRepository.ProductQty::getProductId,
                        TransferOrderLineRepository.ProductQty::getQty));
        for (int i = 0; i < body.lines().size(); i++) {
            TransferOrderCreateRequest.Line line = body.lines().get(i);
            if (line.makeupForCaseId() != null) {
                continue;
            }
            ReplenishmentRequestLine wanted = requested.get(line.productId());
            if (wanted == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG204", i + 1);
            }
            long remaining = wanted.getRequestedQty() - wanted.getToPurchaseQty()
                    - planned.getOrDefault(line.productId(), 0L);
            if (line.plannedQty() > remaining) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG202", parts.get(line.productId()).getSku(),
                        Math.max(remaining, 0));
            }
        }
        return request;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * UC-48: available minus expired, less what open sales orders hold (GVHD 20/11); the source's stock is only
     * checked here, never reserved.
     */
    private void checkSellable(AuthenticatedUser user, TransferOrderCreateRequest body, Map<Long, Product> parts) {
        Map<Long, Integer> sellable = inventory.availability(user, parts.keySet()).stream()
                .filter(row -> row.storeId() == body.sourceStoreId())
                .collect(Collectors.toMap(StockAvailabilityResponse::productId, StockAvailabilityResponse::sellableQty));
        for (TransferOrderCreateRequest.Line line : body.lines()) {
            int held = sellable.getOrDefault(line.productId(), 0);
            if (line.plannedQty() > held) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG201", parts.get(line.productId()).getSku(), held);
            }
        }
    }
}
