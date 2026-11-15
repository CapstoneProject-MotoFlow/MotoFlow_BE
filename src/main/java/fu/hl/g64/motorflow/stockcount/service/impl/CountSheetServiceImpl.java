package fu.hl.g64.motorflow.stockcount.service.impl;

import fu.hl.g64.motorflow.catalog.service.PartCategoryService;
import fu.hl.g64.motorflow.inventory.entity.StockCount;
import fu.hl.g64.motorflow.inventory.entity.StockCountLine;
import fu.hl.g64.motorflow.inventory.entity.enums.ScopeType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;
import fu.hl.g64.motorflow.inventory.repository.LotBalanceRepository;
import fu.hl.g64.motorflow.inventory.repository.LotBalanceRepository.CountedLot;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.inventory.repository.StockCountLineRepository;
import fu.hl.g64.motorflow.stockcount.service.CountSheetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        List<Long> parts = switch (count.getScopeType()) {
            case WHOLE -> lotBalances.findProductIdsWithCountableStock(count.getStoreId());
            case AREA -> stockBalances.findProductIdsByStorageArea(count.getStoreId(), count.getScopeNote());
            case PRODUCTS -> productIds;
            case CATEGORY -> lotBalances.findProductIdsWithStockInCategories(count.getStoreId(),
                    categories.withDescendants(count.getPartCategoryId()));
        };
        Map<Long, List<CountedLot>> lotsByPart = parts.isEmpty() ? Map.of()
                : lotBalances.findCountedLots(count.getStoreId(), parts).stream()
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
        for (StockCountLine line : targets) {
            List<CountedLot> matched = lots.stream()
                    .filter(lot -> line.getLotId() == null
                            ? lot.getProductId().equals(line.getProductId()) && lot.getExpiryDate() == null
                            : lot.getLotId().equals(line.getLotId()))
                    .toList();
            int book = matched.stream().mapToInt(lot -> lot.getAvailable() + lot.getDamaged()).sum();
            int bookDamaged = matched.stream().mapToInt(CountedLot::getDamaged).sum();
            if (restart) {
                line.restartRecount(book, bookDamaged);
            } else {
                line.takeBookQty(book, bookDamaged);
            }
        }
    }
    // endregion

    // region needsApproval (owner: Z)
    @Override
    public boolean needsApproval(List<StockCountLine> countLines) {
        return countLines.stream().anyMatch(StockCountLine::needsApproval);
    }
    // endregion
}