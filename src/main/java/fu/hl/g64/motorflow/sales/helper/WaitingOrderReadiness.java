package fu.hl.g64.motorflow.sales.helper;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.inventory.event.AvailableStockChanged;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import fu.hl.g64.motorflow.sales.entity.SalesOrder;
import fu.hl.g64.motorflow.sales.repository.SalesOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * GVHD 24/11 (A5): tells the store once when a waiting order can be released. Whatever changes what a store can sell
 * (a stock movement into or out of AVAILABLE, a new, changed, confirmed, released or cancelled sales order) marks the
 * store; after the transaction commits, the store's waiting orders are worked out again (BR-90, oldest first). An
 * order that became ready and was not told yet notifies the store's Branch Managers and the order's creator (MSG350)
 * and records {@code ready_notified_at}; an order no longer ready clears it, so it is told again next time. Done in a
 * transaction of its own, so it can neither slow down nor roll back the change that caused it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WaitingOrderReadiness {

    public static final String NOTIFICATION_TYPE = "WAITING_ORDER_READY";
    private static final String REF_TYPE = "SALES_ORDER";
    private static final Locale VIETNAMESE = Locale.forLanguageTag("vi");
    private static final Object STORES_KEY = WaitingOrderReadiness.class.getName() + ".stores";

    private final WaitingOrders waitingOrders;
    private final SalesOrderRepository salesOrders;
    private final NotificationService notifications;
    private final UserAccountRepository users;
    private final MessageSource messages;
    private final PlatformTransactionManager transactions;
    private final Clock clock;

    @EventListener
    public void on(AvailableStockChanged event) {
        storeChanged(event.storeId());
    }

    /** What {@code storeId} can sell changed: check its waiting orders once the current transaction commits. */
    @SuppressWarnings("unchecked")
    public void storeChanged(long storeId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            checkQuietly(storeId);
            return;
        }
        Set<Long> stores = (Set<Long>) TransactionSynchronizationManager.getResource(STORES_KEY);
        if (stores == null) {
            Set<Long> marked = new LinkedHashSet<>();
            TransactionSynchronizationManager.bindResource(STORES_KEY, marked);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    TransactionSynchronizationManager.unbindResourceIfPossible(STORES_KEY);
                    marked.forEach(WaitingOrderReadiness.this::checkQuietly);
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(STORES_KEY);
                }
            });
            stores = marked;
        }
        stores.add(storeId);
    }

    private void checkQuietly(long storeId) {
        try {
            TransactionTemplate own = new TransactionTemplate(transactions);
            own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            own.executeWithoutResult(status -> check(storeId));
        } catch (RuntimeException e) {
            // The change that caused the check is saved already; a missed notice is caught by the next check.
            log.warn("Could not check the waiting orders of store {}", storeId, e);
        }
    }

    /** Runs inside a transaction: the orders read are updated when it commits. */
    void check(long storeId) {
        LocalDateTime now = LocalDateTime.now(clock);
        WaitingOrders.Snapshot snapshot = waitingOrders.of(storeId, now.toLocalDate());
        Map<Long, SalesOrder> orders = new LinkedHashMap<>();
        snapshot.lines().forEach(line -> orders.putIfAbsent(line.order().getId(), line.order()));
        for (SalesOrder order : orders.values()) {
            boolean ready = snapshot.ready(order.getId());
            if (ready && order.getReadyNotifiedAt() == null) {
                // Review 25/11 (R-07): a conditional update, so two checks at once tell the store only once.
                if (salesOrders.markReadyNotified(order.getId(), now) == 1) {
                    order.readyNotified(now);
                    tell(order);
                }
            } else if (!ready && order.getReadyNotifiedAt() != null) {
                order.notReady();
            }
        }
    }

    private void tell(SalesOrder order) {
        String customer = order.getCustomerNameSnapshot() == null ? "" : order.getCustomerNameSnapshot();
        String message = messages.getMessage("MSG350", new Object[]{String.valueOf(order.getId()), customer},
                "MSG350", VIETNAMESE);
        notifications.notifyStoreManagers(order.getStoreId(), NOTIFICATION_TYPE, message, REF_TYPE, order.getId());
        UserAccount creator = users.findById(order.getCreatedBy()).orElse(null);
        boolean toldAlready = creator != null && creator.getRole() == Role.BRANCH_MANAGER
                && order.getStoreId().equals(creator.getStoreId());
        if (creator != null && !toldAlready) {
            notifications.notify(creator.getId(), NOTIFICATION_TYPE, message, REF_TYPE, order.getId());
        }
    }
}
