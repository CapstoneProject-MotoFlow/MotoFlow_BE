package fu.hl.g64.motorflow.stockcount.helper;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.inventory.entity.StockBalance;
import fu.hl.g64.motorflow.inventory.entity.StockCount;
import fu.hl.g64.motorflow.inventory.entity.StockCountLine;
import fu.hl.g64.motorflow.inventory.entity.StockCountRequest;
import fu.hl.g64.motorflow.inventory.entity.StockCountRequestProduct;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.inventory.repository.StockCountLineRepository;
import fu.hl.g64.motorflow.inventory.repository.StockCountRequestProductRepository;
import fu.hl.g64.motorflow.inventory.repository.StockCountRequestRepository;
import fu.hl.g64.motorflow.stockcount.dto.CountRequestResponse;
import fu.hl.g64.motorflow.stockcount.dto.StockCountDetailResponse;
import fu.hl.g64.motorflow.stockcount.helper.StockCountAccess.Viewer;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the stock count and request responses. What a count line shows depends on the viewer (UC-92, BR-72): the
 * counter sees no book quantity, difference, reason or earlier book quantity; only the Owner sees the value of a
 * difference, at the part's current moving average cost.
 */
@Component
@RequiredArgsConstructor
public class StockCountMapper {

    private final StockCountLineRepository lines;
    private final StockCountRequestRepository requests;
    private final StockCountRequestProductRepository requestProducts;
    private final ProductRepository products;
    private final StockBalanceRepository balances;
    private final StoreRepository stores;
    private final UserAccountRepository users;

    public StockCountDetailResponse toDetail(StockCount count, Viewer viewer) {
        Store store = stores.findById(count.getStoreId()).orElseThrow();
        List<StockCountLine> countLines = lines.findByStockCountIdOrderByProductIdAscExpiryDateAscIdAsc(count.getId());
        Set<Long> productIds = countLines.stream().map(StockCountLine::getProductId).collect(Collectors.toSet());
        Map<Long, Product> parts = byId(products.findAllById(productIds), Product::getId);
        Map<Long, StockBalance> locations = byId(balances.findByStoreIdAndProductIdIn(count.getStoreId(), productIds),
                StockBalance::getProductId);
        CountRequestResponse request = count.getStockCountRequestId() == null ? null
                : toRequest(requests.findById(count.getStockCountRequestId()).orElseThrow());
        return new StockCountDetailResponse(count.getId(), store.getId(), store.getStoreName(),
                count.getInventoryPeriodId(), count.getCountType(), count.getCountDate(), count.getScopeType(),
                count.getScopeNote(), count.getStatus(), count.getCreatedBy(), nameOf(count.getCreatedBy()),
                count.getCreatedAt(), count.getCountingStartedAt(), count.getConfirmedBy(), count.getConfirmedAt(),
                count.getApprovedBy(), count.getApprovedAt(), count.getDecisionReason(), count.getCancelledBy(),
                count.getCancelledAt(), count.getCancellationReason(),
                count.getCountingStartedAt() == null && countLines.stream().anyMatch(StockCountLine::isRecountRequested),
                request,
                countLines.stream().map(line -> toLine(line, parts.get(line.getProductId()),
                        locations.get(line.getProductId()), viewer)).toList());
    }

    public CountRequestResponse toRequest(StockCountRequest request) {
        return toRequests(List.of(request)).getFirst();
    }

    /** {@link #toRequest} for many requests, in the same order, with a fixed number of queries. */
    public List<CountRequestResponse> toRequests(List<StockCountRequest> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        Map<Long, List<StockCountRequestProduct>> listed = requestProducts.findByStockCountRequestIdIn(
                        found.stream().map(StockCountRequest::getId).toList()).stream()
                .collect(Collectors.groupingBy(StockCountRequestProduct::getStockCountRequestId));
        Map<Long, Product> parts = byId(products.findAllById(listed.values().stream().flatMap(List::stream)
                .map(StockCountRequestProduct::getProductId).collect(Collectors.toSet())), Product::getId);
        Map<Long, Store> storesById = byId(stores.findAllById(found.stream().map(StockCountRequest::getStoreId)
                .collect(Collectors.toSet())), Store::getId);
        Map<Long, UserAccount> requesters = byId(users.findAllById(found.stream().map(StockCountRequest::getRequestedBy)
                .collect(Collectors.toSet())), UserAccount::getId);
        return found.stream().map(request -> new CountRequestResponse(request.getId(), request.getStoreId(),
                storesById.get(request.getStoreId()).getStoreName(), request.getScopeType(), request.getScopeNote(),
                request.getReason(), request.getStatus(), request.getRequestedBy(),
                requesters.get(request.getRequestedBy()).getFullName(), request.getRequestedAt(),
                request.getCancelledAt(),
                listed.getOrDefault(request.getId(), List.of()).stream()
                        .sorted(Comparator.comparing(StockCountRequestProduct::getProductId))
                        .map(p -> parts.get(p.getProductId()))
                        .map(p -> new CountRequestResponse.Product(p.getId(), p.getSku(), p.getPartName()))
                        .toList())).toList();
    }

    private static StockCountDetailResponse.Line toLine(StockCountLine line, Product part, StockBalance location,
                                                        Viewer viewer) {
        boolean counter = viewer == Viewer.COUNTER;
        Integer difference = counter ? null : line.difference();
        BigDecimal value = viewer == Viewer.OWNER && difference != null && part.getMovingAvgCost() != null
                ? part.getMovingAvgCost().multiply(BigDecimal.valueOf(difference)).setScale(0, RoundingMode.HALF_UP)
                : null;
        return new StockCountDetailResponse.Line(line.getId(), part.getId(), part.getSku(), part.getPartName(),
                part.getBaseUnit(), line.getLotId(), line.getExpiryDate(),
                location == null ? null : location.getStorageArea(), location == null ? null : location.getBinLocation(),
                counter ? null : line.getBookQty(), line.getCountedQty(), difference, value,
                counter ? null : line.getReason(), line.isRecountRequested(), line.getRecountReason(),
                counter ? null : line.getPreviousBookQty(), counter ? null : line.getPreviousCountedQty(),
                line.getCountedAt());
    }

    private String nameOf(long userId) {
        return users.findById(userId).map(UserAccount::getFullName).orElse(null);
    }

    private static <T> Map<Long, T> byId(Collection<T> items, Function<T, Long> id) {
        return items.stream().collect(Collectors.toMap(id, Function.identity()));
    }
}
