package fu.hl.g64.motorflow.stockcount.service.impl;

import fu.hl.g64.motorflow.catalog.service.PartCategoryService;
import fu.hl.g64.motorflow.inventory.entity.StockCount;
import fu.hl.g64.motorflow.inventory.entity.StockCountLine;
import fu.hl.g64.motorflow.inventory.entity.enums.ScopeType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;
import fu.hl.g64.motorflow.inventory.helper.DefectiveArea;
import fu.hl.g64.motorflow.inventory.repository.LotBalanceRepository;
import fu.hl.g64.motorflow.inventory.repository.LotBalanceRepository.CountedLot;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.inventory.repository.StockCountLineRepository;
import fu.hl.g64.motorflow.stockcount.service.CountSheetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CountSheetServiceImpl implements CountSheetService {

    private final StockCountLineRepository lines;
    private final LotBalanceRepository lotBalances;
    private final StockBalanceRepository stockBalances;
    private final PartCategoryService categories;

    // region buildLines (owner: Y)
    @Override
    public List<StockCountLine> buildLines(StockCount count, List<Long> productIds) {
        List<Long> parts = partsInScope(count.getStoreId(), count.getScopeType(), count.getScopeNote(),
                count.getPartCategoryId(), productIds);
        Portion portion = portion(count);
        Map<Long, List<CountedLot>> lotsByPart = parts.isEmpty() ? Map.of()
                : lotBalances.findCountedLots(count.getStoreId(), parts).stream()
                .filter(lot -> portion.counted(lot) > 0)
                .collect(Collectors.groupingBy(CountedLot::getProductId));
        List<StockCountLine> built = new ArrayList<>();
        for (Long part : parts) {
            List<CountedLot> lots = lotsByPart.getOrDefault(part, List.of());
            boolean anyWithoutExpiry = false;
            for (CountedLot lot : lots) {
                if (lot.getExpiryDate() == null) {
                    anyWithoutExpiry = true;
                } else {
                    built.add(StockCountLine.create(count.getId(), part, lot.getLotId(), lot.getExpiryDate()));
                }
            }
            // The lots without expiry share one line; a listed part with no lot at all still gets it.
            if (anyWithoutExpiry || (lots.isEmpty() && count.getScopeType() == ScopeType.PRODUCTS)) {
                built.add(StockCountLine.create(count.getId(), part, null, null));
            }
        }
        return lines.saveAll(built);
    }
    // endregion

    @Override
    public List<Long> partsInScope(long storeId, ScopeType scopeType, String scopeNote, Long partCategoryId,
                                   List<Long> productIds) {
        return switch (scopeType) {
            case WHOLE -> lotBalances.findProductIdsWithCountableStock(storeId);
            case AREA -> defective(scopeType, scopeNote) ? defectiveParts(storeId, scopeNote)
                    : stockBalances.findProductIdsByStorageArea(storeId, scopeNote);
            case PRODUCTS -> productIds;
            case CATEGORY -> lotBalances.findProductIdsWithStockInCategories(storeId,
                    categories.withDescendants(partCategoryId));
        };
    }

    // region captureBookQuantities (owner: Y)
    @Override
    public void captureBookQuantities(StockCount count, List<StockCountLine> countLines) {
        // A count already counting is starting again: only the lines sent back take a new book quantity (BR-79).
        boolean restart = count.getStatus() == StockCountStatus.COUNTING;
        List<StockCountLine> targets = countLines.stream().filter(l -> !restart || l.isRecountRequested()).toList();
        if (targets.isEmpty()) {
            return;
        }
        List<Long> parts = targets.stream().map(StockCountLine::getProductId).distinct().toList();
        List<CountedLot> lots = lotBalances.findCountedLots(count.getStoreId(), parts);
        Portion portion = portion(count);
        for (StockCountLine line : targets) {
            List<CountedLot> matched = lots.stream()
                    .filter(lot -> line.getLotId() == null
                            ? lot.getProductId().equals(line.getProductId()) && lot.getExpiryDate() == null
                            : lot.getLotId().equals(line.getLotId()))
                    .toList();
            int book = matched.stream().mapToInt(portion::counted).sum();
            int bookDamaged = matched.stream().mapToInt(portion::damaged).sum();
            if (restart) {
                line.restartRecount(book, bookDamaged);
            } else {
                line.takeBookQty(book, bookDamaged);
            }
        }
    }
    // endregion

    /*
     * GVHD 20/11: damaged goods are kept in the defective area, not on the shelf of their part. A count of the
     * defective area counts them, and all the stock of the parts shelved there (the dented-goods variants); a count of
     * another area counts only the sellable goods of its parts. So counting both areas never counts the same goods
     * twice. A count of the whole warehouse, a category or listed parts counts both, as before.
     */
    private static boolean defective(ScopeType scopeType, String scopeNote) {
        return scopeType == ScopeType.AREA && DefectiveArea.is(scopeNote);
    }

    private List<Long> defectiveParts(long storeId, String scopeNote) {
        Set<Long> parts = new HashSet<>(stockBalances.findProductIdsByStorageArea(storeId, DefectiveArea.NAME));
        parts.addAll(stockBalances.findProductIdsWithDamaged(storeId));
        return parts.stream().sorted().toList();
    }

    private Portion portion(StockCount count) {
        if (count.getScopeType() != ScopeType.AREA) {
            return new Portion(Part.ALL, Set.of());
        }
        if (!defective(count.getScopeType(), count.getScopeNote())) {
            return new Portion(Part.SELLABLE, Set.of());
        }
        return new Portion(Part.DAMAGED, Set.copyOf(
                stockBalances.findProductIdsByStorageArea(count.getStoreId(), DefectiveArea.NAME)));
    }

    private enum Part { ALL, SELLABLE, DAMAGED }

    /** What a count takes of one lot; the parts in {@code shelvedThere} are always counted whole. */
    private record Portion(Part part, Set<Long> shelvedThere) {

        int counted(CountedLot lot) {
            return available(lot) + damaged(lot);
        }

        int available(CountedLot lot) {
            return part != Part.DAMAGED || shelvedThere.contains(lot.getProductId()) ? lot.getAvailable() : 0;
        }

        int damaged(CountedLot lot) {
            return part != Part.SELLABLE ? lot.getDamaged() : 0;
        }
    }

    // region needsApproval (owner: Z)
    @Override
    public boolean needsApproval(List<StockCountLine> countLines) {
        return countLines.stream().anyMatch(StockCountLine::needsApproval);
    }
    // endregion
}