package fu.hl.g64.motorflow.inventory.service.impl;

import fu.hl.g64.motorflow.inventory.event.AvailableStockChanged;
import org.springframework.context.ApplicationEventPublisher;
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
import fu.hl.g64.motorflow.inventory.entity.enums.CountType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;
import fu.hl.g64.motorflow.inventory.repository.StockCountRepository;
import fu.hl.g64.motorflow.store.entity.Store;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.hibernate.engine.spi.EntityKey;
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
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InventoryPostingServiceImpl implements InventoryPostingService {

    /** Movements that put goods in front of a customer or another store: expired lots are never used (BR-76). */
    private static final Set<MovementType> NO_EXPIRED_LOTS =
            EnumSet.of(MovementType.SALE_ISSUE, MovementType.TRANSFER_DISPATCH, MovementType.TRANSFER_EXTRA_DISPATCH);

    private static final Set<StockCountStatus> UNFINISHED_COUNTS = EnumSet.of(StockCountStatus.DRAFT,
            StockCountStatus.COUNTING, StockCountStatus.AWAITING_APPROVAL);

    private final ProductRepository products;
    private final LotRepository lots;
    private final LotBalanceRepository lotBalances;
    private final StockBalanceRepository stockBalances;
    private final StockMovementRepository movements;
    private final InventoryPeriods inventoryPeriods;
    private final InventoryPeriodRepository periods;
    private final StockCountRepository counts;
    private final StoreRepository stores;
    private final Clock clock;
    private final EntityManager em;
    private final ApplicationEventPublisher events;

    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public Lot receiveNewLot(NewLotPosting p) {
        return receiveNewLotsAll(List.of(p)).getFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public List<Lot> receiveNewLotsAll(List<NewLotPosting> postings) {
        if (postings.isEmpty()) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<PostingTime> times = resolvePostingTimes(postings, now);
        // Lock order everywhere: stock_balance, then product, then lot_balance.
        Map<StoreProduct, StockBalance> balances = lockBalances(postings.stream()
                .map(p -> new StoreProduct(p.storeId(), p.productId())).toList(), now);

        List<Long> productIds = postings.stream().map(NewLotPosting::productId).distinct().sorted().toList();
        Set<Object> managedProductIds = managedIds(Product.class);
        Map<Long, Product> lockedProducts = products.findAllByIdForUpdate(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        if (lockedProducts.size() != productIds.size()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "MSG57");
        }
        List<Product> toRefresh = productIds.stream()
                .filter(managedProductIds::contains)
                .map(lockedProducts::get)
                .toList();
        if (!toRefresh.isEmpty()) {
            em.flush();
            toRefresh.forEach(em::refresh);
        }

        List<Long> avgProductIds = postings.stream()
                .filter(p -> p.sourceType() != LotSourceType.COUNT_SURPLUS)
                .map(NewLotPosting::productId)
                .distinct()
                .toList();
        Map<Long, Long> valuedQtyByProduct = new HashMap<>();
        if (!avgProductIds.isEmpty()) {
            for (Object[] row : stockBalances.sumValuedQuantityByProductIdIn(avgProductIds)) {
                valuedQtyByProduct.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
            }
        }

        Set<Long> checkedStores = new HashSet<>();
        Map<LotKey, LotBalance> locked = new HashMap<>();
        List<Lot> created = new ArrayList<>(postings.size());
        List<Lot> depleted = new ArrayList<>();
        for (int i = 0; i < postings.size(); i++) {
            NewLotPosting p = postings.get(i);
            PostingTime when = times.get(i);
            StockBalance balance = balances.get(new StoreProduct(p.storeId(), p.productId()));
            Product product = lockedProducts.get(p.productId());

            int available = quantityOf(p.quantities(), StockStatus.AVAILABLE);
            // A count surplus comes in at the average (BR-74), so it leaves the average alone.
            boolean surplus = p.sourceType() == LotSourceType.COUNT_SURPLUS;
            if (!surplus) {
                product.receiveIntoAverage(valuedQtyByProduct.getOrDefault(p.productId(), 0L), available,
                        p.purchaseUnitPrice());
            }

            boolean counted = p.sourceType() == LotSourceType.OPENING || p.sourceType() == LotSourceType.COUNT_SURPLUS;
            Long stockCountId = counted ? p.source().id() : null;
            Lot lot = lots.save(Lot.create(p.productId(), p.sourceType(), p.goodsReceiptNoteLineId(), stockCountId,
                    p.supplierId(), p.storeId(), p.receivedDate(), p.purchaseUnitPrice(), p.manufacturerLotNo(),
                    p.manufactureDate(), p.expiryDate()));

            BigDecimal unitCost = surplus && product.getMovingAvgCost() != null ? product.getMovingAvgCost()
                    : BigDecimal.valueOf(p.purchaseUnitPrice());
            boolean anyPositive = false;
            for (Map.Entry<StockStatus, Integer> e : p.quantities().entrySet()) {
                if (e.getValue() == null || e.getValue() == 0) {
                    continue;
                }
                if (e.getValue() < 0) {
                    throw new IllegalArgumentException("received quantity must be positive");
                }
                LotKey key = new LotKey(lot.getId(), p.storeId(), e.getKey());
                LotBalance lb = LotBalance.create(lot.getId(), p.storeId(), e.getKey(), now);
                locked.put(key, lb);
                post(balance, lot, e.getKey(), e.getValue(), unitCost, p.movementType(), p.source(), p.reason(),
                        p.performedBy(), when.period(), now, when.at(), checkedStores, locked);
                lotBalances.save(lb);
                anyPositive = true;
            }
            if (!anyPositive) {
                depleted.add(lot);
            }
            long addedValued = (long) available
                    + quantityOf(p.quantities(), StockStatus.AWAITING_INSPECTION)
                    + quantityOf(p.quantities(), StockStatus.IN_TRANSIT);
            if (addedValued != 0) {
                valuedQtyByProduct.merge(p.productId(), addedValued, Long::sum);
            }
            created.add(lot);
        }
        if (!depleted.isEmpty()) {
            lotBalances.flush();
            for (Lot lot : depleted) {
                lot.refreshDepletion(0);
            }
        }
        return created;
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
        Set<Long> checkedStores = new HashSet<>();
        Map<Long, Lot> touchedLots = new LinkedHashMap<>();
        List<List<LotQuantity>> taken = new ArrayList<>();
        for (OutPosting p : postings) {
            Product product = batch.product(p.productId());
            List<Pick> picks = choose(p.storeId(), product, p.fromStatus(), p.quantity(), p.lots(), p.movementType(),
                    batch, now);
            StockBalance balance = balances.get(new StoreProduct(p.storeId(), p.productId()));
            List<LotQuantity> line = new ArrayList<>();
            for (Pick pick : picks) {
                post(balance, pick.lot, p.fromStatus(), -pick.quantity, costOf(product, pick.lot), p.movementType(),
                        p.source(), p.reason(), p.performedBy(), period, now, now, checkedStores, batch.locked);
                touchedLots.putIfAbsent(pick.lot.getId(), pick.lot);
                line.add(new LotQuantity(pick.lot.getId(), pick.quantity));
            }
            taken.add(line);
        }
        if (!touchedLots.isEmpty()) {
            lotBalances.flush();
            Map<Long, Integer> totals = new HashMap<>();
            for (Object[] row : lotBalances.sumQuantityByLotIdIn(touchedLots.keySet())) {
                totals.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
            }
            for (Lot lot : touchedLots.values()) {
                lot.refreshDepletion(totals.getOrDefault(lot.getId(), 0));
            }
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
        Set<Long> checkedStores = new HashSet<>();
        List<ChosenMove> chosen = new ArrayList<>(postings.size());
        Set<LotKey> destKeys = new HashSet<>();
        for (MovePosting p : postings) {
            Product product = batch.product(p.productId());
            List<Pick> picks = choose(p.fromStoreId(), product, p.fromStatus(), p.quantity(), p.lots(),
                    p.movementType(), batch, now);
            for (Pick pick : picks) {
                destKeys.add(new LotKey(pick.lot.getId(), p.toStoreId(), p.toStatus()));
            }
            chosen.add(new ChosenMove(p, product, picks));
        }
        lockDestinationLotBalances(destKeys, batch.locked, now);
        List<List<LotQuantity>> moved = new ArrayList<>(chosen.size());
        for (ChosenMove item : chosen) {
            MovePosting p = item.posting;
            StockBalance from = balances.get(new StoreProduct(p.fromStoreId(), p.productId()));
            StockBalance to = balances.get(new StoreProduct(p.toStoreId(), p.productId()));
            List<LotQuantity> line = new ArrayList<>();
            for (Pick pick : item.picks) {
                BigDecimal unitCost = costOf(item.product, pick.lot);
                post(from, pick.lot, p.fromStatus(), -pick.quantity, unitCost, p.movementType(), p.source(),
                        p.reason(), p.performedBy(), period, now, now, checkedStores, batch.locked);
                post(to, pick.lot, p.toStatus(), pick.quantity, unitCost, p.movementType(), p.source(), p.reason(),
                        p.performedBy(), period, now, now, checkedStores, batch.locked);
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
        Set<Long> checkedStores = new HashSet<>();
        List<ChosenSplit> chosen = new ArrayList<>(prepared.size());
        Set<LotKey> destKeys = new HashSet<>();
        for (PreparedSplit row : prepared) {
            SplitMovePosting p = row.posting;
            Product product = batch.product(p.productId());
            int total = row.wanted.values().stream().mapToInt(Integer::intValue).sum();
            List<Pick> picks = choose(p.fromStoreId(), product, p.fromStatus(), total, p.lots(), p.movementType(),
                    batch, now);
            Iterator<Map.Entry<StockStatus, Integer>> statuses = row.wanted.entrySet().iterator();
            Map.Entry<StockStatus, Integer> filling = statuses.next();
            int left = filling.getValue();
            List<SplitPick> splitPicks = new ArrayList<>(picks.size());
            for (Pick pick : picks) {
                int lotLeft = pick.quantity;
                List<StatusShare> shares = new ArrayList<>();
                while (lotLeft > 0) {
                    if (left == 0) {
                        filling = statuses.next();
                        left = filling.getValue();
                    }
                    int part = Math.min(lotLeft, left);
                    shares.add(new StatusShare(filling.getKey(), part));
                    destKeys.add(new LotKey(pick.lot.getId(), p.toStoreId(), filling.getKey()));
                    lotLeft -= part;
                    left -= part;
                }
                splitPicks.add(new SplitPick(pick, shares));
            }
            chosen.add(new ChosenSplit(p, product, splitPicks));
        }
        lockDestinationLotBalances(destKeys, batch.locked, now);
        for (ChosenSplit row : chosen) {
            SplitMovePosting p = row.posting;
            StockBalance from = balances.get(new StoreProduct(p.fromStoreId(), p.productId()));
            StockBalance to = balances.get(new StoreProduct(p.toStoreId(), p.productId()));
            for (SplitPick sp : row.picks) {
                BigDecimal unitCost = costOf(row.product, sp.pick.lot);
                post(from, sp.pick.lot, p.fromStatus(), -sp.pick.quantity, unitCost, p.movementType(), p.source(),
                        p.reason(), p.performedBy(), period, now, now, checkedStores, batch.locked);
                for (StatusShare share : sp.shares) {
                    post(to, sp.pick.lot, share.status, share.quantity, unitCost, p.movementType(), p.source(),
                            p.reason(), p.performedBy(), period, now, now, checkedStores, batch.locked);
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
        Map<LotKey, LotBalance> locked = new HashMap<>();
        if (p.quantityChange() < 0) {
            LotBalance lb = lotBalance(lot.getId(), p.storeId(), p.status(), now);
            locked.put(new LotKey(lot.getId(), p.storeId(), p.status()), lb);
            int held = lb.getQuantity();
            if (held + p.quantityChange() < 0) {
                throw notEnough(product, -p.quantityChange(), held);
            }
        }
        Set<Long> checkedStores = new HashSet<>();
        post(balance, lot, p.status(), p.quantityChange(), costOf(product, lot), p.movementType(), p.source(),
                p.reason(), p.performedBy(), when.period(), now, when.at(), checkedStores, locked);
        refreshDepletion(lot);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public void adjustLotsAll(List<LotAdjustPosting> postings) {
        List<LotAdjustPosting> changing = postings.stream().filter(p -> p.quantityChange() != 0).toList();
        if (changing.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        // A lot that does not exist is refused in list order below, like adjustLot, so only known lots are locked here.
        Map<Long, Lot> loaded = loadLots(changing.stream().map(LotAdjustPosting::lotId).toList());
        List<LotAdjustPosting> known = changing.stream().filter(p -> loaded.containsKey(p.lotId())).toList();
        // Same lock order as adjustLot: stock_balance, then product, then lot_balance.
        Map<StoreProduct, StockBalance> balances = lockBalances(known.stream()
                .map(p -> new StoreProduct(p.storeId(), loaded.get(p.lotId()).getProductId())).toList(), now);
        Map<Long, Product> parts = readProducts(known.stream()
                .map(p -> loaded.get(p.lotId()).getProductId()).distinct().toList());
        // Increases are locked too: post() would lock them one by one, and here every row is locked in key order.
        Map<LotKey, LotBalance> locked = new HashMap<>();
        lockDestinationLotBalances(known.stream().map(p -> new LotKey(p.lotId(), p.storeId(), p.status())).toList(),
                locked, now);

        Set<Long> checkedStores = new HashSet<>();
        Map<Long, Lot> changed = new LinkedHashMap<>();
        Map<Long, InventoryPeriod> periodCache = new HashMap<>();
        PostingTime currentMonth = null;
        for (LotAdjustPosting p : changing) {
            // Same order as adjustLot: the period and time are resolved before the lot is looked up.
            PostingTime when;
            if (p.periodId() == null && p.occurredAt() == null) {
                if (currentMonth == null) {
                    currentMonth = postingTime(p.movementType(), null, null, now);
                }
                when = currentMonth;
            } else {
                when = postingTime(p.movementType(), p.periodId(), p.occurredAt(), now, periodCache);
            }
            Lot lot = loaded.get(p.lotId());
            if (lot == null) {
                throw new IllegalArgumentException("unknown lot " + p.lotId());
            }
            Product product = productOf(parts, lot.getProductId());
            if (p.quantityChange() < 0) {
                // The row is the one posted into earlier in this loop, so held already counts those changes.
                int held = locked.get(new LotKey(lot.getId(), p.storeId(), p.status())).getQuantity();
                if (held + p.quantityChange() < 0) {
                    throw notEnough(product, -p.quantityChange(), held);
                }
            }
            post(balances.get(new StoreProduct(p.storeId(), lot.getProductId())), lot, p.status(), p.quantityChange(),
                    costOf(product, lot), p.movementType(), p.source(), p.reason(), p.performedBy(), when.period(), now,
                    when.at(), checkedStores, locked);
            changed.putIfAbsent(lot.getId(), lot);
        }
        refreshDepletions(changed.values());
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
        Product product = fresh(Product.class, Product::getId, () -> products.findByIdForUpdate(lot.getProductId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG57")));

        int available = quantityOf(p.quantities(), StockStatus.AVAILABLE);
        product.receiveIntoAverage(stockBalances.sumValuedQuantity(lot.getProductId()), available,
                lot.getPurchaseUnitPrice());

        BigDecimal unitCost = BigDecimal.valueOf(lot.getPurchaseUnitPrice());
        Set<Long> checkedStores = new HashSet<>();
        Map<LotKey, LotBalance> locked = new HashMap<>();
        for (Map.Entry<StockStatus, Integer> e : p.quantities().entrySet()) {
            if (e.getValue() == null || e.getValue() == 0) {
                continue;
            }
            if (e.getValue() < 0) {
                throw new IllegalArgumentException("received quantity must be positive");
            }
            post(balance, lot, e.getKey(), e.getValue(), unitCost, p.movementType(), p.source(), p.reason(),
                    p.performedBy(), period, now, now, checkedStores, locked);
        }
        refreshDepletion(lot);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public void receiveIntoLotsAll(List<LotReceiptPosting> postings) {
        if (postings.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        InventoryPeriod period = inventoryPeriods.openFor(now.toLocalDate());
        // A lot that does not exist or did not come from a receipt is refused in list order below, so only the
        // receipt lots are locked here.
        Map<Long, Lot> loaded = loadLots(postings.stream().map(LotReceiptPosting::lotId).toList());
        List<LotReceiptPosting> valid = postings.stream()
                .filter(p -> loaded.containsKey(p.lotId()) && loaded.get(p.lotId()).getSourceType() == LotSourceType.RECEIPT)
                .toList();
        List<Long> productIds = valid.stream().map(p -> loaded.get(p.lotId()).getProductId()).distinct().sorted().toList();
        // Same lock order as receiveIntoLot: stock_balance, then product, then lot_balance.
        Map<StoreProduct, StockBalance> balances = lockBalances(valid.stream()
                .map(p -> new StoreProduct(p.storeId(), loaded.get(p.lotId()).getProductId())).toList(), now);
        Map<Long, Product> lockedProducts = productIds.isEmpty() ? Map.of() : products.findAllByIdForUpdate(productIds)
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        if (lockedProducts.size() != productIds.size()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "MSG57");
        }
        Set<Object> managedProductIds = managedIds(Product.class);
        List<Product> toRefresh = productIds.stream()
                .filter(managedProductIds::contains)
                .map(lockedProducts::get)
                .toList();
        if (!toRefresh.isEmpty()) {
            em.flush();
            toRefresh.forEach(em::refresh);
        }
        // Read once, then kept up to date below: the query counts what each earlier posting put into those statuses.
        Map<Long, Long> valuedQtyByProduct = new HashMap<>();
        if (!productIds.isEmpty()) {
            for (Object[] row : stockBalances.sumValuedQuantityByProductIdIn(productIds)) {
                valuedQtyByProduct.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
            }
        }
        Map<LotKey, LotBalance> locked = new HashMap<>();
        lockDestinationLotBalances(valid.stream()
                .flatMap(p -> p.quantities().entrySet().stream()
                        .filter(e -> e.getValue() != null && e.getValue() > 0)
                        .map(e -> new LotKey(p.lotId(), p.storeId(), e.getKey())))
                .toList(), locked, now);

        Set<Long> checkedStores = new HashSet<>();
        Map<Long, Lot> changed = new LinkedHashMap<>();
        for (LotReceiptPosting p : postings) {
            Lot lot = loaded.get(p.lotId());
            if (lot == null) {
                throw new IllegalArgumentException("unknown lot " + p.lotId());
            }
            if (lot.getSourceType() != LotSourceType.RECEIPT) {
                throw new IllegalArgumentException("lot " + lot.getId() + " did not come from a supplier receipt");
            }
            long productId = lot.getProductId();
            StockBalance balance = balances.get(new StoreProduct(p.storeId(), productId));
            Product product = lockedProducts.get(productId);

            int available = quantityOf(p.quantities(), StockStatus.AVAILABLE);
            product.receiveIntoAverage(valuedQtyByProduct.getOrDefault(productId, 0L), available,
                    lot.getPurchaseUnitPrice());

            BigDecimal unitCost = BigDecimal.valueOf(lot.getPurchaseUnitPrice());
            long added = 0;
            for (Map.Entry<StockStatus, Integer> e : p.quantities().entrySet()) {
                if (e.getValue() == null || e.getValue() == 0) {
                    continue;
                }
                if (e.getValue() < 0) {
                    throw new IllegalArgumentException("received quantity must be positive");
                }
                post(balance, lot, e.getKey(), e.getValue(), unitCost, p.movementType(), p.source(), p.reason(),
                        p.performedBy(), period, now, now, checkedStores, locked);
                if (isValued(e.getKey())) {
                    added += e.getValue();
                }
            }
            if (added != 0) {
                valuedQtyByProduct.merge(productId, added, Long::sum);
            }
            changed.putIfAbsent(lot.getId(), lot);
        }
        refreshDepletions(changed.values());
    }

    // ------------------------------------------------------------------------------------------------------------

    private record Pick(Lot lot, int quantity) {
    }

    private record StoreProduct(long storeId, long productId) {
    }

    private record PoolKey(long storeId, StockStatus status, long productId) {
    }

    private record LotKey(long lotId, long storeId, StockStatus status) {
    }

    private record PreparedSplit(SplitMovePosting posting, Map<StockStatus, Integer> wanted) {
    }

    private record ChosenMove(MovePosting posting, Product product, List<Pick> picks) {
    }

    private record StatusShare(StockStatus status, int quantity) {
    }

    private record SplitPick(Pick pick, List<StatusShare> shares) {
    }

    private record ChosenSplit(SplitMovePosting posting, Product product, List<SplitPick> picks) {
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
        private final Map<LotKey, LotBalance> locked;

        private Batch(Map<Long, Product> products, Map<Long, Lot> lots, Map<PoolKey, List<Held>> open,
                      Map<LotKey, LotBalance> locked) {
            this.products = products;
            this.lots = lots;
            this.open = open;
            this.locked = locked;
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
        if (ordered.isEmpty()) {
            return Map.of();
        }
        Long[] storeIds = ordered.stream().map(StoreProduct::storeId).toArray(Long[]::new);
        Long[] productIds = ordered.stream().map(StoreProduct::productId).toArray(Long[]::new);
        stockBalances.insertIfMissingBatch(storeIds, productIds, now);
        Map<Long, List<Long>> byStore = ordered.stream().collect(Collectors.groupingBy(StoreProduct::storeId,
                LinkedHashMap::new, Collectors.mapping(StoreProduct::productId, Collectors.toList())));
        Set<Object> managedBalanceIds = managedIds(StockBalance.class);
        Map<StoreProduct, StockBalance> locked = new HashMap<>();
        List<StockBalance> toRefresh = new ArrayList<>();
        for (Map.Entry<Long, List<Long>> store : byStore.entrySet()) {
            for (StockBalance balance : stockBalances.findByStoreForUpdate(store.getKey(), store.getValue())) {
                if (managedBalanceIds.contains(balance.getId())) {
                    toRefresh.add(balance);
                }
                locked.put(new StoreProduct(balance.getStoreId(), balance.getProductId()), balance);
            }
        }
        if (!toRefresh.isEmpty()) {
            em.flush();
            toRefresh.forEach(em::refresh);
        }
        return locked;
    }

    /** Bulk-creates and locks every missing destination lot balance in {@code (lotId, storeId, stockStatus)} order. */
    private void lockDestinationLotBalances(Collection<LotKey> destKeys, Map<LotKey, LotBalance> locked,
                                            LocalDateTime now) {
        List<LotKey> ordered = destKeys.stream()
                .filter(key -> !locked.containsKey(key))
                .distinct()
                .sorted(Comparator.comparingLong(LotKey::lotId)
                        .thenComparingLong(LotKey::storeId)
                        .thenComparing(key -> key.status().name()))
                .toList();
        if (ordered.isEmpty()) {
            return;
        }
        Long[] lotIds = ordered.stream().map(LotKey::lotId).toArray(Long[]::new);
        Long[] storeIds = ordered.stream().map(LotKey::storeId).toArray(Long[]::new);
        String[] statuses = ordered.stream().map(key -> key.status().name()).toArray(String[]::new);
        lotBalances.insertIfMissingBatch(lotIds, storeIds, statuses, now);

        Set<Long> distinctLotIds = ordered.stream().map(LotKey::lotId).collect(Collectors.toSet());
        Set<Long> distinctStoreIds = ordered.stream().map(LotKey::storeId).collect(Collectors.toSet());
        Set<StockStatus> distinctStatuses = ordered.stream().map(LotKey::status)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(StockStatus.class)));
        List<String> exactKeys = ordered.stream()
                .map(key -> key.lotId() + ":" + key.storeId() + ":" + key.status().name())
                .toList();

        Set<Object> managedIds = managedIds(LotBalance.class);
        List<LotBalance> rows = lotBalances.findAllByKeysForUpdate(distinctLotIds, distinctStoreIds, distinctStatuses,
                exactKeys);
        List<LotBalance> toRefresh = new ArrayList<>();
        for (LotBalance row : rows) {
            if (managedIds.contains(row.getId())) {
                toRefresh.add(row);
            }
            locked.put(new LotKey(row.getLotId(), row.getStoreId(), row.getStockStatus()), row);
        }
        if (!toRefresh.isEmpty()) {
            em.flush();
            toRefresh.forEach(em::refresh);
        }
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
        Map<LotKey, LotBalance> locked = new HashMap<>();
        Set<Object> managedLotBalanceIds = managedIds(LotBalance.class);
        List<LotBalance> toRefresh = new ArrayList<>();
        for (Map.Entry<PoolKey, List<Long>> pool : productsByPool.entrySet()) {
            List<Long> ids = pool.getValue().stream().distinct().toList();
            if (ids.isEmpty()) {
                continue;
            }
            List<LotBalance> rows = lotBalances.findPickableForUpdate(pool.getKey().storeId(), ids,
                    pool.getKey().status());
            for (LotBalance row : rows) {
                if (managedLotBalanceIds.contains(row.getId())) {
                    toRefresh.add(row);
                }
                lotIds.add(row.getLotId());
                locked.put(new LotKey(row.getLotId(), row.getStoreId(), row.getStockStatus()), row);
            }
            picked.addAll(rows);
        }
        if (!toRefresh.isEmpty()) {
            em.flush();
            toRefresh.forEach(em::refresh);
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
        return new Batch(parts, loaded, open, locked);
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
                LotKey key = new LotKey(lot.getId(), storeId, status);
                LotBalance lb = batch.locked.get(key);
                if (lb == null) {
                    lb = lotBalance(lot.getId(), storeId, status, now);
                    batch.locked.put(key, lb);
                }
                int held = lb.getQuantity();
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

    /**
     * Writes the movement and moves lot_balance and stock_balance by the same quantity (BR-29). MSG301 while the
     * warehouse has an unfinished opening count: anything posted before it is approved would be counted twice, and
     * would then refuse the approval (MSG244, BR-86). The store row is read FOR SHARE first, so a count being created
     * in that warehouse (which holds it FOR UPDATE) and this posting cannot both pass their checks.
     */
    private void post(StockBalance balance, Lot lot, StockStatus status, int change, BigDecimal unitCost,
                      MovementType type, SourceRef source, String reason, Long by, InventoryPeriod period,
                      LocalDateTime now, LocalDateTime occurredAt, Set<Long> checkedStores,
                      Map<LotKey, LotBalance> locked) {
        if (type != MovementType.OPENING_BALANCE && checkedStores.add(balance.getStoreId())) {
            Store store = stores.findForShareById(balance.getStoreId()).orElseThrow();
            if (counts.existsByStoreIdAndCountTypeAndStatusIn(store.getId(), CountType.OPENING, UNFINISHED_COUNTS)) {
                throw new BusinessException(HttpStatus.CONFLICT, "MSG301", store.getStoreName());
            }
        }
        LotKey key = new LotKey(lot.getId(), balance.getStoreId(), status);
        LotBalance lotBalance = locked.get(key);
        if (lotBalance == null) {
            lotBalance = lotBalance(lot.getId(), balance.getStoreId(), status, now);
            locked.put(key, lotBalance);
        }
        lotBalance.change(change, now);
        balance.change(status, change, now);
        movements.save(StockMovement.record(balance.getStoreId(), balance.getProductId(), lot.getId(), period.getId(),
                status, change, unitCost, type, source, reason, by, occurredAt));
        if (status == StockStatus.AVAILABLE && change != 0) {
            // GVHD 24/11: a waiting order may have become ready, or no longer be (checked after commit).
            events.publishEvent(new AvailableStockChanged(balance.getStoreId(), balance.getProductId()));
        }
    }

    /** The period a posting goes into and the time it is recorded at. */
    private record PostingTime(InventoryPeriod period, LocalDateTime at) {
    }

    private List<PostingTime> resolvePostingTimes(List<NewLotPosting> postings, LocalDateTime now) {
        InventoryPeriod defaultPeriod = null;
        Map<Long, InventoryPeriod> periodCache = new HashMap<>();
        List<PostingTime> times = new ArrayList<>(postings.size());
        for (NewLotPosting p : postings) {
            if (p.periodId() == null && p.occurredAt() == null) {
                if (defaultPeriod == null) {
                    defaultPeriod = inventoryPeriods.openFor(now.toLocalDate());
                }
                times.add(new PostingTime(defaultPeriod, now));
            } else {
                times.add(postingTime(p.movementType(), p.periodId(), p.occurredAt(), now, periodCache));
            }
        }
        return times;
    }

    /**
     * The current month's open period and now, unless a count adjustment names its own period and time (BR-83): that
     * period must be OPEN (MSG85). Anything else naming a period, or only one of the two, is a programming error.
     */
    private PostingTime postingTime(MovementType type, Long periodId, LocalDateTime occurredAt, LocalDateTime now) {
        if (periodId == null && occurredAt == null) {
            return new PostingTime(inventoryPeriods.openFor(now.toLocalDate()), now);
        }
        return postingTime(type, periodId, occurredAt, now, null);
    }

    private PostingTime postingTime(MovementType type, Long periodId, LocalDateTime occurredAt, LocalDateTime now,
                                    Map<Long, InventoryPeriod> periodCache) {
        if (type != MovementType.COUNT_ADJUSTMENT && type != MovementType.OPENING_BALANCE) {
            throw new IllegalArgumentException("only a count adjustment or opening balance names its period and time");
        }
        if (periodId == null || occurredAt == null) {
            throw new IllegalArgumentException("periodId and occurredAt go together");
        }
        InventoryPeriod period;
        if (periodCache != null) {
            period = periodCache.get(periodId);
            if (period == null) {
                period = periods.findForPostingById(periodId)
                        .orElseThrow(() -> new IllegalArgumentException("unknown period " + periodId));
                periodCache.put(periodId, period);
            }
        } else {
            period = periods.findForPostingById(periodId)
                    .orElseThrow(() -> new IllegalArgumentException("unknown period " + periodId));
        }
        if (!period.isOpen()) {
            throw new BusinessException(HttpStatus.CONFLICT, "MSG85",
                    period.getPeriodMonth().format(DateTimeFormatter.ofPattern("MM/yyyy")));
        }
        // A period's opening adds up the movements before its first day (PeriodTotals), so the time must fall in it.
        LocalDate day = occurredAt.toLocalDate();
        if (day.isBefore(period.getFromDate()) || day.isAfter(period.getToDate())) {
            throw new IllegalArgumentException("occurredAt " + occurredAt + " is outside period " + periodId);
        }
        return new PostingTime(period, occurredAt);
    }

    private StockBalance lockBalance(Long storeId, Long productId, LocalDateTime now) {
        stockBalances.insertIfMissing(storeId, productId, now);
        return fresh(StockBalance.class, StockBalance::getId,
                () -> stockBalances.findForUpdate(storeId, productId).orElseThrow());
    }

    private LotBalance lotBalance(Long lotId, Long storeId, StockStatus status, LocalDateTime now) {
        lotBalances.insertIfMissing(lotId, storeId, status.name(), now);
        return fresh(LotBalance.class, LotBalance::getId,
                () -> lotBalances.findForUpdate(lotId, storeId, status).orElseThrow());
    }

    /**
     * Reads a row again from the database. A locking query returns the entity already in the persistence context
     * without its new values, so without this a caller that loaded the row earlier would post on a stale quantity
     * and overwrite another transaction's committed posting. Pending changes are flushed first so none is lost.
     * Rows not yet in the persistence context already receive the latest committed values from {@code FOR UPDATE},
     * so refreshing them is skipped.
     */
    private <T> T fresh(Class<T> type, Function<T, Object> idOf, Supplier<T> lockQuery) {
        Set<Object> managed = managedIds(type);
        T entity = lockQuery.get();
        if (managed.contains(idOf.apply(entity))) {
            em.flush();
            em.refresh(entity);
        }
        return entity;
    }

    private Set<Object> managedIds(Class<?> type) {
        return em.unwrap(Session.class).getStatistics().getEntityKeys().stream()
                .map(k -> (EntityKey) k)
                .filter(k -> k.getEntityName().equals(type.getName()))
                .map(EntityKey::getIdentifier)
                .collect(Collectors.toSet());
    }

    private void refreshDepletion(Lot lot) {
        lotBalances.flush();
        lot.refreshDepletion((int) lotBalances.sumQuantityOfLot(lot.getId()));
    }

    /** {@link #refreshDepletion} for several lots, with one read of their totals. */
    private void refreshDepletions(Collection<Lot> changed) {
        if (changed.isEmpty()) {
            return;
        }
        lotBalances.flush();
        Map<Long, Integer> totals = new HashMap<>();
        for (Object[] row : lotBalances.sumQuantityByLotIdIn(changed.stream().map(Lot::getId).toList())) {
            totals.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
        }
        for (Lot lot : changed) {
            lot.refreshDepletion(totals.getOrDefault(lot.getId(), 0));
        }
    }

    private Map<Long, Lot> loadLots(Collection<Long> lotIds) {
        Set<Long> ids = new HashSet<>(lotIds);
        return ids.isEmpty() ? Map.of() : lots.findAllById(ids).stream()
                .collect(Collectors.toMap(Lot::getId, Function.identity()));
    }

    /** Reads the parts without a lock, as {@link #product} does: a part this transaction already holds is read again. */
    private Map<Long, Product> readProducts(Collection<Long> productIds) {
        Set<Object> managed = managedIds(Product.class);
        Map<Long, Product> found = products.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        List<Product> toRefresh = found.values().stream().filter(p -> managed.contains(p.getId())).toList();
        if (!toRefresh.isEmpty()) {
            em.flush();
            toRefresh.forEach(em::refresh);
        }
        return found;
    }

    private static Product productOf(Map<Long, Product> parts, Long productId) {
        Product product = parts.get(productId);
        if (product == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "MSG57");
        }
        return product;
    }

    private Product product(Long productId) {
        return fresh(Product.class, Product::getId, () -> products.findById(productId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MSG57")));
    }

    private static int quantityOf(Map<StockStatus, Integer> quantities, StockStatus status) {
        Integer qty = quantities.get(status);
        return qty == null ? 0 : qty;
    }

    /** The statuses that make up the valued quantity of sumValuedQuantityByProductIdIn (BR-74). */
    private static boolean isValued(StockStatus status) {
        return status == StockStatus.AVAILABLE || status == StockStatus.AWAITING_INSPECTION
                || status == StockStatus.IN_TRANSIT;
    }

    /** Outgoing and moved stock is valued at the moving average; a part with no average yet uses the lot's price. */
    private static BigDecimal costOf(Product product, Lot lot) {
        return product.getMovingAvgCost() != null ? product.getMovingAvgCost() : BigDecimal.valueOf(lot.getPurchaseUnitPrice());
    }

    private static BusinessException notEnough(Product product, int needed, int held) {
        return new BusinessException(HttpStatus.CONFLICT, "MSG84", product.getSku(), needed, held);
    }
}
