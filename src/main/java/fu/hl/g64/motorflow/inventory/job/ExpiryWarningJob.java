package fu.hl.g64.motorflow.inventory.job;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.AccountStatus;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.inventory.dto.ExpiringLotRow;
import fu.hl.g64.motorflow.inventory.helper.ExpiryWarning;
import fu.hl.g64.motorflow.inventory.repository.LotBalanceRepository;
import fu.hl.g64.motorflow.inventory.repository.LotRepository;
import fu.hl.g64.motorflow.operation.repository.NotificationRepository;
import fu.hl.g64.motorflow.operation.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JOB-03 (BR-76), just after midnight:
 * <ul>
 *   <li>lots in stock whose expiry date has passed become EXPIRED; their stock is not moved, only blocked from sale
 *       and transfer, and the Branch Managers of each warehouse still holding some are told once (LOT_EXPIRED);</li>
 *   <li>when the Owner has set the UC-104 warning days, the Branch Managers of each warehouse holding a lot that
 *       expires within them are told once per lot (NEAR_EXPIRY).</li>
 * </ul>
 * Each notification is about one lot (ref LOT), so it opens that lot (UC-98).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiryWarningJob {

    static final String NEAR_EXPIRY = "NEAR_EXPIRY";
    static final String LOT_EXPIRED = "LOT_EXPIRED";
    static final String REF_TYPE = "LOT";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final LotRepository lots;
    private final LotBalanceRepository lotBalances;
    private final ExpiryWarning expiryWarning;
    private final UserAccountRepository users;
    private final NotificationRepository notifications;
    private final NotificationService notificationService;
    private final Clock clock;

    public record Result(int markedExpired, int notificationsSent) {
    }

    @Scheduled(cron = "${motoflow.jobs.expiry.cron}", zone = "Asia/Ho_Chi_Minh")
    public void scheduled() {
        run();
    }

    @Transactional
    public Result run() {
        LocalDate today = LocalDate.now(clock);
        Map<Long, List<Long>> managersByStore = users.findByRoleAndStatus(Role.BRANCH_MANAGER, AccountStatus.ACTIVE)
                .stream().filter(m -> m.getStoreId() != null)
                .collect(Collectors.groupingBy(UserAccount::getStoreId,
                        Collectors.mapping(UserAccount::getId, Collectors.toList())));
        int sent = 0;

        List<Long> expiring = lots.findIdsToExpire(today);
        int marked = expiring.isEmpty() ? 0 : lots.markExpired(expiring);
        // Only lots that changed status in this run, so each is reported once.
        for (ExpiringLotRow row : expiring.isEmpty() ? List.<ExpiringLotRow>of() : lotBalances.findAvailableOf(expiring)) {
            String message = "Lô #%d (%s) đã hết hạn ngày %s, còn %d tại %s, không được bán hoặc chuyển kho."
                    .formatted(row.lotId(), row.sku(), DATE.format(row.expiryDate()), row.quantity(), row.storeName());
            for (long managerId : managersByStore.getOrDefault(row.storeId(), List.of())) {
                notificationService.notify(managerId, LOT_EXPIRED, message, REF_TYPE, row.lotId());
                sent++;
            }
        }

        Integer warnDays = expiryWarning.daysInEffect();
        List<Long> managerIds = managersByStore.values().stream().flatMap(List::stream).toList();
        if (warnDays != null && !managerIds.isEmpty()) {
            Set<String> told = notifications.findSent(NEAR_EXPIRY, REF_TYPE, managerIds, false).stream()
                    .map(n -> n.getRecipientId() + ":" + n.getRefId()).collect(Collectors.toSet());
            for (ExpiringLotRow row : lotBalances.findNearExpiry(today, today.plusDays(warnDays))) {
                String message = "Lô #%d (%s) sắp hết hạn ngày %s, còn %d tại %s."
                        .formatted(row.lotId(), row.sku(), DATE.format(row.expiryDate()), row.quantity(), row.storeName());
                for (long managerId : managersByStore.getOrDefault(row.storeId(), List.of())) {
                    if (told.add(managerId + ":" + row.lotId())) {
                        notificationService.notify(managerId, NEAR_EXPIRY, message, REF_TYPE, row.lotId());
                        sent++;
                    }
                }
            }
        }

        log.info("JOB-03 expiry: {} lots marked expired, warning days {}, {} notifications sent", marked, warnDays, sent);
        return new Result(marked, sent);
    }
}
