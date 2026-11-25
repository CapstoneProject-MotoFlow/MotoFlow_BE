package fu.hl.g64.motorflow.sales.helper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * GVHD 24/11 (BR-90): what the store can sell now is shared out among its waiting orders oldest first, so two orders
 * waiting for the same 4 units are not both shown as ready. An older order keeps its claim even when it is not fully
 * covered yet.
 */
public final class WaitingAllocation {

    private WaitingAllocation() {
    }

    /** One line of a waiting order, given oldest order first. */
    public record Need(long orderId, long storeId, long productId, int quantity) {
    }

    /**
     * The quantity each need gets now, in the order given, from {@code sellable} [storeId → productId → quantity].
     */
    public static int[] allocate(List<Need> needs, Map<Long, Map<Long, Integer>> sellable) {
        Map<Long, Map<Long, Integer>> left = new HashMap<>();
        sellable.forEach((store, parts) -> left.put(store, new HashMap<>(parts)));
        int[] ready = new int[needs.size()];
        for (int i = 0; i < needs.size(); i++) {
            Need need = needs.get(i);
            Map<Long, Integer> store = left.computeIfAbsent(need.storeId(), key -> new HashMap<>());
            int available = Math.max(0, store.getOrDefault(need.productId(), 0));
            ready[i] = Math.min(available, need.quantity());
            store.put(need.productId(), available - ready[i]);
        }
        return ready;
    }

    /** The orders whose every line got all it needs. */
    public static Set<Long> readyOrders(List<Need> needs, int[] ready) {
        Map<Long, Boolean> covered = new LinkedHashMap<>();
        for (int i = 0; i < needs.size(); i++) {
            covered.merge(needs.get(i).orderId(), ready[i] >= needs.get(i).quantity(), Boolean::logicalAnd);
        }
        return covered.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }
}
