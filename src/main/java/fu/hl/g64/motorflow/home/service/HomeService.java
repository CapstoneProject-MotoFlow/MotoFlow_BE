package fu.hl.g64.motorflow.home.service;

import fu.hl.g64.motorflow.account.entity.enums.AccountStatus;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.adjustment.entity.enums.AdjustmentRequestStatus;
import fu.hl.g64.motorflow.adjustment.repository.AdjustmentRequestRepository;
import fu.hl.g64.motorflow.discrepancy.repository.DiscrepancyCaseRepository;
import fu.hl.g64.motorflow.home.dto.HomeResponse;
import fu.hl.g64.motorflow.home.dto.HomeShortcut;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;
import fu.hl.g64.motorflow.inventory.repository.StockCountRepository;
import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import fu.hl.g64.motorflow.operation.repository.NotificationRepository;
import fu.hl.g64.motorflow.operation.repository.WarehouseTaskRepository;
import fu.hl.g64.motorflow.receivable.repository.PaymentRepository;
import fu.hl.g64.motorflow.receiving.entity.enums.GoodsReceiptStatus;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptLineRepository;
import fu.hl.g64.motorflow.receiving.repository.GoodsReceiptRepository;
import fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus;
import fu.hl.g64.motorflow.sales.repository.SalesOrderRepository;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.entity.Warehouse;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import fu.hl.g64.motorflow.store.repository.WarehouseRepository;
import fu.hl.g64.motorflow.transfer.entity.enums.ReplenishmentRequestStatus;
import fu.hl.g64.motorflow.transfer.entity.enums.TransferOrderStatus;
import fu.hl.g64.motorflow.transfer.repository.ReplenishmentRequestRepository;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** SCR-03 Home, opened after sign-in (UC-01 step 7): unread notifications and what waits on the user, by role. */
@Service
@RequiredArgsConstructor
public class HomeService {

    private final NotificationRepository notifications;
    private final UserAccountRepository users;
    private final StoreRepository stores;
    private final WarehouseRepository warehouses;
    private final AdjustmentRequestRepository adjustments;
    private final GoodsReceiptRepository receipts;
    private final GoodsReceiptLineRepository receiptLines;
    private final StockCountRepository stockCounts;
    private final ReplenishmentRequestRepository replenishments;
    private final TransferOrderRepository transfers;
    private final DiscrepancyCaseRepository discrepancies;
    private final SalesOrderRepository salesOrders;
    private final PaymentRepository payments;
    private final WarehouseTaskRepository tasks;

    @Transactional(readOnly = true)
    public HomeResponse home(AuthenticatedUser user) {
        Store store = user.storeId() == null ? null : stores.findById(user.storeId()).orElse(null);
        return new HomeResponse(user.role(), user.storeId(), store == null ? null : store.getStoreName(),
                switch (user.role()) {
                    case ADMIN -> null;
                    case OWNER, BRANCH_MANAGER, STAFF -> new HomeResponse.Notifications(
                            notifications.countByRecipientIdAndIsReadFalse(user.id()),
                            notifications.findTop5ByRecipientIdAndIsReadFalseOrderByCreatedAtDescIdDesc(user.id()).stream()
                                    .map(HomeResponse.NotificationItem::from).toList());
                },
                shortcuts(user).entrySet().stream()
                        .map(entry -> new HomeResponse.Shortcut(entry.getKey(), entry.getValue())).toList());
    }

    /** In the order of {@link HomeShortcut}. */
    private Map<HomeShortcut, Long> shortcuts(AuthenticatedUser user) {
        Map<HomeShortcut, Long> counts = new EnumMap<>(HomeShortcut.class);
        switch (user.role()) {
            case ADMIN -> {
                counts.put(HomeShortcut.LOCKED_ACCOUNTS, users.countByStatus(AccountStatus.LOCKED));
                counts.put(HomeShortcut.TEMPORARY_PASSWORD_ACCOUNTS,
                        users.countByStatusAndMustChangePasswordTrue(AccountStatus.ACTIVE));
                counts.put(HomeShortcut.PLANNED_STORES, stores.countByStatus(StoreStatus.PLANNED));
            }
            case OWNER -> {
                counts.put(HomeShortcut.ADJUSTMENTS_AWAITING_APPROVAL,
                        adjustments.countByStatus(AdjustmentRequestStatus.PENDING));
                counts.put(HomeShortcut.EXCESS_AWAITING_DECISION, receiptLines.countReceiptsAwaitingExcessDecision());
                counts.put(HomeShortcut.STOCK_COUNTS_AWAITING_APPROVAL,
                        stockCounts.countByStatus(StockCountStatus.AWAITING_APPROVAL));
                counts.put(HomeShortcut.REPLENISHMENTS_AWAITING_COORDINATION,
                        replenishments.countByStatus(ReplenishmentRequestStatus.AWAITING_COORDINATION));
            }
            case BRANCH_MANAGER -> branchManagerShortcuts(user.storeId(), counts);
            case STAFF -> {
                Map<TaskType, Long> byType = new EnumMap<>(TaskType.class);
                tasks.countAssignedByType(user.id()).forEach(row -> byType.put((TaskType) row[0], (Long) row[1]));
                counts.put(HomeShortcut.PICKING_TASKS, byType.getOrDefault(TaskType.PICKING, 0L));
                counts.put(HomeShortcut.INSPECTION_TASKS, byType.getOrDefault(TaskType.INSPECTION, 0L));
                counts.put(HomeShortcut.DISPATCH_PREPARATION_TASKS, byType.getOrDefault(TaskType.DISPATCH_PREPARATION, 0L));
                counts.put(HomeShortcut.COUNTING_TASKS, byType.getOrDefault(TaskType.COUNTING, 0L));
            }
        }
        return counts;
    }

    private void branchManagerShortcuts(long storeId, Map<HomeShortcut, Long> counts) {
        counts.put(HomeShortcut.ORDERS_TO_ASSIGN, salesOrders.countAwaitingPickingWithoutTask(storeId));
        counts.put(HomeShortcut.ORDERS_AWAITING_CONFIRMATION,
                salesOrders.countByStoreIdAndStatus(storeId, SalesOrderStatus.PICKED));
        counts.put(HomeShortcut.ORDERS_AWAITING_COMPLETION,
                salesOrders.countByStoreIdAndStatus(storeId, SalesOrderStatus.CONFIRMED));
        counts.put(HomeShortcut.PAYMENTS_AWAITING_VERIFICATION, payments.countUnverifiedAtStore(storeId));

        Warehouse warehouse = warehouses.findByStoreId(storeId).orElse(null);
        if (warehouse == null) {
            // A store created before BR-04 was enforced may still have no warehouse: nothing to receive or send.
            List.of(HomeShortcut.RECEIPTS_TO_ASSIGN, HomeShortcut.RECEIPTS_AWAITING_CONFIRMATION,
                            HomeShortcut.TRANSFERS_AWAITING_DISPATCH, HomeShortcut.OPEN_DISCREPANCIES)
                    .forEach(key -> counts.put(key, 0L));
            return;
        }
        long warehouseId = warehouse.getId();
        counts.put(HomeShortcut.RECEIPTS_TO_ASSIGN, receipts.countAwaitingInspectionWithoutTask(warehouseId));
        counts.put(HomeShortcut.RECEIPTS_AWAITING_CONFIRMATION,
                receipts.countAtWarehouse(warehouseId, GoodsReceiptStatus.AWAITING_CONFIRMATION));
        counts.put(HomeShortcut.TRANSFERS_AWAITING_DISPATCH,
                transfers.countBySourceWarehouseIdAndStatus(warehouseId, TransferOrderStatus.AWAITING_DISPATCH));
        counts.put(HomeShortcut.OPEN_DISCREPANCIES, discrepancies.countOpenSupplierCasesAtWarehouse(warehouseId));
        if (warehouse.isCentral()) {
            // The central-warehouse Branch Manager coordinates replenishment with Owner (BR-56).
            counts.put(HomeShortcut.REPLENISHMENTS_AWAITING_COORDINATION,
                    replenishments.countByStatus(ReplenishmentRequestStatus.AWAITING_COORDINATION));
        }
    }
}
