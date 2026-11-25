package fu.hl.g64.motorflow.sales.helper;

import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.sales.entity.SalesOrder;
import fu.hl.g64.motorflow.sales.entity.SalesOrderLine;
import fu.hl.g64.motorflow.sales.repository.SalesOrderLineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** GVHD 24/11: the waiting orders of a store (or every store) with what each line can get now (BR-90). */
@Component
@RequiredArgsConstructor
public class WaitingOrders {

    private final SalesOrderLineRepository lines;
    private final PickingGuide guide;

    /** One line still waiting, and the part of it the store's sellable stock covers now, oldest order first. */
    public record Line(SalesOrderLine line, SalesOrder order, Product part, int readyQty, int sellableQty) {
    }

    /** Every line still waiting, oldest order first, and the orders whose every line is covered. */
    public record Snapshot(List<Line> lines, Set<Long> readyOrders) {

        public boolean ready(long orderId) {
            return readyOrders.contains(orderId);
        }
    }

    /** {@code storeId} null for every store. */
    public Snapshot of(Long storeId, LocalDate today) {
        List<Object[]> found = lines.findWaitingLines(storeId);
        Map<Long, Set<Long>> partsByStore = found.stream().collect(Collectors.groupingBy(
                row -> ((SalesOrder) row[1]).getStoreId(),
                Collectors.mapping(row -> ((SalesOrderLine) row[0]).getProductId(), Collectors.toSet())));
        Map<Long, Map<Long, Integer>> sellable = new HashMap<>();
        partsByStore.forEach((store, parts) -> {
            Map<Long, Integer> quantities = new HashMap<>();
            guide.parts(store, parts, today).forEach((part, stock) -> quantities.put(part, stock.sellableQty()));
            sellable.put(store, quantities);
        });
        List<WaitingAllocation.Need> needs = found.stream().map(row -> {
            SalesOrderLine line = (SalesOrderLine) row[0];
            SalesOrder order = (SalesOrder) row[1];
            return new WaitingAllocation.Need(order.getId(), order.getStoreId(), line.getProductId(),
                    line.getRequestedQty());
        }).toList();
        int[] ready = WaitingAllocation.allocate(needs, sellable);
        List<Line> result = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            SalesOrder order = (SalesOrder) found.get(i)[1];
            SalesOrderLine line = (SalesOrderLine) found.get(i)[0];
            result.add(new Line(line, order, (Product) found.get(i)[2], ready[i],
                    sellable.get(order.getStoreId()).getOrDefault(line.getProductId(), 0)));
        }
        return new Snapshot(result, WaitingAllocation.readyOrders(needs, ready));
    }
}
