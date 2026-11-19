package fu.hl.g64.motorflow.inventory;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.common.config.ClockConfig;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-99. */
class InventoryPeriodApiTest extends ApiTestUsers {

    private static final String URL = "/api/v1/inventory-periods";

    @Autowired
    private InventoryPostingService posting;
    @Autowired
    private TransactionTemplate tx;

    private UserAccount owner;
    private long longBien;
    private long cauGiay;
    private long brakes;
    private long chain;
    private long pads;
    private long supplier;
    private long po;

    @BeforeEach
    void setUp() {
        resetDatabaseAndClock();
        longBien = createStore("Long Biên");
        cauGiay = createStore("Cầu Giấy");
        owner = createUser("owner", Role.OWNER, null);
        createUser("bm", Role.BRANCH_MANAGER, longBien);
        createUser("staff", Role.STAFF, longBien);
        brakes = jdbc.queryForObject("INSERT INTO part_category (category_name, vehicle_type) "
                + "VALUES ('Phanh', 'MOTORCYCLE') RETURNING id", Long.class);
        long other = jdbc.queryForObject("INSERT INTO part_category (category_name, vehicle_type) "
                + "VALUES ('Truyền động', 'MOTORCYCLE') RETURNING id", Long.class);
        chain = product("AAA-1", "Xích 428", other);
        pads = product("BBB-1", "Má phanh trước", brakes);
        supplier = jdbc.queryForObject("INSERT INTO supplier (supplier_code, supplier_name) VALUES ('NCC001', 'Honda') "
                + "RETURNING id", Long.class);
        po = jdbc.queryForObject("INSERT INTO purchase_order (supplier_id, store_id, created_by) VALUES (?, ?, ?) "
                + "RETURNING id", Long.class, supplier, longBien, owner.getId());
    }

    @Test
    void periodsAreListedNewestFirstWithWhoClosedThemAndTheNextToClose() throws Exception {
        closedSeptember();
        at("2026-10-05T10:00:00");
        receive(longBien, chain, 1);
        at("2026-11-05T10:00:00");
        receive(longBien, chain, 1);

        call("owner", "").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].month", contains("2026-11", "2026-10", "2026-09")))
                .andExpect(jsonPath("$[*].status", contains("OPEN", "OPEN", "CLOSED")))
                .andExpect(jsonPath("$[*].nextToClose", contains(false, true, false)))
                .andExpect(jsonPath("$[2].fromDate").value("2026-09-01"))
                .andExpect(jsonPath("$[2].toDate").value("2026-09-30"))
                .andExpect(jsonPath("$[2].closedByName").value("Test owner"))
                .andExpect(jsonPath("$[0].closedByName").value(nullValue()));
    }

    @Test
    void anOpenPeriodIsWorkedOutNowAndMarkedProvisional() throws Exception {
        at("2026-10-01T10:00:00");
        receive(longBien, chain, 10);
        at("2026-10-02T10:00:00");
        sell(longBien, chain, 3);

        call("owner", "/" + october() + "/balances").andExpect(status().isOk())
                .andExpect(jsonPath("$.provisional").value(true))
                .andExpect(jsonPath("$.period.month").value("2026-10"))
                .andExpect(jsonPath("$.period.nextToClose").value(true))
                .andExpect(jsonPath("$.page.totalItems").value(1))
                .andExpect(jsonPath("$.page.items[0].storeName").value("Long Biên"))
                .andExpect(jsonPath("$.page.items[0].sku").value("AAA-1"))
                .andExpect(jsonPath("$.page.items[0].openingQty").value(0))
                .andExpect(jsonPath("$.page.items[0].inQty").value(10))
                .andExpect(jsonPath("$.page.items[0].inValue").value(1_000_000))
                .andExpect(jsonPath("$.page.items[0].outQty").value(3))
                .andExpect(jsonPath("$.page.items[0].outValue").value(300_000))
                .andExpect(jsonPath("$.page.items[0].closingQty").value(7))
                .andExpect(jsonPath("$.page.items[0].closingValue").value(700_000))
                .andExpect(jsonPath("$.page.items[0].periodAvgUnitCost").value(100_000))
                .andExpect(jsonPath("$.totals.closingValue").value(700_000));
    }

    @Test
    void aClosedPeriodShowsTheBalancesStoredWhenItWasClosed() throws Exception {
        long september = closedSeptember();

        call("owner", "/" + september + "/balances").andExpect(status().isOk())
                .andExpect(jsonPath("$.provisional").value(false))
                .andExpect(jsonPath("$.period.status").value("CLOSED"))
                .andExpect(jsonPath("$.page.items[0].inQty").value(40))
                .andExpect(jsonPath("$.page.items[0].closingQty").value(40))
                .andExpect(jsonPath("$.page.items[0].closingValue").value(4_000_000))
                .andExpect(jsonPath("$.page.items[0].periodAvgUnitCost").value(100_000));
    }

    @Test
    void filtersByWarehousePartAndCategoryAndPagesByWarehouseNameThenSku() throws Exception {
        at("2026-10-01T10:00:00");
        receive(longBien, chain, 5);
        receive(longBien, pads, 4);
        receive(cauGiay, chain, 2);
        String balances = "/" + october() + "/balances";

        call("owner", balances).andExpect(jsonPath("$.page.totalItems").value(3))
                .andExpect(jsonPath("$.page.items[*].storeName", contains("Cầu Giấy", "Long Biên", "Long Biên")))
                .andExpect(jsonPath("$.page.items[*].sku", contains("AAA-1", "AAA-1", "BBB-1")))
                .andExpect(jsonPath("$.totals.inValue").value(1_100_000));
        call("owner", balances + "?storeId=" + longBien).andExpect(jsonPath("$.page.totalItems").value(2));
        call("owner", balances + "?q=má PHANH").andExpect(jsonPath("$.page.items[*].sku", contains("BBB-1")));
        call("owner", balances + "?q=ma phanh truoc").andExpect(jsonPath("$.page.items[*].sku", contains("BBB-1")));
        call("owner", balances + "?categoryId=" + brakes).andExpect(jsonPath("$.page.items[*].sku", contains("BBB-1")));
        call("owner", balances + "?size=2&page=1").andExpect(jsonPath("$.page.totalItems").value(3))
                .andExpect(jsonPath("$.page.items[*].sku", contains("BBB-1")))
                // Totals cover every matching row, not only the page.
                .andExpect(jsonPath("$.totals.inValue").value(1_100_000));
    }

    @Test
    void onlyTheOwnerSeesPeriodsAndUnknownIdsAreNotFound() throws Exception {
        at("2026-10-01T10:00:00");
        receive(longBien, chain, 1);

        call("bm", "").andExpect(status().isForbidden());
        call("staff", "").andExpect(status().isForbidden());
        call("bm", "/" + october() + "/balances").andExpect(status().isForbidden());
        call("owner", "/9999/balances").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG293"));
        call("owner", "/" + october() + "/balances?storeId=9999").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG52"));
    }

    private ResultActions call(String username, String path) throws Exception {
        return mvc.perform(get(URL + path).header(HttpHeaders.AUTHORIZATION, signIn(username)));
    }

    private long october() {
        return jdbc.queryForObject("SELECT id FROM inventory_period WHERE period_month = '2026-10-01'", Long.class);
    }

    private long closedSeptember() {
        long id = jdbc.queryForObject("INSERT INTO inventory_period (period_month, from_date, to_date, status, "
                + "closed_by, closed_at) VALUES ('2026-09-01', '2026-09-01', '2026-09-30', 'CLOSED', ?, "
                + "'2026-10-02 08:00') RETURNING id", Long.class, owner.getId());
        jdbc.update("INSERT INTO period_stock_balance (inventory_period_id, store_id, product_id, opening_qty, "
                + "opening_value, in_qty, in_value, out_qty, out_value, closing_qty, closing_value, "
                + "period_avg_unit_cost) VALUES (?, ?, ?, 0, 0, 40, 4000000, 0, 0, 40, 4000000, 100000)",
                id, longBien, chain);
        return id;
    }

    private long product(String sku, String name, long categoryId) {
        return jdbc.queryForObject("INSERT INTO product (sku, part_name, category_id, base_unit, created_by) "
                + "VALUES (?, ?, ?, 'cái', ?) RETURNING id", Long.class, sku, name, categoryId, owner.getId());
    }

    private void receive(long store, long product, int qty) {
        long grn = jdbc.queryForObject("INSERT INTO goods_receipt_note (receipt_type, purchase_order_id, "
                + "delivery_document_no, arrived_at) VALUES ('SUPPLIER_DELIVERY', ?, ?, now()) RETURNING id", Long.class,
                po, "PX-" + System.nanoTime());
        long line = jdbc.queryForObject("INSERT INTO goods_receipt_note_line (goods_receipt_note_id, product_id, "
                + "document_qty, delivered_qty, passed_qty) VALUES (?, ?, ?, ?, ?) RETURNING id", Long.class,
                grn, product, qty, qty, qty);
        tx.execute(s -> posting.receiveNewLot(new NewLotPosting(store, product, LotSourceType.RECEIPT, line, supplier,
                LocalDate.of(2026, 10, 1), 100_000, null, null, null, Map.of(StockStatus.AVAILABLE, qty),
                MovementType.RECEIPT_CONFIRMED, SourceRef.goodsReceiptNote(grn), owner.getId(), null)));
    }

    private void sell(long store, long product, int qty) {
        long sale = jdbc.queryForObject("INSERT INTO sales_order (store_id, price_type, created_by) VALUES (?, 'RETAIL', ?) "
                + "RETURNING id", Long.class, store, owner.getId());
        tx.execute(s -> posting.takeOut(new OutPosting(store, product, StockStatus.AVAILABLE, qty, null,
                MovementType.SALE_ISSUE, SourceRef.salesOrder(sale), owner.getId(), null)));
    }

    private void at(String vietnamTime) {
        clock.set(LocalDateTime.parse(vietnamTime).atZone(ClockConfig.VIETNAM).toInstant());
    }
}
