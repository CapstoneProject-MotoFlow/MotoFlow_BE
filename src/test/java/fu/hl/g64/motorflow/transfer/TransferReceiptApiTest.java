package fu.hl.g64.motorflow.transfer;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.support.QueryCounter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-106, UC-43, UC-44 for a transfer arriving at its destination. */
class TransferReceiptApiTest extends TransferFlowTestData {

    @Autowired
    private QueryCounter queries;

    @Test
    void fullReceiptMovesTheOrdersLotsIntoAvailable() throws Exception {
        long soon = lot(source, brake, 3, 100_000, today.plusDays(10), "SOM");
        long later = lot(source, brake, 10, 120_000, today.plusDays(90), "MUON");
        long order = dispatchedOrder(brake, 5);

        long receipt = arrive("bmlb", order);
        receiptView("nvlb", order, receipt).andExpect(status().isForbidden());
        assign("bmlb", "INSPECTION", receipt, destStaff).andExpect(status().isOk());
        receiptView("nvlb", order, receipt).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].documentQty").value(5));
        result("nvlb", order, receipt, brake, 5, 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_CONFIRMATION"));
        confirm("bmlb", order, receipt).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        assertThat(stock(dest, brake, "available_qty")).isEqualTo(5);
        assertThat(stock(dest, brake, "in_transit_qty")).isZero();
        assertThat(lotStock(soon, dest, "AVAILABLE")).isEqualTo(3);
        assertThat(lotStock(later, dest, "AVAILABLE")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE movement_type = 'TRANSFER_RECEIPT' "
                + "AND goods_receipt_note_id = ?", Long.class, receipt)).isEqualTo(4);
        view("bmkt", "/" + order).andExpect(jsonPath("$.status").value("RECONCILING"))
                .andExpect(jsonPath("$.lines[0].receivedQty").value(5))
                .andExpect(jsonPath("$.lines[0].unresolvedQty").value(0))
                .andExpect(jsonPath("$.discrepancies").isEmpty());
        assertThat(recipients("TRANSFER_RECEIVED")).containsExactly(coordinatorId());
        assertThat(recipients("TRANSFER_DISCREPANCY")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM warehouse_task WHERE goods_receipt_note_id = ? "
                + "AND status <> 'CANCELLED'",
                String.class, receipt)).isEqualTo("DONE");

        confirm("bmlb", order, receipt).andExpect(status().isOk());
        assertThat(stock(dest, brake, "available_qty")).isEqualTo(5);
        arrive(order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG210"));
    }

    @Test
    void shortReceiptOpensACaseAndTheRestStaysInTransit() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = dispatchedOrder(brake, 5);

        long receipt = inspected(order, brake, 3, 0);
        confirm("bmlb", order, receipt).andExpect(status().isOk());

        assertThat(stock(dest, brake, "available_qty")).isEqualTo(3);
        assertThat(stock(dest, brake, "in_transit_qty")).isEqualTo(2);
        view("owner", "/" + order).andExpect(jsonPath("$.lines[0].unresolvedQty").value(2))
                .andExpect(jsonPath("$.discrepancies[0].shortQty").value(2))
                .andExpect(jsonPath("$.discrepancies[0].status").value("OPEN"));
        assertThat(recipients("TRANSFER_DISCREPANCY")).containsExactlyInAnyOrder(owner.getId(), coordinatorId());

        // The rest may still arrive: the next receipt expects only what is missing, and the case is not opened twice.
        long late = arrive("bmlb", order);
        receiptView("bmlb", order, late).andExpect(jsonPath("$.lines[0].documentQty").value(2));
        arrive(order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG211"));
        assign("bmlb", "INSPECTION", late, destStaff).andExpect(status().isOk());
        result("nvlb", order, late, brake, 1, 0).andExpect(status().isOk());
        confirm("bmlb", order, late).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM discrepancy_case", Long.class)).isEqualTo(1);
        view("owner", "/" + order).andExpect(jsonPath("$.lines[0].unresolvedQty").value(1));
    }

    @Test
    void damagedGoodsOfOneLotGoToDamagedWithOneMovementOut() throws Exception {
        long only = lot(source, brake, 3, 100_000, null, "A");
        long order = dispatchedOrder(brake, 3);

        long receipt = inspected(order, brake, 2, 1);
        confirm("bmlb", order, receipt).andExpect(status().isOk());

        assertThat(lotStock(only, dest, "AVAILABLE")).isEqualTo(2);
        assertThat(lotStock(only, dest, "DAMAGED")).isEqualTo(1);
        assertThat(stock(dest, brake, "damaged_qty")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT stock_status || ':' || quantity_change FROM stock_movement WHERE "
                + "goods_receipt_note_id = ? ORDER BY quantity_change", String.class, receipt))
                .containsExactly("IN_TRANSIT:-3", "DAMAGED:1", "AVAILABLE:2");
        view("owner", "/" + order).andExpect(jsonPath("$.lines[0].receivedQty").value(3))
                .andExpect(jsonPath("$.lines[0].unresolvedQty").value(0));
    }

    /** Review FE PR #44 R-01: dispatched 10, passed 7, wrong item 1, damaged 1. */
    @Test
    void aWrongItemIsRecordedButTheExpectedPartStaysUnresolved() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = dispatchedOrder(brake, 10);
        long receipt = arrive("bmlb", order);
        assign("bmlb", "INSPECTION", receipt, destStaff).andExpect(status().isOk());

        mvc.perform(put(ORDERS + "/" + order + "/receipts/" + receipt + "/result")
                        .header(HttpHeaders.AUTHORIZATION, signIn("nvlb")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("inspectionNote", "1 hộp bugi giao nhầm, 1 má phanh nứt.",
                                "lines", List.of(Map.of("productId", brake, "passedQty", 7, "wrongItemQty", 1,
                                        "damagedQty", 1))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].deliveredQty").value(9))
                .andExpect(jsonPath("$.lines[0].passedQty").value(7))
                .andExpect(jsonPath("$.lines[0].wrongItemQty").value(1))
                .andExpect(jsonPath("$.lines[0].damagedQty").value(1));
        assertThat(jdbc.queryForMap("SELECT delivered_qty, wrong_item_qty FROM goods_receipt_note_line "
                + "WHERE goods_receipt_note_id = ?", receipt))
                .containsEntry("delivered_qty", 9).containsEntry("wrong_item_qty", 1);
        receiptView("nvlb", order, receipt).andExpect(jsonPath("$.lines[0].wrongItemQty").value(1));

        confirm("bmlb", order, receipt).andExpect(status().isOk());

        // Only the part itself leaves transit; the wrong item is another part, so 2 brakes stay unresolved.
        assertThat(stock(dest, brake, "available_qty")).isEqualTo(7);
        assertThat(stock(dest, brake, "damaged_qty")).isEqualTo(1);
        assertThat(stock(dest, brake, "in_transit_qty")).isEqualTo(2);
        view("owner", "/" + order).andExpect(jsonPath("$.lines[0].receivedQty").value(8))
                .andExpect(jsonPath("$.lines[0].unresolvedQty").value(2))
                .andExpect(jsonPath("$.discrepancies[0].shortQty").value(2));
    }

    /** A wrong item is a discrepancy, so the result needs an inspection note (G06). */
    @Test
    void aWrongItemNeedsAnInspectionNote() throws Exception {
        lot(source, brake, 5, 100_000, null, "A");
        long order = dispatchedOrder(brake, 5);
        long receipt = arrive("bmlb", order);
        assign("bmlb", "INSPECTION", receipt, destStaff).andExpect(status().isOk());

        mvc.perform(put(ORDERS + "/" + order + "/receipts/" + receipt + "/result")
                        .header(HttpHeaders.AUTHORIZATION, signIn("nvlb")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("lines", List.of(Map.of("productId", brake, "passedQty", 4,
                                "wrongItemQty", 1, "damagedQty", 0))))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG265"));
    }

    @Test
    void excessStaysOutOfStockAndTheSourceIsTold() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = dispatchedOrder(brake, 2);
        long receipt = arrive("bmlb", order);
        assign("bmlb", "INSPECTION", receipt, destStaff).andExpect(status().isOk());

        result("nvlb", order, receipt, brake, 0, 3).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG212"));
        result("nvlb", order, receipt, brake, 3, 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].excessQty").value(1));
        confirm("bmlb", order, receipt).andExpect(status().isOk());

        assertThat(stock(dest, brake, "available_qty")).isEqualTo(2);
        assertThat(stock(source, brake, "available_qty")).isEqualTo(8);
        view("owner", "/" + order).andExpect(jsonPath("$.lines[0].receivedQty").value(2))
                .andExpect(jsonPath("$.lines[0].extraReceivedQty").value(1))
                .andExpect(jsonPath("$.lines[0].unresolvedQty").value(0));
        assertThat(recipients("TRANSFER_EXCESS_RECEIVED")).containsExactlyInAnyOrder(sourceManager.getId(),
                otherSourceManager.getId());
    }

    @Test
    void twoOrdersOfOnePartEachReceiveTheirOwnLots() throws Exception {
        long soon = lot(source, brake, 3, 100_000, today.plusDays(10), "SOM");
        long later = lot(source, brake, 10, 120_000, today.plusDays(90), "MUON");
        long first = dispatchedOrder(brake, 2);
        long second = dispatchedOrder(brake, 2);

        confirm("bmlb", second, inspected(second, brake, 2, 0)).andExpect(status().isOk());

        assertThat(lotStock(soon, dest, "AVAILABLE")).isEqualTo(1);
        assertThat(lotStock(later, dest, "AVAILABLE")).isEqualTo(1);
        assertThat(lotStock(soon, dest, "IN_TRANSIT")).isEqualTo(2);
        assertThat(lotStock(later, dest, "IN_TRANSIT")).isZero();

        confirm("bmlb", first, inspected(first, brake, 2, 0)).andExpect(status().isOk());
        assertThat(lotStock(soon, dest, "AVAILABLE")).isEqualTo(3);
        assertThat(stock(dest, brake, "in_transit_qty")).isZero();
    }

    @Test
    void whoMayRecordAndConfirm() throws Exception {
        UserAccount secondDestManager = createUser("bmlb2", Role.BRANCH_MANAGER, dest);
        lot(source, brake, 10, 100_000, null, "A");
        long order = createOrder(brake, 2);
        arrive(order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG29"));
        assign("bmcg", "DISPATCH_PREPARATION", order, sourceStaff).andExpect(status().isOk());
        prepare("nvcg", order, brake, 2).andExpect(status().isOk());
        dispatch("bmcg", order, brake, 2).andExpect(status().isOk());

        arriveAs("bmcg", order, arrivalNote())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        arriveAs("owner", order, arrivalNote())
                .andExpect(status().isForbidden());
        long receipt = arrive("bmlb", order);
        result("nvlb", order, receipt, brake, 2, 0).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        assign("bmlb", "INSPECTION", receipt, destStaff).andExpect(status().isOk());
        result("nvlb", order, receipt, brake, 1, 0).andExpect(status().isOk());

        confirm("bmcg", order, receipt).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        confirm("owner", order, receipt).andExpect(status().isForbidden());
        confirm("bmlb2", 999_999, receipt).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG175"));
        assertThat(stock(dest, brake, "available_qty")).isZero();

        mvc.perform(post(ORDERS + "/" + order + "/receipts/" + receipt + "/recount")
                        .header(HttpHeaders.AUTHORIZATION, signIn("bmlb2")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "Đếm lại thùng 2"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AWAITING_RECOUNT"));
        assertThat(recipients("TRANSFER_RECEIPT_RECOUNT")).containsExactly(destStaff.getId());
        result("nvlb", order, receipt, brake, 2, 0).andExpect(status().isOk());
        confirm("bmlb2", order, receipt).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE notification_type = 'TASK_SELF_CONFIRMED'",
                Long.class)).isZero();
        assertThat(stock(dest, brake, "available_qty")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT confirmed_by FROM goods_receipt_note WHERE id = ?", Long.class, receipt))
                .isEqualTo(secondDestManager.getId());
        result("nvlb", order, receipt, brake, 2, 0).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------------------------------------------

    private List<Long> recipients(String type) {
        return jdbc.queryForList("SELECT recipient_id FROM notification WHERE notification_type = ?", Long.class, type);
    }

    private long coordinatorId() {
        return jdbc.queryForObject("SELECT id FROM user_account WHERE username = 'bmkt'", Long.class);
    }

    /** No N+1: the receiving list runs as many queries for three transfer orders as for one. */
    @Test
    void theReceivingListLoadsEveryOrderTogether() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        lot(source, chain, 10, 50_000, null, "B");
        long first = createOrder(brake, 1);
        holdReceiving("nvlb", first);
        String token = signIn("nvlb");
        queries.reset();
        receivingList(token).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        long forOne = queries.prepared();

        long second = createOrder(brake, 1, chain, 1);
        long third = createOrder(chain, 2);
        holdReceiving("nvlb", second);
        holdReceiving("nvlb", third);
        queries.reset();
        receivingList(token).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].documentId", contains((int) first, (int) second,
                        (int) third)))
                .andExpect(jsonPath("$[*].partnerName", everyItem(
                        is("Cầu Giấy"))))
                .andExpect(jsonPath("$[*].lineCount", contains(1, 2, 1)));
        assertThat(queries.prepared()).isEqualTo(forOne);
    }

    private ResultActions receivingList(String token) throws Exception {
        return mvc.perform(get("/api/v1/warehouse-tasks/assigned-to-me").param("taskType", "TRANSFER_RECEIVING")
                .header(HttpHeaders.AUTHORIZATION, token));
    }

    // Lecturer feedback 12/11: the Staff member given the receiving records the arrival and then inspects it.
    @Test
    void theStaffGivenTheReceivingRecordsTheArrival() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = dispatchedOrder(brake, 2);
        arriveAs("nvlb", order, arrivalNote())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        assign("bmlb", "TRANSFER_RECEIVING", order, destStaff).andExpect(status().isOk());
        mvc.perform(get("/api/v1/warehouse-tasks/assigned-to-me").param("taskType", "TRANSFER_RECEIVING")
                        .header(HttpHeaders.AUTHORIZATION, signIn("nvlb")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].documentId").value(order));
        view("nvlb", "/" + order).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].productId").value(brake));

        Map<String, Object> future = arrivalNote();
        future.put("arrivedAt", java.time.LocalDateTime.now(clock).plusHours(1).toString());
        arriveAs("nvlb", order, future).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG138"));

        long staffId = userId("nvlb");
        String created = arriveAs("nvlb", order, arrivalNoteWithPhoto())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deliveryDocumentNo").isEmpty())
                .andExpect(jsonPath("$.createdBy").value(staffId))
                .andExpect(jsonPath("$.documents.length()").value(1))
                .andExpect(jsonPath("$.lines[0].documentQty").value(2))
                .andReturn().getResponse().getContentAsString();
        long receipt = ((Number) JsonPath.read(created, "$.id")).longValue();
        holdReceiving("bmlb", order);
        arriveAs("bmlb", order, arrivalNote()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG211"));
        assertThat(jdbc.queryForObject("SELECT status FROM warehouse_task WHERE task_type = 'TRANSFER_RECEIVING' "
                + "AND transfer_order_id = ? AND status = 'DONE'", String.class, order)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT assigned_to FROM warehouse_task WHERE task_type = 'INSPECTION' "
                + "AND goods_receipt_note_id = ? AND status = 'ASSIGNED'", Long.class, receipt)).isEqualTo(staffId);

        result("nvlb", order, receipt, brake, 2, 0).andExpect(status().isOk());
        confirm("bmlb", order, receipt).andExpect(status().isOk());
    }
}
