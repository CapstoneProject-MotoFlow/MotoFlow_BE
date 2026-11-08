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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-103. */
class PastDateStockApiTest extends ApiTestUsers {

    private static final String URL = "/api/v1/inventory/past-date-stock";

    @Autowired
    private InventoryPostingService posting;
    @Autowired
    private TransactionTemplate tx;

    private UserAccount owner;
    private long central;
    private long source;
    private long dest;
    private long category;
    private long childCategory;
    private long alpha;
    private long beta;
    private long supplier;
    private long po;

    @BeforeEach
    void setUp() {
        resetDatabaseAndClock();
        central = createStore("Kho tổng");
        jdbc.update("UPDATE store SET is_central = TRUE WHERE id = ?", central);
        source = createStore("Long Biên");
        dest = createStore("Cầu Giấy");
        owner = createUser("owner", Role.OWNER, null);
        createUser("admin", Role.ADMIN, null);
        createUser("bm", Role.BRANCH_MANAGER, source);
        createUser("bmkt", Role.BRANCH_MANAGER, central);
        createUser("staff", Role.STAFF, source);
        category = jdbc.queryForObject("INSERT INTO part_category (category_name, vehicle_type) "
                + "VALUES ('Phanh', 'MOTORCYCLE') RETURNING id", Long.class);
        childCategory = jdbc.queryForObject("INSERT INTO part_category (category_name, vehicle_type, parent_id) "
                + "VALUES ('Má phanh', 'MOTORCYCLE', ?) RETURNING id", Long.class, category);
        alpha = product("AAA-1", "Xích 428", category);
        beta = product("BBB-1", "Má phanh trước", childCategory);
        supplier = jdbc.queryForObject("INSERT INTO supplier (supplier_code, supplier_name) VALUES ('NCC001', 'Honda') "
                + "RETURNING id", Long.class);
        po = jdbc.queryForObject("INSERT INTO purchase_order (supplier_id, store_id, created_by) VALUES (?, ?, ?) "
                + "RETURNING id", Long.class, supplier, central, owner.getId());
    }

    @Test
    void closingQuantityFollowsSalesAndTransfersAndExcludesInTransit() throws Exception {
        at("2026-10-01T10:00:00");
        receive(source, alpha, 10);
        at("2026-10-03T10:00:00");
        sell(source, alpha, 3);
        at("2026-10-05T10:00:00");
        long transfer = jdbc.queryForObject("INSERT INTO transfer_order (source_store_id, dest_store_id, created_by) "
                + "VALUES (?, ?, ?) RETURNING id", Long.class, source, dest, owner.getId());
        tx.execute(s -> posting.move(new MovePosting(alpha, source, StockStatus.AVAILABLE, dest, StockStatus.IN_TRANSIT,
                2, null, MovementType.TRANSFER_DISPATCH, SourceRef.transferOrder(transfer), owner.getId(), null)));
        today();

        query("owner", "date=2026-09-30&storeId=" + source).andExpect(jsonPath("$.page.totalItems").value(0));
        query("owner", "date=2026-09-30&storeId=" + source + "&includeZero=true").andExpect(qty(0));
        query("owner", "date=2026-10-02&storeId=" + source).andExpect(qty(10));
        query("owner", "date=2026-10-03&storeId=" + source).andExpect(qty(7));
        query("owner", "date=2026-10-04&storeId=" + source).andExpect(qty(7));
        query("owner", "date=2026-10-05&storeId=" + source).andExpect(qty(5))
                .andExpect(jsonPath("$.storeName").value("Long Biên"))
                .andExpect(jsonPath("$.basedOnPeriod").value(nullValue()))
                .andExpect(jsonPath("$.page.items[0].sku").value("AAA-1"))
                .andExpect(jsonPath("$.page.items[0].partName").value("Xích 428"))
                .andExpect(jsonPath("$.page.items[0].baseUnit").value("cái"));
        // Goods in transit sit at the destination but are not counted there.
        query("owner", "date=2026-10-05&storeId=" + dest).andExpect(jsonPath("$.page.totalItems").value(0));
        query("owner", "date=2026-10-05&storeId=" + dest + "&includeZero=true").andExpect(qty(0));
    }

    @Test
    void movingToDamagedKeepsTheClosingQuantity() throws Exception {
        at("2026-10-01T10:00:00");
        receive(source, alpha, 10);
        at("2026-10-02T10:00:00");
        long sale = jdbc.queryForObject("INSERT INTO sales_order (store_id, price_type, created_by) VALUES (?, 'RETAIL', ?) "
                + "RETURNING id", Long.class, source, owner.getId());
        tx.execute(s -> posting.move(new MovePosting(alpha, source, StockStatus.AVAILABLE, source, StockStatus.DAMAGED,
                4, null, MovementType.RETURN_HOLD, SourceRef.salesOrder(sale), owner.getId(), null)));
        today();

        query("owner", "date=2026-10-03&storeId=" + source).andExpect(qty(10));
    }

    @Test
    void startsFromTheLatestClosedPeriodThatEndedBeforeTheDate() throws Exception {
        closedPeriod("2026-10-01", "2026-10-31", alpha, source, 50);
        at("2026-11-03T10:00:00");
        receive(source, alpha, 5);
        today();

        query("owner", "date=2026-11-04&storeId=" + source).andExpect(qty(55))
                .andExpect(jsonPath("$.basedOnPeriod").value("2026-10"));
        // The period ends on 31/10, so a date inside it cannot use its closing quantity.
        query("owner", "date=2026-10-15&storeId=" + source + "&includeZero=true").andExpect(qty(0))
                .andExpect(jsonPath("$.basedOnPeriod").value(nullValue()));
    }

    @Test
    void anEarlierClosedPeriodIsUsedWhenTheLaterOneEndsAfterTheDate() throws Exception {
        closedPeriod("2026-09-01", "2026-09-30", alpha, source, 40);
        at("2026-10-05T10:00:00");
        receive(source, alpha, 5);
        closedPeriod("2026-10-01", "2026-10-31", alpha, source, 50);
        today();

        query("owner", "date=2026-10-15&storeId=" + source).andExpect(qty(45))
                .andExpect(jsonPath("$.basedOnPeriod").value("2026-09"));
        query("owner", "date=2026-11-01&storeId=" + source).andExpect(qty(50))
                .andExpect(jsonPath("$.basedOnPeriod").value("2026-10"));
    }

    @Test
    void anOpenPeriodIsNeverUsedAsABase() throws Exception {
        at("2026-10-01T10:00:00");
        receive(source, alpha, 10);
        jdbc.update("INSERT INTO period_stock_balance (inventory_period_id, store_id, product_id, opening_qty, "
                + "opening_value, in_qty, in_value, out_qty, out_value, closing_qty, closing_value) "
                + "SELECT id, ?, ?, 99, 0, 0, 0, 0, 0, 99, 0 FROM inventory_period WHERE period_month = '2026-10-01'",
                source, alpha);
        today();

        query("owner", "date=2026-10-20&storeId=" + source).andExpect(qty(10))
                .andExpect(jsonPath("$.basedOnPeriod").value(nullValue()));
    }

    @Test
    void todayAndFutureDatesAreRejected() throws Exception {
        today();
        query("owner", "date=2026-11-10&storeId=" + source).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG92"));
        query("owner", "date=2026-11-11&storeId=" + source).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG92"));
    }

    @Test
    void permissionsAndRequiredParameters() throws Exception {
        today();
        query("bm", "date=2026-11-01&storeId=" + dest).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        query("bmkt", "date=2026-11-01&storeId=" + source).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        query("bm", "date=2026-11-01").andExpect(status().isOk()).andExpect(jsonPath("$.storeId").value(source));
        query("bmkt", "date=2026-11-01").andExpect(status().isOk()).andExpect(jsonPath("$.storeName").value("Kho tổng"));
        query("owner", "date=2026-11-01").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"));
        query("owner", "date=2026-11-01&storeId=9999").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG52"));
        query("staff", "date=2026-11-01").andExpect(status().isForbidden());
        query("admin", "date=2026-11-01&storeId=" + source).andExpect(status().isForbidden());
        query("owner", "storeId=" + source).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"));
        query("owner", "date=01-11-2026&storeId=" + source).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"));
    }

    @Test
    void zeroQuantitiesAreHiddenUnlessAskedForAndPagesFollowTheSku() throws Exception {
        at("2026-10-01T10:00:00");
        receive(source, alpha, 5);
        receive(source, beta, 3);
        at("2026-10-02T10:00:00");
        sell(source, beta, 3);
        today();

        String base = "date=2026-10-03&storeId=" + source;
        query("owner", base).andExpect(jsonPath("$.page.totalItems").value(1))
                .andExpect(jsonPath("$.page.items[*].sku", contains("AAA-1")));
        query("owner", base + "&includeZero=true").andExpect(jsonPath("$.page.totalItems").value(2))
                .andExpect(jsonPath("$.page.items[*].sku", contains("AAA-1", "BBB-1")));
        query("owner", base + "&includeZero=true&size=1&page=0").andExpect(jsonPath("$.page.totalItems").value(2))
                .andExpect(jsonPath("$.page.items[*].sku", contains("AAA-1")));
        query("owner", base + "&includeZero=true&size=1&page=1").andExpect(jsonPath("$.page.totalItems").value(2))
                .andExpect(jsonPath("$.page.items[*].sku", contains("BBB-1")))
                .andExpect(jsonPath("$.page.items[0].closingQty").value(0));
        // Before the sale both parts have stock.
        query("owner", "date=2026-10-01&storeId=" + source).andExpect(jsonPath("$.page.totalItems").value(2));
    }

    @Test
    void filtersBySearchTextAndCategoryWithDescendants() throws Exception {
        at("2026-10-01T10:00:00");
        receive(source, alpha, 5);
        receive(source, beta, 3);
        today();

        String base = "date=2026-10-03&storeId=" + source;
        query("owner", base + "&q=XÍCH").andExpect(jsonPath("$.page.items[*].sku", contains("AAA-1")));
        query("owner", base + "&q=bbb").andExpect(jsonPath("$.page.items[*].sku", contains("BBB-1")));
        query("owner", base + "&categoryId=" + category).andExpect(jsonPath("$.page.totalItems").value(2));
        query("owner", base + "&categoryId=" + childCategory).andExpect(jsonPath("$.page.items[*].sku", contains("BBB-1")));
    }

    @Test
    void theLastSecondOfTheDayCountsAndMidnightDoesNot() throws Exception {
        at("2026-10-01T10:00:00");
        receive(source, alpha, 10);
        at("2026-10-05T23:59:59");
        receive(source, alpha, 4);
        at("2026-10-06T00:00:00");
        receive(source, alpha, 8);
        today();

        query("owner", "date=2026-10-05&storeId=" + source).andExpect(qty(14));
        query("owner", "date=2026-10-06&storeId=" + source).andExpect(qty(22));
    }

    private ResultActions query(String username, String query) throws Exception {
        return mvc.perform(get(URL + "?" + query).header(HttpHeaders.AUTHORIZATION, signIn(username)));
    }

    private static org.springframework.test.web.servlet.ResultMatcher qty(int expected) {
        return jsonPath("$.page.items[0].closingQty").value(expected);
    }

    private void at(String vietnamTime) {
        clock.set(LocalDateTime.parse(vietnamTime).atZone(ClockConfig.VIETNAM).toInstant());
    }

    private void today() {
        at("2026-11-10T10:00:00");
    }

    /** Makes the month a CLOSED period (posting may already have opened it) with one closing balance. */
    private void closedPeriod(String month, String lastDay, long product, long store, int closingQty) {
        long id = jdbc.queryForObject("INSERT INTO inventory_period (period_month, from_date, to_date, status, closed_by, "
                + "closed_at) VALUES (?::date, ?::date, ?::date, 'CLOSED', ?, '2026-11-01') "
                + "ON CONFLICT (period_month) DO UPDATE SET status = 'CLOSED', closed_by = EXCLUDED.closed_by, "
                + "closed_at = EXCLUDED.closed_at RETURNING id", Long.class,
                month, month, lastDay, owner.getId());
        jdbc.update("INSERT INTO period_stock_balance (inventory_period_id, store_id, product_id, opening_qty, "
                + "opening_value, in_qty, in_value, out_qty, out_value, closing_qty, closing_value) "
                + "VALUES (?, ?, ?, ?, 0, 0, 0, 0, 0, ?, 0)", id, store, product, closingQty, closingQty);
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
}
