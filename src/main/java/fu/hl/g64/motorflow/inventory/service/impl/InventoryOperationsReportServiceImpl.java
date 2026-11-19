package fu.hl.g64.motorflow.inventory.service.impl;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.catalog.service.PartCategoryService;
import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.common.text.SearchText;
import fu.hl.g64.motorflow.inventory.dto.InventoryOperationsResponse;
import fu.hl.g64.motorflow.inventory.dto.InventoryOperationsRow;
import fu.hl.g64.motorflow.inventory.dto.InventoryOperationsRow.SourceFlow;
import fu.hl.g64.motorflow.inventory.dto.StockSource;
import fu.hl.g64.motorflow.inventory.entity.InventoryPeriod;
import fu.hl.g64.motorflow.inventory.entity.enums.InventoryPeriodStatus;
import fu.hl.g64.motorflow.inventory.helper.PeriodTotals;
import fu.hl.g64.motorflow.inventory.helper.PeriodTotals.Amount;
import fu.hl.g64.motorflow.inventory.helper.PeriodTotals.Line;
import fu.hl.g64.motorflow.inventory.repository.InventoryPeriodRepository;
import fu.hl.g64.motorflow.inventory.service.InventoryOperationsReportService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InventoryOperationsReportServiceImpl implements InventoryOperationsReportService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final int MAX_DAYS = 366;

    private final PeriodTotals totals;
    private final InventoryPeriodRepository periods;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final PartCategoryService categories;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public InventoryOperationsResponse report(AuthenticatedUser user, YearMonth month, LocalDate from, LocalDate to,
                                              Long storeId, String q, Long categoryId, int page, int size) {
        boolean byDates = from != null || to != null;
        // A month or both dates, never a mix and never one date alone.
        if (byDates && (month != null || from == null || to == null)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG02");
        }
        if (byDates && (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG264", MAX_DAYS);
        }
        boolean owner = user.role() == Role.OWNER;
        if (!owner) {
            if (user.storeId() == null || storeId != null && !storeId.equals(user.storeId())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
            }
            storeId = user.storeId();
        } else if (storeId != null && !stores.existsById(storeId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "MSG52");
        }
        List<Long> storeIds = storeId == null ? null : List.of(storeId);
        LocalDate first = byDates ? from : (month != null ? month : YearMonth.now(clock)).atDay(1);
        LocalDate last = byDates ? to : first.plusMonths(1).minusDays(1);
        InventoryPeriodStatus status = byDates ? null
                : periods.findByPeriodMonth(first).map(InventoryPeriod::getStatus).orElse(null);
        boolean provisional = first.withDayOfMonth(1).datesUntil(last.plusDays(1), Period.ofMonths(1))
                .anyMatch(m -> periods.findByPeriodMonth(m).filter(p -> !p.isOpen()).isEmpty());
        List<Line> lines = byDates ? totals.between(from, to, storeIds) : totals.forMonth(first, storeIds);

        Map<Long, Product> partsById = products.findAllById(lines.stream().map(Line::productId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, Store> storesById = stores.findAllById(lines.stream().map(Line::storeId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Store::getId, Function.identity()));
        Set<Long> inCategory = categoryId == null ? null : new HashSet<>(categories.withDescendants(categoryId));
        String text = StringUtils.hasText(q) ? SearchText.fold(q.strip()) : null;

        List<Line> matching = lines.stream()
                .filter(l -> matches(partsById.get(l.productId()), text, inCategory))
                .sorted(Comparator.comparing((Line l) -> storesById.get(l.storeId()).getStoreName())
                        .thenComparing(l -> partsById.get(l.productId()).getSku()))
                .toList();

        int pageNo = Math.max(page, 0);
        int pageSize = Math.clamp(size, 1, 100);
        List<InventoryOperationsRow> items = matching.stream().skip((long) pageNo * pageSize).limit(pageSize)
                .map(l -> row(l, storesById.get(l.storeId()), partsById.get(l.productId()), owner))
                .toList();
        return new InventoryOperationsResponse(byDates ? null : first.format(MONTH), first, last, status, provisional,
                owner, owner ? sum(matching) : null, new PageResponse<>(items, matching.size(), pageNo, pageSize));
    }

    private static boolean matches(Product part, String text, Set<Long> inCategory) {
        if (inCategory != null && !inCategory.contains(part.getCategoryId())) {
            return false;
        }
        return text == null || SearchText.contains(part.getSku(), text) || SearchText.contains(part.getPartName(), text);
    }

    /** Movement types summed into their sources, in the order the sources are declared. */
    private static Map<StockSource, long[]> bySource(Line l) {
        Map<StockSource, long[]> sources = new EnumMap<>(StockSource.class);
        l.byType().forEach((type, flow) -> {
            long[] s = sources.computeIfAbsent(StockSource.of(type), k -> new long[4]);
            s[0] += flow.in().qty();
            s[1] += flow.in().value();
            s[2] += flow.out().qty();
            s[3] += flow.out().value();
        });
        return sources;
    }

    private static InventoryOperationsRow row(Line l, Store store, Product part, boolean withValues) {
        List<SourceFlow> sources = bySource(l).entrySet().stream()
                .map(e -> new SourceFlow(e.getKey(), e.getValue()[0], value(e.getValue()[1], withValues),
                        e.getValue()[2], value(e.getValue()[3], withValues)))
                .toList();
        return new InventoryOperationsRow(l.storeId(), store.getStoreName(), l.productId(), part.getSku(),
                part.getPartName(), part.getBaseUnit(), l.opening().qty(), value(l.opening(), withValues),
                l.in().qty(), value(l.in(), withValues), l.out().qty(), value(l.out(), withValues),
                l.closing().qty(), value(l.closing(), withValues), l.inTransit().qty(),
                value(l.inTransit(), withValues), sources);
    }

    private static InventoryOperationsResponse.Totals sum(List<Line> lines) {
        Map<StockSource, long[]> sources = new EnumMap<>(StockSource.class);
        lines.forEach(l -> bySource(l).forEach((source, s) -> {
            long[] t = sources.computeIfAbsent(source, k -> new long[2]);
            t[0] += s[1];
            t[1] += s[3];
        }));
        return new InventoryOperationsResponse.Totals(
                lines.stream().mapToLong(l -> l.opening().value()).sum(),
                lines.stream().mapToLong(l -> l.in().value()).sum(),
                lines.stream().mapToLong(l -> l.out().value()).sum(),
                lines.stream().mapToLong(l -> l.closing().value()).sum(),
                lines.stream().mapToLong(l -> l.inTransit().value()).sum(),
                sources.entrySet().stream()
                        .map(e -> new InventoryOperationsResponse.SourceTotal(e.getKey(), e.getValue()[0],
                                e.getValue()[1]))
                        .toList());
    }

    private static Long value(Amount amount, boolean withValues) {
        return withValues ? amount.value() : null;
    }

    private static Long value(long value, boolean withValues) {
        return withValues ? value : null;
    }
}
