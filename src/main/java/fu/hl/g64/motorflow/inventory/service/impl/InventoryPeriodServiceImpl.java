package fu.hl.g64.motorflow.inventory.service.impl;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.catalog.service.PartCategoryService;
import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.dto.InventoryPeriodResponse;
import fu.hl.g64.motorflow.inventory.dto.PeriodBalanceResponse;
import fu.hl.g64.motorflow.inventory.dto.PeriodBalanceRow;
import fu.hl.g64.motorflow.inventory.entity.InventoryPeriod;
import fu.hl.g64.motorflow.inventory.entity.enums.InventoryPeriodStatus;
import fu.hl.g64.motorflow.inventory.helper.PeriodTotals;
import fu.hl.g64.motorflow.inventory.helper.PeriodTotals.Line;
import fu.hl.g64.motorflow.inventory.repository.InventoryPeriodRepository;
import fu.hl.g64.motorflow.inventory.service.InventoryPeriodService;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InventoryPeriodServiceImpl implements InventoryPeriodService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final InventoryPeriodRepository periods;
    private final PeriodTotals totals;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final PartCategoryService categories;
    private final UserAccountRepository users;

    @Override
    @Transactional(readOnly = true)
    public List<InventoryPeriodResponse> list() {
        List<InventoryPeriod> all = periods.findAll(Sort.by(Sort.Direction.DESC, "periodMonth"));
        Long nextToClose = nextToClose();
        Map<Long, String> closers = users.findAllById(all.stream().map(InventoryPeriod::getClosedBy)
                        .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName));
        return all.stream().map(p -> response(p, p.getId().equals(nextToClose), closers)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PeriodBalanceResponse balances(long periodId, Long storeId, String q, Long categoryId, int page, int size) {
        InventoryPeriod period = periods.findById(periodId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG293"));
        if (storeId != null && !stores.existsById(storeId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "MSG52");
        }
        List<Line> lines = totals.of(period, storeId == null ? null : List.of(storeId));

        Map<Long, Product> partsById = products.findAllById(lines.stream().map(Line::productId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, Store> storesById = stores.findAllById(lines.stream().map(Line::storeId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Store::getId, Function.identity()));
        Set<Long> inCategory = categoryId == null ? null : new HashSet<>(categories.withDescendants(categoryId));
        String text = StringUtils.hasText(q) ? q.strip().toLowerCase(Locale.ROOT) : null;

        List<PeriodBalanceRow> rows = lines.stream()
                .filter(l -> matches(partsById.get(l.productId()), text, inCategory))
                .map(l -> row(l, storesById.get(l.storeId()), partsById.get(l.productId())))
                .sorted(Comparator.comparing(PeriodBalanceRow::storeName).thenComparing(PeriodBalanceRow::sku))
                .toList();

        PeriodBalanceResponse.Totals sum = new PeriodBalanceResponse.Totals(
                rows.stream().mapToLong(PeriodBalanceRow::openingValue).sum(),
                rows.stream().mapToLong(PeriodBalanceRow::inValue).sum(),
                rows.stream().mapToLong(PeriodBalanceRow::outValue).sum(),
                rows.stream().mapToLong(PeriodBalanceRow::closingValue).sum(),
                rows.stream().mapToLong(PeriodBalanceRow::inTransitValue).sum());
        int pageNo = Math.max(page, 0);
        int pageSize = Math.clamp(size, 1, 100);
        List<PeriodBalanceRow> items = rows.stream().skip((long) pageNo * pageSize).limit(pageSize).toList();
        Long closer = period.getClosedBy();
        Map<Long, String> closers = closer == null ? Map.of()
                : users.findById(closer).map(u -> Map.of(u.getId(), u.getFullName())).orElse(Map.of());
        return new PeriodBalanceResponse(response(period, period.getId().equals(nextToClose()), closers),
                period.isOpen(), sum,
                new PageResponse<>(items, rows.size(), pageNo, pageSize));
    }

    private Long nextToClose() {
        return periods.findFirstByStatusOrderByPeriodMonthAsc(InventoryPeriodStatus.OPEN)
                .map(InventoryPeriod::getId).orElse(null);
    }

    private static boolean matches(Product part, String text, Set<Long> inCategory) {
        if (inCategory != null && !inCategory.contains(part.getCategoryId())) {
            return false;
        }
        return text == null || part.getSku().toLowerCase(Locale.ROOT).contains(text)
                || part.getPartName().toLowerCase(Locale.ROOT).contains(text);
    }

    private static PeriodBalanceRow row(Line l, Store store, Product part) {
        return new PeriodBalanceRow(l.storeId(), store.getStoreName(), l.productId(), part.getSku(),
                part.getPartName(), part.getBaseUnit(), l.opening().qty(), l.opening().value(), l.in().qty(),
                l.in().value(), l.out().qty(), l.out().value(), l.closing().qty(), l.closing().value(),
                l.inTransit().qty(), l.inTransit().value(), l.periodAvgUnitCost());
    }

    private static InventoryPeriodResponse response(InventoryPeriod p, boolean nextToClose, Map<Long, String> closers) {
        return new InventoryPeriodResponse(p.getId(), p.getPeriodMonth().format(MONTH), p.getFromDate(),
                p.getToDate(), p.getStatus(), p.getClosedBy(),
                p.getClosedBy() == null ? null : closers.get(p.getClosedBy()), p.getClosedAt(), nextToClose);
    }
}
