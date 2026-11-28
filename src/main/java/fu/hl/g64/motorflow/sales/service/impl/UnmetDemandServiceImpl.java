package fu.hl.g64.motorflow.sales.service.impl;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.common.text.SearchText;
import fu.hl.g64.motorflow.sales.dto.UnmetDemandRow;
import fu.hl.g64.motorflow.sales.dto.UnmetDemandSearch;
import fu.hl.g64.motorflow.sales.entity.SalesOrder;
import fu.hl.g64.motorflow.sales.entity.SalesOrderLine;
import fu.hl.g64.motorflow.sales.entity.enums.SalesOrderStatus;
import fu.hl.g64.motorflow.sales.entity.enums.WaitingOrderState;
import fu.hl.g64.motorflow.sales.helper.PickingGuide;
import fu.hl.g64.motorflow.sales.helper.WaitingOrders;
import fu.hl.g64.motorflow.sales.repository.SalesOrderLineRepository;
import fu.hl.g64.motorflow.sales.service.UnmetDemandService;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UnmetDemandServiceImpl implements UnmetDemandService {

    /** Released: the order went on from waiting (in full, or the part a release sold). */
    private static final Set<SalesOrderStatus> RELEASED = EnumSet.of(SalesOrderStatus.AWAITING_PICKING,
            SalesOrderStatus.PICKED, SalesOrderStatus.CONFIRMED, SalesOrderStatus.COMPLETED);

    private final SalesOrderLineRepository lines;
    private final StoreRepository stores;
    private final WaitingOrders waitingOrders;
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
        Set<WaitingOrderState> states = search.states() == null || search.states().isEmpty()
                ? EnumSet.of(WaitingOrderState.WAITING, WaitingOrderState.READY) : EnumSet.copyOf(search.states());
        LocalDate today = LocalDate.now(clock);
        String needle = StringUtils.hasText(search.q()) ? SearchText.fold(search.q().strip()) : null;
        List<UnmetDemandRow> rows = new ArrayList<>();

        if (states.contains(WaitingOrderState.WAITING) || states.contains(WaitingOrderState.READY)) {
            // Shared out over every waiting order of the store, before any filter (BR-90).
            WaitingOrders.Snapshot snapshot = waitingOrders.of(storeId, today);
            LocalDateTime from = search.from() == null ? null : search.from().atStartOfDay();
            LocalDateTime to = search.to() == null ? null : search.to().plusDays(1).atStartOfDay();
            List<UnmetDemandRow> ready = new ArrayList<>();
            List<UnmetDemandRow> waiting = new ArrayList<>();
            for (WaitingOrders.Line line : snapshot.lines()) {
                SalesOrder order = line.order();
                boolean orderReady = snapshot.ready(order.getId());
                WaitingOrderState state = orderReady ? WaitingOrderState.READY : WaitingOrderState.WAITING;
                if (!states.contains(state) || !matches(search, needle, line.line(), order, line.part())
                        || (from != null && order.getCreatedAt().isBefore(from))
                        || (to != null && !order.getCreatedAt().isBefore(to))) {
                    continue;
                }
                LocalDate expected = order.getExpectedReadyDate();
                (orderReady ? ready : waiting).add(row(order, state, line.line(), line.part(), 0,
                        line.line().getRequestedQty(), line.sellableQty(), line.readyQty(), orderReady,
                        !orderReady && expected != null && today.isAfter(expected)));
            }
            rows.addAll(ready);
            rows.addAll(waiting);
        }

        Set<SalesOrderStatus> former = EnumSet.noneOf(SalesOrderStatus.class);
        if (states.contains(WaitingOrderState.RELEASED)) {
            former.addAll(RELEASED);
        }
        if (states.contains(WaitingOrderState.CANCELLED)) {
            former.add(SalesOrderStatus.CANCELLED);
        }
        if (!former.isEmpty()) {
            LocalDate to = search.to() != null ? search.to() : today;
            LocalDate from = search.from() != null ? search.from() : to.minusDays(29);
            String pattern = needle == null ? "%" : SearchText.likePattern(search.q());
            List<Object[]> found = lines.findFormerWaitingLines(former, storeId, search.productId(),
                    search.customerId(), from.atStartOfDay(), to.plusDays(1).atStartOfDay(), pattern);
            Map<Long, Set<Long>> partsByStore = found.stream().collect(Collectors.groupingBy(
                    row -> ((SalesOrder) row[1]).getStoreId(),
                    Collectors.mapping(row -> ((SalesOrderLine) row[0]).getProductId(), Collectors.toSet())));
            Map<Long, Map<Long, Integer>> stock = guide.sellableByStore(partsByStore, today);
            for (Object[] entry : found) {
                SalesOrderLine line = (SalesOrderLine) entry[0];
                SalesOrder order = (SalesOrder) entry[1];
                if (search.orderId() != null && !search.orderId().equals(order.getId())) {
                    continue;
                }
                boolean cancelled = order.getStatus() == SalesOrderStatus.CANCELLED;
                int delivered = order.getStatus() == SalesOrderStatus.COMPLETED && line.getDeliveredQty() != null
                        ? line.getDeliveredQty() : 0;
                rows.add(row(order, cancelled ? WaitingOrderState.CANCELLED : WaitingOrderState.RELEASED, line,
                        (Product) entry[2], delivered, cancelled ? line.getRequestedQty() : 0,
                        stock.get(order.getStoreId()).getOrDefault(line.getProductId(), 0), 0, false, false));
            }
        }

        Map<Long, String> storeNames = stores.findAllById(rows.stream().map(UnmetDemandRow::storeId)
                        .collect(Collectors.toCollection(HashSet::new))).stream()
                .collect(Collectors.toMap(Store::getId, Store::getStoreName));
        int pageSize = Math.clamp(size, 1, 100);
        int pageNo = Math.max(page, 0);
        List<UnmetDemandRow> items = rows.stream().skip((long) pageNo * pageSize).limit(pageSize)
                .map(row -> withStoreName(row, storeNames.get(row.storeId()))).toList();
        return new PageResponse<>(items, rows.size(), pageNo, pageSize);
    }

    private static boolean matches(UnmetDemandSearch search, String needle, SalesOrderLine line, SalesOrder order,
                                   Product part) {
        if (search.productId() != null && !search.productId().equals(line.getProductId())) {
            return false;
        }
        if (search.orderId() != null && !search.orderId().equals(order.getId())) {
            return false;
        }
        if (search.customerId() != null && !search.customerId().equals(order.getCustomerId())) {
            return false;
        }
        return needle == null || SearchText.contains(part.getSku(), needle)
                || SearchText.contains(part.getPartName(), needle)
                || SearchText.contains(order.getCustomerNameSnapshot(), needle)
                || (order.getCustomerPhoneSnapshot() != null && order.getCustomerPhoneSnapshot().contains(needle));
    }

    private static UnmetDemandRow row(SalesOrder order, WaitingOrderState state, SalesOrderLine line, Product part,
                                      int delivered, int missing, int sellable, int readyQty, boolean orderReady,
                                      boolean overdue) {
        LocalDateTime at = order.getStatus() == SalesOrderStatus.CANCELLED ? order.getCancelledAt()
                : order.getCreatedAt();
        return new UnmetDemandRow(at, order.getId(), order.getStatus(), state, order.getStoreId(), null,
                order.getCustomerId(), order.getCustomerNameSnapshot(), order.getCustomerPhoneSnapshot(),
                line.getId(), part.getId(), part.getSku(), part.getPartName(), part.getBaseUnit(),
                line.getRequestedQty(), delivered, missing, sellable, readyQty, orderReady,
                order.getExpectedReadyDate(), overdue);
    }

    private static UnmetDemandRow withStoreName(UnmetDemandRow row, String storeName) {
        return new UnmetDemandRow(row.occurredAt(), row.orderId(), row.orderStatus(), row.state(), row.storeId(),
                storeName, row.customerId(), row.customerName(), row.customerPhone(), row.lineId(), row.productId(),
                row.sku(), row.partName(), row.baseUnit(), row.requestedQty(), row.deliveredQty(), row.missingQty(),
                row.sellableQty(), row.readyQty(), row.orderReady(), row.expectedReadyDate(), row.overdue());
    }
}
