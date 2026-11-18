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
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-88. */
class InventoryOperationsReportApiTest extends ApiTestUsers {

    private static final String URL = "/api/v1/reports/inventory-operations";

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
    void theOwnerSeesInAndOutBySourceWithValuesAndTheSourcesAddUp() throws Exception {
        at("2026-09-20T10:00:00");
        receive(longBien, chain, 4);
        at("2026-10-01T10:00:00");
        receive(longBien, chain, 10);
        at("2026-10-02T10:00:00");
        sell(longBien, chain, 3);
        returnToSupplier(longBien, chain, 2);

        call("owner", "?month=2026-10").andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-10"))
                .andExpect(jsonPath("$.periodStatus").value("OPEN"))
                .andExpect(jsonPath("$.provisional").value(true))
                .andExpect(jsonPath("$.withValues").value(true))
                .andExpect(jsonPath("$.page.totalItems").value(1))
                .andExpect(jsonPath("$.page.items[0].openingQty").value(4))
                .andExpect(jsonPath("$.page.items[0].openingValue").value(400_000))
                .andExpect(jsonPath("$.page.items[0].inQty").value(10))
                .andExpect(jsonPath("$.page.items[0].outQty").value(5))
                .andExpect(jsonPath("$.page.items[0].outValue").value(500_000))
                .andExpect(jsonPath("$.page.items[0].closingQty").value(9))
                .andExpect(jsonPath("$.page.items[0].closingValue").value(900_000))
                .andExpect(jsonPath("$.page.items[0].bySource[*].source",
                        contains("PURCHASE_RECEIPT", "SUPPLIER_RETURN", "SALE")))
                .andExpect(jsonPath("$.page.items[0].bySource[*].inQty", contains(10, 0, 0)))
                .andExpect(jsonPath("$.page.items[0].bySource[*].outQty", contains(0, 2, 3)))
                .andExpect(jsonPath("$.page.items[0].bySource[*].outValue", contains(0, 200_000, 300_000)))
                .andExpect(jsonPath("$.totals.inValue").value(1_000_000))
                .andExpect(jsonPath("$.totals.outValue").value(500_000))
                .andExpect(jsonPath("$.totals.bySource[*].source",
                        contains("PURCHASE_RECEIPT", "SUPPLIER_RETURN", "SALE")))
                .andExpect(jsonPath("$.totals.bySource[*].inValue", contains(1_000_000, 0, 0)))
                .andExpect(jsonPath("$.totals.bySource[*].outValue", contains(0, 200_000, 300_000)));
    }

    @Test
    void aMonthWithNoMovementShowsTheStockCarriedIn() throws Exception {
        at("2026-09-20T10:00:00");
        receive(longBien, chain, 4);
        at("2026-11-05T10:00:00");

        call("owner", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-11"))
                .andExpect(jsonPath("$.periodStatus").value(nullValue()))
                .andExpect(jsonPath("$.page.items[0].openingQty").value(4))
                .andExpect(jsonPath("$.page.items[0].inQty").value(0))
                .andExpect(jsonPath("$.page.items[0].closingQty").value(4))
                .andExpect(jsonPath("$.page.items[0].bySource", empty()));
    }

    @Test
    void aBranchManagerSeesOwnWarehouseInQuantitiesOnly() throws Exception {
        at("2026-10-01T10:00:00");
        receive(longBien, chain, 5);
        receive(cauGiay, pads, 2);

        call("bm", "?month=2026-10").andExpect(status().isOk())
                .andExpect(jsonPath("$.withValues").value(false))
                .andExpect(jsonPath("$.totals").value(nullValue()))
                .andExpect(jsonPath("$.page.items[*].storeName", contains("Long Biên")))
                .andExpect(jsonPath("$.page.items[0].inQty").value(5))
                .andExpect(jsonPath("$.page.items[0].inValue").value(nullValue()))
                .andExpect(jsonPath("$.page.items[0].closingValue").value(nullValue()))
                .andExpect(jsonPath("$.page.items[0].bySource[0].inValue").value(nullValue()));
        call("bm", "?month=2026-10&storeId=" + longBien).andExpect(status().isOk());
        call("bm", "?month=2026-10&storeId=" + cauGiay).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        call("staff", "?month=2026-10").andExpect(status().isForbidden());
    }

    @Test
    void theOwnerFiltersByWarehousePartAndCategory() throws Exception {
        at("2026-10-01T10:00:00");
        receive(longBien, chain, 5);
        receive(longBien, pads, 4);
        receive(cauGiay, chain, 2);

        call("owner", "?month=2026-10").andExpect(jsonPath("$.page.totalItems").value(3))
                .andExpect(jsonPath("$.page.items[*].storeName", contains("Cầu Giấy", "Long Biên", "Long Biên")))
                .andExpect(jsonPath("$.totals.inValue").value(1_100_000));
        call("owner", "?month=2026-10&storeId=" + cauGiay).andExpect(jsonPath("$.page.totalItems").value(1));
        call("owner", "?month=2026-10&q=má PHANH").andExpect(jsonPath("$.page.items[*].sku", contains("BBB-1")));
        call("owner", "?month=2026-10&categoryId=" + brakes)
                .andExpect(jsonPath("$.page.items[*].sku", contains("BBB-1")));
        call("owner", "?month=2026-10&storeId=9999").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG52"));
    }

    private ResultActions call(String username, String query) throws Exception {
        return mvc.perform(get(URL + query).header(HttpHeaders.AUTHORIZATION, signIn(username)));
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

    private void returnToSupplier(long store, long product, int qty) {
        long grn = jdbc.queryForObject("SELECT max(id) FROM goods_receipt_note", Long.class);
        tx.execute(s -> posting.takeOut(new OutPosting(store, product, StockStatus.AVAILABLE, qty, null,
                MovementType.SUPPLIER_RETURN, SourceRef.goodsReceiptNote(grn), owner.getId(), null)));
    }
}
