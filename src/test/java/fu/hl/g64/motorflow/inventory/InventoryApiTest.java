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
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-47 to UC-51. */
class InventoryApiTest extends ApiTestUsers {

    @Autowired
    private InventoryPostingService posting;
    @Autowired
    private TransactionTemplate tx;

    private UserAccount owner;
    private long central;
    private long branch;
    private long brake;
    private long chain;
    private long supplier;
    private long po;

    @BeforeEach
    void setUp() {
        resetDatabaseAndClock();
        central = createStore("Kho tổng");
        jdbc.update("UPDATE store SET is_central = TRUE WHERE id = ?", central);
        branch = createStore("Long Biên");
        owner = createUser("owner", Role.OWNER, null);
        createUser("bmkt", Role.BRANCH_MANAGER, central);
        createUser("bm", Role.BRANCH_MANAGER, branch);
        createUser("staff", Role.STAFF, branch);
        long category = jdbc.queryForObject("INSERT INTO part_category (category_name, vehicle_type) "
                + "VALUES ('Phanh', 'MOTORCYCLE') RETURNING id", Long.class);
        brake = product("MA-PHANH", "Má phanh trước", category);
        chain = product("XICH-428", "Xích 428", category);
        supplier = jdbc.queryForObject("INSERT INTO supplier (supplier_code, supplier_name) VALUES ('NCC001', 'Honda') "
                + "RETURNING id", Long.class);
        po = jdbc.queryForObject("INSERT INTO purchase_order (supplier_id, store_id, created_by) VALUES (?, ?, ?) "
                + "RETURNING id", Long.class, supplier, central, owner.getId());
        jdbc.update("INSERT INTO pricing_policy (policy_type, expiry_warning_days, effective_from, status, set_by, set_at) "
                + "VALUES ('EXPIRY_WARNING', 30, '2026-01-01', 'ACTIVE', ?, '2026-01-01')", owner.getId());

        // Central: 10 brakes at 100,000 (5 expire 2026-11-01, i.e. within 30 days), 3 chains. Branch: 2 brakes.
        receive(central, brake, 5, 100_000, LocalDate.of(2026, 11, 1));
        receive(central, brake, 5, 100_000, null);
        receive(central, chain, 3, 50_000, LocalDate.of(2026, 10, 1));
        receive(branch, brake, 2, 100_000, null);
        jdbc.update("UPDATE stock_balance SET low_stock_threshold = 5 WHERE store_id = ? AND product_id = ?", branch, brake);
    }

    @Test
    void ownerSeesEveryWarehouseWithValueAndWarnings() throws Exception {
        mvc.perform(get("/api/v1/inventory/stock").header(HttpHeaders.AUTHORIZATION, signIn("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items[0].sku").value("MA-PHANH"))
                .andExpect(jsonPath("$.items[0].availableQty").value(10))
                .andExpect(jsonPath("$.items[0].nearExpiryQty").value(5))
                .andExpect(jsonPath("$.items[0].stockValue").value(1_000_000))
                .andExpect(jsonPath("$.items[1].sku").value("XICH-428"))
                .andExpect(jsonPath("$.items[1].expiredQty").value(3))
                .andExpect(jsonPath("$.items[2].storeName").value("Long Biên"))
                .andExpect(jsonPath("$.items[2].lowStock").value(true));
    }

    @Test
    void branchManagerSeesOnlyTheirWarehouseAndNoValues() throws Exception {
        String token = signIn("bm");
        mvc.perform(get("/api/v1/inventory/stock").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].storeName").value("Long Biên"))
                .andExpect(jsonPath("$.items[0].stockValue").value(nullValue()));
        mvc.perform(get("/api/v1/inventory/stock?storeId={id}", central).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        mvc.perform(get("/api/v1/inventory/stock").header(HttpHeaders.AUTHORIZATION, signIn("staff")))
                .andExpect(status().isForbidden());
    }

    @Test
    void filtersBySearchTextAndLowStock() throws Exception {
        String token = signIn("owner");
        mvc.perform(get("/api/v1/inventory/stock?q=xích").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.items[*].sku", contains("XICH-428")));
        mvc.perform(get("/api/v1/inventory/stock?lowStockOnly=true").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.items[*].storeName", contains("Long Biên")));
    }

    @Test
    void stockDetailSplitsByLotAndHidesPurchasePriceFromManagers() throws Exception {
        mvc.perform(get("/api/v1/inventory/stock/{s}/{p}", central, brake).header(HttpHeaders.AUTHORIZATION, signIn("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lots[*].expiryState", contains("NEAR_EXPIRY", "NO_EXPIRY")))
                .andExpect(jsonPath("$.lots[0].supplierName").value("Honda"))
                .andExpect(jsonPath("$.lots[0].purchaseUnitPrice").value(100_000))
                .andExpect(jsonPath("$.movingAvgCost").value(100_000.0))
                .andExpect(jsonPath("$.expiryWarningDays").value(30));
        mvc.perform(get("/api/v1/inventory/stock/{s}/{p}", central, brake).header(HttpHeaders.AUTHORIZATION, signIn("bmkt")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lots[0].purchaseUnitPrice").value(nullValue()))
                .andExpect(jsonPath("$.movingAvgCost").value(nullValue()));
        mvc.perform(get("/api/v1/inventory/stock/{s}/{p}", branch, chain).header(HttpHeaders.AUTHORIZATION, signIn("bm")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG89"));
    }

    @Test
    void availabilityShowsSellableStockForTransferPlanning() throws Exception {
        mvc.perform(get("/api/v1/inventory/availability?productIds={a},{b}", brake, chain)
                        .header(HttpHeaders.AUTHORIZATION, signIn("bmkt")))
                .andExpect(status().isOk())
                // Expired chains are not sellable, so no row for them.
                .andExpect(jsonPath("$[*].storeName", contains("Kho tổng", "Long Biên")))
                .andExpect(jsonPath("$[*].sellableQty", contains(10, 2)))
                .andExpect(jsonPath("$[0].central").value(true));
        mvc.perform(get("/api/v1/inventory/availability?productIds={a}", brake).header(HttpHeaders.AUTHORIZATION, signIn("bm")))
                .andExpect(status().isForbidden());
    }

    @Test
    void movementHistoryTracesSourceDocuments() throws Exception {
        long sale = jdbc.queryForObject("INSERT INTO sales_order (store_id, price_type, created_by) VALUES (?, 'RETAIL', ?) "
                + "RETURNING id", Long.class, branch, owner.getId());
        clock.advance(java.time.Duration.ofHours(1));
        tx.execute(s -> posting.takeOut(new OutPosting(branch, brake, StockStatus.AVAILABLE, 1, null,
                MovementType.SALE_ISSUE, SourceRef.salesOrder(sale), owner.getId(), null)));

        mvc.perform(get("/api/v1/inventory/movements?productId={p}", brake).header(HttpHeaders.AUTHORIZATION, signIn("bm")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].movementType", contains("SALE_ISSUE", "RECEIPT_CONFIRMED")))
                .andExpect(jsonPath("$.items[0].sourceType").value("SALES_ORDER"))
                .andExpect(jsonPath("$.items[0].sourceId").value(sale))
                .andExpect(jsonPath("$.items[0].quantityChange").value(-1))
                .andExpect(jsonPath("$.items[0].performedByName").value("Test owner"))
                .andExpect(jsonPath("$.items[0].value").value(nullValue()));
        mvc.perform(get("/api/v1/inventory/movements?movementType=SALE_ISSUE").header(HttpHeaders.AUTHORIZATION, signIn("owner")))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].value").value(-100_000));
        mvc.perform(get("/api/v1/inventory/movements?from=2026-10-14").header(HttpHeaders.AUTHORIZATION, signIn("owner")))
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void managersSetLocationAndThresholdOnlyInTheirOwnWarehouse() throws Exception {
        String token = signIn("bm");
        mvc.perform(put("/api/v1/inventory/stock/{s}/{p}/location", branch, brake).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("storageArea", "Kệ A", "binLocation", " A-03 "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storageArea").value("Kệ A"))
                .andExpect(jsonPath("$.binLocation").value("A-03"));
        // A part not stocked yet gets its row, so it can be placed before the first receipt.
        mvc.perform(put("/api/v1/inventory/stock/{s}/{p}/low-stock-threshold", branch, chain)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("lowStockThreshold", 4))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lowStock").value(true));
        Map<String, Object> clear = new HashMap<>();
        clear.put("lowStockThreshold", null);
        mvc.perform(put("/api/v1/inventory/stock/{s}/{p}/low-stock-threshold", branch, chain)
                        .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content(json(clear)))
                .andExpect(jsonPath("$.lowStockThreshold").value(nullValue()));
        mvc.perform(put("/api/v1/inventory/stock/{s}/{p}/low-stock-threshold", branch, chain)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("lowStockThreshold", -1))))
                .andExpect(status().isBadRequest());

        mvc.perform(put("/api/v1/inventory/stock/{s}/{p}/location", central, brake).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("storageArea", "X"))))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/inventory/stock/{s}/{p}/location", central, brake)
                        .header(HttpHeaders.AUTHORIZATION, signIn("owner"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("storageArea", "Kho B"))))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------------------------------------------------

    private long product(String sku, String name, long category) {
        return jdbc.queryForObject("INSERT INTO product (sku, part_name, category_id, base_unit, created_by) "
                + "VALUES (?, ?, ?, 'cái', ?) RETURNING id", Long.class, sku, name, category, owner.getId());
    }

    private void receive(long store, long product, int qty, long price, LocalDate expiry) {
        long grn = jdbc.queryForObject("INSERT INTO goods_receipt_note (receipt_type, purchase_order_id, "
                + "delivery_document_no, arrived_at) VALUES ('SUPPLIER_DELIVERY', ?, ?, now()) RETURNING id", Long.class,
                po, "PX-" + System.nanoTime());
        long line = jdbc.queryForObject("INSERT INTO goods_receipt_note_line (goods_receipt_note_id, product_id, "
                + "document_qty, delivered_qty, passed_qty) VALUES (?, ?, ?, ?, ?) RETURNING id", Long.class,
                grn, product, qty, qty, qty);
        tx.execute(s -> posting.receiveNewLot(new NewLotPosting(store, product, LotSourceType.RECEIPT, line, supplier,
                LocalDate.of(2026, 10, 13), price, null, null, expiry, Map.of(StockStatus.AVAILABLE, qty),
                MovementType.RECEIPT_CONFIRMED, SourceRef.goodsReceiptNote(grn), owner.getId(), null)));
    }
}
