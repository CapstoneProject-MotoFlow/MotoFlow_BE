package fu.hl.g64.motorflow.inventory;

import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.dto.LotAdjustPosting;
import fu.hl.g64.motorflow.inventory.dto.NewLotPosting;
import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.LotSourceType;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.inventory.service.InventoryPostingService;
import fu.hl.g64.motorflow.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A count adjustment may post into the count's own period and time (BR-83); nothing else may, and the rest is unchanged. */
class InventoryPostingPeriodTest extends IntegrationTest {

    private static final LocalDateTime END_OF_SEPTEMBER = LocalDateTime.of(2026, 9, 30, 23, 59, 59);

    @Autowired
    private InventoryPostingService posting;

    @Autowired
    private TransactionTemplate tx;

    private long owner;
    private long store;
    private long product;
    private long count;
    private long lot;
    private long september;

    @BeforeEach
    void setUp() {
        resetDatabaseAndClock();
        owner = id("INSERT INTO user_account (username, password_hash, full_name, role) "
                + "VALUES ('owner', 'x', 'Owner', 'OWNER') RETURNING id");
        store = id("INSERT INTO store (store_name, address) VALUES ('Gia Lâm', 'HN') RETURNING id");
        long category = id("INSERT INTO part_category (category_name, vehicle_type) VALUES ('Phanh', 'MOTORCYCLE') RETURNING id");
        product = id("INSERT INTO product (sku, part_name, category_id, base_unit, moving_avg_cost, created_by) "
                + "VALUES ('MA-PHANH', 'Má phanh', " + category + ", 'cái', 100000, " + owner + ") RETURNING id");
        september = id("INSERT INTO inventory_period (period_month, from_date, to_date, status) "
                + "VALUES ('2026-09-01', '2026-09-01', '2026-09-30', 'OPEN') RETURNING id");
        count = id("INSERT INTO stock_count (store_id, inventory_period_id, count_type, count_date, scope_type, "
                + "created_by) VALUES (" + store + ", " + september + ", 'CYCLE', '2026-09-30', 'WHOLE', " + owner
                + ") RETURNING id");
        // Current month (October 2026), posted the usual way, from an earlier count.
        long earlier = id("INSERT INTO stock_count (store_id, inventory_period_id, count_type, count_date, scope_type, "
                + "created_by) VALUES (" + store + ", " + september + ", 'CYCLE', '2026-09-01', 'WHOLE', " + owner
                + ") RETURNING id");
        lot = tx.execute(s -> posting.receiveNewLot(new NewLotPosting(store, product, LotSourceType.COUNT_SURPLUS, null,
                null, LocalDate.of(2026, 9, 1), 100_000, null, null, null, Map.of(StockStatus.AVAILABLE, 3),
                MovementType.COUNT_ADJUSTMENT, SourceRef.stockCount(earlier), owner, "test", null, null))).getId();
    }

    @Test
    void withoutPeriodAndTimeThePostingGoesToTheCurrentMonth() {
        long october = id("SELECT id FROM inventory_period WHERE period_month = '2026-10-01'");

        tx.executeWithoutResult(s -> posting.adjustLot(adjust(2, MovementType.COUNT_ADJUSTMENT, null, null)));

        var movements = jdbc.queryForList("SELECT inventory_period_id, occurred_at FROM stock_movement "
                + "WHERE quantity_change = 2");
        assertThat(movements).hasSize(1);
        assertThat(movements.getFirst().get("inventory_period_id")).isEqualTo(october);
        assertThat(((java.sql.Timestamp) movements.getFirst().get("occurred_at")).toLocalDateTime())
                .isEqualTo(LocalDateTime.now(clock));
    }

    @Test
    void aCountAdjustmentCanPostIntoAnOpenEarlierPeriodAtTheGivenTime() {
        tx.executeWithoutResult(s -> posting.adjustLot(
                adjust(-2, MovementType.COUNT_ADJUSTMENT, september, END_OF_SEPTEMBER)));
        tx.execute(s -> posting.receiveNewLot(surplus(4, september, END_OF_SEPTEMBER)));

        var movements = jdbc.queryForList("SELECT inventory_period_id, occurred_at FROM stock_movement "
                + "WHERE stock_count_id = ? AND (quantity_change = -2 OR quantity_change = 4)", count);
        assertThat(movements).hasSize(2).allSatisfy(m -> {
            assertThat(m.get("inventory_period_id")).isEqualTo(september);
            assertThat(((java.sql.Timestamp) m.get("occurred_at")).toLocalDateTime()).isEqualTo(END_OF_SEPTEMBER);
        });
        // The balances change as usual: 3 - 2 + 4.
        assertThat(jdbc.queryForObject("SELECT available_qty FROM stock_balance WHERE store_id = ? AND product_id = ?",
                Integer.class, store, product)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM lot_balance", Integer.class)).isEqualTo(5);
    }

    @Test
    void aSurplusLotLeavesTheMovingAverageAndValuesTheMovementAtIt() {
        tx.execute(s -> posting.receiveNewLot(surplus(4, september, END_OF_SEPTEMBER)));

        assertThat(jdbc.queryForObject("SELECT moving_avg_cost FROM product WHERE id = ?", java.math.BigDecimal.class,
                product)).isEqualByComparingTo("100000");
        assertThat(jdbc.queryForObject("SELECT unit_cost FROM stock_movement WHERE quantity_change = 4",
                java.math.BigDecimal.class)).isEqualByComparingTo("100000");
        assertThat(jdbc.queryForObject("SELECT source_type FROM lot WHERE id = (SELECT max(id) FROM lot)",
                String.class)).isEqualTo("COUNT_SURPLUS");
    }

    @Test
    void aClosedPeriodIsRefusedWithMsg85AndNothingIsWritten() {
        jdbc.update("UPDATE inventory_period SET status = 'CLOSED', closed_at = now() WHERE id = ?", september);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> posting.adjustLot(
                adjust(-1, MovementType.COUNT_ADJUSTMENT, september, END_OF_SEPTEMBER))))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("MSG85"));
        assertThatThrownBy(() -> tx.execute(s -> posting.receiveNewLot(surplus(1, september, END_OF_SEPTEMBER))))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("MSG85"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM lot_balance", Integer.class)).isEqualTo(3);
    }

    @Test
    void onlyACountAdjustmentMayNameAPeriod() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> posting.adjustLot(
                adjust(-1, MovementType.ADJUSTMENT, september, END_OF_SEPTEMBER))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tx.execute(s -> posting.receiveNewLot(new NewLotPosting(store, product,
                LotSourceType.OPENING, null, null, LocalDate.of(2026, 9, 30), 100_000, null, null, null,
                Map.of(StockStatus.AVAILABLE, 1), MovementType.OPENING_BALANCE, SourceRef.stockCount(count), owner,
                null, september, END_OF_SEPTEMBER))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Integer.class)).isEqualTo(1);
    }

    @Test
    void thePeriodAndTheTimeComeTogether() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> posting.adjustLot(
                adjust(-1, MovementType.COUNT_ADJUSTMENT, september, null))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> posting.adjustLot(
                adjust(-1, MovementType.COUNT_ADJUSTMENT, null, END_OF_SEPTEMBER))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private LotAdjustPosting adjust(int change, MovementType type, Long periodId, LocalDateTime at) {
        return new LotAdjustPosting(store, lot, StockStatus.AVAILABLE, change, type, SourceRef.stockCount(count),
                owner, "test", periodId, at);
    }

    /** A new COUNT_SURPLUS lot of {@code qty} of the part, from the count. */
    private NewLotPosting surplus(int qty, Long periodId, LocalDateTime at) {
        return new NewLotPosting(store, product, LotSourceType.COUNT_SURPLUS, null, null, LocalDate.of(2026, 9, 30),
                100_000, null, null, null, Map.of(StockStatus.AVAILABLE, qty), MovementType.COUNT_ADJUSTMENT,
                SourceRef.stockCount(count), owner, "test", periodId, at);
    }

    private long id(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
