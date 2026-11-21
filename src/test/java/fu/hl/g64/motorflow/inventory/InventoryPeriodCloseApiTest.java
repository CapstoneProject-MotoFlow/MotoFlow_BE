package fu.hl.g64.motorflow.inventory;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.common.config.ClockConfig;
import fu.hl.g64.motorflow.inventory.dto.MovePosting;
import fu.hl.g64.motorflow.inventory.dto.NewLotPosting;
import fu.hl.g64.motorflow.inventory.dto.OutPosting;
import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.LotSourceType;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.inventory.service.InventoryPostingService;
import fu.hl.g64.motorflow.support.ApiTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-100. */
class InventoryPeriodCloseApiTest extends ApiTestUsers {

    private static final String URL = "/api/v1/inventory-periods/";

    @Autowired
    private InventoryPostingService posting;
    @Autowired
    private TransactionTemplate tx;

    private UserAccount owner;
    private long source;
    private long dest;
    private long chain;
    private long supplier;
    private long po;

    @BeforeEach
    void setUp() {
        resetDatabaseAndClock();
        source = createStore("Long Biên");
        dest = createStore("Cầu Giấy");
        owner = createUser("owner", Role.OWNER, null);
        createUser("bm", Role.BRANCH_MANAGER, source);
        long category = jdbc.queryForObject("INSERT INTO part_category (category_name, vehicle_type) "
                + "VALUES ('Truyền động', 'MOTORCYCLE') RETURNING id", Long.class);
        chain = jdbc.queryForObject("INSERT INTO product (sku, part_name, category_id, base_unit, created_by) "
                + "VALUES ('AAA-1', 'Xích 428', ?, 'cái', ?) RETURNING id", Long.class, category, owner.getId());
        supplier = jdbc.queryForObject("INSERT INTO supplier (supplier_name) VALUES ('Honda') "
                + "RETURNING id", Long.class);
        po = jdbc.queryForObject("INSERT INTO purchase_order (supplier_id, store_id, created_by) VALUES (?, ?, ?) "
                + "RETURNING id", Long.class, supplier, source, owner.getId());
    }

    @Test
    void closingStoresTheBalancesLocksThePeriodAndOpensTheNextMonth() throws Exception {
        at("2026-10-01T10:00:00");
        receive(10);
        at("2026-10-02T10:00:00");
        sell(3);
        at("2026-10-03T10:00:00");
        dispatch(2);
        at("2026-11-10T10:00:00");
        long october = period("2026-10-01");

        call(post(URL + october + "/close"), "owner").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedByName").value("Test owner"))
                .andExpect(jsonPath("$.nextToClose").value(false));

        var rows = jdbc.queryForList("SELECT store_id, opening_qty, in_qty, out_qty, closing_qty, closing_value, "
                + "period_avg_unit_cost FROM period_stock_balance WHERE inventory_period_id = ? ORDER BY store_id",
                october);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("store_id", source).containsEntry("opening_qty", 0)
                .containsEntry("in_qty", 10).containsEntry("out_qty", 5).containsEntry("closing_qty", 5)
                .containsEntry("closing_value", 500_000L);
        assertThat((BigDecimal) rows.get(0).get("period_avg_unit_cost")).isEqualByComparingTo("100000");
        assertThat(rows.get(1)).containsEntry("store_id", dest).containsEntry("in_qty", 2)
                .containsEntry("closing_qty", 2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'PERIOD_CLOSED' "
                + "AND entity_id = ?", Integer.class, october)).isEqualTo(1);

        // November is open and next; its opening comes from October's stored closing.
        long november = period("2026-11-01");
        call(get("/api/v1/inventory-periods"), "owner")
                .andExpect(jsonPath("$[*].month", contains("2026-11", "2026-10")))
                .andExpect(jsonPath("$[0].nextToClose").value(true));
        call(get(URL + november + "/balances?storeId=" + source), "owner")
                .andExpect(jsonPath("$.page.items[0].openingQty").value(5))
                .andExpect(jsonPath("$.page.items[0].openingValue").value(500_000));
        // October now shows the stored balances.
        call(get(URL + october + "/balances"), "owner").andExpect(jsonPath("$.provisional").value(false));

        call(post(URL + october + "/close"), "owner").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG296"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM period_stock_balance", Integer.class)).isEqualTo(2);
    }

    @Test
    void periodsCloseInMonthOrderAndOnlyOnceTheMonthHasEnded() throws Exception {
        at("2026-09-20T10:00:00");
        receive(4);
        at("2026-10-05T10:00:00");
        receive(1);
        at("2026-11-10T10:00:00");
        sell(1);
        long september = period("2026-09-01");
        long october = period("2026-10-01");
        long november = period("2026-11-01");

        call(post(URL + october + "/close"), "owner").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG294"))
                .andExpect(jsonPath("$.message").value("Kỳ tháng 10/2026 chưa tới lượt đóng: phải đóng kỳ mở cũ "
                        + "nhất trước."));
        call(post(URL + september + "/close"), "owner").andExpect(status().isOk());
        call(post(URL + october + "/close"), "owner").andExpect(status().isOk());
        call(get(URL + november + "/closing-preview"), "owner").andExpect(jsonPath("$.canClose").value(false))
                .andExpect(jsonPath("$.reason").value("MSG297"));
        call(post(URL + november + "/close"), "owner").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG297"));

        // A month with no movement has no period: after November, December is opened by the close.
        at("2026-12-01T08:00:00");
        call(post(URL + november + "/close"), "owner").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM inventory_period WHERE period_month = '2026-12-01'",
                String.class)).isEqualTo("OPEN");
        // Closed periods take no more movements (MSG85 is checked by the posting service).
        assertThat(jdbc.queryForObject("SELECT closing_qty FROM period_stock_balance WHERE inventory_period_id = ?",
                Integer.class, november)).isEqualTo(4);
    }

    @Test
    void anUnfinishedCountBlocksClosingWhileReceiptsAndTransfersOnlyWarn() throws Exception {
        at("2026-10-01T10:00:00");
        receive(5);
        at("2026-11-10T10:00:00");
        long october = period("2026-10-01");
        long count = jdbc.queryForObject("INSERT INTO stock_count (store_id, inventory_period_id, count_type, "
                + "count_date, scope_type, created_by, status) VALUES (?, ?, 'CYCLE', '2026-10-31', 'WHOLE', ?, "
                + "'COUNTING') RETURNING id", Long.class, source, october, owner.getId());
        long receipt = jdbc.queryForObject("INSERT INTO goods_receipt_note (receipt_type, purchase_order_id, "
                + "delivery_document_no, arrived_at) VALUES ('SUPPLIER_DELIVERY', ?, 'PX-9', '2026-10-30 15:00') "
                + "RETURNING id", Long.class, po);
        long transfer = jdbc.queryForObject("INSERT INTO transfer_order (source_store_id, dest_store_id, status, "
                + "created_by, dispatched_by, dispatched_at) VALUES (?, ?, 'IN_TRANSIT', ?, ?, '2026-10-31 09:00') "
                + "RETURNING id", Long.class, source, dest, owner.getId(), owner.getId());

        call(get(URL + october + "/closing-preview"), "owner").andExpect(status().isOk())
                .andExpect(jsonPath("$.canClose").value(false))
                .andExpect(jsonPath("$.reason").value("MSG295"))
                .andExpect(jsonPath("$.blockingCounts[*].id", contains((int) count)))
                .andExpect(jsonPath("$.blockingCounts[0].storeName").value("Long Biên"))
                .andExpect(jsonPath("$.blockingCounts[0].status").value("COUNTING"))
                .andExpect(jsonPath("$.unconfirmedReceipts[*].id", contains((int) receipt)))
                .andExpect(jsonPath("$.unconfirmedReceipts[0].storeName").value("Long Biên"))
                .andExpect(jsonPath("$.transfersInTransit[*].id", contains((int) transfer)))
                .andExpect(jsonPath("$.transfersInTransit[0].destStoreName").value("Cầu Giấy"))
                .andExpect(jsonPath("$.balanceRows").value(1))
                .andExpect(jsonPath("$.totals.closingValue").value(500_000));
        call(post(URL + october + "/close"), "owner").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG295"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM period_stock_balance", Integer.class)).isZero();

        jdbc.update("UPDATE stock_count SET status = 'CANCELLED', cancelled_by = ?, cancelled_at = now(), "
                + "cancellation_reason = 'test' WHERE id = ?", owner.getId(), count);
        call(get(URL + october + "/closing-preview"), "owner").andExpect(jsonPath("$.canClose").value(true))
                .andExpect(jsonPath("$.blockingCounts", hasSize(0)))
                .andExpect(jsonPath("$.unconfirmedReceipts", hasSize(1)));
        call(post(URL + october + "/close"), "owner").andExpect(status().isOk());
    }

    @Test
    void onlyTheOwnerClosesAndUnknownPeriodsAreNotFound() throws Exception {
        at("2026-10-01T10:00:00");
        receive(1);
        at("2026-11-10T10:00:00");
        long october = period("2026-10-01");

        call(get(URL + october + "/closing-preview"), "bm").andExpect(status().isForbidden());
        call(post(URL + october + "/close"), "bm").andExpect(status().isForbidden());
        call(get(URL + "9999/closing-preview"), "owner").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG293"));
        call(post(URL + "9999/close"), "owner").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG293"));
        assertThat(jdbc.queryForObject("SELECT status FROM inventory_period WHERE id = ?", String.class, october))
                .isEqualTo("OPEN");
    }

    private ResultActions call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               String username) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, signIn(username)));
    }

    private long period(String month) {
        return jdbc.queryForObject("SELECT id FROM inventory_period WHERE period_month = ?::date", Long.class, month);
    }

    private void receive(int qty) {
        long grn = jdbc.queryForObject("INSERT INTO goods_receipt_note (receipt_type, purchase_order_id, "
                + "delivery_document_no, arrived_at) VALUES ('SUPPLIER_DELIVERY', ?, ?, now()) RETURNING id", Long.class,
                po, "PX-" + System.nanoTime());
        jdbc.update("UPDATE goods_receipt_note SET status = 'CONFIRMED', recorded_by = ?, submitted_at = now() "
                + "WHERE id = ?", owner.getId(), grn);
        long line = jdbc.queryForObject("INSERT INTO goods_receipt_note_line (goods_receipt_note_id, product_id, "
                + "document_qty, delivered_qty, passed_qty) VALUES (?, ?, ?, ?, ?) RETURNING id", Long.class,
                grn, chain, qty, qty, qty);
        tx.execute(s -> posting.receiveNewLot(new NewLotPosting(source, chain, LotSourceType.RECEIPT, line, supplier,
                LocalDate.of(2026, 9, 1), 100_000, null, null, null, Map.of(StockStatus.AVAILABLE, qty),
                MovementType.RECEIPT_CONFIRMED, SourceRef.goodsReceiptNote(grn), owner.getId(), null)));
    }

    private void sell(int qty) {
        long sale = jdbc.queryForObject("INSERT INTO sales_order (store_id, price_type, created_by) VALUES (?, 'RETAIL', ?) "
                + "RETURNING id", Long.class, source, owner.getId());
        tx.execute(s -> posting.takeOut(new OutPosting(source, chain, StockStatus.AVAILABLE, qty, null,
                MovementType.SALE_ISSUE, SourceRef.salesOrder(sale), owner.getId(), null)));
    }

    private void dispatch(int qty) {
        long transfer = jdbc.queryForObject("INSERT INTO transfer_order (source_store_id, dest_store_id, status, "
                + "created_by, dispatched_by, dispatched_at) VALUES (?, ?, 'CLOSED', ?, ?, now()) RETURNING id",
                Long.class, source, dest, owner.getId(), owner.getId());
        tx.execute(s -> posting.move(new MovePosting(chain, source, StockStatus.AVAILABLE, dest, StockStatus.IN_TRANSIT,
                qty, null, MovementType.TRANSFER_DISPATCH, SourceRef.transferOrder(transfer), owner.getId(), null)));
    }

    private void at(String vietnamTime) {
        clock.set(LocalDateTime.parse(vietnamTime).atZone(ClockConfig.VIETNAM).toInstant());
    }
}
