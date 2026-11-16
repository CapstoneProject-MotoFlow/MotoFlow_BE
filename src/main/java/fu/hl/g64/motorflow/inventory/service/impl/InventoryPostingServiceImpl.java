package fu.hl.g64.motorflow.inventory.service.impl;

import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.dto.LotAdjustPosting;
import fu.hl.g64.motorflow.inventory.dto.LotQuantity;
import fu.hl.g64.motorflow.inventory.dto.LotReceiptPosting;
import fu.hl.g64.motorflow.inventory.dto.MovePosting;
import fu.hl.g64.motorflow.inventory.dto.NewLotPosting;
import fu.hl.g64.motorflow.inventory.dto.OutPosting;
import fu.hl.g64.motorflow.inventory.dto.SplitMovePosting;
import fu.hl.g64.motorflow.inventory.entity.InventoryPeriod;
import fu.hl.g64.motorflow.inventory.entity.Lot;
import fu.hl.g64.motorflow.inventory.entity.LotBalance;
import fu.hl.g64.motorflow.inventory.entity.StockBalance;
import fu.hl.g64.motorflow.inventory.entity.StockMovement;
import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.LotSourceType;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.inventory.helper.InventoryPeriods;
import fu.hl.g64.motorflow.inventory.repository.InventoryPeriodRepository;
import fu.hl.g64.motorflow.inventory.repository.LotBalanceRepository;
import fu.hl.g64.motorflow.inventory.repository.LotRepository;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.inventory.repository.StockMovementRepository;
import fu.hl.g64.motorflow.inventory.service.InventoryPostingService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InventoryPostingServiceImpl implements InventoryPostingService {

    /** Movements that put goods in front of a customer or another store: expired lots are never used (BR-76). */
    private static final Set<MovementType> NO_EXPIRED_LOTS =
            EnumSet.of(MovementType.SALE_ISSUE, MovementType.TRANSFER_DISPATCH, MovementType.TRANSFER_EXTRA_DISPATCH);

    private final ProductRepository products;
    private final LotRepository lots;
    private final LotBalanceRepository lotBalances;
    private final StockBalanceRepository stockBalances;
    private final StockMovementRepository movements;
    private final InventoryPeriods inventoryPeriods;
    private final InventoryPeriodRepository periods;
    private final Clock clock;
    private final EntityManager em;

    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public Lot receiveNewLot(NewLotPosting p) {
        LocalDateTime now = LocalDateTime.now(clock);
        PostingTime when = postingTime(p.movementType(), p.periodId(), p.occurredAt(), now);
        InventoryPeriod period = when.period();
        // Lock order everywhere: stock_balance, then product, then lot_balance.
        StockBalance balance = lockBalance(p.storeId(), p.productId(), now);
        Product product = fresh(products.findByIdForUpdate(p.productId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG57")));

        int available = p.quantities().getOrDefault(StockStatus.AVAILABLE, 0);
        // A count surplus comes in at the average (BR-74), so it leaves the average alone.
        boolean surplus = p.sourceType() == LotSourceType.COUNT_SURPLUS;
        if (!surplus) {
            product.receiveIntoAverage(stockBalances.sumValuedQuantity(p.productId()), available,
                    p.purchaseUnitPrice());
        }

        Long stockCountId = p.sourceType() == LotSourceType.RECEIPT ? null : p.source().id();
        Lot lot = lots.save(Lot.create(p.productId(), p.sourceType(), p.goodsReceiptNoteLineId(), stockCountId,
                p.supplierId(), p.storeId(), p.receivedDate(), p.purchaseUnitPrice(), p.manufacturerLotNo(),
                p.manufactureDate(), p.expiryDate()));

        BigDecimal unitCost = surplus && product.getMovingAvgCost() != null ? product.getMovingAvgCost()
                : BigDecimal.valueOf(p.purchaseUnitPrice());
        for (Map.Entry<StockStatus, Integer> e : p.quantities().entrySet()) {
            if (e.getValue() == null || e.getValue() == 0) {
                continue;
            }
            if (e.getValue() < 0) {
                throw new IllegalArgumentException("received quantity must be positive");
            }
            post(balance, lot, e.getKey(), e.getValue(), unitCost, p.movementType(), p.source(), p.reason(),
                    p.performedBy(), period, now, when.at());
        }
        refreshDepletion(lot);
        return lot;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public List<LotQuantity> takeOut(OutPosting p) {
        return takeOutAll(List.of(p)).getFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public List<List<LotQuantity>> takeOutAll(List<OutPosting> postings) {
        if (postings.isEmpty()) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        InventoryPeriod period = inventoryPeriods.openFor(now.toLocalDate());
        Map<StoreProduct, StockBalance> balances = lockBalances(postings.stream()
                .map(p -> new StoreProduct(p.storeId(), p.productId())).toList(), now);
        Batch batch = batch(postings.stream().map(OutPosting::productId).toList(), postings.stream()
                .filter(p -> p.lots() == null || p.lots().isEmpty())
                .map(p -> new PoolKey(p.storeId(), p.fromStatus(), p.productId())).toList(),
                namedLots(postings.stream().map(OutPosting::lots).toList()), now);
        List<List<LotQuantity>> taken = new ArrayList<>();
        for (OutPosting p : postings) {
            Product product = batch.product(p.productId());
            List<Pick> picks = choose(p.storeId(), product, p.fromStatus(), p.quantity(), p.lots(), p.movementType(),
                    batch, now);
            StockBalance balance = balances.get(new StoreProduct(p.storeId(), p.productId()));
            List<LotQuantity> line = new ArrayList<>();
            for (Pick pick : picks) {
                post(balance, pick.lot, p.fromStatus(), -pick.quantity, costOf(product, pick.lot), p.movementType(),
                        p.source(), p.reason(), p.performedBy(), period, now, now);
                refreshDepletion(pick.lot);
                line.add(new LotQuantity(pick.lot.getId(), pick.quantity));
            }
            taken.add(line);
        }
        return taken;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public List<LotQuantity> move(MovePosting p) {
        return moveAll(List.of(p)).getFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public List<List<LotQuantity>> moveAll(List<MovePosting> postings) {
        if (postings.isEmpty()) {
            return List.of();
        }
        for (MovePosting p : postings) {
            if (p.fromStoreId().equals(p.toStoreId()) && p.fromStatus() == p.toStatus()) {
                throw new IllegalArgumentException("a move must change the status or the warehouse");
            }
        }
        LocalDateTime now = LocalDateTime.now(clock);
        InventoryPeriod period = inventoryPeriods.openFor(now.toLocalDate());
        List<StoreProduct> keys = new ArrayList<>();
        postings.forEach(p -> {
            keys.add(new StoreProduct(p.fromStoreId(), p.productId()));
            keys.add(new StoreProduct(p.toStoreId(), p.productId()));
        });
        Map<StoreProduct, StockBalance> balances = lockBalances(keys, now);
        Batch batch = batch(postings.stream().map(MovePosting::productId).toList(), postings.stream()
                .filter(p -> p.lots() == null || p.lots().isEmpty())
                .map(p -> new PoolKey(p.fromStoreId(), p.fromStatus(), p.productId())).toList(),
                namedLots(postings.stream().map(MovePosting::lots).toList()), now);
        List<List<LotQuantity>> moved = new ArrayList<>();
        for (MovePosting p : postings) {
            Product product = batch.product(p.productId());
            List<Pick> picks = choose(p.fromStoreId(), product, p.fromStatus(), p.quantity(), p.lots(),
                    p.movementType(), batch, now);
            StockBalance from = balances.get(new StoreProduct(p.fromStoreId(), p.productId()));
            StockBalance to = balances.get(new StoreProduct(p.toStoreId(), p.productId()));
            List<LotQuantity> line = new ArrayList<>();
            for (Pick pick : picks) {
                BigDecimal unitCost = costOf(product, pick.lot);
                post(from, pick.lot, p.fromStatus(), -pick.quantity, unitCost, p.movementType(), p.source(),
                        p.reason(), p.performedBy(), period, now, now);
                post(to, pick.lot, p.toStatus(), pick.quantity, unitCost, p.movementType(), p.source(), p.reason(),
                        p.performedBy(), period, now, now);
                line.add(new LotQuantity(pick.lot.getId(), pick.quantity));
            }
            moved.add(line);
        }
        return moved;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public void moveToStatusesAll(List<SplitMovePosting> postings) {
        if (postings.isEmpty()) {
            return;
        }
        List<PreparedSplit> prepared = new ArrayList<>();
        List<StoreProduct> keys = new ArrayList<>();
        for (SplitMovePosting p : postings) {
            if (p.lots() == null || p.lots().isEmpty()) {
                throw new IllegalArgumentException("the lots to move must be given");
            }
            Map<StockStatus, Integer> wanted = new EnumMap<>(StockStatus.class);
            p.toQuantities().forEach((status, quantity) -> {
                if (quantity < 0 || (p.fromStoreId().equals(p.toStoreId()) && status == p.fromStatus())) {
                    throw new IllegalArgumentException("cannot move " + quantity + " into " + status);
                }
                if (quantity > 0) {
                    wanted.put(status, quantity);
                }
            });
            prepared.add(new PreparedSplit(p, wanted));
            keys.add(new StoreProduct(p.fromStoreId(), p.productId()));
            keys.add(new StoreProduct(p.toStoreId(), p.productId()));
        }
        LocalDateTime now = LocalDateTime.now(clock);
        InventoryPeriod period = inventoryPeriods.openFor(now.toLocalDate());
        Map<StoreProduct, StockBalance> balances = lockBalances(keys, now);
        Batch batch = batch(postings.stream().map(SplitMovePosting::productId).toList(), List.of(),
                namedLots(postings.stream().map(SplitMovePosting::lots).toList()), now);
        for (PreparedSplit row : prepared) {
            SplitMovePosting p = row.posting;
            Product product = batch.product(p.productId());
            int total = row.wanted.values().stream().mapToInt(Integer::intValue).sum();
            List<Pick> picks = choose(p.fromStoreId(), product, p.fromStatus(), total, p.lots(), p.movementType(),
                    batch, now);
            StockBalance from = balances.get(new StoreProduct(p.fromStoreId(), p.productId()));
            StockBalance to = balances.get(new StoreProduct(p.toStoreId(), p.productId()));
            Iterator<Map.Entry<StockStatus, Integer>> statuses = row.wanted.entrySet().iterator();
            Map.Entry<StockStatus, Integer> filling = statuses.next();
            int left = filling.getValue();
            for (Pick pick : picks) {
                BigDecimal unitCost = costOf(product, pick.lot);
                post(from, pick.lot, p.fromStatus(), -pick.quantity, unitCost, p.movementType(), p.source(),
                        p.reason(), p.performedBy(), period, now, now);
                int lotLeft = pick.quantity;
                while (lotLeft > 0) {
                    if (left == 0) {
                        filling = statuses.next();
                        left = filling.getValue();
                    }
                    int part = Math.min(lotLeft, left);
                    post(to, pick.lot, filling.getKey(), part, unitCost, p.movementType(), p.source(), p.reason(),
                            p.performedBy(), period, now, now);
                    lotLeft -= part;
                    left -= part;
                }
            }
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public void adjustLot(LotAdjustPosting p) {
        if (p.quantityChange() == 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        PostingTime when = postingTime(p.movementType(), p.periodId(), p.occurredAt(), now);
        Lot lot = lots.findById(p.lotId()).orElseThrow(() -> new IllegalArgumentException("unknown lot " + p.lotId()));
        StockBalance balance = lockBalance(p.storeId(), lot.getProductId(), now);
        Product product = product(lot.getProductId());
        if (p.quantityChange() < 0) {
            int held = lotBalance(lot.getId(), p.storeId(), p.status(), now).getQuantity();
            if (held + p.quantityChange() < 0) {
                throw notEnough(product, -p.quantityChange(), held);
            }
        }
        post(balance, lot, p.status(), p.quantityChange(), costOf(product, lot), p.movementType(), p.source(),
                p.reason(), p.performedBy(), when.period(), now, when.at());
        refreshDepletion(lot);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public void receiveIntoLot(LotReceiptPosting p) {
        LocalDateTime now = LocalDateTime.now(clock);
        InventoryPeriod period = inventoryPeriods.openFor(now.toLocalDate());
        Lot lot = lots.findById(p.lotId()).orElseThrow(() -> new IllegalArgumentException("unknown lot " + p.lotId()));
        if (lot.getSourceType() != LotSourceType.RECEIPT) {
            throw new IllegalArgumentException("lot " + lot.getId() + " did not come from a supplier receipt");
        }
        // Same lock order as receiveNewLot: stock_balance, then product, then lot_balance.
        StockBalance balance = lockBalance(p.storeId(), lot.getProductId(), now);
        Product product = fresh(products.findByIdForUpdate(lot.getProductId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG57")));

        int available = p.quantities().getOrDefault(StockStatus.AVAILABLE, 0);
        product.receiveIntoAverage(stockBalances.sumValuedQuantity(lot.getProductId()), available,
                lot.getPurchaseUnitPrice());

        BigDecimal unitCost = BigDecimal.valueOf(lot.getPurchaseUnitPrice());
        for (Map.Entry<StockStatus, Integer> e : p.quantities().entrySet()) {
            if (e.getValue() == null || e.getValue() == 0) {
                continue;
            }
            if (e.getValue() < 0) {
                throw new IllegalArgumentException("received quantity must be positive");
            }
            post(balance, lot, e.getKey(), e.getValue(), unitCost, p.movementType(), p.source(), p.reason(),
                    p.performedBy(), period, now, now);
        }
        refreshDepletion(lot);
    }

    // ------------------------------------------------------------------------------------------------------------

    private record Pick(Lot lot, int quantity) {
    }

    private record StoreProduct(long storeId, long productId) {
    }

    private record PoolKey(long storeId, StockStatus status, long productId) {
    }

    private record PreparedSplit(SplitMovePosting posting, Map<StockStatus, Integer> wanted) {
    }

    /** Lots still available to a later line of the same part in this document. */
    private static final class Held {
        private final Lot lot;
        private int remaining;

        private Held(Lot lot, int remaining) {
            this.lot = lot;
            this.remaining = remaining;
        }
    }

    private static final class Batch {
        private final Map<Long, Product> products;
        private final Map<Long, Lot> lots;
        private final Map<PoolKey, List<Held>> open;

        private Batch(Map<Long, Product> products, Map<Long, Lot> lots, Map<PoolKey, List<Held>> open) {
            this.products = products;
            this.lots = lots;
            this.open = open;
        }

        private Product product(long productId) {
            Product product = products.get(productId);
            if (product == null) {
                throw new BusinessException(HttpStatus.NOT_FOUND, "MSG57");
            }
            return product;
        }
    }

    /** Locks every warehouse row in store id then product id order, after creating any missing row. */
    private Map<StoreProduct, StockBalance> lockBalances(List<StoreProduct> keys, LocalDateTime now) {
        List<StoreProduct> ordered = keys.stream().distinct()
                .sorted(Comparator.comparingLong(StoreProduct::storeId).thenComparingLong(StoreProduct::productId))
                .toList();
        for (StoreProduct key : ordered) {
            stockBalances.insertIfMissing(key.storeId(), key.productId(), now);
        }
        Map<Long, List<Long>> byStore = ordered.stream().collect(Collectors.groupingBy(StoreProduct::storeId,
                LinkedHashMap::new, Collectors.mapping(StoreProduct::productId, Collectors.toList())));
        Map<StoreProduct, StockBalance> locked = new HashMap<>();
        em.flush();
        for (Map.Entry<Long, List<Long>> store : byStore.entrySet()) {
            for (StockBalance balance : stockBalances.findByStoreForUpdate(store.getKey(), store.getValue())) {
                em.refresh(balance);
                locked.put(new StoreProduct(balance.getStoreId(), balance.getProductId()), balance);
            }
        }
        return locked;
    }

    private static List<Long> namedLots(List<List<LotQuantity>> lines) {
        return lines.stream().filter(line -> line != null).flatMap(Collection::stream).map(LotQuantity::lotId).distinct()
                .toList();
    }

    /** One read of the parts and of every lot the document may take, grouped so later lines see what earlier lines took. */
    private Batch batch(Collection<Long> productIds, List<PoolKey> openKeys, Collection<Long> namedLotIds,
                        LocalDateTime now) {
        Map<Long, Product> parts = products.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<PoolKey, List<Long>> productsByPool = openKeys.stream().collect(Collectors.groupingBy(
                key -> new PoolKey(key.storeId(), key.status(), 0),
                Collectors.mapping(PoolKey::productId, Collectors.toList())));
        Map<PoolKey, List<Held>> open = new HashMap<>();
        Set<Long> lotIds = new HashSet<>(namedLotIds);
        List<LotBalance> picked = new ArrayList<>();
        for (Map.Entry<PoolKey, List<Long>> pool : productsByPool.entrySet()) {
            List<Long> ids = pool.getValue().stream().distinct().toList();
            if (ids.isEmpty()) {
                continue;
            }
            List<LotBalance> rows = lotBalances.findPickableForUpdate(pool.getKey().storeId(), ids,
                    pool.getKey().status());
            rows.forEach(em::refresh);
            rows.forEach(row -> lotIds.add(row.getLotId()));
            picked.addAll(rows);
        }
        Map<Long, Lot> loaded = lotIds.isEmpty() ? Map.of() : lots.findAllById(lotIds).stream()
                .collect(Collectors.toMap(Lot::getId, Function.identity()));
        Map<PoolKey, List<Held>> built = new HashMap<>();
        for (LotBalance row : picked) {
            Lot lot = loaded.get(row.getLotId());
            built.computeIfAbsent(new PoolKey(row.getStoreId(), row.getStockStatus(), lot.getProductId()),
                    id -> new ArrayList<>()).add(new Held(lot, row.getQuantity()));
        }
        open.putAll(built);
        return new Batch(parts, loaded, open);
    }

    /** Chooses the lots to take {@code quantity} from, after checking there is enough (MSG84) and no expired lot (MSG86). */
    private List<Pick> choose(Long storeId, Product product, StockStatus status, int quantity, List<LotQuantity> wanted,
                              MovementType type, Batch batch, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        boolean freshOnly = status == StockStatus.AVAILABLE && NO_EXPIRED_LOTS.contains(type);
        List<Pick> picks = new ArrayList<>();
        if (wanted != null && !wanted.isEmpty()) {
            int total = 0;
            Set<Long> seen = new HashSet<>();
            for (LotQuantity w : wanted) {
                if (w.quantity() <= 0) {
                    throw new IllegalArgumentException("lot " + w.lotId() + " must have a positive quantity");
                }
                if (!seen.add(w.lotId())) {
                    throw new IllegalArgumentException("lot " + w.lotId() + " is listed twice");
                }
                Lot lot = batch.lots.get(w.lotId());
                if (lot == null) {
                    lot = lots.findById(w.lotId()).orElse(null);
                }
                if (lot == null || !lot.getProductId().equals(product.getId())) {
                    throw new IllegalArgumentException("lot " + w.lotId() + " is not of this part");
                }
                if (freshOnly && lot.isExpiredOn(today)) {
                    throw new BusinessException(HttpStatus.CONFLICT, "MSG86", lot.getId(), product.getSku());
                }
                int held = lotBalance(lot.getId(), storeId, status, now).getQuantity();
                if (w.quantity() > held) {
                    throw notEnough(product, w.quantity(), held);
                }
                picks.add(new Pick(lot, w.quantity()));
                total += w.quantity();
            }
            if (total != quantity) {
                throw new IllegalArgumentException("lot quantities do not add up to the quantity");
            }
            return picks;
        }
        int left = quantity;
        int usable = 0;
        for (Held held : batch.open.getOrDefault(new PoolKey(storeId, status, product.getId()), List.of())) {
            if (freshOnly && held.lot.isExpiredOn(today)) {
                continue;
            }
            usable += held.remaining;
            if (left > 0 && held.remaining > 0) {
                int take = Math.min(left, held.remaining);
                held.remaining -= take;
                picks.add(new Pick(held.lot, take));
                left -= take;
            }
        }
        if (left > 0) {
            throw notEnough(product, quantity, usable);
        }
        return picks;
    }

    /** Writes the movement and moves lot_balance and stock_balance by the same quantity (BR-29). */
    private void post(StockBalance balance, Lot lot, StockStatus status, int change, BigDecimal unitCost,
                      MovementType type, SourceRef source, String reason, Long by, InventoryPeriod period,
                      LocalDateTime now, LocalDateTime occurredAt) {
        LotBalance lotBalance = lotBalance(lot.getId(), balance.getStoreId(), status, now);
        lotBalance.change(change, now);
        balance.change(status, change, now);
        movements.save(StockMovement.record(balance.getStoreId(), balance.getProductId(), lot.getId(), period.getId(),
                status, change, unitCost, type, source, reason, by, occurredAt));
    }

    /** The period a posting goes into and the time it is recorded at. */
    private record PostingTime(InventoryPeriod period, LocalDateTime at) {
    }

    /**
     * The current month's open period and now, unless a count adjustment names its own period and time (BR-83): that
     * period must be OPEN (MSG85). Anything else naming a period, or only one of the two, is a programming error.
     */
    private PostingTime postingTime(MovementType type, Long periodId, LocalDateTime occurredAt, LocalDateTime now) {
        if (periodId == null && occurredAt == null) {
            return new PostingTime(inventoryPeriods.openFor(now.toLocalDate()), now);
        }
        if (type != MovementType.COUNT_ADJUSTMENT) {
            throw new IllegalArgumentException("only a count adjustment may name its period and time");
        }
        if (periodId == null || occurredAt == null) {
            throw new IllegalArgumentException("periodId and occurredAt go together");
        }
        InventoryPeriod period = periods.findById(periodId)
                .orElseThrow(() -> new IllegalArgumentException("unknown period " + periodId));
        if (!period.isOpen()) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG85",
                    period.getPeriodMonth().format(DateTimeFormatter.ofPattern("MM/yyyy")));
        }
        return new PostingTime(period, occurredAt);
    }

    private StockBalance lockBalance(Long storeId, Long productId, LocalDateTime now) {
        stockBalances.insertIfMissing(storeId, productId, now);
        return fresh(stockBalances.findForUpdate(storeId, productId).orElseThrow());
    }

    private LotBalance lotBalance(Long lotId, Long storeId, StockStatus status, LocalDateTime now) {
        lotBalances.insertIfMissing(lotId, storeId, status.name(), now);
        return fresh(lotBalances.findForUpdate(lotId, storeId, status).orElseThrow());
    }

    /**
     * Reads a row again from the database. A locking query returns the entity already in the persistence context
     * without its new values, so without this a caller that loaded the row earlier would post on a stale quantity
     * and overwrite another transaction's committed posting. Pending changes are flushed first so none is lost.
     */
    private <T> T fresh(T entity) {
        em.flush();
        em.refresh(entity);
        return entity;
    }

    private void refreshDepletion(Lot lot) {
        lotBalances.flush();
        lot.refreshDepletion((int) lotBalances.sumQuantityOfLot(lot.getId()));
    }

    private Product product(Long productId) {
        return fresh(products.findById(productId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG57")));
    }

    /** Outgoing and moved stock is valued at the moving average; a part with no average yet uses the lot's price. */
    private static BigDecimal costOf(Product product, Lot lot) {
        return product.getMovingAvgCost() != null ? product.getMovingAvgCost() : BigDecimal.valueOf(lot.getPurchaseUnitPrice());
    }

    private static BusinessException notEnough(Product product, int needed, int held) {
        return new BusinessException(HttpStatus.CONFLICT, "MSG84", product.getSku(), needed, held);
    }
}
