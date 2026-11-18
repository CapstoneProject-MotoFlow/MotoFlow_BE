package fu.hl.g64.motorflow.stockcount.helper;

import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.dto.NewLotPosting;
import fu.hl.g64.motorflow.inventory.entity.InventoryPeriod;
import fu.hl.g64.motorflow.inventory.entity.StockCount;
import fu.hl.g64.motorflow.inventory.entity.StockCountLine;
import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.InventoryPeriodStatus;
import fu.hl.g64.motorflow.inventory.entity.enums.LotSourceType;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.inventory.helper.InventoryPeriods;
import fu.hl.g64.motorflow.inventory.repository.InventoryPeriodRepository;
import fu.hl.g64.motorflow.inventory.repository.LotRepository;
import fu.hl.g64.motorflow.inventory.repository.StockCountLineRepository;
import fu.hl.g64.motorflow.inventory.repository.StockMovementRepository;
import fu.hl.g64.motorflow.inventory.service.InventoryPostingService;
import fu.hl.g64.motorflow.stockcount.dto.OpeningCostsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * UC-96, BR-74, BR-86: the rules of an opening count. It is allowed only while nothing has moved in the warehouse,
 * in a month not closed yet. On approval each counted line (one part and expiry date) becomes one OPENING lot at the
 * part's initial unit cost, posted as OPENING_BALANCE at the end of the count day.
 */
@Component
@RequiredArgsConstructor
public class OpeningBalances {

    private final StockMovementRepository movements;
    private final InventoryPeriodRepository periods;
    private final InventoryPeriods inventoryPeriods;
    private final StockCountLineRepository lines;
    private final ProductRepository products;
    private final LotRepository lots;
    private final InventoryPostingService posting;

    /** MSG244: opening stock is recorded once, before anything else moves in the warehouse (BR-86). */
    public void requireNoMovement(long storeId) {
        if (movements.existsByStoreId(storeId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG244");
        }
    }

    /**
     * MSG232 unless {@code date}'s month is open, or has no period yet and comes after every closed month: the
     * opening balances are posted into that month.
     */
    public void requireMonthOpen(LocalDate date) {
        boolean closed = inventoryPeriods.findFor(date).map(p -> !p.isOpen()).orElse(false)
                || periods.existsByStatusAndPeriodMonthGreaterThanEqual(InventoryPeriodStatus.CLOSED,
                date.withDayOfMonth(1));
        if (closed) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG232");
        }
    }

    /** Whether an opening count with this part was approved at its initial unit cost, which then stays (BR-74). */
    public boolean costLocked(Product part) {
        return part.getInitialUnitCost() != null
                && lots.existsByProductIdAndSourceType(part.getId(), LotSourceType.OPENING);
    }

    /** The counted lines with a quantity, by part then expiry date (no expiry first). */
    public List<StockCountLine> countedLines(StockCount count) {
        return lines.findByStockCountIdOrderByProductIdAscExpiryDateAscIdAsc(count.getId()).stream()
                .filter(l -> l.isCounted() && l.getCountedQty() > 0)
                .sorted(Comparator.comparing(StockCountLine::getProductId)
                        .thenComparing(StockCountLine::getExpiryDate, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    /** What approval will post, at the initial unit costs entered so far. */
    public OpeningCostsResponse preview(StockCount count) {
        List<StockCountLine> counted = countedLines(count);
        Map<Long, List<StockCountLine>> byPart = counted.stream()
                .collect(Collectors.groupingBy(StockCountLine::getProductId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, Product> parts = products.findAllById(byPart.keySet()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        List<OpeningCostsResponse.Part> rows = byPart.entrySet().stream().map(e -> {
            Product part = parts.get(e.getKey());
            Long cost = part.getInitialUnitCost();
            List<OpeningCostsResponse.Lot> lotRows = e.getValue().stream()
                    .map(l -> new OpeningCostsResponse.Lot(l.getExpiryDate(), l.getCountedQty(), l.getDamagedQty(),
                            cost == null ? null : cost * l.getCountedQty()))
                    .toList();
            int quantity = e.getValue().stream().mapToInt(StockCountLine::getCountedQty).sum();
            return new OpeningCostsResponse.Part(part.getId(), part.getSku(), part.getPartName(), part.getBaseUnit(),
                    cost, costLocked(part), quantity, cost == null ? null : cost * quantity, lotRows);
        }).toList();
        List<String> missing = rows.stream().filter(r -> r.initialUnitCost() == null)
                .map(OpeningCostsResponse.Part::sku).toList();
        long total = rows.stream().map(OpeningCostsResponse.Part::value).filter(Objects::nonNull)
                .mapToLong(Long::longValue).sum();
        return new OpeningCostsResponse(count.getId(), missing.isEmpty(), missing, total, rows);
    }

    /**
     * Approval of an opening count: MSG244 if the warehouse moved meanwhile, MSG232 if its month closed, MSG300 while
     * a part has no initial unit cost. Each line becomes one OPENING lot; the first lot of a part sets its moving
     * average to the initial unit cost (BR-74).
     */
    public void post(StockCount count, long ownerId) {
        requireNoMovement(count.getStoreId());
        requireMonthOpen(count.getCountDate());
        OpeningCostsResponse preview = preview(count);
        if (!preview.complete()) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG300", preview.missingSkus().size(),
                    String.join(", ", preview.missingSkus()));
        }
        InventoryPeriod period = inventoryPeriods.openFor(count.getCountDate());
        LocalDateTime occurredAt = count.getCountDate().atTime(23, 59, 59);
        Map<Long, Product> parts = products.findAllById(preview.parts().stream()
                .map(OpeningCostsResponse.Part::productId).toList()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        for (StockCountLine line : countedLines(count)) {
            Map<StockStatus, Integer> quantities = new LinkedHashMap<>();
            int available = line.getCountedQty() - line.getDamagedQty();
            if (available > 0) {
                quantities.put(StockStatus.AVAILABLE, available);
            }
            if (line.getDamagedQty() > 0) {
                quantities.put(StockStatus.DAMAGED, line.getDamagedQty());
            }
            posting.receiveNewLot(new NewLotPosting(count.getStoreId(), line.getProductId(), LotSourceType.OPENING,
                    null, null, count.getCountDate(), parts.get(line.getProductId()).getInitialUnitCost(), null, null,
                    line.getExpiryDate(), quantities, MovementType.OPENING_BALANCE, SourceRef.stockCount(count.getId()),
                    ownerId, null, period.getId(), occurredAt));
        }
    }
}
