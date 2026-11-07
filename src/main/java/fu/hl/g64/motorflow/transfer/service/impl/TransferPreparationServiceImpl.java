package fu.hl.g64.motorflow.transfer.service.impl;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.audit.AuditLog;
import fu.hl.g64.motorflow.common.audit.AuditLogRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.operation.entity.WarehouseTask;
import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import fu.hl.g64.motorflow.operation.repository.WarehouseTaskRepository;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.operation.service.WarehouseTaskService;
import fu.hl.g64.motorflow.sales.helper.PickingGuide;
import fu.hl.g64.motorflow.sales.helper.SalesOrderHistory;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import fu.hl.g64.motorflow.transfer.dto.PreparedLinesRequest;
import fu.hl.g64.motorflow.transfer.dto.TransferPreparationResponse;
import fu.hl.g64.motorflow.transfer.dto.TransferPreparationResponse.Preparer;
import fu.hl.g64.motorflow.transfer.entity.TransferOrder;
import fu.hl.g64.motorflow.transfer.entity.TransferOrderLine;
import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;
import fu.hl.g64.motorflow.transfer.repository.TransferPreparationRepository;
import fu.hl.g64.motorflow.transfer.service.TransferPreparationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TransferPreparationServiceImpl implements TransferPreparationService {

    /** audit_log.entity_type and notification.ref_type of a transfer order. */
    private static final String ENTITY = "TRANSFER_ORDER";
    public static final String PREPARED = "TRANSFER_ORDER_DISPATCH_PREPARED";

    private final TransferPreparationRepository transfers;
    private final ProductRepository products;
    private final StoreRepository stores;
    private final UserAccountRepository users;
    private final AuditLogRepository auditLog;
    private final WarehouseTaskRepository tasks;
    private final WarehouseTaskService warehouseTasks;
    private final NotificationService notifications;
    private final PickingGuide guide;
    private final Clock clock;

    @Transactional(readOnly = true)
    @Override
    public TransferPreparationResponse preparation(AuthenticatedUser user, long transferOrderId) {
        TransferOrder order = transfers.findById(transferOrderId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG175"));
        Optional<WarehouseTask> holder = holder(order.getId());
        boolean manager = user.role() == Role.OWNER || (user.role() == Role.BRANCH_MANAGER
                && Objects.equals(user.storeId(), order.getSourceStoreId()));
        boolean preparing = holder.filter(task -> task.getAssignedTo() == user.id()
                && task.getStatus() != WarehouseTaskStatus.DONE).isPresent();
        if (!manager && !preparing) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
        }
        return view(user, order, holder);
    }

    @Transactional
    @Override
    public TransferPreparationResponse recordPrepared(AuthenticatedUser preparer, long transferOrderId,
                                                      PreparedLinesRequest request) {
        TransferOrder order = transfers.findByIdForUpdate(transferOrderId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG175"));
        if (order.getStatus() != TransferOrderStatus.AWAITING_DISPATCH) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG29");
        }
        long id = order.getId();
        boolean correcting = tasks.findOpenForUpdate(TaskType.DISPATCH_PREPARATION, id)
                .filter(task -> task.getStatus() == WarehouseTaskStatus.RESULT_SUBMITTED).isPresent();
        if (correcting) {
            warehouseTasks.reopenOwnResult(preparer, TaskType.DISPATCH_PREPARATION, id);
        } else {
            warehouseTasks.requireAssignee(preparer, TaskType.DISPATCH_PREPARATION, id);
        }
        List<TransferOrderLine> lines = transfers.findLines(id);
        Map<Long, Product> parts = byId(products.findAllById(lines.stream().map(TransferOrderLine::getProductId)
                .toList()));
        Map<Long, TransferOrderLine> byProduct = lines.stream()
                .collect(Collectors.toMap(TransferOrderLine::getProductId, Function.identity()));
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < request.lines().size(); i++) {
            Long productId = request.lines().get(i).productId();
            if (!byProduct.containsKey(productId) || !seen.add(productId)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG166", i + 1);
            }
        }
        String missing = lines.stream().filter(line -> !seen.contains(line.getProductId()))
                .map(line -> parts.get(line.getProductId()).getSku()).collect(Collectors.joining(", "));
        if (!missing.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG167", missing);
        }
        for (PreparedLinesRequest.Line line : request.lines()) {
            TransferOrderLine target = byProduct.get(line.productId());
            if (line.preparedQty() > target.getPlannedQty()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG176", parts.get(line.productId()).getSku(),
                        target.getPlannedQty());
            }
        }
        List<Map<String, Object>> recorded = new ArrayList<>();
        for (PreparedLinesRequest.Line line : request.lines()) {
            TransferOrderLine target = byProduct.get(line.productId());
            target.recordPrepared(line.preparedQty());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("product_id", line.productId());
            row.put("planned_qty", target.getPlannedQty());
            row.put("prepared_qty", line.preparedQty());
            row.put("short_reason", line.preparedQty() < target.getPlannedQty() && StringUtils.hasText(
                    line.shortReason()) ? line.shortReason().strip() : null);
            recorded.add(row);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        warehouseTasks.submitResult(preparer, TaskType.DISPATCH_PREPARATION, id);
        auditLog.save(AuditLog.record(ENTITY, id, PREPARED, preparer.id(), null, null, Map.of("lines", recorded),
                now));
        notifications.notifyStoreManagers(order.getSourceStoreId(), "TRANSFER_DISPATCH_PREPARED",
                "Phiếu chuyển kho #%d đã chuẩn bị hàng xong, đang chờ xác nhận xuất.".formatted(id), ENTITY, id);
        return view(preparer, order, holder(id));
    }

    /** The task of whoever prepares or prepared the dispatch, if someone was given it. */
    private Optional<WarehouseTask> holder(long transferOrderId) {
        return tasks.findHistory(TaskType.DISPATCH_PREPARATION, transferOrderId).stream()
                .filter(task -> task.getStatus() != WarehouseTaskStatus.CANCELLED).findFirst();
    }

    private TransferPreparationResponse view(AuthenticatedUser user, TransferOrder order,
                                             Optional<WarehouseTask> holder) {
        long id = order.getId();
        List<TransferOrderLine> lines = transfers.findLines(id);
        Set<Long> productIds = lines.stream().map(TransferOrderLine::getProductId).collect(Collectors.toSet());
        Map<Long, Product> parts = byId(products.findAllById(productIds));
        boolean open = order.getStatus() == TransferOrderStatus.AWAITING_DISPATCH;
        Map<Long, PickingGuide.Part> stock = open
                ? guide.parts(order.getSourceStoreId(), productIds, LocalDate.now(clock)) : Map.of();
        Map<Long, String> reasons = new HashMap<>();
        auditLog.findByEntityTypeAndEntityIdAndActionInOrderByOccurredAtDescIdDesc(ENTITY, id, List.of(PREPARED))
                .stream().findFirst().ifPresent(step -> SalesOrderHistory.lines(step.getNewData()).forEach(row -> {
                    if (row.get("short_reason") != null) {
                        reasons.put(SalesOrderHistory.number(row.get("product_id")).longValue(),
                                (String) row.get("short_reason"));
                    }
                }));
        List<TransferPreparationResponse.Line> rows = lines.stream().map(line -> {
            Product part = parts.get(line.getProductId());
            PickingGuide.Part held = stock.get(line.getProductId());
            int needed = line.getPreparedQty() != null ? line.getPreparedQty() : line.getPlannedQty();
            Integer shortQty = line.getPreparedQty() == null ? null : line.getPlannedQty() - line.getPreparedQty();
            return new TransferPreparationResponse.Line(line.getId(), part.getId(), part.getSku(), part.getPartName(),
                    part.getBaseUnit(), held == null ? List.of() : held.barcodes(),
                    held == null ? null : held.storageArea(), held == null ? null : held.binLocation(),
                    line.getPlannedQty(), line.getPreparedQty(), shortQty,
                    shortQty != null && shortQty > 0 ? reasons.get(line.getProductId()) : null,
                    held == null ? 0 : held.sellableQty(), held == null ? List.of() : held.pickFirst(needed));
        }).toList();
        Map<Long, String> names = users.findAllById(holder.map(task -> List.of(task.getAssignedTo()))
                .orElse(List.of())).stream().collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName));
        Preparer preparer = holder.map(task -> new Preparer(task.getAssignedTo(), names.get(task.getAssignedTo()),
                task.getStatus())).orElse(null);
        boolean canRecord = open && holder.filter(task -> task.getAssignedTo() == user.id()
                && (task.getStatus() == WarehouseTaskStatus.ASSIGNED
                || task.getStatus() == WarehouseTaskStatus.RESULT_SUBMITTED)).isPresent();
        Map<Long, String> storeNames = stores.findAllById(List.of(order.getSourceStoreId(), order.getDestStoreId()))
                .stream().collect(Collectors.toMap(Store::getId, Store::getStoreName));
        return new TransferPreparationResponse(id, order.getStatus(), order.getSourceStoreId(),
                storeNames.get(order.getSourceStoreId()), order.getDestStoreId(), storeNames.get(order.getDestStoreId()),
                preparer, canRecord, rows);
    }

    private static Map<Long, Product> byId(List<Product> rows) {
        return rows.stream().collect(Collectors.toMap(Product::getId, Function.identity()));
    }
}
