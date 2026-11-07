package fu.hl.g64.motorflow.sales.service.impl;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.sales.dto.UnmetDemandRow;
import fu.hl.g64.motorflow.sales.dto.UnmetDemandSearch;
import fu.hl.g64.motorflow.sales.entity.SalesOrder;
import fu.hl.g64.motorflow.sales.entity.SalesOrderLine;
import fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus;
import fu.hl.g64.motorflow.sales.helper.PickingGuide;
import fu.hl.g64.motorflow.sales.repository.SalesOrderLineRepository;
import fu.hl.g64.motorflow.sales.service.UnmetDemandService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UnmetDemandServiceImpl implements UnmetDemandService {

    private final SalesOrderLineRepository lines;
    private final StoreRepository stores;
    private final PickingGuide guide;
    private final Clock clock;

    @Transactional(readOnly = true)
    @Override
    public PageResponse<UnmetDemandRow> list(AuthenticatedUser user, UnmetDemandSearch search, int page, int size) {
        Long storeId = search.storeId();
        if (user.role() != Role.OWNER) {
            if (storeId != null && !storeId.equals(user.storeId())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "MSG05");
            }
            storeId = user.storeId();
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate to = search.to() != null ? search.to() : today;
        LocalDate from = search.from() != null ? search.from() : to.minusDays(29);
        String pattern = StringUtils.hasText(search.q())
                ? "%" + search.q().strip().toLowerCase(Locale.ROOT) + "%" : "%";
        Page<Object[]> found = lines.findUnmetDemand(storeId, search.productId(), search.customerId(),
                from.atStartOfDay(), to.plusDays(1).atStartOfDay(), pattern,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100)));

        Map<Long, Set<Long>> partsByStore = found.getContent().stream().collect(Collectors.groupingBy(
                row -> ((SalesOrder) row[1]).getStoreId(),
                Collectors.mapping(row -> ((SalesOrderLine) row[0]).getProductId(), Collectors.toSet())));
        Map<Long, Map<Long, PickingGuide.Part>> stock = new HashMap<>();
        partsByStore.forEach((store, parts) -> stock.put(store, guide.parts(store, parts, today)));
        Map<Long, String> storeNames = stores.findAllById(partsByStore.keySet()).stream()
                .collect(Collectors.toMap(Store::getId, Store::getStoreName));

        List<UnmetDemandRow> rows = found.getContent().stream().map(row -> {
            SalesOrderLine line = (SalesOrderLine) row[0];
            SalesOrder order = (SalesOrder) row[1];
            Product part = (Product) row[2];
            boolean cancelled = order.getStatus() == SalesOrderStatus.CANCELLED;
            int delivered = cancelled || line.getDeliveredQty() == null ? 0 : line.getDeliveredQty();
            PickingGuide.Part held = stock.get(order.getStoreId()).get(line.getProductId());
            return new UnmetDemandRow(cancelled ? order.getCancelledAt() : order.getCompletedAt(), order.getId(),
                    order.getStatus(), order.getStoreId(), storeNames.get(order.getStoreId()), order.getCustomerId(),
                    order.getCustomerNameSnapshot(), order.getCustomerPhoneSnapshot(), part.getId(), part.getSku(),
                    part.getPartName(), part.getBaseUnit(), line.getRequestedQty(), delivered,
                    line.getRequestedQty() - delivered, held == null ? 0 : held.sellableQty());
        }).toList();
        return PageResponse.of(found, rows);
    }
}
