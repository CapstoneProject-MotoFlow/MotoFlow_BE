package fu.hl.g64.motorflow.inventory;

import fu.hl.g64.motorflow.common.config.ClockConfig;
import fu.hl.g64.motorflow.inventory.dto.MovePosting;
import fu.hl.g64.motorflow.inventory.dto.NewLotPosting;
import fu.hl.g64.motorflow.inventory.dto.OutPosting;
import fu.hl.g64.motorflow.inventory.entity.InventoryPeriod;
import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.LotSourceType;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.inventory.helper.PeriodTotals;
import fu.hl.g64.motorflow.inventory.helper.PeriodTotals.Amount;
import fu.hl.g64.motorflow.inventory.helper.PeriodTotals.Line;
import fu.hl.g64.motorflow.inventory.repository.InventoryPeriodRepository;
import fu.hl.g64.motorflow.inventory.service.InventoryPostingService;
import fu.hl.g64.motorflow.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** BR-85: opening, in, out and closing of each part and warehouse, worked out from the ledger. */
class PeriodTotalsTest extends IntegrationTest {

    private static final long COST = 100_000;

    @Autowired
    private PeriodTotals totals;
    @Autowired
    private InventoryPeriodRepository periods;
    @Autowired
    private InventoryPostingService posting;
    @Autowired
    private TransactionTemplate tx;

    private long owner;
    private long source;
    private long dest;
    private long alpha;
    private long supplier;
    private long po;

    @BeforeEach
    void setUp() {
        resetDatabaseAndClock();
        owner = id("INSERT INTO user_account (username, password_hash, full_name, role) "
                + "VALUES ('owner', 'x', 'Owner', 'OWNER') RETURNING id");
        source = id("INSERT INTO store (store_name, address) VALUES ('Long Biên', 'HN') RETURNING id");
        dest = id("INSERT INTO store (store_name, address) VALUES ('Cầu Giấy', 'HN') RETURNING id");
        long category = id("INSERT INTO part_category (category_name, vehicle_type) VALUES ('Phanh', 'MOTORCYCLE') "
                + "RETURNING id");
        alpha = id("INSERT INTO product (sku, part_name, category_id, base_unit, created_by) "
                + "VALUES ('AAA-1', 'Xích 428', " + category + ", 'cái', " + owner + ") RETURNING id");
        supplier = id("INSERT INTO supplier (supplier_code, supplier_name) VALUES ('NCC001', 'Honda') RETURNING id");
        po = id("INSERT INTO purchase_order (supplier_id, store_id, created_by) VALUES (" + supplier + ", " + source
                + ", " + owner + ") RETURNING id");
    }

    @Test
    void salesAndDispatchesGoOutButChangesOfStatusInsideAWarehouseDoNot() {
        at("2026-10-01T10:00:00");
        receive(source, 10);
        at("2026-10-02T10:00:00");
        long sale = sell(source, 3);
        // A customer return held as damaged: a change of status in the same warehouse.
        tx.execute(s -> posting.move(new MovePosting(alpha, source, StockStatus.AVAILABLE, source, StockStatus.DAMAGED,
                4, null, MovementType.RETURN_HOLD, SourceRef.salesOrder(sale), owner, null)));
        at("2026-10-03T10:00:00");
        long transfer = dispatch(2);

        List<Line> lines = totals.of(october(), null);

        assertThat(lines).hasSize(2);
        Line from = lines.stream().filter(l -> l.storeId() == source).findFirst().orElseThrow();
        assertThat(from.opening()).isEqualTo(Amount.ZERO);
        assertThat(from.in()).isEqualTo(amount(10));
        assertThat(from.out()).isEqualTo(amount(5));
        assertThat(from.closing()).isEqualTo(amount(5));
        assertThat(from.inTransit()).isEqualTo(Amount.ZERO);
        assertThat(from.byType()).containsOnlyKeys(MovementType.RECEIPT_CONFIRMED, MovementType.SALE_ISSUE,
                MovementType.TRANSFER_DISPATCH);
        assertThat(from.byType().get(MovementType.SALE_ISSUE).out()).isEqualTo(amount(3));
        // Goods on their way belong to the destination from dispatch, and are shown as in transit there.
        Line to = lines.stream().filter(l -> l.storeId() == dest).findFirst().orElseThrow();
        assertThat(to.in()).isEqualTo(amount(2));
        assertThat(to.out()).isEqualTo(Amount.ZERO);
        assertThat(to.closing()).isEqualTo(amount(2));
        assertThat(to.inTransit()).isEqualTo(amount(2));

        // Receiving them is a change of status: still in 2, no longer in transit.
        at("2026-10-04T10:00:00");
        tx.execute(s -> posting.move(new MovePosting(alpha, dest, StockStatus.IN_TRANSIT, dest, StockStatus.AVAILABLE,
                2, null, MovementType.TRANSFER_RECEIPT, SourceRef.transferOrder(transfer), owner, null)));
        Line received = totals.of(october(), List.of(dest)).getFirst();
        assertThat(received.in()).isEqualTo(amount(2));
        assertThat(received.closing()).isEqualTo(amount(2));
        assertThat(received.inTransit()).isEqualTo(Amount.ZERO);
        assertThat(received.byType()).containsOnlyKeys(MovementType.TRANSFER_DISPATCH);
    }

    @Test
    void onlyTheRequestedWarehousesAreReturned() {
        at("2026-10-01T10:00:00");
        receive(source, 10);
        at("2026-10-03T10:00:00");
        dispatch(2);

        assertThat(totals.of(october(), List.of(source))).extracting(Line::storeId).containsExactly(source);
        assertThat(totals.of(october(), List.of(dest))).extracting(Line::storeId).containsExactly(dest);
    }

    @Test
    void theOpeningStartsFromTheLatestClosedPeriodAndAddsTheOpenPeriodsAfterIt() {
        at("2026-10-05T10:00:00");
        receive(source, 5);
        long september = id("INSERT INTO inventory_period (period_month, from_date, to_date, status, closed_by, "
                + "closed_at) VALUES ('2026-09-01', '2026-09-01', '2026-09-30', 'CLOSED', " + owner + ", now()) "
                + "RETURNING id");
        jdbc.update("INSERT INTO period_stock_balance (inventory_period_id, store_id, product_id, opening_qty, "
                + "opening_value, in_qty, in_value, out_qty, out_value, closing_qty, closing_value) "
                + "VALUES (?, ?, ?, 0, 0, 40, 4000000, 0, 0, 40, 4000000)", september, source, alpha);
        at("2026-11-05T10:00:00");
        sell(source, 1);

        Line october = totals.of(october(), null).getFirst();
        assertThat(october.opening()).isEqualTo(amount(40));
        assertThat(october.closing()).isEqualTo(amount(45));
        // October is still open, so November starts from September plus October's movements.
        Line november = totals.of(period("2026-11-01"), null).getFirst();
        assertThat(november.opening()).isEqualTo(amount(45));
        assertThat(november.in()).isEqualTo(Amount.ZERO);
        assertThat(november.out()).isEqualTo(amount(1));
        assertThat(november.closing()).isEqualTo(amount(44));
    }

    @Test
    void aWarehouseWithNothingLeftAndNoMovementIsLeftOut() {
        at("2026-10-01T10:00:00");
        receive(source, 2);
        sell(source, 2);
        at("2026-11-02T10:00:00");
        receive(dest, 1);

        assertThat(totals.of(period("2026-11-01"), null)).extracting(Line::storeId).containsExactly(dest);
    }

    private InventoryPeriod october() {
        return period("2026-10-01");
    }

    private InventoryPeriod period(String month) {
        return periods.findByPeriodMonth(LocalDate.parse(month)).orElseThrow();
    }

    private static Amount amount(long qty) {
        return new Amount(qty, qty * COST);
    }

    private void receive(long store, int qty) {
        long grn = id("INSERT INTO goods_receipt_note (receipt_type, purchase_order_id, delivery_document_no, arrived_at) "
                + "VALUES ('SUPPLIER_DELIVERY', " + po + ", 'PX-" + System.nanoTime() + "', now()) RETURNING id");
        long line = id("INSERT INTO goods_receipt_note_line (goods_receipt_note_id, product_id, document_qty, "
                + "delivered_qty, passed_qty) VALUES (" + grn + ", " + alpha + ", " + qty + ", " + qty + ", " + qty
                + ") RETURNING id");
        tx.execute(s -> posting.receiveNewLot(new NewLotPosting(store, alpha, LotSourceType.RECEIPT, line, supplier,
                LocalDate.of(2026, 10, 1), COST, null, null, null, Map.of(StockStatus.AVAILABLE, qty),
                MovementType.RECEIPT_CONFIRMED, SourceRef.goodsReceiptNote(grn), owner, null)));
    }

    private long sell(long store, int qty) {
        long sale = id("INSERT INTO sales_order (store_id, price_type, created_by) VALUES (" + store + ", 'RETAIL', "
                + owner + ") RETURNING id");
        tx.execute(s -> posting.takeOut(new OutPosting(store, alpha, StockStatus.AVAILABLE, qty, null,
                MovementType.SALE_ISSUE, SourceRef.salesOrder(sale), owner, null)));
        return sale;
    }

    private long dispatch(int qty) {
        long transfer = id("INSERT INTO transfer_order (source_store_id, dest_store_id, created_by) VALUES (" + source
                + ", " + dest + ", " + owner + ") RETURNING id");
        tx.execute(s -> posting.move(new MovePosting(alpha, source, StockStatus.AVAILABLE, dest, StockStatus.IN_TRANSIT,
                qty, null, MovementType.TRANSFER_DISPATCH, SourceRef.transferOrder(transfer), owner, null)));
        return transfer;
    }

    private void at(String vietnamTime) {
        clock.set(LocalDateTime.parse(vietnamTime).atZone(ClockConfig.VIETNAM).toInstant());
    }

    private long id(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
