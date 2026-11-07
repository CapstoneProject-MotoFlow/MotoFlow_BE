package fu.hl.g64.motorflow.transfer;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.support.ApiTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-72, UC-73. */
class TransferOrderViewApiTest extends ApiTestUsers {

    private static final String ORDERS = "/api/v1/transfer-orders";

    private UserAccount owner;
    private UserAccount branchManager;
    private UserAccount sourceStaff;
    private UserAccount branchStaff;
    private long central;
    private long source;
    private long branch;
    private long brake;
    private long chain;
    private long dispatched;
    private long waiting;
    private long closed;
    private long requested;
    private long request;

    @BeforeEach
    void setUp() {
        resetDatabaseAndClock();
        central = createStore("Kho tổng");
        jdbc.update("UPDATE store SET is_central = TRUE WHERE id = ?", central);
        source = createStore("Cầu Giấy");
        branch = createStore("Long Biên");
        long other = createStore("Hà Đông");
        owner = createUser("owner", Role.OWNER, null);
        createUser("bmkt", Role.BRANCH_MANAGER, central);
        createUser("bmcg", Role.BRANCH_MANAGER, source);
        branchManager = createUser("bm", Role.BRANCH_MANAGER, branch);
        createUser("bmhd", Role.BRANCH_MANAGER, other);
        sourceStaff = createUser("staffcg", Role.STAFF, source);
        branchStaff = createUser("staff", Role.STAFF, branch);
        createUser("idle", Role.STAFF, branch);
        long category = jdbc.queryForObject("INSERT INTO part_category (category_name, vehicle_type) "
                + "VALUES ('Phanh', 'MOTORCYCLE') RETURNING id", Long.class);
        brake = product("MA-PHANH", "Má phanh trước", category);
        chain = product("XICH-XE", "Xích tải", category);
        request = jdbc.queryForObject("INSERT INTO replenishment_request (requesting_store_id, created_by) "
                + "VALUES (?, ?) RETURNING id", Long.class, source, owner.getId());

        dispatched = order(null, source, branch, "RECONCILING", "2026-11-01 08:00");
        line(dispatched, brake, 10, 10);
        line(dispatched, chain, 3, 3);
        waiting = order(null, central, other, "AWAITING_DISPATCH", "2026-11-03 08:00");
        line(waiting, brake, 2, null);
        closed = order(null, central, branch, "CLOSED", "2026-11-05 08:00");
        jdbc.update("UPDATE transfer_order SET closed_at = '2026-11-06 08:00' WHERE id = ?", closed);
        requested = order(request, central, source, "AWAITING_DISPATCH", "2026-11-04 08:00");
        line(requested, chain, 1, null);
    }

    @Test
    void eachRoleSeesItsOrdersUnclosedFirst() throws Exception {
        task("DISPATCH_PREPARATION", "transfer_order_id", requested, sourceStaff, "ASSIGNED");
        task("DISPATCH_PREPARATION", "transfer_order_id", waiting, sourceStaff, "CANCELLED");
        long receipt = receipt(dispatched, "TRANSFER_IN", "AWAITING_INSPECTION");
        task("INSPECTION", "goods_receipt_note_id", receipt, branchStaff, "ASSIGNED");

        for (String coordinator : new String[] {"owner", "bmkt"}) {
            list(coordinator).andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems").value(4))
                    .andExpect(jsonPath("$.items[*].id").value(contains((int) requested, (int) waiting,
                            (int) dispatched, (int) closed)));
        }
        list("bm").andExpect(jsonPath("$.items[*].id").value(contains((int) dispatched, (int) closed)));
        list("bmcg").andExpect(jsonPath("$.items[*].id").value(contains((int) requested, (int) dispatched)));
        list("staffcg").andExpect(jsonPath("$.items[*].id").value(contains((int) requested)));
        list("staff").andExpect(jsonPath("$.items[*].id").value(contains((int) dispatched)));
        list("idle").andExpect(jsonPath("$.items").value(empty()));

        list("owner").andExpect(jsonPath("$.items[2].sourceStoreName").value("Cầu Giấy"))
                .andExpect(jsonPath("$.items[2].destStoreName").value("Long Biên"))
                .andExpect(jsonPath("$.items[2].lineCount").value(2))
                .andExpect(jsonPath("$.items[2].totalPlannedQty").value(13))
                .andExpect(jsonPath("$.items[2].createdByName").isNotEmpty())
                .andExpect(jsonPath("$.items[3].closedAt").value("2026-11-06T08:00:00"));
    }

    @Test
    void filters() throws Exception {
        list("owner", "status", "CLOSED").andExpect(jsonPath("$.items[*].id").value(contains((int) closed)));
        list("owner", "sourceStoreId", String.valueOf(central))
                .andExpect(jsonPath("$.items[*].id").value(contains((int) requested, (int) waiting, (int) closed)));
        list("owner", "destStoreId", String.valueOf(branch))
                .andExpect(jsonPath("$.items[*].id").value(contains((int) dispatched, (int) closed)));
        list("owner", "replenishmentRequestId", String.valueOf(request))
                .andExpect(jsonPath("$.items[*].id").value(contains((int) requested)));
        list("owner", "from", "2026-11-03", "to", "2026-11-04")
                .andExpect(jsonPath("$.items[*].id").value(contains((int) requested, (int) waiting)));
        list("owner", "size", "1", "page", "1").andExpect(jsonPath("$.items[*].id").value(contains((int) waiting)));
        list("owner", "status", "LOST").andExpect(status().isBadRequest());
    }

    @Test
    void detailReconcilesReceiptsReturnsAndAppliedLoss() throws Exception {
        long arrived = receipt(dispatched, "TRANSFER_IN", "CONFIRMED");
        receiptLine(arrived, brake, 6, 1);
        receiptLine(arrived, chain, 4, 0);
        receiptLine(receipt(dispatched, "RETURN_TO_SOURCE", "CONFIRMED"), brake, 1, 0);
        receiptLine(receipt(dispatched, "TRANSFER_IN", "AWAITING_INSPECTION"), brake, 2, 0);
        long shortage = jdbc.queryForObject("INSERT INTO discrepancy_case (source_type, transfer_order_id, product_id, "
                + "short_qty, opened_at) VALUES ('TRANSFER', ?, ?, 3, now()) RETURNING id", Long.class, dispatched, brake);
        loss(shortage, 1, "APPLIED");
        loss(shortage, 5, "APPROVED");
        long period = jdbc.queryForObject("INSERT INTO inventory_period (period_month, from_date, to_date) "
                + "VALUES ('2026-11-01', '2026-11-01', '2026-11-30') RETURNING id", Long.class);
        dispatch(period, lot(brake, "A1", "2027-01-01"), brake, 6);
        dispatch(period, lot(brake, "B2", null), brake, 4);

        detail("owner", dispatched).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECONCILING"))
                .andExpect(jsonPath("$.sourceStoreName").value("Cầu Giấy"))
                .andExpect(jsonPath("$.createdBy.id").value(owner.getId()))
                .andExpect(jsonPath("$.dispatchedBy").value(nullValue()))
                .andExpect(jsonPath("$.lines[0].sku").value("MA-PHANH"))
                .andExpect(jsonPath("$.lines[0].dispatchedQty").value(10))
                .andExpect(jsonPath("$.lines[0].receivedQty").value(7))
                .andExpect(jsonPath("$.lines[0].returnedQty").value(1))
                .andExpect(jsonPath("$.lines[0].lossQty").value(1))
                .andExpect(jsonPath("$.lines[0].extraReceivedQty").value(0))
                .andExpect(jsonPath("$.lines[0].unresolvedQty").value(1))
                .andExpect(jsonPath("$.lines[1].receivedQty").value(3))
                .andExpect(jsonPath("$.lines[1].extraReceivedQty").value(1))
                .andExpect(jsonPath("$.lines[1].unresolvedQty").value(0))
                .andExpect(jsonPath("$.lots[*].manufacturerLotNo").value(contains("A1", "B2")))
                .andExpect(jsonPath("$.lots[0].quantity").value(6))
                .andExpect(jsonPath("$.lots[0].unitCost").value(1000))
                .andExpect(jsonPath("$.receipts.length()").value(3))
                .andExpect(jsonPath("$.discrepancies[0].shortQty").value(3))
                .andExpect(jsonPath("$.discrepancies[0].events.length()").value(2));

        detail("bmkt", dispatched).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].unresolvedQty").value(1))
                .andExpect(jsonPath("$.lots[0].unitCost").value(nullValue()));
    }

    @Test
    void staffSeeNoReconciliation() throws Exception {
        task("INSPECTION", "goods_receipt_note_id", receipt(dispatched, "TRANSFER_IN", "AWAITING_INSPECTION"),
                branchStaff, "ASSIGNED");
        jdbc.update("INSERT INTO discrepancy_case (source_type, transfer_order_id, product_id, short_qty, opened_at) "
                + "VALUES ('TRANSFER', ?, ?, 3, now())", dispatched, brake);

        detail("staff", dispatched).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].plannedQty").value(10))
                .andExpect(jsonPath("$.lines[0].dispatchedQty").value(10))
                .andExpect(jsonPath("$.lines[0].receivedQty").value(nullValue()))
                .andExpect(jsonPath("$.lines[0].returnedQty").value(nullValue()))
                .andExpect(jsonPath("$.lines[0].lossQty").value(nullValue()))
                .andExpect(jsonPath("$.lines[0].unresolvedQty").value(nullValue()))
                .andExpect(jsonPath("$.discrepancies").value(empty()));
    }

    @Test
    void detailScope() throws Exception {
        detail("bm", dispatched).andExpect(status().isOk());
        detail("bmcg", dispatched).andExpect(status().isOk());
        detail("bmhd", dispatched).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        detail("idle", dispatched).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        detail("owner", 9999).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG175"));
    }

    // ------------------------------------------------------------------------------------------------------------

    private ResultActions list(String username, String... params) throws Exception {
        var request = get(ORDERS).header(HttpHeaders.AUTHORIZATION, signIn(username));
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    private ResultActions detail(String username, long id) throws Exception {
        return mvc.perform(get(ORDERS + "/" + id).header(HttpHeaders.AUTHORIZATION, signIn(username)));
    }

    private long order(Long requestId, long from, long to, String status, String createdAt) {
        return jdbc.queryForObject("INSERT INTO transfer_order (replenishment_request_id, source_store_id, dest_store_id, "
                + "status, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?::timestamp) RETURNING id", Long.class,
                requestId, from, to, status, owner.getId(), createdAt);
    }

    private void line(long orderId, long productId, int planned, Integer dispatchedQty) {
        jdbc.update("INSERT INTO transfer_order_line (transfer_order_id, product_id, planned_qty, prepared_qty, "
                + "dispatched_qty) VALUES (?, ?, ?, ?, ?)", orderId, productId, planned, dispatchedQty, dispatchedQty);
    }

    private void task(String type, String documentColumn, long documentId, UserAccount staff, String status) {
        jdbc.update("INSERT INTO warehouse_task (task_type, " + documentColumn + ", assigned_to, assigned_by, "
                + "assigned_at, status) VALUES (?, ?, ?, ?, now(), ?)", type, documentId, staff.getId(),
                branchManager.getId(), status);
    }

    private long receipt(long orderId, String type, String status) {
        boolean confirmed = status.equals("CONFIRMED");
        return jdbc.queryForObject("INSERT INTO goods_receipt_note (receipt_type, transfer_order_id, arrived_at, status, "
                + "recorded_by, submitted_at, confirmed_by, confirmed_at) VALUES (?, ?, now(), ?, ?, ?, ?, ?) "
                + "RETURNING id", Long.class, type, orderId, status, confirmed ? owner.getId() : null,
                confirmed ? java.sql.Timestamp.valueOf("2026-11-02 08:00:00") : null,
                confirmed ? branchManager.getId() : null,
                confirmed ? java.sql.Timestamp.valueOf("2026-11-02 09:00:00") : null);
    }

    private void receiptLine(long receiptId, long productId, int passed, int damaged) {
        jdbc.update("INSERT INTO goods_receipt_note_line (goods_receipt_note_id, product_id, document_qty, delivered_qty, "
                + "passed_qty, damaged_qty) VALUES (?, ?, ?, ?, ?, ?)", receiptId, productId, passed + damaged,
                passed + damaged, passed, damaged);
    }

    private void loss(long caseId, int quantity, String adjustmentStatus) {
        long adjustment = jdbc.queryForObject("INSERT INTO adjustment_request (adjustment_type, store_id, "
                + "transfer_order_id, reason, status, requested_by) VALUES ('STOCK_LOSS', ?, ?, 'Mất trên đường', ?, ?) "
                + "RETURNING id", Long.class, branch, dispatched, adjustmentStatus, owner.getId());
        jdbc.update("INSERT INTO discrepancy_resolution_event (discrepancy_case_id, resolution_type, quantity, "
                + "adjustment_request_id, recorded_by, occurred_at) VALUES (?, 'APPROVED_LOSS', ?, ?, ?, now())",
                caseId, quantity, adjustment, owner.getId());
    }

    private long lot(long productId, String lotNo, String expiry) {
        long count = jdbc.queryForObject("INSERT INTO stock_count (store_id, count_type, count_date, scope_type, "
                + "created_by) VALUES (?, 'OPENING', '2026-10-01', 'WHOLE', ?) RETURNING id", Long.class, source,
                owner.getId());
        return jdbc.queryForObject("INSERT INTO lot (product_id, source_type, stock_count_id, store_id, received_date, "
                + "purchase_unit_price, manufacturer_lot_no, expiry_date) VALUES (?, 'OPENING', ?, ?, '2026-10-01', "
                + "1000, ?, ?::date) RETURNING id", Long.class, productId, count, source, lotNo, expiry);
    }

    /** The source minus and the destination plus that a dispatch writes. */
    private void dispatch(long period, long lotId, long productId, int quantity) {
        String insert = "INSERT INTO stock_movement (store_id, product_id, lot_id, inventory_period_id, stock_status, "
                + "quantity_change, unit_cost, value, movement_type, transfer_order_id, performed_by, occurred_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, 1000, ?, 'TRANSFER_DISPATCH', ?, ?, now())";
        jdbc.update(insert, source, productId, lotId, period, "AVAILABLE", -quantity, -1000L * quantity, dispatched,
                owner.getId());
        jdbc.update(insert, branch, productId, lotId, period, "IN_TRANSIT", quantity, 1000L * quantity, dispatched,
                owner.getId());
    }

    private long product(String sku, String name, long category) {
        return jdbc.queryForObject("INSERT INTO product (sku, part_name, category_id, base_unit, created_by) "
                + "VALUES (?, ?, ?, 'cái', ?) RETURNING id", Long.class, sku, name, category, owner.getId());
    }
}
