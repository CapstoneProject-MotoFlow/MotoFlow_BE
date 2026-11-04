package fu.hl.g64.motorflow.inventory;

import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.dto.LotAdjustPosting;
import fu.hl.g64.motorflow.inventory.dto.LotQuantity;
import fu.hl.g64.motorflow.inventory.dto.MovePosting;
import fu.hl.g64.motorflow.inventory.dto.NewLotPosting;
import fu.hl.g64.motorflow.inventory.dto.OutPosting;
import fu.hl.g64.motorflow.inventory.entity.Lot;
import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.LotSourceType;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.catalog.repository.ProductRepository;
import fu.hl.g64.motorflow.inventory.repository.LotBalanceRepository;
import fu.hl.g64.motorflow.inventory.repository.StockBalanceRepository;
import fu.hl.g64.motorflow.inventory.service.InventoryPostingService;
import fu.hl.g64.motorflow.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** InventoryPostingService: stock_movement, lot_balance and stock_balance always change together (BR-29). */
class InventoryPostingServiceTest extends IntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    @Autowired
    private InventoryPostingService posting;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private StockBalanceRepository stockBalances;

    @Autowired
    private LotBalanceRepository lotBalances;

    @Autowired
    private ProductRepository products;

    private long owner;
    private long store;
    private long otherStore;
    private long product;
    private long supplier;
    private long po;
    private long grn;
    private long sale;

    @BeforeEach
    void setUp() {
        resetDatabaseAndClock();
        owner = id("INSERT INTO user_account (username, password_hash, full_name, role) "
                + "VALUES ('owner', 'x', 'Owner', 'OWNER') RETURNING id");
        store = id("INSERT INTO store (store_name, address) VALUES ('Gia Lâm', 'HN') RETURNING id");
        otherStore = id("INSERT INTO store (store_name, address) VALUES ('Long Biên', 'HN') RETURNING id");
        long category = id("INSERT INTO part_category (category_name, vehicle_type) VALUES ('Phanh', 'MOTORCYCLE') RETURNING id");
        product = id("INSERT INTO product (sku, part_name, category_id, base_unit, created_by) "
                + "VALUES ('MA-PHANH', 'Má phanh', " + category + ", 'cái', " + owner + ") RETURNING id");
        supplier = id("INSERT INTO supplier (supplier_code, supplier_name) VALUES ('NCC001', 'Honda') RETURNING id");
        po = id("INSERT INTO purchase_order (supplier_id, store_id, created_by) VALUES (" + supplier + ", " + store
                + ", " + owner + ") RETURNING id");
        grn = newGrn("PX-1");
        sale = id("INSERT INTO sales_order (store_id, price_type, created_by) VALUES (" + store + ", 'RETAIL', "
                + owner + ") RETURNING id");
    }

    @Test
    void receiptCreatesOneLotPerLineAndRecalculatesTheMovingAverage() {
        Lot first = receive(grn, 10, 2, 100_000, null);

        assertThat(balance(store, "available_qty")).isEqualTo(10);
        assertThat(balance(store, "damaged_qty")).isEqualTo(2);
        assertThat(lotQty(first.getId(), store, "AVAILABLE")).isEqualTo(10);
        assertThat(avgCost()).isEqualByComparingTo("100000");
        assertThat(jdbc.queryForObject("SELECT sum(value) FROM stock_movement", Long.class)).isEqualTo(1_200_000L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_period WHERE period_month = '2026-10-01' "
                + "AND status = 'OPEN'", Integer.class)).isEqualTo(1);

        // 10 held at 100,000 and 10 more at 130,000: (10 x 100,000 + 10 x 130,000) / 20 = 115,000. Damaged goods stay out.
        receive(newGrn("PX-2"), 10, 0, 130_000, null);
        assertThat(avgCost()).isEqualByComparingTo("115000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lot", Integer.class)).isEqualTo(2);
        assertConsistent();
    }

    @Test
    void saleTakesEarliestExpiryFirstThenOldestAndSkipsExpiredLots() {
        Lot noExpiry = receive(grn, 5, 0, 100_000, null);
        Lot later = receive(newGrn("PX-2"), 5, 0, 100_000, LocalDate.of(2027, 3, 1));
        Lot sooner = receive(newGrn("PX-3"), 5, 0, 100_000, LocalDate.of(2026, 12, 1));
        Lot expired = receive(newGrn("PX-4"), 5, 0, 100_000, LocalDate.of(2026, 10, 1));

        List<LotQuantity> taken = inTx(() -> posting.takeOut(sale(12)));

        assertThat(taken).containsExactly(new LotQuantity(sooner.getId(), 5), new LotQuantity(later.getId(), 5),
                new LotQuantity(noExpiry.getId(), 2));
        assertThat(lotQty(expired.getId(), store, "AVAILABLE")).isEqualTo(5);
        assertThat(balance(store, "available_qty")).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT status FROM lot WHERE id = ?", String.class, sooner.getId()))
                .isEqualTo("DEPLETED");
        assertConsistent();

        // 3 sellable units left (the expired lot does not count): asking for 4 is refused and writes nothing.
        assertThatThrownBy(() -> inTx(() -> posting.takeOut(sale(4))))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("MSG84"));
        assertThatThrownBy(() -> inTx(() -> posting.takeOut(new OutPosting(store, product, StockStatus.AVAILABLE, 1,
                List.of(new LotQuantity(expired.getId(), 1)), MovementType.SALE_ISSUE, SourceRef.salesOrder(sale),
                owner, null))))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("MSG86"));
        assertThat(balance(store, "available_qty")).isEqualTo(8);
        assertConsistent();
    }

    @Test
    void outgoingStockIsValuedAtTheMovingAverage() {
        receive(grn, 10, 0, 100_000, null);
        receive(newGrn("PX-2"), 10, 0, 130_000, null);

        inTx(() -> posting.takeOut(sale(3)));

        assertThat(jdbc.queryForObject("SELECT value FROM stock_movement WHERE movement_type = 'SALE_ISSUE'",
                Long.class)).isEqualTo(-345_000L);
    }

    @Test
    void transferMovesStockToTheDestinationInTransitThenAvailable() {
        Lot lot = receive(grn, 10, 0, 100_000, null);
        long transfer = id("INSERT INTO transfer_order (source_store_id, dest_store_id, created_by) VALUES ("
                + store + ", " + otherStore + ", " + owner + ") RETURNING id");

        inTx(() -> posting.move(new MovePosting(product, store, StockStatus.AVAILABLE, otherStore,
                StockStatus.IN_TRANSIT, 4, null, MovementType.TRANSFER_DISPATCH, SourceRef.transferOrder(transfer),
                owner, null)));
        assertThat(balance(store, "available_qty")).isEqualTo(6);
        assertThat(balance(otherStore, "in_transit_qty")).isEqualTo(4);

        inTx(() -> posting.move(new MovePosting(product, otherStore, StockStatus.IN_TRANSIT, otherStore,
                StockStatus.AVAILABLE, 4, List.of(new LotQuantity(lot.getId(), 4)), MovementType.TRANSFER_RECEIPT,
                SourceRef.transferOrder(transfer), owner, null)));
        assertThat(balance(otherStore, "in_transit_qty")).isZero();
        assertThat(balance(otherStore, "available_qty")).isEqualTo(4);
        assertThat(lotQty(lot.getId(), otherStore, "AVAILABLE")).isEqualTo(4);
        assertThat(avgCost()).isEqualByComparingTo("100000");
        assertConsistent();
    }

    @Test
    void lotAdjustmentNeverGoesBelowZero() {
        Lot lot = receive(grn, 3, 0, 100_000, null);
        long count = id("INSERT INTO stock_count (store_id, inventory_period_id, count_type, count_date, scope_type, "
                + "created_by) VALUES (" + store + ", (SELECT id FROM inventory_period), 'CYCLE', '2026-10-13', 'WHOLE', "
                + owner + ") RETURNING id");

        assertThatThrownBy(() -> inTx(() -> {
            posting.adjustLot(new LotAdjustPosting(store, lot.getId(), StockStatus.AVAILABLE, -4,
                    MovementType.COUNT_ADJUSTMENT, SourceRef.stockCount(count), owner, "đếm thiếu"));
            return null;
        })).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("MSG84"));

        inTx(() -> {
            posting.adjustLot(new LotAdjustPosting(store, lot.getId(), StockStatus.AVAILABLE, -1,
                    MovementType.COUNT_ADJUSTMENT, SourceRef.stockCount(count), owner, "đếm thiếu"));
            return null;
        });
        assertThat(balance(store, "available_qty")).isEqualTo(2);
        assertConsistent();
    }

    @Test
    void closedPeriodRefusesPostings() {
        receive(grn, 3, 0, 100_000, null);
        jdbc.update("UPDATE inventory_period SET status = 'CLOSED', closed_at = now()");

        assertThatThrownBy(() -> inTx(() -> posting.takeOut(sale(1))))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("MSG85"));
    }

    @Test
    void twoSalesAtTheSameTimeCannotSellTheSameUnits() throws Exception {
        receive(grn, 10, 0, 100_000, null);
        long otherSale = id("INSERT INTO sales_order (store_id, price_type, created_by) VALUES (" + store
                + ", 'RETAIL', " + owner + ") RETURNING id");
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> sellSix = () -> {
            start.await();
            try {
                inTx(() -> posting.takeOut(new OutPosting(store, product, StockStatus.AVAILABLE, 6, null,
                        MovementType.SALE_ISSUE, SourceRef.salesOrder(sale), owner, null)));
                return "ok";
            } catch (BusinessException e) {
                return e.getCode();
            }
        };
        Callable<String> sellSixMore = () -> {
            start.await();
            try {
                inTx(() -> posting.takeOut(new OutPosting(store, product, StockStatus.AVAILABLE, 6, null,
                        MovementType.SALE_ISSUE, SourceRef.salesOrder(otherSale), owner, null)));
                return "ok";
            } catch (BusinessException e) {
                return e.getCode();
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> a = pool.submit(sellSix);
            Future<String> b = pool.submit(sellSixMore);
            start.countDown();
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder("ok", "MSG84");
        } finally {
            pool.shutdown();
        }
        assertThat(balance(store, "available_qty")).isEqualTo(4);
        assertConsistent();
    }

    @Test
    void callerThatAlreadyLoadedTheStockBalanceDoesNotLoseAConcurrentPosting() throws Exception {
        receive(grn, 10, 0, 100_000, null);
        runWhileOtherSaleCommits(() -> stockBalances.findAll());
        assertThat(balance(store, "available_qty")).isEqualTo(5);
        assertConsistent();
    }

    @Test
    void callerThatAlreadyLoadedTheLotBalancesDoesNotLoseAConcurrentPosting() throws Exception {
        Lot lot = receive(grn, 10, 0, 100_000, null);
        runWhileOtherSaleCommits(() -> lotBalances.findAll());
        assertThat(lotQty(lot.getId(), store, "AVAILABLE")).isEqualTo(5);
        assertConsistent();
    }

    @Test
    void callerThatAlreadyLoadedTheProductDoesNotLoseAConcurrentAverage() throws Exception {
        receive(grn, 10, 0, 100_000, null);
        long otherGrn = newGrn("PX-2");
        long lastGrn = newGrn("PX-3");
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch otherDone = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> inTx(() -> {
                products.findAll();
                loaded.countDown();
                await(otherDone);
                return receiveInTx(lastGrn, 20, 160_000);
            }));
            Future<?> b = pool.submit(() -> {
                await(loaded);
                receive(otherGrn, 10, 0, 130_000, null);
                otherDone.countDown();
                return null;
            });
            b.get();
            a.get();
        } finally {
            pool.shutdown();
        }
        // 20 held at 115,000 after the other receipt, then 20 more at 160,000: (20 x 115,000 + 20 x 160,000) / 40.
        assertThat(avgCost()).isEqualByComparingTo("137500");
        assertConsistent();
    }

    @Test
    void chosenLotsMustHavePositiveQuantitiesAndNoRepeats() {
        Lot a = receive(grn, 10, 0, 100_000, null);
        Lot b = receive(newGrn("PX-2"), 10, 0, 100_000, null);

        assertThatThrownBy(() -> inTx(() -> posting.takeOut(new OutPosting(store, product, StockStatus.AVAILABLE, 5,
                List.of(new LotQuantity(a.getId(), 10), new LotQuantity(b.getId(), -5)), MovementType.SALE_ISSUE,
                SourceRef.salesOrder(sale), owner, null))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inTx(() -> posting.takeOut(new OutPosting(store, product, StockStatus.AVAILABLE, 8,
                List.of(new LotQuantity(a.getId(), 4), new LotQuantity(a.getId(), 4)), MovementType.SALE_ISSUE,
                SourceRef.salesOrder(sale), owner, null))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(balance(store, "available_qty")).isEqualTo(20);
        assertConsistent();
    }

    /** Tx A runs {@code preload} first, waits while another sale of 3 commits, then sells 2 itself. 10 - 3 - 2 = 5. */
    private void runWhileOtherSaleCommits(Runnable preload) throws Exception {
        long otherSale = id("INSERT INTO sales_order (store_id, price_type, created_by) VALUES (" + store
                + ", 'RETAIL', " + owner + ") RETURNING id");
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch otherDone = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> inTx(() -> {
                preload.run();
                loaded.countDown();
                await(otherDone);
                return posting.takeOut(sale(2));
            }));
            Future<?> b = pool.submit(() -> {
                await(loaded);
                inTx(() -> posting.takeOut(new OutPosting(store, product, StockStatus.AVAILABLE, 3, null,
                        MovementType.SALE_ISSUE, SourceRef.salesOrder(otherSale), owner, null)));
                otherDone.countDown();
                return null;
            });
            b.get();
            a.get();
        } finally {
            pool.shutdown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    private Lot receiveInTx(long grnId, int passed, long price) {
        return newLot(grnId, passed, 0, price, null);
    }

    private Lot receive(long grnId, int passed, int damaged, long price, LocalDate expiry) {
        return inTx(() -> newLot(grnId, passed, damaged, price, expiry));
    }

    private Lot newLot(long grnId, int passed, int damaged, long price, LocalDate expiry) {
        long line = id("INSERT INTO goods_receipt_note_line (goods_receipt_note_id, product_id, document_qty, "
                + "delivered_qty, passed_qty, damaged_qty) VALUES (" + grnId + ", " + product + ", " + (passed + damaged)
                + ", " + (passed + damaged) + ", " + passed + ", " + damaged + ") RETURNING id");
        return posting.receiveNewLot(new NewLotPosting(store, product, LotSourceType.RECEIPT, line,
                supplier, TODAY, price, null, null, expiry,
                Map.of(StockStatus.AVAILABLE, passed, StockStatus.DAMAGED, damaged),
                MovementType.RECEIPT_CONFIRMED, SourceRef.goodsReceiptNote(grnId), owner, null));
    }

    private long newGrn(String documentNo) {
        return id("INSERT INTO goods_receipt_note (receipt_type, purchase_order_id, delivery_document_no, arrived_at) "
                + "VALUES ('SUPPLIER_DELIVERY', " + po + ", '" + documentNo + "', now()) RETURNING id");
    }

    private OutPosting sale(int quantity) {
        return new OutPosting(store, product, StockStatus.AVAILABLE, quantity, null, MovementType.SALE_ISSUE,
                SourceRef.salesOrder(sale), owner, null);
    }

    private <T> T inTx(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    private long id(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private int balance(long storeId, String column) {
        return jdbc.queryForObject("SELECT coalesce(max(" + column + "), 0) FROM stock_balance WHERE store_id = ? "
                + "AND product_id = ?", Integer.class, storeId, product);
    }

    private int lotQty(long lotId, long storeId, String status) {
        return jdbc.queryForObject("SELECT coalesce(max(quantity), 0) FROM lot_balance WHERE lot_id = ? AND store_id = ? "
                + "AND stock_status = ?", Integer.class, lotId, storeId, status);
    }

    private BigDecimal avgCost() {
        return jdbc.queryForObject("SELECT moving_avg_cost FROM product WHERE id = ?", BigDecimal.class, product);
    }

    /** The same check as scripts/seed/check_stock_consistency.sql: no row means the three tables agree. */
    private void assertConsistent() {
        Integer mismatches = jdbc.queryForObject("""
                WITH m AS (SELECT store_id, product_id, stock_status, sum(quantity_change) q
                           FROM stock_movement GROUP BY 1, 2, 3),
                     l AS (SELECT b.store_id, l.product_id, stock_status, sum(quantity) q
                           FROM lot_balance b JOIN lot l ON l.id = b.lot_id GROUP BY 1, 2, 3),
                     s AS (SELECT store_id, product_id, x.stock_status, x.q FROM stock_balance,
                           LATERAL (VALUES ('AVAILABLE', available_qty), ('AWAITING_INSPECTION', awaiting_inspection_qty),
                                           ('WRONG_ITEM', wrong_item_qty), ('DAMAGED', damaged_qty),
                                           ('AWAITING_SUPPLIER_RETURN', awaiting_supplier_return_qty),
                                           ('IN_TRANSIT', in_transit_qty)) x(stock_status, q))
                SELECT count(*) FROM s
                FULL JOIN m USING (store_id, product_id, stock_status)
                FULL JOIN l USING (store_id, product_id, stock_status)
                WHERE coalesce(s.q, 0) <> coalesce(m.q, 0) OR coalesce(s.q, 0) <> coalesce(l.q, 0)""", Integer.class);
        assertThat(mismatches).isZero();
    }
}
