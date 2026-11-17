package fu.hl.g64.motorflow.adjustment.helper;

import fu.hl.g64.motorflow.adjustment.dto.AdjustmentLineView;
import fu.hl.g64.motorflow.adjustment.entity.AdjustmentRequest;
import fu.hl.g64.motorflow.adjustment.entity.AdjustmentStockLine;
import fu.hl.g64.motorflow.adjustment.repository.AdjustmentStockLineRepository;
import fu.hl.g64.motorflow.adjustment.repository.AdjustmentStockLineRepository.OpenDeduction;
import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.dto.LotAdjustPosting;
import fu.hl.g64.motorflow.inventory.dto.LotQuantity;
import fu.hl.g64.motorflow.inventory.dto.OutPosting;
import fu.hl.g64.motorflow.inventory.entity.Lot;
import fu.hl.g64.motorflow.inventory.entity.LotBalance;
import fu.hl.g64.motorflow.inventory.entity.StockBalance;
import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.inventory.repository.LotBalanceRepository;
import fu.hl.g64.motorflow.inventory.repository.LotRepository;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.inventory.service.InventoryPostingService;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderRepository;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderRepository.TransitLot;
import fu.hl.g64.motorflow.transfer.repository.TransferOrderRepository.TransitLotOfPart;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The stock side of adjustment requests: what a warehouse can still give up (UC-83 step 4, UC-84 step 4), how a
 * request's lines look against the stock now (UC-84 step 2), and posting them when the request is applied (UC-84
 * step 7). Every write-off is an ADJUSTMENT movement valued at the moving average (BR-74), dated the day it is applied
 * in the open period (BR-65); the inventory posting service refuses a closed month (MSG85) and stock below zero
 * (MSG84).
 */
@Component
@RequiredArgsConstructor
public class AdjustmentStock {

    private final AdjustmentStockLineRepository stockLines;
    private final ProductRepository products;
    private final LotRepository lots;
    private final LotBalanceRepository lotBalances;
    private final StockBalanceRepository stockBalances;
    private final TransferOrderRepository transferOrders;
    private final InventoryPostingService posting;

    /** A wanted stock change before it is saved: {@code change} is negative for goods that leave. */
    public record Change(long productId, Long lotId, StockStatus status, int change) {
    }

    /**
     * Checks that the warehouse can give up every negative change, after what other open requests already take
     * (MSG257); a lot must be of the part (MSG258). {@code transferOrderId}: the changes are IN_TRANSIT goods of that
     * order at the destination warehouse {@code storeId}. {@code exceptRequestId} is the request being checked again
     * when it is approved, 0 for a new one.
     */
    public void requireAvailable(long storeId, Long transferOrderId, List<Change> changes, long exceptRequestId) {
        requireAvailable(storeId, transferOrderId, changes, exceptRequestId, Map.of());
    }

    /**
     * As above, keeping {@code reserved} units out of reach, keyed "productId:status": held goods of open supplier
     * discrepancy cases, which only a write-off linked to their delivery may take (UC-46, UC-83).
     */
    public void requireAvailable(long storeId, Long transferOrderId, List<Change> changes, long exceptRequestId,
                                 Map<String, Long> reserved) {
        Map<Long, Product> parts = parts(changes.stream().map(Change::productId).toList());
        Map<Long, Lot> lotById = lots.findAllById(changes.stream().map(Change::lotId).filter(Objects::nonNull)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Lot::getId, Function.identity()));
        boolean takesAny = changes.stream().anyMatch(c -> c.change() < 0);
        Snapshot stock = takesAny ? snapshot(storeId, transferOrderId, changes) : null;
        Taken taken = takesAny ? taken(storeId, transferOrderId, parts.keySet(), exceptRequestId) : null;
        Map<String, Integer> byPartAndStatus = new LinkedHashMap<>();
        for (Change c : changes) {
            Product part = parts.get(c.productId());
            if (c.lotId() != null) {
                Lot lot = lotById.get(c.lotId());
                if (lot == null || !lot.getProductId().equals(c.productId())) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG258", c.lotId(), part.getSku());
                }
                if (c.change() < 0) {
                    long free = stock.lot(lot.getId(), c.status())
                            - taken.lot(c.productId(), c.status(), lot.getId());
                    if (-c.change() > free) {
                        throw tooMuch(part, free);
                    }
                }
            }
            if (c.change() < 0) {
                byPartAndStatus.merge(c.productId() + ":" + c.status(), -c.change(), Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> e : byPartAndStatus.entrySet()) {
            String[] key = e.getKey().split(":");
            long productId = Long.parseLong(key[0]);
            StockStatus status = StockStatus.valueOf(key[1]);
            Product part = parts.get(productId);
            long free = transferOrderId != null
                    ? stock.transit(productId) - taken.transit(productId)
                    : stock.status(productId, status) - taken.status(productId, status)
                    - reserved.getOrDefault(e.getKey(), 0L);
            if (e.getValue() > free) {
                throw tooMuch(part, free);
            }
        }
    }

    /** MSG256: one line per part, lot and status; a line without a lot is the only line of its part and status. */
    public static void requireDistinct(List<Change> changes) {
        Set<String> seen = new HashSet<>();
        Set<String> partAndStatuses = new HashSet<>();
        Set<String> withoutLot = new HashSet<>();
        for (int i = 0; i < changes.size(); i++) {
            Change c = changes.get(i);
            String partAndStatus = c.productId() + ":" + c.status();
            boolean firstOfPartAndStatus = partAndStatuses.add(partAndStatus);
            boolean clash = !seen.add(partAndStatus + ":" + c.lotId())
                    || withoutLot.contains(partAndStatus)
                    || c.lotId() == null && !firstOfPartAndStatus;
            if (clash) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MSG256", i + 1);
            }
            if (c.lotId() == null) {
                withoutLot.add(partAndStatus);
            }
        }
    }

    /** The lines as they stand against the stock now (UC-84 step 2); cost figures only when {@code withCost}. */
    public List<AdjustmentLineView> views(long storeId, Long transferOrderId, List<Change> changes, boolean withCost) {
        Map<Long, Product> parts = parts(changes.stream().map(Change::productId).toList());
        Map<Long, Lot> lotById = lots.findAllById(changes.stream().map(Change::lotId).filter(Objects::nonNull)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Lot::getId, Function.identity()));
        Snapshot stock = snapshot(storeId, transferOrderId, changes);
        List<AdjustmentLineView> views = new ArrayList<>();
        for (Change c : changes) {
            Product part = parts.get(c.productId());
            Lot lot = c.lotId() == null ? null : lotById.get(c.lotId());
            int current = (int) (lot != null ? stock.lot(lot.getId(), c.status())
                    : transferOrderId != null && c.status() == StockStatus.IN_TRANSIT
                    ? stock.transit(c.productId())
                    : stock.status(c.productId(), c.status()));
            Long unitCost = withCost ? unitCost(part) : null;
            views.add(new AdjustmentLineView(part.getId(), part.getSku(), part.getPartName(), part.getBaseUnit(),
                    lot == null ? null : lot.getId(), lot == null ? null : lot.getManufacturerLotNo(),
                    lot == null ? null : lot.getExpiryDate(), c.status(), c.change(), current, current + c.change(),
                    unitCost, unitCost == null ? null : unitCost * c.change()));
        }
        return views;
    }

    /** The moving average cost of the part now, rounded to the dong (BR-74). */
    public static long unitCost(Product part) {
        BigDecimal average = part.getMovingAvgCost();
        return average == null ? 0 : average.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public static List<Change> changesOf(Collection<AdjustmentStockLine> lines) {
        return lines.stream().map(l -> new Change(l.getProductId(), l.getLotId(), l.getStockStatus(),
                l.getQuantityChange())).toList();
    }

    /**
     * UC-84 step 7: posts every stock line of the request, part by part in id order (the posting service's lock
     * order). A transit loss takes only the order's own goods in transit (BR-75); a line without a lot takes FEFO,
     * then FIFO; an increase goes back into its lot.
     */
    public void apply(AdjustmentRequest request, List<AdjustmentStockLine> lines, long ownerId) {
        SourceRef source = SourceRef.adjustmentRequest(request.getId());
        List<AdjustmentStockLine> ordered = lines.stream()
                .sorted(Comparator.comparing(AdjustmentStockLine::getProductId)
                        .thenComparing(AdjustmentStockLine::getStockStatus)
                        .thenComparing(l -> l.getLotId() == null ? 0L : l.getLotId()))
                .toList();
        for (AdjustmentStockLine line : ordered) {
            int change = line.getQuantityChange();
            if (request.getTransferOrderId() != null && line.getStockStatus() == StockStatus.IN_TRANSIT) {
                posting.takeOut(new OutPosting(line.getStoreId(), line.getProductId(), StockStatus.IN_TRANSIT,
                        -change, transitLots(request.getTransferOrderId(), line.getStoreId(), line.getProductId(),
                        -change), MovementType.ADJUSTMENT, source, ownerId, request.getReason()));
            } else if (line.getLotId() != null) {
                posting.adjustLot(new LotAdjustPosting(line.getStoreId(), line.getLotId(), line.getStockStatus(),
                        change, MovementType.ADJUSTMENT, source, ownerId, request.getReason()));
            } else if (change < 0) {
                posting.takeOut(new OutPosting(line.getStoreId(), line.getProductId(), line.getStockStatus(), -change,
                        null, MovementType.ADJUSTMENT, source, ownerId, request.getReason()));
            } else {
                throw new IllegalStateException("an increase needs a lot: line " + line.getId());
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    /** The order's goods of the part in transit, FEFO, enough for {@code quantity} (MSG84 when they are not). */
    private List<LotQuantity> transitLots(long orderId, long storeId, long productId, int quantity) {
        List<LotQuantity> taken = new ArrayList<>();
        int left = quantity;
        for (TransitLot lot : transferOrders.findTransitLots(orderId, storeId, productId)) {
            if (left == 0) {
                break;
            }
            int take = (int) Math.min(left, lot.getQuantity());
            taken.add(new LotQuantity(lot.getLotId(), take));
            left -= take;
        }
        if (left > 0) {
            Product part = products.findById(productId).orElseThrow();
            throw new BusinessException(HttpStatus.CONFLICT, "MSG84", part.getSku(), quantity, quantity - left);
        }
        return taken;
    }

    /**
     * The warehouse's stock of the changes' parts and lots, read once: lot balances by lot and status, part balances
     * by status, and, for a transit loss, the order's own goods in transit by part (BR-75).
     */
    private record Snapshot(Map<String, Long> byLot, Map<Long, StockBalance> byPart, Map<Long, Long> inTransit) {

        long lot(long lotId, StockStatus status) {
            return byLot.getOrDefault(lotId + ":" + status, 0L);
        }

        long status(long productId, StockStatus status) {
            StockBalance balance = byPart.get(productId);
            return balance == null ? 0 : balance.quantityOf(status);
        }

        long transit(long productId) {
            return inTransit.getOrDefault(productId, 0L);
        }
    }

    /**
     * What other open requests would already take: by part, status and lot at the warehouse, and, for a transit
     * loss, by part from the order's goods in transit.
     */
    private record Taken(Map<String, Long> byLot, Map<String, Long> byStatus, Map<Long, Long> fromTransit) {

        long lot(long productId, StockStatus status, long lotId) {
            return byLot.getOrDefault(productId + ":" + status + ":" + lotId, 0L);
        }

        /** Every lot of the part and status, and lines that name no lot. */
        long status(long productId, StockStatus status) {
            return byStatus.getOrDefault(productId + ":" + status, 0L);
        }

        long transit(long productId) {
            return fromTransit.getOrDefault(productId, 0L);
        }
    }

    private Taken taken(long storeId, Long transferOrderId, Collection<Long> productIds, long exceptRequestId) {
        Map<String, Long> byLot = new HashMap<>();
        Map<String, Long> byStatus = new HashMap<>();
        for (OpenDeduction d : stockLines.findOpenDeductions(storeId, productIds, exceptRequestId)) {
            String partAndStatus = d.getProductId() + ":" + d.getStatus();
            byStatus.merge(partAndStatus, d.getQuantity(), Long::sum);
            if (d.getLotId() != null) {
                byLot.merge(partAndStatus + ":" + d.getLotId(), d.getQuantity(), Long::sum);
            }
        }
        Map<Long, Long> fromTransit = new HashMap<>();
        if (transferOrderId != null) {
            for (Object[] row : stockLines.findOpenTransitWriteOffs(transferOrderId, productIds, exceptRequestId)) {
                fromTransit.put((Long) row[0], ((Number) row[1]).longValue());
            }
        }
        return new Taken(byLot, byStatus, fromTransit);
    }

    private Snapshot snapshot(long storeId, Long transferOrderId, List<Change> changes) {
        Set<Long> lotIds = changes.stream().map(Change::lotId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> productIds = changes.stream().map(Change::productId).collect(Collectors.toSet());
        Map<String, Long> byLot = new HashMap<>();
        if (!lotIds.isEmpty()) {
            for (LotBalance b : lotBalances.findByStoreIdAndLotIdIn(storeId, lotIds)) {
                byLot.merge(b.getLotId() + ":" + b.getStockStatus(), (long) b.getQuantity(), Long::sum);
            }
        }
        Map<Long, StockBalance> byPart = productIds.isEmpty() ? Map.of()
                : stockBalances.findByStoreIdAndProductIdIn(storeId, productIds).stream()
                .collect(Collectors.toMap(StockBalance::getProductId, Function.identity()));
        Map<Long, Long> inTransit = new HashMap<>();
        if (transferOrderId != null) {
            for (TransitLotOfPart t : transferOrders.findTransitLots(transferOrderId, storeId)) {
                inTransit.merge(t.getProductId(), t.getQuantity(), Long::sum);
            }
        }
        return new Snapshot(byLot, byPart, inTransit);
    }

    private Map<Long, Product> parts(Collection<Long> ids) {
        Map<Long, Product> found = products.findAllById(new HashSet<>(ids)).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        if (found.size() != new HashSet<>(ids).size()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "MSG57");
        }
        return found;
    }

    private static BusinessException tooMuch(Product part, long free) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "MSG257", part.getSku(), Math.max(free, 0));
    }
}
