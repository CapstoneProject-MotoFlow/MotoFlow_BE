package fu.hl.g64.motorflow.sales.helper;

import fu.hl.g64.motorflow.inventory.entity.Lot;
import fu.hl.g64.motorflow.inventory.entity.StockBalance;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.sales.repository.SalesOrderLineRepository;
import fu.hl.g64.motorflow.sales.repository.SellableLotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a seller or picker needs to know about the goods of a warehouse: how much can be handed over now (available
 * and not expired, BR-76, less what open sales orders hold, BR-33 since GVHD 20/11), where the part is kept and which
 * lots to take first (BR-75). Reading it reserves nothing: an open order holds by being open.
 */
@Component
@RequiredArgsConstructor
public class PickingGuide {

    private final SellableLotRepository sellable;
    private final StockBalanceRepository balances;
    private final SalesOrderLineRepository orderLines;

    /** One lot to take from, with how many of the needed units it covers. */
    public record LotToPick(long lotId, String manufacturerLotNo, LocalDate manufactureDate, LocalDate expiryDate,
                            LocalDate receivedDate, int availableQty, int takeQty) {
    }

    /**
     * One part in one warehouse. {@code sellableQty}: available, unexpired and not held by other open sales orders;
     * {@code heldByOthersQty}: what those orders hold. The lots are what is left after the holds, taken from the
     * first lots in picking order.
     */
    public record Part(int sellableQty, int heldByOthersQty, String storageArea, String binLocation,
                       List<Lot> lotsInOrder, List<Integer> lotQuantities) {

        /** The lots that cover {@code needed} units, in picking order; fewer units if the warehouse holds fewer. */
        public List<LotToPick> pickFirst(int needed) {
            List<LotToPick> picks = new ArrayList<>();
            int left = needed;
            for (int i = 0; i < lotsInOrder.size() && left > 0; i++) {
                Lot lot = lotsInOrder.get(i);
                int held = lotQuantities.get(i);
                int take = Math.min(left, held);
                picks.add(new LotToPick(lot.getId(), lot.getManufacturerLotNo(), lot.getManufactureDate(),
                        lot.getExpiryDate(), lot.getReceivedDate(), held, take));
                left -= take;
            }
            return picks;
        }
    }

    /**
     * The parts as they stand in the store's warehouse today, less what every open sales order holds; a part the
     * warehouse never held has nothing.
     */
    public Map<Long, Part> parts(long storeId, Collection<Long> productIds, LocalDate today) {
        return parts(storeId, productIds, today, null);
    }

    /** As {@link #parts(long, Collection, LocalDate)}, for sales order {@code orderId}: its own holding is its own. */
    public Map<Long, Part> parts(long storeId, Collection<Long> productIds, LocalDate today, Long orderId) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> heldByOthers = new HashMap<>();
        for (Object[] row : orderLines.reservedQuantities(storeId, productIds, orderId == null ? -1L : orderId)) {
            heldByOthers.put((Long) row[0], ((Number) row[1]).intValue());
        }
        Map<Long, List<Lot>> lots = new HashMap<>();
        Map<Long, List<Integer>> quantities = new HashMap<>();
        for (Object[] row : sellable.findSellableLots(storeId, productIds, today)) {
            Lot lot = (Lot) row[0];
            lots.computeIfAbsent(lot.getProductId(), key -> new ArrayList<>()).add(lot);
            quantities.computeIfAbsent(lot.getProductId(), key -> new ArrayList<>()).add((Integer) row[1]);
        }
        Map<Long, StockBalance> places = balances.findByStoreIdAndProductIdIn(storeId, productIds).stream()
                .collect(Collectors.toMap(StockBalance::getProductId, b -> b));
        Map<Long, Part> parts = new HashMap<>();
        for (Long productId : productIds) {
            int holding = heldByOthers.getOrDefault(productId, 0);
            List<Lot> inOrder = new ArrayList<>();
            List<Integer> left = new ArrayList<>();
            // The other orders take the first lots (FEFO, BR-75); this order is shown what remains after them.
            int toSkip = holding;
            List<Lot> found = lots.getOrDefault(productId, List.of());
            List<Integer> held = quantities.getOrDefault(productId, List.of());
            for (int i = 0; i < found.size(); i++) {
                int skipped = Math.min(toSkip, held.get(i));
                toSkip -= skipped;
                if (held.get(i) > skipped) {
                    inOrder.add(found.get(i));
                    left.add(held.get(i) - skipped);
                }
            }
            StockBalance place = places.get(productId);
            parts.put(productId, new Part(left.stream().mapToInt(Integer::intValue).sum(), holding,
                    place == null ? null : place.getStorageArea(), place == null ? null : place.getBinLocation(),
                    inOrder, left));
        }
        return parts;
    }

    /**
     * {@code sellableQty} of each part of each warehouse, as {@code parts(store, ids, today).get(id).sellableQty()} gives
     * it, for every (store, part) asked: {@code partsByStore} maps a store to the parts to read there. Two statements for
     * all the stores, however many there are. A pair with no lot or no hold reads 0; a part not asked is left out.
     *
     * <p>Why the sum is right: {@link #parts} hands the first lots in picking order to the other open orders (FEFO),
     * so what is left for this read is the lots' total less what those orders hold, never below 0. Nothing is
     * excluded for one order, the same as {@code parts} with no {@code orderId}.
     */
    public Map<Long, Map<Long, Integer>> sellableByStore(Map<Long, ? extends Collection<Long>> partsByStore,
                                                         LocalDate today) {
        if (partsByStore.isEmpty()) {
            return Map.of();
        }
        Set<Long> productIds = new HashSet<>();
        partsByStore.values().forEach(productIds::addAll);
        Map<Long, Map<Long, Integer>> lots = new HashMap<>();
        Map<Long, Map<Long, Integer>> holding = new HashMap<>();
        if (!productIds.isEmpty()) {
            for (Object[] row : orderLines.reservedByStore(productIds)) {
                holding.computeIfAbsent((Long) row[0], key -> new HashMap<>())
                        .put((Long) row[1], ((Number) row[2]).intValue());
            }
            for (Object[] row : sellable.sumSellableByStore(partsByStore.keySet(), productIds, today)) {
                lots.computeIfAbsent((Long) row[0], key -> new HashMap<>())
                        .put((Long) row[1], ((Number) row[2]).intValue());
            }
        }
        Map<Long, Map<Long, Integer>> sellableByStore = new HashMap<>();
        partsByStore.forEach((store, ids) -> {
            Map<Long, Integer> quantities = new HashMap<>();
            for (Long productId : ids) {
                int total = lots.getOrDefault(store, Map.of()).getOrDefault(productId, 0);
                int held = holding.getOrDefault(store, Map.of()).getOrDefault(productId, 0);
                quantities.put(productId, Math.max(0, total - held));
            }
            sellableByStore.put(store, quantities);
        });
        return sellableByStore;
    }
}
