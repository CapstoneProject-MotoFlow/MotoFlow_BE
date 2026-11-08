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
import fu.hl.g64.motorflow.inventory.dto.StockAvailabilityResponse;
import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
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
import fu.hl.g64.motorflow.transfer.repository.ReplenishmentRequestLineRepository;
import fu.hl.g64.motorflow.transfer.repository.ReplenishmentRequestRepository;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderLineRepository;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderRepository;
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
        body.lines().stream().filter(line -> line.makeupForCaseId() != null)
                .forEach(line -> checkMakeup(line, body.destStoreId(), parts.get(line.productId())));
        ReplenishmentRequest request = body.replenishmentRequestId() == null ? null : coveredRequest(body, parts);
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
        boolean canConfirm = ready && sourceManager && preparer.get().getAssignedTo() != user.id();
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
        List<Map<String, Object>> recorded = new ArrayList<>();
        for (TransferOrderLine line : orderLines) {
            int quantity = sent.get(line.getProductId());
            if (quantity > 0) {
                posting.move(new MovePosting(line.getProductId(), order.getSourceStoreId(), StockStatus.AVAILABLE,
                        order.getDestStoreId(), StockStatus.IN_TRANSIT, quantity, null, MovementType.TRANSFER_DISPATCH,
                        SourceRef.transferOrder(id), user.id(), null));
            }
            line.recordDispatched(quantity);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("product_id", line.getProductId());
            row.put("prepared_qty", line.getPreparedQty());
            row.put("dispatched_qty", quantity);
            recorded.add(row);
        }
        order.dispatch(user.id(), now);
        auditLog.save(AuditLog.record(ENTITY, id, "TRANSFER_ORDER_DISPATCHED", user.id(), null, null,
                Map.of("lines", recorded), now));
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
                line -> Math.max(figures.get(line.getProductId()).extraReceived() - line.getExtraDispatchedQty(), 0)));
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
                user.id());
        if (order.getCreatedBy() != user.id()) {
            notifications.notify(order.getCreatedBy(), "TRANSFER_EXTRA_DISPATCH_CONFIRMED",
                    "Phiếu chuyển kho #%d: kho nguồn đã xác nhận phần xuất thêm, có thể đối soát để đóng phiếu"
                            .formatted(id), ENTITY, id);
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
        Map<String, String> blocked = order.getStatus() == TransferOrderStatus.RECONCILING
                ? blockers(rows, lines.openReceiptQty(id)) : Map.of();
        return new ClosingPreviewResponse(id, order.getStatus(),
                order.getStatus() == TransferOrderStatus.RECONCILING && blocked.isEmpty(),
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
        Map<String, String> blocked = blockers(rows, lines.openReceiptQty(id));
        if (!blocked.isEmpty()) {
            Map.Entry<String, String> first = blocked.entrySet().iterator().next();
            throw new BusinessException(HttpStatus.CONFLICT, first.getKey(), first.getValue());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<Map<String, Object>> posted = new ArrayList<>();
        // BR-68: the confirmed excess leaves the source's available stock for the destination's, FEFO then FIFO.
        for (ClosingPreviewResponse.Line row : rows) {
            if (row.extraDispatchedQty() > 0) {
                posting.move(new MovePosting(row.productId(), order.getSourceStoreId(), StockStatus.AVAILABLE,
                        order.getDestStoreId(), StockStatus.AVAILABLE, row.extraDispatchedQty(), null,
                        MovementType.TRANSFER_EXTRA_DISPATCH, SourceRef.transferOrder(id), user.id(), null));
                posted.add(Map.of("product_id", row.productId(), "extra_qty", row.extraDispatchedQty()));
            }
        }
        order.close(user.id(), now);
        auditLog.save(AuditLog.record(ENTITY, id, "TRANSFER_ORDER_CLOSED", user.id(), null, null,
                Map.of("extra_dispatch", posted), now));
        String message = "Phiếu chuyển kho #%d đã đóng".formatted(id);
        notifications.notifyStoreManagers(order.getSourceStoreId(), "TRANSFER_CLOSED", message, ENTITY, id);
        notifications.notifyStoreManagers(order.getDestStoreId(), "TRANSFER_CLOSED", message, ENTITY, id);
        if (order.getReplenishmentRequestId() != null) {
            closeRequestIfDone(user, order.getReplenishmentRequestId(), now);
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
                    figure.dispatched(), figure.received(), figure.returned(), figure.loss(), figure.unresolved(),
                    figure.extraReceived(), line.getExtraDispatchedQty(), held == null ? 0 : held.sellableQty());
        }).toList();
    }

    /** UC-77 step 2: each reason the order cannot close, with the parts it concerns, in the order checked. */
    private static Map<String, String> blockers(List<ClosingPreviewResponse.Line> rows,
                                                List<TransferOrderLineRepository.ProductQty> open) {
        Map<String, String> blocked = new LinkedHashMap<>();
        String unresolved = rows.stream().filter(row -> row.unresolvedQty() > 0)
                .map(row -> row.sku() + " x" + row.unresolvedQty()).collect(Collectors.joining(", "));
        if (!unresolved.isEmpty()) {
            blocked.put("MSG221", unresolved);
        }
        String unconfirmed = rows.stream().filter(row -> row.extraReceivedQty() > row.extraDispatchedQty())
                .map(row -> row.sku() + " x" + (row.extraReceivedQty() - row.extraDispatchedQty()))
                .collect(Collectors.joining(", "));
        if (!unconfirmed.isEmpty()) {
            blocked.put("MSG222", unconfirmed);
        }
        String shortAtSource = rows.stream().filter(row -> row.extraDispatchedQty() > row.sourceSellableQty())
                .map(row -> row.sku() + " cần " + row.extraDispatchedQty() + ", còn " + row.sourceSellableQty())
                .collect(Collectors.joining(", "));
        if (!shortAtSource.isEmpty()) {
            blocked.put("MSG223", shortAtSource);
        }
        // A receipt is only ever opened with lines expecting at least one unit, so any open receipt shows up here.
        Map<Long, Long> expected = open.stream().collect(Collectors.toMap(
                TransferOrderLineRepository.ProductQty::getProductId, TransferOrderLineRepository.ProductQty::getQty));
        String opened = rows.stream().filter(row -> expected.getOrDefault(row.productId(), 0L) > 0)
                .map(row -> row.sku() + " x" + expected.get(row.productId())).collect(Collectors.joining(", "));
        if (!opened.isEmpty()) {
            blocked.put("MSG225", opened);
        }
        return blocked;
    }

    /** UC-77 step 6: all the request's orders are closed and each part was received or passed to purchasing. */
    private void closeRequestIfDone(AuthenticatedUser user, long requestId, LocalDateTime now) {
        ReplenishmentRequest request = requests.findByIdForUpdate(requestId).orElseThrow();
        if (request.getStatus() == ReplenishmentRequestStatus.CLOSED
                || orders.findLinkedOrders(requestId).stream()
                .anyMatch(row -> row.getTransferOrder().getStatus() != TransferOrderStatus.CLOSED)) {
            return;
        }
        Map<Long, Long> fulfilled = reconciliation.fulfilled(requestId);
        boolean done = requestLines.findByReplenishmentRequestIdOrderById(requestId).stream().allMatch(line ->
                fulfilled.getOrDefault(line.getProductId(), 0L) + line.getToPurchaseQty() >= line.getRequestedQty());
        if (!done) {
            return;
        }
        request.close(now);
        auditLog.save(AuditLog.record("REPLENISHMENT_REQUEST", requestId, "REPLENISHMENT_REQUEST_CLOSED", user.id(),
                null, null, Map.of("transfer_order_closed", true), now));
        if (request.getCreatedBy() != user.id()) {
            notifications.notify(request.getCreatedBy(), "REPLENISHMENT_CLOSED",
                    "Yêu cầu bổ sung #%d đã hoàn tất".formatted(requestId), "REPLENISHMENT_REQUEST", requestId);
        }
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
                        order.getExtraDispatchConfirmedBy(), order.getClosedBy()).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName));
        List<TransferOrderDetailResponse.Discrepancy> discrepancies = staff ? List.of() : discrepancies(id);
        return new TransferOrderDetailResponse(id, order.getStatus(), order.getReplenishmentRequestId(),
                order.getSourceStoreId(), storeNames.get(order.getSourceStoreId()), order.getDestStoreId(),
                storeNames.get(order.getDestStoreId()),
                person(order.getCreatedBy(), names), order.getCreatedAt(),
                person(order.getDispatchedBy(), names), order.getDispatchedAt(),
                person(order.getExtraDispatchConfirmedBy(), names), order.getExtraDispatchConfirmedAt(),
                person(order.getClosedBy(), names), order.getClosedAt(),
                orderLines.stream().map(line -> {
                    Product part = parts.get(line.getProductId());
                    TransferReconciliation.Part figure = figures.get(line.getProductId());
                    return new TransferOrderDetailResponse.Line(part.getId(), part.getSku(), part.getPartName(),
                            part.getBaseUnit(), line.getPlannedQty(), line.getPreparedQty(), line.getDispatchedQty(),
                            staff ? null : figure.received(), staff ? null : figure.returned(),
                            staff ? null : figure.loss(), staff ? null : figure.extraReceived(),
                            staff ? null : line.getExtraDispatchedQty(), staff ? null : figure.unresolved(),
                            line.getMakeupForCaseId());
                }).toList(),
                orders.findDispatchedLots(id).stream().map(lot -> new TransferOrderDetailResponse.Lot(
                        lot.getProductId(), lot.getLotId(), lot.getManufacturerLotNo(), lot.getExpiryDate(),
                        lot.getQuantity(), owner ? lot.getUnitCost() : null)).toList(),
                orders.findReceipts(id).stream().map(receipt -> new TransferOrderDetailResponse.Receipt(
                        receipt.getId(), receipt.getReceiptType(), receipt.getStatus(), receipt.getArrivedAt(),
                        receipt.getConfirmedAt())).toList(),
                discrepancies, staff ? List.of() : attachments.list(OwnerType.TRANSFER_ORDER, id));
    }

    private List<TransferOrderDetailResponse.Discrepancy> discrepancies(long transferOrderId) {
        List<DiscrepancyCase> found = orders.findCases(transferOrderId);
        if (found.isEmpty()) {
            return List.of();
        }
        Map<Long, List<DiscrepancyResolutionEvent>> events = orders.findEvents(found.stream()
                .map(DiscrepancyCase::getId).toList()).stream()
                .collect(Collectors.groupingBy(DiscrepancyResolutionEvent::getDiscrepancyCaseId));
        return found.stream().map(shortage -> new TransferOrderDetailResponse.Discrepancy(shortage.getId(),
                shortage.getProductId(), shortage.getShortQty(), shortage.getStatus(), shortage.getOpenedAt(),
                shortage.getClosedAt(), events.getOrDefault(shortage.getId(), List.of()).stream()
                        .map(event -> new TransferOrderDetailResponse.Event(event.getId(), event.getResolutionType(),
                                event.getQuantity(), event.getGoodsReceiptNoteId(), event.getAdjustmentRequestId(),
                                event.getReason(), event.getOccurredAt(), event.getConfirmedAt())).toList()))
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
    private void checkMakeup(TransferOrderCreateRequest.Line line, long destStoreId, Product part) {
        boolean valid = cases.findById(line.makeupForCaseId())
                .filter(found -> found.getSourceType() == SourceType.TRANSFER && found.isOpen()
                        && found.getProductId().equals(line.productId()) && line.plannedQty() <= found.getShortQty())
                .map(DiscrepancyCase::getTransferOrderId).flatMap(orders::findById)
                .filter(original -> original.getDestStoreId().equals(destStoreId)).isPresent();
        if (!valid) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG206", part.getSku());
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

    /** UC-48: available minus expired; the source's stock is only checked here, never reserved. */
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
