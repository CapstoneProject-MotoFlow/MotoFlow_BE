package fu.hl.g64.motorflow.sales.helper;

import fu.hl.g64.motorflow.catalog.entity.ProductIdentifier;
import fu.hl.g64.motorflow.catalog.entity.enums.IdentifierType;
import fu.hl.g64.motorflow.catalog.repository.ProductIdentifierRepository;
import fu.hl.g64.motorflow.inventory.entity.Lot;
import fu.hl.g64.motorflow.inventory.entity.StockBalance;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.sales.repository.SellableLotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What a seller or picker needs to know about the goods of a warehouse: how much can be handed over now (available
 * and not expired, BR-76), where the part is kept and which lots to take first (BR-75). Reading it reserves nothing
 * (BR-33).
 */
@Component
@RequiredArgsConstructor
public class PickingGuide {

    private final SellableLotRepository sellable;
    private final StockBalanceRepository balances;
    private final ProductIdentifierRepository identifiers;

    /** One lot to take from, with how many of the needed units it covers. */
    public record LotToPick(long lotId, String manufacturerLotNo, LocalDate manufactureDate, LocalDate expiryDate,
                            LocalDate receivedDate, int availableQty, int takeQty) {
    }

    /** One part in one warehouse. */
    public record Part(int sellableQty, String storageArea, String binLocation, List<String> barcodes,
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

    /** The parts as they stand in the store's warehouse today; a part the warehouse never held has nothing. */
    public Map<Long, Part> parts(long storeId, Collection<Long> productIds, LocalDate today) {
        if (productIds.isEmpty()) {
            return Map.of();
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
        Map<Long, List<String>> barcodes = identifiers.findByProductIdIn(productIds).stream()
                .filter(x -> x.getIdentifierType() == IdentifierType.BARCODE)
                .collect(Collectors.groupingBy(ProductIdentifier::getProductId,
                        Collectors.mapping(ProductIdentifier::getIdentifierValue, Collectors.toList())));
        Map<Long, Part> parts = new HashMap<>();
        for (Long productId : productIds) {
            List<Integer> held = quantities.getOrDefault(productId, List.of());
            StockBalance place = places.get(productId);
            parts.put(productId, new Part(held.stream().mapToInt(Integer::intValue).sum(),
                    place == null ? null : place.getStorageArea(), place == null ? null : place.getBinLocation(),
                    barcodes.getOrDefault(productId, List.of()), lots.getOrDefault(productId, List.of()), held));
        }
        return parts;
    }
}
