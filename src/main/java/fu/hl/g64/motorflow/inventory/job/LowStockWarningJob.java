package fu.hl.g64.motorflow.inventory.job;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.AccountStatus;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.inventory.dto.LowStockRow;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.operation.repository.NotificationRepository;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JOB-02: every morning, tells the Branch Managers of a store which parts fell below their low-stock threshold
 * (UC-51). One notification per part, opening that part. A manager is not told again about a part while the
 * previous notification about it is still unread.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LowStockWarningJob {

    static final String TYPE = "LOW_STOCK";
    static final String REF_TYPE = "PRODUCT";

    private final StockBalanceRepository stockBalances;
    private final UserAccountRepository users;
    private final NotificationRepository notifications;
    private final NotificationService notificationService;

    @Scheduled(cron = "${motoflow.jobs.low-stock.cron}", zone = "Asia/Ho_Chi_Minh")
    public void scheduled() {
        run();
    }

    /** @return how many notifications were sent */
    @Transactional
    public int run() {
        List<LowStockRow> rows = stockBalances.findBelowThreshold();
        Map<Long, List<Long>> managersByStore = users.findByRoleAndStatus(Role.BRANCH_MANAGER, AccountStatus.ACTIVE)
                .stream().filter(m -> m.getStoreId() != null)
                .collect(Collectors.groupingBy(UserAccount::getStoreId,
                        Collectors.mapping(UserAccount::getId, Collectors.toList())));
        List<Long> managerIds = managersByStore.values().stream().flatMap(List::stream).toList();
        Set<String> unread = managerIds.isEmpty() ? Set.of()
                : notifications.findSent(TYPE, REF_TYPE, managerIds, true).stream()
                .map(n -> key(n.getRecipientId(), n.getRefId())).collect(Collectors.toSet());

        int sent = 0;
        for (LowStockRow row : rows) {
            String message = "Phụ tùng %s - %s dưới ngưỡng tồn tối thiểu: còn %d, ngưỡng %d."
                    .formatted(row.sku(), row.partName(), row.availableQty(), row.lowStockThreshold());
            for (long managerId : managersByStore.getOrDefault(row.storeId(), List.of())) {
                if (!unread.contains(key(managerId, row.productId()))) {
                    notificationService.notify(managerId, TYPE, message, REF_TYPE, row.productId());
                    sent++;
                }
            }
        }
        log.info("JOB-02 low stock: {} parts below threshold in {} stores, {} notifications sent", rows.size(),
                rows.stream().map(LowStockRow::storeId).distinct().count(), sent);
        return sent;
    }

    private static String key(Long recipientId, Long refId) {
        return recipientId + ":" + refId;
    }
}
