package fu.hl.g64.motorflow.stockcount;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.dto.NewLotPosting;
import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.LotSourceType;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.inventory.service.InventoryPostingService;
import fu.hl.g64.motorflow.store.repository.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC-96 Record Opening Balance, through the API: the Branch Manager of a warehouse where nothing moved yet creates an
 * opening count, Staff enters one line per part and expiry date, the Owner enters initial unit costs and approves.
 * Long Biên ({@code otherStore}) has no stock in the seed data.
 */
class OpeningBalanceApiTest extends StockCountTestData {

    private static final String COUNTS = "/api/v1/stock-counts";

    @Autowired
    private InventoryPostingService posting;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private StoreRepository stores;

    private String lbStaffToken;

    @BeforeEach
    void longBienStaff() throws Exception {
        createUser("staff.lb", Role.STAFF, otherStore);
        lbStaffToken = signIn("staff.lb");
    }

    @Test
    void anOpeningCountBecomesOpeningLotsAtTheInitialUnitCosts() throws Exception {
        long count = countingOpening();
        putAs(lbStaffToken, COUNTS + "/" + count + "/opening-lines", Map.of("lines", List.of(
                line(pad, null, 10, 1), line(filter, "2027-01-31", 4), line(filter, "2027-06-30", 3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(3));
        postAs(lbStaffToken, COUNTS + "/" + count + "/submit", null).andExpect(status().isOk());
        // No book to differ from, so no reason is needed.
        postAs(otherManagerToken, COUNTS + "/" + count + "/confirm", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"));

        postAs(ownerToken, COUNTS + "/" + count + "/approve", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG300"));
        getAs(ownerToken, COUNTS + "/" + count + "/opening-costs").andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.missingSkus", contains("LOC-01", "MA-PHANH-01")));

        putAs(ownerToken, COUNTS + "/" + count + "/opening-costs", Map.of("costs", List.of(
                cost(pad, 80_000), cost(filter, 50_000)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.totalValue").value(10 * 80_000 + 7 * 50_000))
                .andExpect(jsonPath("$.parts[?(@.sku == 'LOC-01')].lots[*].quantity", contains(4, 3)));
        // Nothing posts into the warehouse while the count waits for approval, so approving still works (R-01).
        assertThatThrownBy(() -> tx.execute(s -> posting.receiveNewLot(surplusInto(otherStore, count))))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("MSG301"));
        postAs(ownerToken, COUNTS + "/" + count + "/approve", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        postAs(ownerToken, COUNTS + "/" + count + "/approve", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG235"));

        // One OPENING lot per part and expiry date, posted at the end of the count day into its month.
        assertThat(jdbc.queryForList("SELECT expiry_date FROM lot WHERE stock_count_id = ? AND source_type = 'OPENING' "
                + "AND store_id = ? ORDER BY product_id, expiry_date NULLS FIRST", count, otherStore)).hasSize(3);
        assertThat(availableOf(otherStore, pad)).isEqualTo(9);
        assertThat(availableOf(otherStore, filter)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT damaged_qty FROM stock_balance WHERE store_id = ? AND product_id = ?",
                Integer.class, otherStore, pad)).isEqualTo(1);
        assertThat(movementsOf(count)).allSatisfy(m -> assertThat(m)
                .containsEntry("movement_type", "OPENING_BALANCE").containsEntry("inventory_period_id", period)
                .containsEntry("occurred_at", Timestamp.valueOf(TODAY.atTime(23, 59, 59))));
        assertThat(jdbc.queryForObject("SELECT sum(value) FROM stock_movement WHERE stock_count_id = ?", Long.class,
                count)).isEqualTo(10 * 80_000 + 7 * 50_000);
        // The first average of a part with no stock is its initial unit cost (BR-74).
        assertThat(jdbc.queryForObject("SELECT moving_avg_cost FROM product WHERE id = ?", BigDecimal.class, pad))
                .isEqualByComparingTo("80000");

        // Once something moved in the warehouse, no second opening count.
        postAs(otherManagerToken, COUNTS, opening()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG244"));
    }

    @Test
    void anApprovedInitialUnitCostCannotChange() throws Exception {
        // Gia Lâm's seeded lots are opening lots, so the oil's initial cost is settled; the brake pad's is not.
        long count = id("INSERT INTO stock_count (store_id, count_type, count_date, scope_type, status, created_by) "
                + "VALUES (?, 'OPENING', ?, 'WHOLE', 'AWAITING_APPROVAL', ?) RETURNING id", otherStore, TODAY,
                otherManager.getId());
        for (long part : List.of(oil, pad)) {
            jdbc.update("INSERT INTO stock_count_line (stock_count_id, product_id, book_qty, counted_qty) "
                    + "VALUES (?, ?, 0, 5)", count, part);
        }
        jdbc.update("UPDATE product SET initial_unit_cost = 80000 WHERE id = ?", oil);
        String costs = COUNTS + "/" + count + "/opening-costs";

        putAs(ownerToken, costs, Map.of("costs", List.of(cost(oil, 90_000)))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG299"));
        putAs(ownerToken, costs, Map.of("costs", List.of(cost(oil, 80_000), cost(pad, 70_000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parts[*].locked", contains(true, false)))
                .andExpect(jsonPath("$.complete").value(true));
        putAs(ownerToken, costs, Map.of("costs", List.of(cost(plug, 1)))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG237"));
        putAs(otherManagerToken, costs, Map.of("costs", List.of(cost(pad, 1)))).andExpect(status().isForbidden());
    }

    @Test
    void anOpeningCountNeedsAnUnmovedWarehouseAnOpenMonthAndTheWholeWarehouse() throws Exception {
        postAs(ownerToken, COUNTS, opening()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        Map<String, Object> products = new HashMap<>(opening());
        products.put("scopeType", "PRODUCTS");
        products.put("productIds", List.of(oil));
        postAs(otherManagerToken, COUNTS, products).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"));

        closePeriod(period);
        postAs(otherManagerToken, COUNTS, opening()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG232"));

        // A month with no period yet, after every closed one, is fine: the count starts with no line.
        today(TODAY.plusMonths(1));
        Map<String, Object> november = new HashMap<>(opening());
        november.put("countDate", TODAY.plusMonths(1).toString());
        postAs(otherManagerToken, COUNTS, november).andExpect(status().isCreated())
                .andExpect(jsonPath("$.countType").value("OPENING"))
                .andExpect(jsonPath("$.inventoryPeriodId").doesNotExist())
                .andExpect(jsonPath("$.lines.length()").value(0));
    }

    @Test
    void openingLinesAreCheckedAndOnlyForAnOpeningCountBeingCounted() throws Exception {
        long count = countingOpening();
        String url = COUNTS + "/" + count + "/opening-lines";

        putAs(lbStaffToken, url, Map.of("lines", List.of(line(pad, "2027-01-31", 2), line(pad, "2027-01-31", 1))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG298"));
        putAs(lbStaffToken, url, Map.of("lines", List.of(line(pad, null, 2, 3))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG266"));
        putAs(lbStaffToken, url, Map.of("lines", List.of(line(9_999, null, 2, 0))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG242"));
        putAs(otherManagerToken, url, Map.of("lines", List.of(line(pad, null, 2, 0))))
                .andExpect(status().isForbidden());
        putAs(staffToken, url, Map.of("lines", List.of(line(pad, null, 2, 0)))).andExpect(status().isForbidden());

        // Saving again replaces the lines.
        putAs(lbStaffToken, url, Map.of("lines", List.of(line(pad, null, 2, 0), line(oil, null, 1, 0))))
                .andExpect(status().isOk());
        putAs(lbStaffToken, url, Map.of("lines", List.of(line(pad, null, 5, 0)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].countedQty").value(5));

        long cycle = id("INSERT INTO stock_count (store_id, inventory_period_id, count_type, count_date, scope_type, "
                + "status, created_by, counting_started_at) VALUES (?, ?, 'CYCLE', ?, 'WHOLE', 'COUNTING', ?, now()) "
                + "RETURNING id", otherStore, period, TODAY, otherManager.getId());
        jdbc.update("UPDATE stock_count SET status = 'CANCELLED', cancellation_reason = 'x' WHERE id = ?", count);
        assign(cycle);
        putAs(lbStaffToken, COUNTS + "/" + cycle + "/opening-lines", Map.of("lines", List.of(line(pad, null, 1, 0))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG235"));
    }

    @Test
    void nothingMovesInAWarehouseWhileItsOpeningCountIsUnfinished() throws Exception {
        long count = idOf(postAs(otherManagerToken, COUNTS, opening()).andExpect(status().isCreated()));

        // A surplus lot stands for any posting: every one of them goes through the same check.
        assertThatThrownBy(() -> tx.execute(s -> posting.receiveNewLot(surplusInto(otherStore, count))))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("MSG301"));
        assertThat(id("SELECT count(*) FROM stock_movement WHERE store_id = ?", otherStore)).isZero();

        // Gia Lâm has no opening count, so it is not held up.
        tx.execute(s -> posting.receiveNewLot(surplusInto(store, count)));

        postAs(otherManagerToken, COUNTS + "/" + count + "/cancel", Map.of("reason", "Lập nhầm"))
                .andExpect(status().isOk());
        tx.execute(s -> posting.receiveNewLot(surplusInto(otherStore, count)));
        assertThat(id("SELECT count(*) FROM stock_movement WHERE store_id = ?", otherStore)).isEqualTo(1);
    }

    /**
     * R-02: creating a count holds the store row FOR UPDATE and a posting reads it FOR SHARE, so a posting that runs
     * while an opening count is being created waits for it and then sees it, instead of slipping in unseen.
     */
    @Test
    void aPostingWaitsForAnOpeningCountBeingCreatedAndThenSeesIt() throws Exception {
        // The surplus needs a committed count to point at; a cancelled one does not hold the warehouse.
        long source = id("INSERT INTO stock_count (store_id, count_type, count_date, scope_type, status, created_by, "
                + "cancellation_reason) VALUES (?, 'OPENING', ?, 'WHOLE', 'CANCELLED', ?, 'x') RETURNING id",
                otherStore, TODAY, otherManager.getId());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch posted = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> creating = pool.submit(() -> tx.execute(s -> {
                stores.findForUpdateById(otherStore).orElseThrow();
                jdbc.update("INSERT INTO stock_count (store_id, count_type, count_date, scope_type, status, "
                        + "created_by) VALUES (?, 'OPENING', ?, 'WHOLE', 'DRAFT', ?)", otherStore, TODAY,
                        otherManager.getId());
                locked.countDown();
                try {
                    // The posting must still be waiting on the store row when this commits.
                    assertThat(posted.await(1, TimeUnit.SECONDS)).isFalse();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return null;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> tx.execute(s -> posting.receiveNewLot(surplusInto(otherStore, source))))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getCode()).isEqualTo("MSG301"));
            posted.countDown();
            creating.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(id("SELECT count(*) FROM stock_movement WHERE store_id = ?", otherStore)).isZero();
    }

    private NewLotPosting surplusInto(long storeId, long count) {
        return new NewLotPosting(storeId, pad, LotSourceType.COUNT_SURPLUS, null, null, TODAY, 10_000, null, null,
                null, Map.of(StockStatus.AVAILABLE, 1), MovementType.COUNT_ADJUSTMENT, SourceRef.stockCount(count),
                owner.getId(), null, period, TODAY.atTime(23, 59, 59));
    }

    /** An opening count of Long Biên, given to its Staff and started. */
    private long countingOpening() throws Exception {
        long count = idOf(postAs(otherManagerToken, COUNTS, opening()).andExpect(status().isCreated()));
        assign(count);
        postAs(lbStaffToken, COUNTS + "/" + count + "/start", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COUNTING"));
        return count;
    }

    private void assign(long count) throws Exception {
        long staffLb = id("SELECT id FROM user_account WHERE username = 'staff.lb'");
        postAs(otherManagerToken, "/api/v1/warehouse-tasks", Map.of("taskType", "COUNTING", "documentId", count,
                "assigneeId", staffLb)).andExpect(status().isOk());
    }

    private static Map<String, Object> opening() {
        return Map.of("countType", "OPENING", "countDate", TODAY.toString(), "scopeType", "WHOLE");
    }

    private static Map<String, Object> line(long product, String expiry, int counted) {
        return line(product, expiry, counted, 0);
    }

    private static Map<String, Object> line(long product, String expiry, int counted, int damaged) {
        Map<String, Object> line = new HashMap<>();
        line.put("productId", product);
        line.put("expiryDate", expiry);
        line.put("countedQty", counted);
        line.put("damagedQty", damaged);
        return line;
    }

    private static Map<String, Object> cost(long product, long cost) {
        return Map.of("productId", product, "initialUnitCost", cost);
    }

    private static long idOf(ResultActions actions) throws Exception {
        return ((Number) JsonPath.read(actions.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }
}
