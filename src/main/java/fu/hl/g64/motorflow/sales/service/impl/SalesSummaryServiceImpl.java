package fu.hl.g64.motorflow.sales.service.impl;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.sales.dto.SalesSummaryResponse;
import fu.hl.g64.motorflow.sales.dto.SalesSummaryResponse.Figures;
import fu.hl.g64.motorflow.sales.dto.SalesSummaryResponse.StoreFigures;
import fu.hl.g64.motorflow.sales.repository.SalesOrderRepository;
import fu.hl.g64.motorflow.sales.service.SalesSummaryService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SalesSummaryServiceImpl implements SalesSummaryService {

    private static final long MAX_DAYS = 366;

    private final SalesOrderRepository orders;
    private final StoreRepository stores;
    private final Clock clock;

    @Transactional(readOnly = true)
    @Override
    public SalesSummaryResponse summary(AuthenticatedUser user, Long storeId, LocalDate from, LocalDate to) {
        if (user.role() != Role.OWNER) {
            if (storeId != null && !storeId.equals(user.storeId())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
            }
            storeId = user.storeId();
        } else if (storeId != null && !stores.existsById(storeId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "MSG52");
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate last = to != null ? to : (from != null && from.isAfter(today) ? from : today);
        LocalDate first = from != null ? from : last;
        if (first.isAfter(last) || ChronoUnit.DAYS.between(first, last) + 1 > MAX_DAYS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG264", MAX_DAYS);
        }
        LocalDateTime start = first.atStartOfDay();
        LocalDateTime end = last.plusDays(1).atStartOfDay();

        // [store id, completed orders, revenue, credit orders, credit sales, collected at sale]
        Map<Long, Object[]> sold = new HashMap<>();
        orders.sumCompletedByStore(storeId, start, end).forEach(row -> sold.put(number(row[0]), row));
        // [store id, debt collected]
        Map<Long, Long> debts = new HashMap<>();
        orders.sumDebtCollectedByStore(storeId, start, end).forEach(row -> debts.put(number(row[0]), number(row[1])));

        Set<Long> storeIds = new HashSet<>(sold.keySet());
        storeIds.addAll(debts.keySet());
        if (storeId != null) {
            storeIds.add(storeId);
        } else {
            // Every active store shows, even with nothing in the period.
            stores.findByStatusOrderByStoreNameAsc(StoreStatus.ACTIVE).forEach(store -> storeIds.add(store.getId()));
        }
        List<StoreFigures> rows = stores.findAllById(storeIds).stream()
                .sorted(Comparator.comparing(Store::getStoreName).thenComparing(Store::getId))
                .map(store -> new StoreFigures(store.getId(), store.getStoreName(),
                        figures(sold.get(store.getId()), debts.getOrDefault(store.getId(), 0L))))
                .toList();
        Figures total = rows.stream().map(StoreFigures::figures).reduce(Figures.ZERO, Figures::plus);
        return new SalesSummaryResponse(first, last, total, rows);
    }

    private static Figures figures(Object[] sold, long debtCollected) {
        if (sold == null) {
            return new Figures(0, 0, 0, 0, 0, debtCollected, debtCollected);
        }
        long collectedAtSale = number(sold[5]);
        return new Figures(number(sold[1]), number(sold[2]), collectedAtSale, number(sold[3]), number(sold[4]),
                debtCollected, collectedAtSale + debtCollected);
    }

    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }
}
