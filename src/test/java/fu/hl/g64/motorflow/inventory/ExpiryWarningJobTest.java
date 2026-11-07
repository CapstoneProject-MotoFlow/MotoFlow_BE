package fu.hl.g64.motorflow.inventory;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.inventory.dto.LotQuantity;
import fu.hl.g64.motorflow.inventory.dto.NewLotPosting;
import fu.hl.g64.motorflow.inventory.dto.OutPosting;
import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.LotSourceType;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import fu.hl.g64.motorflow.inventory.job.ExpiryWarningJob;
import fu.hl.g64.motorflow.inventory.service.InventoryPostingService;
import fu.hl.g64.motorflow.support.ApiTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** JOB-03, BR-76. */
class ExpiryWarningJobTest extends ApiTestUsers {

    @Autowired
    private ExpiryWarningJob job;
    @Autowired
    private InventoryPostingService posting;
    @Autowired
    private TransactionTemplate tx;

    private UserAccount owner;
    private UserAccount bm;
    private UserAccount bmkt;
    private LocalDate today;
    private long central;
    private long branch;
    private long oil;
    private long honda;
    private long po;
    private long expired;
    private long near;
    private long far;
    private long noExpiry;

    @BeforeEach
    void setUp() {
        resetDatabaseAndClock();
        today = LocalDate.now(clock);
        central = createStore("Kho tổng");
        jdbc.update("UPDATE store SET is_central = TRUE WHERE id = ?", central);
        branch = createStore("Long Biên");
        owner = createUser("owner", Role.OWNER, null);
        bmkt = createUser("bmkt", Role.BRANCH_MANAGER, central);
        bm = createUser("bm", Role.BRANCH_MANAGER, branch);
        createUser("staff", Role.STAFF, branch);
        long category = jdbc.queryForObject("INSERT INTO part_category (category_name, vehicle_type) "
                + "VALUES ('Dầu nhớt', 'MOTORCYCLE') RETURNING id", Long.class);
        oil = jdbc.queryForObject("INSERT INTO product (sku, part_name, category_id, base_unit, created_by) "
                + "VALUES ('NHOT-10W40', 'Nhớt 10W40', ?, 'chai', ?) RETURNING id", Long.class, category, owner.getId());
        honda = jdbc.queryForObject("INSERT INTO supplier (supplier_code, supplier_name) VALUES ('NCC001', 'Honda') "
                + "RETURNING id", Long.class);
        po = jdbc.queryForObject("INSERT INTO purchase_order (supplier_id, store_id, created_by) VALUES (?, ?, ?) "
                + "RETURNING id", Long.class, honda, central, owner.getId());
        jdbc.update("INSERT INTO pricing_policy (policy_type, expiry_warning_days, effective_from, status, set_by, set_at) "
                + "VALUES ('EXPIRY_WARNING', 30, '2026-01-01', 'ACTIVE', ?, '2026-01-01')", owner.getId());

        expired = receive(branch, today.minusDays(1));
        near = receive(branch, today.plusDays(10));
        far = receive(branch, today.plusDays(100));
        noExpiry = receive(branch, null);
    }

    @Test
    void lotPastItsExpiryIsMarkedAndTheManagerTold() {
        ExpiryWarningJob.Result result = job.run();

        assertThat(result.markedExpired()).isEqualTo(1);
        assertThat(lotStatus(expired)).isEqualTo("EXPIRED");
        assertThat(lotStatus(near)).isEqualTo("IN_STOCK");
        assertThat(lotStatus(far)).isEqualTo("IN_STOCK");
        assertThat(lotStatus(noExpiry)).isEqualTo("IN_STOCK");
        assertThat(sent(bm, "LOT_EXPIRED")).containsExactly(expired);
        assertThat(message(bm, "LOT_EXPIRED")).isEqualTo("Lô #%d (NHOT-10W40) đã hết hạn ngày %s, còn 3 tại Long Biên, "
                .formatted(expired, date(today.minusDays(1))) + "không được bán hoặc chuyển kho.");
        // The stock stays where it was: nothing is written off.
        assertThat(jdbc.queryForObject("SELECT available_qty FROM stock_balance WHERE store_id = ?", Integer.class,
                branch)).isEqualTo(12);
    }

    @Test
    void lotNearExpiryIsToldToTheManagerOfItsWarehouse() {
        job.run();

        assertThat(sent(bm, "NEAR_EXPIRY")).containsExactly(near);
        assertThat(message(bm, "NEAR_EXPIRY")).isEqualTo("Lô #%d (NHOT-10W40) sắp hết hạn ngày %s, còn 3 tại Long Biên."
                .formatted(near, date(today.plusDays(10))));
        assertThat(sent(bmkt, "NEAR_EXPIRY")).isEmpty();
        assertThat(sent(owner, "NEAR_EXPIRY")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT ref_type FROM notification WHERE recipient_id = ? "
                + "AND notification_type = 'NEAR_EXPIRY'", String.class, bm.getId())).isEqualTo("LOT");
    }

    @Test
    void runningAgainTheSameDayDoesNothing() {
        job.run();

        assertThat(job.run()).isEqualTo(new ExpiryWarningJob.Result(0, 0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification", Integer.class)).isEqualTo(2);
    }

    @Test
    void lotSplitBetweenWarehousesIsToldToBothManagers() {
        jdbc.update("UPDATE lot_balance SET quantity = 2 WHERE lot_id = ? AND store_id = ?", near, branch);
        jdbc.update("INSERT INTO lot_balance (lot_id, store_id, stock_status, quantity) VALUES (?, ?, 'AVAILABLE', 1)",
                near, central);

        job.run();

        assertThat(sent(bm, "NEAR_EXPIRY")).containsExactly(near);
        assertThat(sent(bmkt, "NEAR_EXPIRY")).containsExactly(near);
        assertThat(message(bmkt, "NEAR_EXPIRY")).endsWith("còn 1 tại Kho tổng.");
    }

    @Test
    void withoutWarningDaysLotsAreStillMarkedButNoNearExpiryIsSent() {
        jdbc.update("DELETE FROM pricing_policy");

        assertThat(job.run().markedExpired()).isEqualTo(1);
        assertThat(lotStatus(expired)).isEqualTo("EXPIRED");
        assertThat(sent(bm, "LOT_EXPIRED")).containsExactly(expired);
        assertThat(sent(bm, "NEAR_EXPIRY")).isEmpty();
    }

    @Test
    void markedLotIsStillBlockedFromSaleAndListedAsExpired() throws Exception {
        job.run();

        long sale = jdbc.queryForObject("INSERT INTO sales_order (store_id, price_type, created_by) "
                + "VALUES (?, 'RETAIL', ?) RETURNING id", Long.class, branch, owner.getId());
        assertThatThrownBy(() -> tx.execute(s -> posting.takeOut(new OutPosting(branch, oil, StockStatus.AVAILABLE, 1,
                List.of(new LotQuantity(expired, 1)), MovementType.SALE_ISSUE, SourceRef.salesOrder(sale),
                owner.getId(), null))))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("MSG86"));
        mvc.perform(get("/api/v1/inventory/lots?tab=EXPIRED").header(HttpHeaders.AUTHORIZATION, signIn("bm")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.items[*].lotId", hasItem((int) expired)));
    }

    /** 3 units from a new goods receipt note line; returns the lot. */
    private long receive(long store, LocalDate expiry) {
        long grn = jdbc.queryForObject("INSERT INTO goods_receipt_note (receipt_type, purchase_order_id, "
                + "delivery_document_no, arrived_at) VALUES ('SUPPLIER_DELIVERY', ?, ?, now()) RETURNING id", Long.class,
                po, "PX-" + System.nanoTime());
        long line = jdbc.queryForObject("INSERT INTO goods_receipt_note_line (goods_receipt_note_id, product_id, "
                + "document_qty, delivered_qty, passed_qty) VALUES (?, ?, 3, 3, 3) RETURNING id", Long.class, grn, oil);
        tx.execute(s -> posting.receiveNewLot(new NewLotPosting(store, oil, LotSourceType.RECEIPT, line, honda,
                today.minusDays(30), 100_000, null, null, expiry, Map.of(StockStatus.AVAILABLE, 3),
                MovementType.RECEIPT_CONFIRMED, SourceRef.goodsReceiptNote(grn), owner.getId(), null)));
        return jdbc.queryForObject("SELECT max(id) FROM lot", Long.class);
    }

    private String lotStatus(long lot) {
        return jdbc.queryForObject("SELECT status FROM lot WHERE id = ?", String.class, lot);
    }

    private List<Long> sent(UserAccount user, String type) {
        return jdbc.queryForList("SELECT ref_id FROM notification WHERE recipient_id = ? AND notification_type = ? "
                + "ORDER BY id", Long.class, user.getId(), type);
    }

    private String message(UserAccount user, String type) {
        return jdbc.queryForObject("SELECT message FROM notification WHERE recipient_id = ? AND notification_type = ?",
                String.class, user.getId(), type);
    }

    private static String date(LocalDate day) {
        return day.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }
}
