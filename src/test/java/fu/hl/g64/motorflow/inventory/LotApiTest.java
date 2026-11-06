package fu.hl.g64.motorflow.inventory;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-97. */
class LotApiTest extends ApiTestUsers {

    private static final String LOTS = "/api/v1/inventory/lots";

    @Autowired
    private InventoryPostingService posting;
    @Autowired
    private TransactionTemplate tx;

    private UserAccount owner;
    private LocalDate today;
    private long central;
    private long branch;
    private long brake;
    private long chain;
    private long honda;
    private long yamaha;
    private long po;
    private long a;
    private long b;
    private long c;
    private long d;
    private long e;
    private long f;
    private long other;

    @BeforeEach
    void setUp() {
        resetDatabaseAndClock();
        today = LocalDate.now(clock);
        central = createStore("Kho tổng");
        jdbc.update("UPDATE store SET is_central = TRUE WHERE id = ?", central);
        branch = createStore("Long Biên");
        owner = createUser("owner", Role.OWNER, null);
        createUser("admin", Role.ADMIN, null);
        createUser("bmkt", Role.BRANCH_MANAGER, central);
        createUser("bm", Role.BRANCH_MANAGER, branch);
        createUser("staff", Role.STAFF, branch);
        long category = jdbc.queryForObject("INSERT INTO part_category (category_name, vehicle_type) "
                + "VALUES ('Phanh', 'MOTORCYCLE') RETURNING id", Long.class);
        brake = product("MA-PHANH", "Má phanh trước", category);
        chain = product("XICH-XE", "Xích tải", category);
        honda = supplier("NCC001", "Honda");
        yamaha = supplier("NCC002", "Yamaha");
        po = jdbc.queryForObject("INSERT INTO purchase_order (supplier_id, store_id, created_by) VALUES (?, ?, ?) "
                + "RETURNING id", Long.class, honda, central, owner.getId());
        jdbc.update("INSERT INTO pricing_policy (policy_type, expiry_warning_days, effective_from, status, set_by, set_at) "
                + "VALUES ('EXPIRY_WARNING', 30, '2026-01-01', 'ACTIVE', ?, '2026-01-01')", owner.getId());

        a = receive(branch, brake, honda, today.minusDays(5), today.plusDays(10), "NSX-ALPHA");
        b = receive(branch, brake, honda, today.minusDays(6), today.minusDays(1), null);
        c = receive(branch, brake, yamaha, today.minusDays(7), today.plusDays(100), null);
        d = receive(branch, brake, honda, today.minusDays(8), null, null);
        e = receive(branch, chain, honda, today.minusDays(9), null, null);
        sellAll(branch, chain, 3);
        f = opening(branch, brake, today.minusDays(20));
        other = receive(central, brake, honda, today.minusDays(4), today.plusDays(5), null);
    }

    @Test
    void defaultTabIsNearExpiry() throws Exception {
        lots("bm", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.expiryWarningDays").value(30))
                .andExpect(jsonPath("$.page.items[*].lotId", contains((int) a)))
                .andExpect(jsonPath("$.page.totalItems").value(1));
    }

    @Test
    void tabsSplitTheLots() throws Exception {
        lots("bm", "?tab=EXPIRED").andExpect(jsonPath("$.page.items[*].lotId", contains((int) b)));
        lots("bm", "?tab=IN_STOCK").andExpect(jsonPath("$.page.items[*].lotId",
                containsInAnyOrder((int) a, (int) b, (int) c, (int) d, (int) f)));
        lots("bm", "?tab=DEPLETED")
                .andExpect(jsonPath("$.page.items[*].lotId", contains((int) e)))
                .andExpect(jsonPath("$.page.items[0].remainingQty").value(0));
    }

    @Test
    void lotMarkedExpiredCountsAsExpired() throws Exception {
        jdbc.update("UPDATE lot SET status = 'EXPIRED' WHERE id = ?", c);
        lots("bm", "?tab=EXPIRED").andExpect(jsonPath("$.page.items[*].lotId", contains((int) b, (int) c)));
    }

    @Test
    void remainingIgnoresStockInTransit() throws Exception {
        jdbc.update("INSERT INTO lot_balance (lot_id, store_id, stock_status, quantity) VALUES (?, ?, 'DAMAGED', 2), "
                + "(?, ?, 'IN_TRANSIT', 7)", a, branch, a, branch);
        // On its way to the central warehouse: it has not been there yet, so it is not a depleted lot of that warehouse.
        jdbc.update("INSERT INTO lot_balance (lot_id, store_id, stock_status, quantity) VALUES (?, ?, 'IN_TRANSIT', 4)",
                c, central);
        lots("bm", "").andExpect(jsonPath("$.page.items[0].remainingQty").value(5));
        lots("bmkt", "?tab=DEPLETED").andExpect(jsonPath("$.page.items", empty()));
    }

    @Test
    void listsAreOrderedWithPagingCounts() throws Exception {
        lots("bm", "?tab=IN_STOCK").andExpect(jsonPath("$.page.items[*].lotId",
                contains((int) b, (int) a, (int) c, (int) f, (int) d)));
        lots("bm", "?tab=IN_STOCK&page=1&size=2")
                .andExpect(jsonPath("$.page.totalItems").value(5))
                .andExpect(jsonPath("$.page.items[*].lotId", contains((int) c, (int) f)));

        long newer = receive(branch, chain, honda, today.minusDays(2), null, null);
        long sameDay = receive(branch, chain, honda, today.minusDays(2), null, null);
        sellAll(branch, chain, 6);
        lots("bm", "?tab=DEPLETED").andExpect(jsonPath("$.page.items[*].lotId",
                contains((int) sameDay, (int) newer, (int) e)));
    }

    @Test
    void filters() throws Exception {
        lots("bm", "?tab=IN_STOCK&q=" + d).andExpect(jsonPath("$.page.items[*].lotId", contains((int) d)));
        lots("bm", "?tab=DEPLETED&q=xich-").andExpect(jsonPath("$.page.items[*].lotId", contains((int) e)));
        lots("bm", "?tab=DEPLETED&q=XÍCH").andExpect(jsonPath("$.page.items[*].lotId", contains((int) e)));
        lots("bm", "?tab=IN_STOCK&q=nsx-alpha").andExpect(jsonPath("$.page.items[*].lotId", contains((int) a)));
        lots("bm", "?tab=IN_STOCK&supplierId=" + yamaha).andExpect(jsonPath("$.page.items[*].lotId", contains((int) c)));
        lots("bm", "?tab=IN_STOCK&source=OPENING")
                .andExpect(jsonPath("$.page.items[*].lotId", contains((int) f)))
                .andExpect(jsonPath("$.page.items[0].supplierName").value(nullValue()));
        lots("bm", "?tab=IN_STOCK&receivedFrom=" + today.minusDays(7) + "&receivedTo=" + today.minusDays(6))
                .andExpect(jsonPath("$.page.items[*].lotId", contains((int) b, (int) c)));
    }

    @Test
    void rowCarriesPartStoreSupplierAndDaysToExpiry() throws Exception {
        lots("bm", "?tab=IN_STOCK&size=100")
                .andExpect(jsonPath("$.page.items[0].daysToExpiry").value(-1))
                .andExpect(jsonPath("$.page.items[1].daysToExpiry").value(10))
                .andExpect(jsonPath("$.page.items[1].storeName").value("Long Biên"))
                .andExpect(jsonPath("$.page.items[1].sku").value("MA-PHANH"))
                .andExpect(jsonPath("$.page.items[1].partName").value("Má phanh trước"))
                .andExpect(jsonPath("$.page.items[1].baseUnit").value("cái"))
                .andExpect(jsonPath("$.page.items[1].supplierName").value("Honda"))
                .andExpect(jsonPath("$.page.items[1].manufacturerLotNo").value("NSX-ALPHA"))
                .andExpect(jsonPath("$.page.items[1].remainingQty").value(3))
                .andExpect(jsonPath("$.page.items[4].lotId").value(d))
                .andExpect(jsonPath("$.page.items[4].daysToExpiry").value(nullValue()));
    }

    @Test
    void branchManagersStayInTheirOwnWarehouse() throws Exception {
        lots("bm", "?storeId=" + branch).andExpect(status().isOk());
        lots("bm", "?storeId=" + central).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        lots("bmkt", "").andExpect(jsonPath("$.page.items[*].lotId", contains((int) other)));
        lots("bmkt", "?storeId=" + branch).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
    }

    @Test
    void ownerSeesEveryWarehouseOrOne() throws Exception {
        lots("owner", "").andExpect(jsonPath("$.page.items[*].lotId", contains((int) other, (int) a)));
        lots("owner", "?storeId=" + central).andExpect(jsonPath("$.page.items[*].lotId", contains((int) other)));
        lots("owner", "?storeId=9999").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG52"));
    }

    @Test
    void listCarriesNoPrice() throws Exception {
        String body = lots("owner", "?tab=IN_STOCK").andReturn().getResponse().getContentAsString().toLowerCase();
        assertThat(body).contains("lotid").doesNotContain("price").doesNotContain("cost");
    }

    @Test
    void staffAndAdminAreRefused() throws Exception {
        lots("staff", "").andExpect(status().isForbidden());
        lots("admin", "").andExpect(status().isForbidden());
    }

    @Test
    void withoutWarningPeriodNearExpiryIsEmpty() throws Exception {
        jdbc.update("DELETE FROM pricing_policy WHERE policy_type = 'EXPIRY_WARNING'");
        lots("bm", "")
                .andExpect(jsonPath("$.expiryWarningDays").value(nullValue()))
                .andExpect(jsonPath("$.page.items", empty()));
        lots("bm", "?tab=EXPIRED").andExpect(jsonPath("$.page.items[*].lotId", contains((int) b)));
    }

    // ------------------------------------------------------------------------------------------------------------

    private ResultActions lots(String username, String query) throws Exception {
        return mvc.perform(get(LOTS + query).header(HttpHeaders.AUTHORIZATION, signIn(username)));
    }

    private long product(String sku, String name, long category) {
        return jdbc.queryForObject("INSERT INTO product (sku, part_name, category_id, base_unit, created_by) "
                + "VALUES (?, ?, ?, 'cái', ?) RETURNING id", Long.class, sku, name, category, owner.getId());
    }

    private long supplier(String code, String name) {
        return jdbc.queryForObject("INSERT INTO supplier (supplier_code, supplier_name) VALUES (?, ?) RETURNING id",
                Long.class, code, name);
    }

    /** 3 units from a new goods receipt note line; returns the lot. */
    private long receive(long store, long product, long supplier, LocalDate received, LocalDate expiry, String lotNo) {
        long grn = jdbc.queryForObject("INSERT INTO goods_receipt_note (receipt_type, purchase_order_id, "
                + "delivery_document_no, arrived_at) VALUES ('SUPPLIER_DELIVERY', ?, ?, now()) RETURNING id", Long.class,
                po, "PX-" + System.nanoTime());
        long line = jdbc.queryForObject("INSERT INTO goods_receipt_note_line (goods_receipt_note_id, product_id, "
                + "document_qty, delivered_qty, passed_qty) VALUES (?, ?, 3, 3, 3) RETURNING id", Long.class, grn, product);
        tx.execute(s -> posting.receiveNewLot(new NewLotPosting(store, product, LotSourceType.RECEIPT, line, supplier,
                received, 100_000, lotNo, null, expiry, Map.of(StockStatus.AVAILABLE, 3),
                MovementType.RECEIPT_CONFIRMED, SourceRef.goodsReceiptNote(grn), owner.getId(), null)));
        return newestLot();
    }

    private long opening(long store, long product, LocalDate received) {
        long count = jdbc.queryForObject("INSERT INTO stock_count (store_id, count_type, count_date, scope_type, created_by) "
                + "VALUES (?, 'OPENING', ?, 'WHOLE', ?) RETURNING id", Long.class, store, received, owner.getId());
        tx.execute(s -> posting.receiveNewLot(new NewLotPosting(store, product, LotSourceType.OPENING, null, null,
                received, 80_000, null, null, null, Map.of(StockStatus.AVAILABLE, 3),
                MovementType.OPENING_BALANCE, SourceRef.stockCount(count), owner.getId(), null)));
        return newestLot();
    }

    private void sellAll(long store, long product, int qty) {
        long sale = jdbc.queryForObject("INSERT INTO sales_order (store_id, price_type, created_by) VALUES (?, 'RETAIL', ?) "
                + "RETURNING id", Long.class, store, owner.getId());
        tx.execute(s -> posting.takeOut(new OutPosting(store, product, StockStatus.AVAILABLE, qty, null,
                MovementType.SALE_ISSUE, SourceRef.salesOrder(sale), owner.getId(), null)));
    }

    private long newestLot() {
        return jdbc.queryForObject("SELECT max(id) FROM lot", Long.class);
    }
}
