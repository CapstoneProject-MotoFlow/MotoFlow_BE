package fu.hl.g64.motorflow.inventory;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.inventory.dto.NewLotPosting;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-104. */
class ExpiryWarningApiTest extends ApiTestUsers {

    private static final String WARNING = "/api/v1/inventory/expiry-warning";

    @Autowired
    private InventoryPostingService posting;
    @Autowired
    private TransactionTemplate tx;

    private UserAccount owner;
    private LocalDate today;
    private long branch;

    @BeforeEach
    void setUp() {
        resetDatabaseAndClock();
        today = LocalDate.now(clock);
        branch = createStore("Long Biên");
        owner = createUser("owner", Role.OWNER, null);
        createUser("bm", Role.BRANCH_MANAGER, branch);
        createUser("staff", Role.STAFF, branch);
    }

    @Test
    void nothingSetYet() throws Exception {
        warning("owner").andExpect(status().isOk())
                .andExpect(jsonPath("$.inEffect").value(nullValue()))
                .andExpect(jsonPath("$.waiting").value(nullValue()))
                .andExpect(jsonPath("$.history", hasSize(0)));
    }

    @Test
    void settingForTodayAppliesToLotsAtOnce() throws Exception {
        long lot = lotTenDaysFromExpiry();
        stock().andExpect(jsonPath("$.items[0].nearExpiryQty").value(0));

        set("owner", 30, today).andExpect(status().isOk())
                .andExpect(jsonPath("$.inEffect.days").value(30))
                .andExpect(jsonPath("$.inEffect.setBy").value(owner.getId()))
                .andExpect(jsonPath("$.inEffect.setByName").value("Test owner"));

        warning("owner").andExpect(jsonPath("$.inEffect.days").value(30))
                .andExpect(jsonPath("$.waiting").value(nullValue()))
                .andExpect(jsonPath("$.history[*].days", contains(30)));
        mvc.perform(get("/api/v1/inventory/lots?tab=NEAR_EXPIRY").header(HttpHeaders.AUTHORIZATION, signIn("owner")))
                .andExpect(jsonPath("$.page.items[*].lotId", contains((int) lot)));
        stock().andExpect(jsonPath("$.items[0].nearExpiryQty").value(3));
    }

    @Test
    void laterVersionWaitsForItsDay() throws Exception {
        set("owner", 30, today);
        set("owner", 60, today.plusDays(1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.inEffect.days").value(30))
                .andExpect(jsonPath("$.waiting.days").value(60))
                .andExpect(jsonPath("$.history[*].days", contains(60, 30)));

        clock.advance(Duration.ofDays(1));

        warning("owner").andExpect(jsonPath("$.inEffect.days").value(60))
                .andExpect(jsonPath("$.waiting").value(nullValue()))
                .andExpect(jsonPath("$.history[*].days", contains(60, 30)));
    }

    @Test
    void savingTheSameLaterDayAgainLeavesOneWaiting() throws Exception {
        set("owner", 30, today.plusDays(3));
        set("owner", 45, today.plusDays(3)).andExpect(status().isOk())
                .andExpect(jsonPath("$.waiting.days").value(45))
                .andExpect(jsonPath("$.history", hasSize(1)));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM pricing_policy", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_type = 'PRICING_POLICY' "
                + "AND action = 'EXPIRY_WARNING_SET'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT old_data::text FROM audit_log WHERE action = 'EXPIRY_WARNING_SET' "
                + "ORDER BY id DESC LIMIT 1", String.class)).contains("replaced_scheduled");
    }

    @Test
    void historyStopsAtTheTwentyNewest() throws Exception {
        for (int i = 25; i >= 1; i--) {
            jdbc.update("INSERT INTO pricing_policy (policy_type, expiry_warning_days, effective_from, status, set_by, "
                    + "set_at) VALUES ('EXPIRY_WARNING', ?, ?, 'ACTIVE', ?, ?)", i, today.minusDays(i).atStartOfDay(),
                    owner.getId(), today.atStartOfDay());
        }
        warning("owner").andExpect(jsonPath("$.history", hasSize(20)))
                .andExpect(jsonPath("$.history[0].days").value(1))
                .andExpect(jsonPath("$.inEffect.days").value(1));
    }

    @Test
    void badValuesAreRefused() throws Exception {
        set("owner", 0, today).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG02"));
        set("owner", 366, today).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG02"));
        set("owner", 30, today.minusDays(1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG125"));
        send("{\"days\":30}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG02"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pricing_policy", Integer.class)).isZero();
    }

    @Test
    void numberSentAsTheDateIsRefused() throws Exception {
        send("{\"days\":30,\"effectiveDate\":2147483648}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"));
    }

    @Test
    void onlyTheOwnerGetsIn() throws Exception {
        set("bm", 30, today).andExpect(status().isForbidden());
        warning("bm").andExpect(status().isForbidden());
        warning("staff").andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------------------------------------------

    private ResultActions warning(String username) throws Exception {
        return mvc.perform(get(WARNING).header(HttpHeaders.AUTHORIZATION, signIn(username)));
    }

    private ResultActions set(String username, int days, LocalDate from) throws Exception {
        return mvc.perform(put(WARNING).header(HttpHeaders.AUTHORIZATION, signIn(username))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("days", days, "effectiveDate", from.toString()))));
    }

    private ResultActions send(String body) throws Exception {
        return mvc.perform(put(WARNING).header(HttpHeaders.AUTHORIZATION, signIn("owner"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions stock() throws Exception {
        return mvc.perform(get("/api/v1/inventory/stock").header(HttpHeaders.AUTHORIZATION, signIn("owner")));
    }

    /** 3 available units at the branch in a lot from a new receipt; returns the lot. */
    private long lotTenDaysFromExpiry() {
        long category = jdbc.queryForObject("INSERT INTO part_category (category_name, vehicle_type) "
                + "VALUES ('Phanh', 'MOTORCYCLE') RETURNING id", Long.class);
        long product = jdbc.queryForObject("INSERT INTO product (sku, part_name, category_id, base_unit, created_by) "
                + "VALUES ('MA-PHANH', 'Má phanh', ?, 'cái', ?) RETURNING id", Long.class, category, owner.getId());
        long supplier = jdbc.queryForObject("INSERT INTO supplier (supplier_code, supplier_name) "
                + "VALUES ('NCC001', 'Honda') RETURNING id", Long.class);
        long po = jdbc.queryForObject("INSERT INTO purchase_order (supplier_id, store_id, created_by) VALUES (?, ?, ?) "
                + "RETURNING id", Long.class, supplier, branch, owner.getId());
        long grn = jdbc.queryForObject("INSERT INTO goods_receipt_note (receipt_type, purchase_order_id, "
                + "delivery_document_no, arrived_at) VALUES ('SUPPLIER_DELIVERY', ?, 'PX-1', now()) RETURNING id",
                Long.class, po);
        long line = jdbc.queryForObject("INSERT INTO goods_receipt_note_line (goods_receipt_note_id, product_id, "
                + "document_qty, delivered_qty, passed_qty) VALUES (?, ?, 3, 3, 3) RETURNING id", Long.class, grn, product);
        tx.execute(s -> posting.receiveNewLot(new NewLotPosting(branch, product, LotSourceType.RECEIPT, line, supplier,
                today, 100_000, null, null, today.plusDays(10), Map.of(StockStatus.AVAILABLE, 3),
                MovementType.RECEIPT_CONFIRMED, SourceRef.goodsReceiptNote(grn), owner.getId(), null)));
        return jdbc.queryForObject("SELECT max(id) FROM lot", Long.class);
    }
}
