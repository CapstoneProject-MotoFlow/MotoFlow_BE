package fu.hl.g64.motorflow.operation;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.common.error.BusinessException;
import fu.hl.g64.motorflow.operation.entity.enums.TaskType;
import fu.hl.g64.motorflow.operation.service.WarehouseTaskService;
import fu.hl.g64.motorflow.security.AuthenticatedUser;
import fu.hl.g64.motorflow.support.ApiTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-86 Assign Warehouse Staff, and BR-32 for the features that record a task's result. */
class WarehouseTaskApiTest extends ApiTestUsers {

    private static final String MSG39 = "Staff đã gửi kết quả cho chứng từ này nên không thể đổi người được giao.";

    @Autowired
    private WarehouseTaskService warehouseTasks;
    @Autowired
    private DataSource dataSource;

    private long store;
    private long otherStore;
    private UserAccount manager;
    private UserAccount staff1;
    private UserAccount staff2;
    private UserAccount lockedStaff;
    private UserAccount otherStoreStaff;
    private String token;
    private int suppliers;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabaseAndClock();
        store = createStore("Gia Lâm");
        otherStore = createStore("Long Biên");
        createUser("owner", Role.OWNER, null);
        manager = createUser("bm", Role.BRANCH_MANAGER, store);
        createUser("bm2", Role.BRANCH_MANAGER, otherStore);
        staff2 = createUser("staff2", Role.STAFF, store);
        staff1 = createUser("staff1", Role.STAFF, store);
        lockedStaff = createUser("staff3", Role.STAFF, store);
        jdbc.update("UPDATE user_account SET status = 'LOCKED' WHERE id = ?", lockedStaff.getId());
        otherStoreStaff = createUser("staff4", Role.STAFF, otherStore);
        token = signIn("bm");
    }

    @Test
    void managerGivesAnOrderToAStaffMemberWhoIsNotified() throws Exception {
        long order = order("AWAITING_PICKING");

        assignment(token, TaskType.PICKING, order)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(store))
                .andExpect(jsonPath("$.canAssign").value(true))
                .andExpect(jsonPath("$.blockedCode").doesNotExist())
                .andExpect(jsonPath("$.current").doesNotExist())
                // Active Staff of the store only, by name: not the locked one, nor Staff of another store.
                .andExpect(jsonPath("$.eligibleStaff[*].fullName", contains("Test staff1", "Test staff2")))
                .andExpect(jsonPath("$.eligibleStaff[*].openTasks", contains(0, 0)))
                .andExpect(jsonPath("$.history", hasSize(0)));

        assign(token, TaskType.PICKING, order, staff1.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.staffId").value(staff1.getId()))
                .andExpect(jsonPath("$.current.staffName").value("Test staff1"))
                .andExpect(jsonPath("$.current.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.current.assignedById").value(manager.getId()))
                .andExpect(jsonPath("$.current.assignedByName").value("Test bm"))
                .andExpect(jsonPath("$.current.assignedAt").isNotEmpty())
                .andExpect(jsonPath("$.current.resultSubmittedAt").doesNotExist())
                .andExpect(jsonPath("$.eligibleStaff[*].openTasks", contains(1, 0)))
                .andExpect(jsonPath("$.history", hasSize(1)));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM warehouse_task WHERE task_type = 'PICKING' "
                + "AND sales_order_id = ? AND assigned_to = ? AND status = 'ASSIGNED'", Long.class, order, staff1.getId()))
                .isEqualTo(1);
        assertThat(notificationsOf(staff1)).containsExactly(
                List.of("TASK_ASSIGNED", "Bạn được giao lấy hàng cho đơn bán #" + order + ".", "SALES_ORDER", order));
    }

    @Test
    void givingItToSomeoneElseBeforeTheResultKeepsTheHistoryAndTellsBoth() throws Exception {
        long order = order("AWAITING_PICKING");
        assign(token, TaskType.PICKING, order, staff1.getId()).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(5));

        assign(token, TaskType.PICKING, order, staff2.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.staffId").value(staff2.getId()))
                .andExpect(jsonPath("$.history[*].staffName", contains("Test staff2", "Test staff1")))
                .andExpect(jsonPath("$.history[*].status", contains("ASSIGNED", "CANCELLED")))
                .andExpect(jsonPath("$.eligibleStaff[*].openTasks", contains(0, 1)));

        assertThat(notificationsOf(staff1)).containsExactly(
                List.of("TASK_ASSIGNED", "Bạn được giao lấy hàng cho đơn bán #" + order + ".", "SALES_ORDER", order),
                List.of("TASK_REASSIGNED", "Việc lấy hàng cho đơn bán #" + order + " đã được giao cho người khác.",
                        "SALES_ORDER", order));
        assertThat(notificationsOf(staff2)).containsExactly(
                List.of("TASK_ASSIGNED", "Bạn được giao lấy hàng cho đơn bán #" + order + ".", "SALES_ORDER", order));
    }

    @Test
    void sendingTheCurrentAssigneeAgainChangesNothing() throws Exception {
        long order = order("AWAITING_PICKING");
        assign(token, TaskType.PICKING, order, staff1.getId()).andExpect(status().isOk());

        assign(token, TaskType.PICKING, order, staff1.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.staffId").value(staff1.getId()))
                .andExpect(jsonPath("$.history", hasSize(1)));
        assertThat(notificationsOf(staff1)).hasSize(1);
    }

    @Test
    void onceTheResultIsInTheAssigneeStaysUntilItIsSentBack() throws Exception {
        long receipt = supplierReceipt(store, "AWAITING_INSPECTION");
        assign(token, TaskType.INSPECTION, receipt, staff1.getId()).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(10));
        warehouseTasks.submitResult(principal(staff1), TaskType.INSPECTION, receipt);

        // Refused from the open task alone, before the receipt itself shows the result...
        assign(token, TaskType.INSPECTION, receipt, staff2.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG39"))
                .andExpect(jsonPath("$.message").value(MSG39));
        // ... and once it waits for confirmation.
        jdbc.update("UPDATE goods_receipt_note SET status = 'AWAITING_CONFIRMATION', recorded_by = ?, submitted_at = ? "
                + "WHERE id = ?", staff1.getId(), LocalDateTime.now(clock), receipt);
        assignment(token, TaskType.INSPECTION, receipt)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canAssign").value(false))
                .andExpect(jsonPath("$.blockedCode").value("MSG39"))
                .andExpect(jsonPath("$.blockedMessage").value(MSG39))
                .andExpect(jsonPath("$.current.status").value("RESULT_SUBMITTED"))
                .andExpect(jsonPath("$.current.resultSubmittedAt").isNotEmpty());
        assign(token, TaskType.INSPECTION, receipt, staff2.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG39"));

        // Confirm Goods Receipt Note sends it back for a recount: the recorder is kept, and can be replaced again.
        assertThat(warehouseTasks.returnForRecount(TaskType.INSPECTION, receipt)).isEqualTo(staff1.getId());
        jdbc.update("UPDATE goods_receipt_note SET status = 'AWAITING_RECOUNT' WHERE id = ?", receipt);
        assignment(token, TaskType.INSPECTION, receipt)
                .andExpect(jsonPath("$.canAssign").value(true))
                .andExpect(jsonPath("$.current.staffId").value(staff1.getId()))
                .andExpect(jsonPath("$.current.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.current.resultSubmittedAt").doesNotExist());
        assign(token, TaskType.INSPECTION, receipt, staff2.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.staffId").value(staff2.getId()));
    }

    @Test
    void onlyAnActiveStaffMemberOfTheStoreCanTakeTheTask() throws Exception {
        long order = order("AWAITING_PICKING");

        assign(token, TaskType.PICKING, order, otherStoreStaff.getId())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG41"))
                .andExpect(jsonPath("$.message").value("Nhân viên được chọn không thuộc cửa hàng hoặc đã bị khóa."));
        assign(token, TaskType.PICKING, order, lockedStaff.getId())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG41"));
        // A Branch Manager cannot take the task either, not even their own store's one.
        assign(token, TaskType.PICKING, order, manager.getId())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG122"))
                .andExpect(jsonPath("$.message").value("Chỉ có thể giao việc cho tài khoản Staff."));
        assign(token, TaskType.PICKING, order, 999_999)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG48"));
        mvc.perform(post("/api/v1/warehouse-tasks").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("taskType", "PICKING", "documentId", order))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"))
                .andExpect(jsonPath("$.fieldErrors.staffId").value("Vui lòng chọn Staff."));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM warehouse_task", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification", Long.class)).isZero();
    }

    @Test
    void onlyTheBranchManagerOfTheDocumentsStoreAssigns() throws Exception {
        long order = order("AWAITING_PICKING");

        for (String username : List.of("bm2", "owner", "staff1")) {
            String other = signIn(username);
            assignment(other, TaskType.PICKING, order)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("MSG05"));
            assign(other, TaskType.PICKING, order, staff1.getId())
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("MSG05"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM warehouse_task", Long.class)).isZero();
    }

    @Test
    void finishedOrCancelledDocumentsAreRefused() throws Exception {
        long cancelled = order("CANCELLED");
        long dispatched = transfer(store, otherStore, "IN_TRANSIT");

        assignment(token, TaskType.PICKING, cancelled)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canAssign").value(false))
                .andExpect(jsonPath("$.blockedCode").value("MSG121"))
                .andExpect(jsonPath("$.blockedMessage").value("Chứng từ đã hoàn tất hoặc đã hủy nên không thể giao việc."));
        assign(token, TaskType.PICKING, cancelled, staff1.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG121"));
        assign(token, TaskType.DISPATCH_PREPARATION, dispatched, staff1.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG121"));
    }

    @Test
    void unknownDocumentsAreNotFound() throws Exception {
        long order = order("AWAITING_PICKING");

        assignment(token, TaskType.PICKING, 999_999)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG120"))
                .andExpect(jsonPath("$.message").value("Không tìm thấy chứng từ cần giao việc."));
        // The task type says which kind of document the id is: there is no stock count with the order's id.
        assign(token, TaskType.COUNTING, order, staff1.getId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG120"));
    }

    @Test
    void eachDocumentIsAssignedAtTheStoreThatDoesTheWork() throws Exception {
        String otherManager = signIn("bm2");
        long delivery = supplierReceipt(store, "AWAITING_INSPECTION");
        long incoming = receipt("TRANSFER_IN", null, transfer(otherStore, store, "IN_TRANSIT"), "AWAITING_INSPECTION");
        long sentBack = receipt("RETURN_TO_SOURCE", null, transfer(store, otherStore, "RECONCILING"),
                "AWAITING_INSPECTION");
        long outgoing = transfer(store, otherStore, "AWAITING_DISPATCH");
        long count = stockCount("DRAFT");

        record Case(TaskType type, long id, String refType, String work) {
        }
        List<Case> cases = List.of(
                new Case(TaskType.INSPECTION, delivery, "GOODS_RECEIPT_NOTE", "kiểm nhận hàng cho phiếu nhận hàng"),
                new Case(TaskType.INSPECTION, incoming, "GOODS_RECEIPT_NOTE", "kiểm nhận hàng cho phiếu nhận hàng"),
                new Case(TaskType.INSPECTION, sentBack, "GOODS_RECEIPT_NOTE", "kiểm nhận hàng cho phiếu nhận hàng"),
                new Case(TaskType.DISPATCH_PREPARATION, outgoing, "TRANSFER_ORDER",
                        "chuẩn bị hàng xuất cho phiếu chuyển kho"),
                new Case(TaskType.COUNTING, count, "STOCK_COUNT", "đếm hàng cho phiếu kiểm kê"));
        // Gia Lâm receives the delivery, the incoming transfer and the goods sent back, prepares the outgoing transfer
        // and counts its own stock, so only its manager assigns them, even where Long Biên is the other end.
        for (Case c : cases) {
            assign(otherManager, c.type(), c.id(), otherStoreStaff.getId())
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("MSG05"));
            assign(token, c.type(), c.id(), staff1.getId())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.storeId").value(store))
                    .andExpect(jsonPath("$.current.staffId").value(staff1.getId()));
        }

        assertThat(notificationsOf(staff1)).containsExactlyElementsOf(cases.stream()
                .map(c -> List.<Object>of("TASK_ASSIGNED", "Bạn được giao " + c.work() + " #" + c.id() + ".",
                        c.refType(), c.id()))
                .toList());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM warehouse_task WHERE status = 'ASSIGNED' AND ("
                + "(task_type = 'INSPECTION' AND goods_receipt_note_id IN (?, ?, ?)) "
                + "OR (task_type = 'DISPATCH_PREPARATION' AND transfer_order_id = ?) "
                + "OR (task_type = 'COUNTING' AND stock_count_id = ?))", Long.class,
                delivery, incoming, sentBack, outgoing, count)).isEqualTo(5);
    }

    @Test
    void onlyTheAssigneeRecordsTheResultAndItsTaskFollowsTheDocument() throws Exception {
        long count = stockCount("COUNTING");
        assign(token, TaskType.COUNTING, count, staff1.getId()).andExpect(status().isOk());

        // BR-32: nobody else records, not even another Staff member of the store.
        assertThatThrownBy(() -> warehouseTasks.requireAssignee(principal(staff2), TaskType.COUNTING, count))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("MSG05"));
        warehouseTasks.requireAssignee(principal(staff1), TaskType.COUNTING, count);

        clock.advance(Duration.ofMinutes(10));
        warehouseTasks.submitResult(principal(staff1), TaskType.COUNTING, count);
        assertThat(jdbc.queryForObject("SELECT result_submitted_at FROM warehouse_task WHERE stock_count_id = ?",
                LocalDateTime.class, count)).isEqualTo(LocalDateTime.now(clock));
        // A result that has been sent cannot be changed or sent again.
        assertThatThrownBy(() -> warehouseTasks.requireAssignee(principal(staff1), TaskType.COUNTING, count))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("MSG05"));
        assertThatThrownBy(() -> warehouseTasks.submitResult(principal(staff1), TaskType.COUNTING, count))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("MSG05"));

        // The Branch Manager confirms; while the count waits for the Owner, nobody else can take it.
        warehouseTasks.confirmResult(TaskType.COUNTING, count);
        jdbc.update("UPDATE stock_count SET status = 'AWAITING_APPROVAL' WHERE id = ?", count);
        assertThat(taskStatuses(count)).containsExactly("DONE");
        assign(token, TaskType.COUNTING, count, staff2.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG39"));

        // The Owner asks for a recount (UC-95): the same Staff member counts again, or the manager gives it on.
        assertThat(warehouseTasks.returnForRecount(TaskType.COUNTING, count)).isEqualTo(staff1.getId());
        jdbc.update("UPDATE stock_count SET status = 'COUNTING' WHERE id = ?", count);
        warehouseTasks.requireAssignee(principal(staff1), TaskType.COUNTING, count);
        assign(token, TaskType.COUNTING, count, staff2.getId()).andExpect(status().isOk());
        assertThat(taskStatuses(count)).containsExactly("CANCELLED", "ASSIGNED");

        // Cancelling the count closes its open task; there is then nothing left to send back.
        warehouseTasks.cancelForDocument(TaskType.COUNTING, count);
        assertThat(taskStatuses(count)).containsExactly("CANCELLED", "CANCELLED");
        assertThatThrownBy(() -> warehouseTasks.returnForRecount(TaskType.COUNTING, count))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void whenTwoManagersAssignTheSameDocumentAtOnceTheSecondIsToldToReload() throws Exception {
        long order = order("AWAITING_PICKING");
        UserAccount peer = createUser("bm3", Role.BRANCH_MANAGER, store);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection otherManager = dataSource.getConnection()) {
            // The other manager's assignment is written but not committed yet when ours arrives.
            otherManager.setAutoCommit(false);
            try (PreparedStatement insert = otherManager.prepareStatement("INSERT INTO warehouse_task (task_type, "
                    + "sales_order_id, assigned_to, assigned_by, assigned_at) VALUES ('PICKING', ?, ?, ?, ?)")) {
                insert.setLong(1, order);
                insert.setLong(2, staff2.getId());
                insert.setLong(3, peer.getId());
                insert.setObject(4, LocalDateTime.now(clock));
                insert.executeUpdate();
            }
            Future<MvcResult> ours = executor.submit(() ->
                    assign(token, TaskType.PICKING, order, staff1.getId()).andReturn());
            // Ours waits on the unique index for the other transaction; let that one commit first.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!ours.isDone() && System.nanoTime() < deadline && jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'", Long.class) == 0) {
                Thread.sleep(20);
            }
            otherManager.commit();

            MvcResult result = ours.get(30, TimeUnit.SECONDS);
            String body = result.getResponse().getContentAsString();
            assertThat(result.getResponse().getStatus()).as(body).isEqualTo(409);
            assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo("MSG123");
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForList("SELECT assigned_to FROM warehouse_task WHERE sales_order_id = ?", Long.class,
                order)).containsExactly(staff2.getId());
        assertThat(notificationsOf(staff1)).isEmpty();
    }

    private ResultActions assignment(String bearer, TaskType type, long documentId) throws Exception {
        return mvc.perform(get("/api/v1/warehouse-tasks").header(HttpHeaders.AUTHORIZATION, bearer)
                .param("taskType", type.name()).param("documentId", String.valueOf(documentId)));
    }

    private ResultActions assign(String bearer, TaskType type, long documentId, long staffId) throws Exception {
        return mvc.perform(post("/api/v1/warehouse-tasks").header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("taskType", type, "documentId", documentId, "staffId", staffId))));
    }

    /** [type, message, refType, refId] of the user's notifications, oldest first. */
    private List<List<Object>> notificationsOf(UserAccount user) {
        return jdbc.query("SELECT notification_type, message, ref_type, ref_id FROM notification WHERE recipient_id = ? "
                        + "ORDER BY id", (rs, i) -> List.<Object>of(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4)), user.getId());
    }

    private List<String> taskStatuses(long stockCountId) {
        return jdbc.queryForList("SELECT status FROM warehouse_task WHERE stock_count_id = ? ORDER BY id", String.class,
                stockCountId);
    }

    private static AuthenticatedUser principal(UserAccount user) {
        return new AuthenticatedUser(user.getId(), 0L, user.getUsername(), user.getFullName(), user.getRole(),
                user.getStoreId(), Set.of(), false);
    }

    private long order(String status) {
        return jdbc.queryForObject("INSERT INTO sales_order (store_id, status, price_type, created_by, "
                        + "cancellation_reason) VALUES (?, ?, 'RETAIL', ?, ?) RETURNING id", Long.class, store, status,
                manager.getId(), status.equals("CANCELLED") ? "Khách đổi ý" : null);
    }

    private long supplierReceipt(long storeId, String status) {
        suppliers++;
        Long supplier = jdbc.queryForObject("INSERT INTO supplier (supplier_code, supplier_name) VALUES (?, ?) "
                + "RETURNING id", Long.class, "NCC" + suppliers, "Minh Phát " + suppliers);
        Long purchaseOrder = jdbc.queryForObject("INSERT INTO purchase_order (supplier_id, store_id, status, created_by) "
                + "VALUES (?, ?, 'RECEIVING', ?) RETURNING id", Long.class, supplier, storeId, manager.getId());
        return receipt("SUPPLIER_DELIVERY", purchaseOrder, null, status);
    }

    private long receipt(String type, Long purchaseOrderId, Long transferOrderId, String status) {
        boolean inspected = !status.equals("AWAITING_INSPECTION");
        return jdbc.queryForObject("INSERT INTO goods_receipt_note (receipt_type, purchase_order_id, transfer_order_id, "
                        + "delivery_document_no, arrived_at, status, recorded_by, submitted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id", Long.class, type, purchaseOrderId,
                transferOrderId, purchaseOrderId == null ? null : "PX-" + purchaseOrderId, LocalDateTime.now(clock),
                status, inspected ? staff1.getId() : null, inspected ? LocalDateTime.now(clock) : null);
    }

    private long transfer(long sourceStoreId, long destStoreId, String status) {
        return jdbc.queryForObject("INSERT INTO transfer_order (source_store_id, dest_store_id, status, created_by) "
                + "VALUES (?, ?, ?, ?) RETURNING id", Long.class, sourceStoreId, destStoreId, status, manager.getId());
    }

    private long stockCount(String status) {
        return jdbc.queryForObject("INSERT INTO stock_count (store_id, count_type, count_date, scope_type, status, "
                + "created_by) VALUES (?, 'OPENING', CURRENT_DATE, 'WHOLE', ?, ?) RETURNING id", Long.class, store,
                status, manager.getId());
    }
}
