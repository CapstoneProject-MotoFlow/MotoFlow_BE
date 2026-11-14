package fu.hl.g64.motorflow.report.service.impl;

import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.repository.PartCategoryRepository;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.report.dto.GrossMarginReport;
import fu.hl.g64.motorflow.report.dto.MarginGrouping;
import fu.hl.g64.motorflow.report.dto.ReportGrouping;
import fu.hl.g64.motorflow.report.dto.SalesCollectionReport;
import fu.hl.g64.motorflow.report.dto.SalesCollectionReport.Figures;
import fu.hl.g64.motorflow.report.dto.SalesCollectionReport.PeriodRow;
import fu.hl.g64.motorflow.report.dto.SalesCollectionReport.StoreRow;
import fu.hl.g64.motorflow.report.helper.AccountingWorkbook;
import fu.hl.g64.motorflow.report.helper.ReportScope;
import fu.hl.g64.motorflow.report.repository.ReportQueries;
import fu.hl.g64.motorflow.report.service.ReportService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.entity.enums.StoreStatus;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static fu.hl.g64.motorflow.report.repository.ReportQueries.number;

@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private final ReportQueries queries;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final PartCategoryRepository categories;
    private final AccountingWorkbook workbook;
    private final Clock clock;

    @Transactional(readOnly = true)
    @Override
    public SalesCollectionReport salesCollection(AuthenticatedUser user, Long storeId, LocalDate from, LocalDate to,
                                                 ReportGrouping groupBy) {
        ReportScope scope = ReportScope.of(user, storeId, from, to, LocalDate.now(clock), stores);
        ReportGrouping grouping = groupBy == null ? ReportGrouping.DAY : groupBy;
        String unit = grouping == ReportGrouping.MONTH ? "month" : "day";

        // (store, day) -> [orders, revenue, credit sales, order payments, refunds, debt, revenue corr., coll. corr.,
        // receivable corr.]
        Map<Long, Map<LocalDate, long[]>> cells = new HashMap<>();
        queries.sales(scope.storeId(), scope.start(), scope.end(), unit).forEach(r -> {
            long[] c = cell(cells, r);
            c[0] += number(r.get("orders"));
            c[1] += number(r.get("revenue"));
            c[2] += number(r.get("credit_sales"));
        });
        queries.orderCollections(scope.storeId(), scope.start(), scope.end(), unit).forEach(r -> {
            long[] c = cell(cells, r);
            c[3] += number(r.get("order_payments"));
            c[4] += number(r.get("refunds"));
        });
        queries.debtCollections(scope.storeId(), scope.start(), scope.end(), unit)
                .forEach(r -> cell(cells, r)[5] += number(r.get("debt_collected")));
        queries.amountCorrections(scope.storeId(), scope.start(), scope.end(), unit).forEach(r -> {
            long[] c = cell(cells, r);
            c[6] += number(r.get("revenue_corrections"));
            c[7] += number(r.get("collection_corrections"));
            c[8] += number(r.get("receivable_corrections"));
        });
        Map<Long, Long> owed = queries.receivableAt(scope.storeId(), scope.end()).stream()
                .collect(Collectors.toMap(r -> number(r.get("store_id")), r -> number(r.get("remaining"))));

        Set<Long> storeIds = new HashSet<>(cells.keySet());
        storeIds.addAll(owed.keySet());
        if (scope.storeId() != null) {
            storeIds.add(scope.storeId());
        } else {
            stores.findByStatusOrderByStoreNameAsc(StoreStatus.ACTIVE).forEach(s -> storeIds.add(s.getId()));
        }
        List<StoreRow> storeRows = stores.findAllById(storeIds).stream()
                .sorted(Comparator.comparing(Store::getStoreName).thenComparing(Store::getId))
                .map(store -> {
                    Figures f = cells.getOrDefault(store.getId(), Map.of()).values().stream().map(c -> figures(c, null))
                            .reduce(Figures.ZERO, Figures::plus);
                    return new StoreRow(store.getId(), store.getStoreName(), withOwed(f,
                            owed.getOrDefault(store.getId(), 0L)));
                })
                .toList();

        Map<LocalDate, Figures> byPeriod = new TreeMap<>();
        cells.values().forEach(days -> days.forEach((day, c) ->
                byPeriod.merge(day, figures(c, null), Figures::plus)));
        List<PeriodRow> periods = new ArrayList<>();
        LocalDate day = grouping == ReportGrouping.MONTH ? scope.first().withDayOfMonth(1) : scope.first();
        while (!day.isAfter(scope.last())) {
            LocalDate next = grouping == ReportGrouping.MONTH ? day.plusMonths(1) : day.plusDays(1);
            LocalDate first = day.isBefore(scope.first()) ? scope.first() : day;
            LocalDate last = next.minusDays(1).isAfter(scope.last()) ? scope.last() : next.minusDays(1);
            periods.add(new PeriodRow(first, last, byPeriod.getOrDefault(day, Figures.ZERO)));
            day = next;
        }
        Figures total = storeRows.stream().map(StoreRow::figures).reduce(Figures.ZERO, Figures::plus);
        return new SalesCollectionReport(scope.first(), scope.last(), grouping, scope.storeId(),
                total.receivableAtEnd() == null ? withOwed(total, 0) : total, periods, storeRows);
    }

    @Transactional(readOnly = true)
    @Override
    public GrossMarginReport grossMargin(AuthenticatedUser user, Long storeId, Long categoryId, Long productId,
                                         LocalDate from, LocalDate to, MarginGrouping groupBy) {
        ReportScope scope = ReportScope.of(user, storeId, from, to, LocalDate.now(clock), stores);
        MarginGrouping grouping = groupBy == null ? MarginGrouping.STORE : groupBy;
        if (categoryId != null && !categories.existsById(categoryId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "MSG61");
        }
        if (productId != null && !products.existsById(productId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "MSG57");
        }
        List<Map<String, Object>> rows = queries.margins(scope.storeId(), categoryId, productId, scope.start(),
                scope.end());
        Map<Long, Product> parts = products.findAllById(rows.stream().map(r -> number(r.get("product_id")))
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Product::getId, Function.identity()));

        // key id -> [quantity, revenue, cost, correction revenue, correction cost, write-off]
        Map<Long, long[]> sums = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Product part = parts.get(number(r.get("product_id")));
            long key = switch (grouping) {
                case STORE -> number(r.get("store_id"));
                case CATEGORY -> part.getCategoryId();
                case PRODUCT -> part.getId();
            };
            long[] s = sums.computeIfAbsent(key, k -> new long[6]);
            s[0] += number(r.get("quantity"));
            s[1] += number(r.get("revenue"));
            s[2] += number(r.get("cost"));
            s[3] += number(r.get("correction_revenue"));
            s[4] += number(r.get("correction_cost"));
            s[5] += number(r.get("write_off_value"));
        }
        if (grouping == MarginGrouping.STORE && scope.storeId() != null) {
            sums.computeIfAbsent(scope.storeId(), k -> new long[6]);
        }

        List<GrossMarginReport.Row> result = new ArrayList<>();
        switch (grouping) {
            case STORE -> stores.findAllById(sums.keySet()).forEach(s -> result.add(new GrossMarginReport.Row(
                    s.getId(), null, s.getStoreName(), margin(sums.get(s.getId())))));
            case CATEGORY -> categories.findAllById(sums.keySet()).forEach(c -> result.add(new GrossMarginReport.Row(
                    c.getId(), null, c.getCategoryName(), margin(sums.get(c.getId())))));
            case PRODUCT -> sums.keySet().forEach(id -> result.add(new GrossMarginReport.Row(id,
                    parts.get(id).getSku(), parts.get(id).getPartName(), margin(sums.get(id)))));
        }
        result.sort(Comparator.comparing((GrossMarginReport.Row r) -> r.figures().revenue()).reversed()
                .thenComparing(GrossMarginReport.Row::name).thenComparing(GrossMarginReport.Row::id));
        long[] all = new long[6];
        sums.values().forEach(s -> {
            for (int i = 0; i < all.length; i++) {
                all[i] += s[i];
            }
        });
        return new GrossMarginReport(scope.first(), scope.last(), grouping, scope.storeId(), categoryId, productId,
                margin(all), result);
    }

    @Transactional(readOnly = true)
    @Override
    public ExportedFile accountingExport(AuthenticatedUser user, Long storeId, LocalDate from, LocalDate to) {
        ReportScope scope = ReportScope.of(user, storeId, from, to, LocalDate.now(clock), stores);
        SalesCollectionReport daily = salesCollection(user, scope.storeId(), scope.first(), scope.last(),
                ReportGrouping.DAY);
        Map<Long, String> storeNames = stores.findAll().stream()
                .collect(Collectors.toMap(Store::getId, Store::getStoreName));
        byte[] content = workbook.write(scope, daily,
                queries.salesLines(scope.storeId(), scope.start(), scope.end()),
                queries.purchaseLines(scope.storeId(), scope.start(), scope.end()),
                queries.payments(scope.storeId(), scope.start(), scope.end()),
                queries.adjustments(scope.storeId(), scope.start(), scope.end()), storeNames);
        return new ExportedFile("MotoFlow_KeToan_%s_%s.xlsx".formatted(scope.first(), scope.last()), content);
    }

    // ------------------------------------------------------------------------------------------------------------

    private static long[] cell(Map<Long, Map<LocalDate, long[]>> cells, Map<String, Object> row) {
        LocalDate day = ((Date) row.get("day")).toLocalDate();
        return cells.computeIfAbsent(number(row.get("store_id")), k -> new HashMap<>())
                .computeIfAbsent(day, k -> new long[9]);
    }

    private static Figures figures(long[] c, Long owed) {
        return new Figures(c[0], c[1], c[3], c[4], c[5], c[3] - c[4] + c[5], c[2], c[6], c[7], c[8], owed);
    }

    private static Figures withOwed(Figures f, long owed) {
        return new Figures(f.completedOrders(), f.revenue(), f.orderPayments(), f.refunds(), f.debtCollected(),
                f.totalCollected(), f.creditSales(), f.revenueCorrections(), f.collectionCorrections(),
                f.receivableCorrections(), owed);
    }

    private static GrossMarginReport.Figures margin(long[] s) {
        long margin = s[1] - s[2];
        BigDecimal rate = s[1] == 0 ? null : BigDecimal.valueOf(margin).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(s[1]), 2, RoundingMode.HALF_UP);
        return new GrossMarginReport.Figures(s[0], s[1], s[2], margin, rate, s[3], s[4], margin + s[3] - s[4], s[5]);
    }
}
