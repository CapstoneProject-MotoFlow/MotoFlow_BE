package fu.hl.g64.motorflow.receiving;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Lecturer feedback 12/11: a Staff member given the receiving of a PO records its GRN and then inspects it. */
class StaffReceivingApiTest extends GoodsReceiptTestData {

    private ResultActions giveReceiving(String token, long purchaseOrder, UserAccount assignee) throws Exception {
        return mvc.perform(post("/api/v1/warehouse-tasks").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("taskType", "SUPPLIER_RECEIVING",
                        "documentId", purchaseOrder, "assigneeId", assignee.getId()))));
    }

    private ResultActions receivingView(String token, long purchaseOrder) throws Exception {
        return mvc.perform(get("/api/v1/purchase-orders/" + purchaseOrder + "/receiving-view")
                .header(HttpHeaders.AUTHORIZATION, token));
    }

    private String taskStatus(String type, String column, long documentId) {
        return jdbc.queryForObject("SELECT status FROM warehouse_task WHERE task_type = ? AND " + column
                + " = ? ORDER BY id DESC LIMIT 1", String.class, type, documentId);
    }

    @Test
    void theStaffGivenTheReceivingRecordsTheGrnInspectsItAndAnotherPersonConfirms() throws Exception {
        // Not holding the task: MSG05, for a Branch Manager too.
        createReceiptAs(staffToken, order, deliveryNote("PX-001"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        createReceiptAs(managerToken, order, deliveryNote("PX-001"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        receivingView(staffToken, order)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));

        giveReceiving(managerToken, order, staff).andExpect(status().isOk());
        mvc.perform(get("/api/v1/warehouse-tasks/assigned-to-me").param("taskType", "SUPPLIER_RECEIVING")
                        .header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].documentId").value(order))
                .andExpect(jsonPath("$[0].partnerName").value("Honda Việt Nam"))
                .andExpect(jsonPath("$[0].lineCount").value(2))
                .andExpect(jsonPath("$[0].assignedById").value(manager.getId()));
        receivingView(staffToken, order)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("#" + order))
                .andExpect(jsonPath("$.canCreateReceipt").value(true))
                .andExpect(jsonPath("$.lines[*].orderedQty", contains(10, 24)))
                .andExpect(jsonPath("$.lines[*].outstandingQty", contains(10, 24)))
                .andExpect(content().string(not(containsString("rice"))))
                .andExpect(content().string(not(containsString("alue"))));
        receivingView(staff2Token, order).andExpect(status().isForbidden());

        String created = createReceiptAs(staffToken, order, deliveryNote("PX-001"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[*].unitPrice", everyItem(nullValue())))
                .andExpect(jsonPath("$.assignee.id").value(staff.getId()))
                .andReturn().getResponse().getContentAsString();
        long grn = ((Number) JsonPath.read(created, "$.id")).longValue();
        assertThat(taskStatus("SUPPLIER_RECEIVING", "purchase_order_id", order)).isEqualTo("DONE");
        assertThat(jdbc.queryForList("SELECT status, assigned_to, assigned_by FROM warehouse_task "
                + "WHERE task_type = 'INSPECTION' AND goods_receipt_note_id = ?", grn).get(0))
                .containsEntry("status", "ASSIGNED").containsEntry("assigned_to", staff.getId())
                .containsEntry("assigned_by", manager.getId());
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_log WHERE entity_type = 'GOODS_RECEIPT_NOTE' "
                + "AND entity_id = ? AND action = 'GOODS_RECEIPT_CREATED'", Long.class, grn)).isEqualTo(staff.getId());
        // The receiver knows; nobody is told about the inspection.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE notification_type = 'TASK_ASSIGNED' "
                + "AND ref_type <> 'PURCHASE_ORDER' AND ref_id = ?", Long.class, grn)).isZero();
        // The receiving is done: a second GRN needs a new receiving task.
        createReceiptAs(staffToken, order, deliveryNote("PX-002")).andExpect(status().isForbidden());

        record(staffToken, grn, findings(counted(lineOf(grn, pad), 10, 10, 0, 0),
                counted(lineOf(grn, oil), 24, 24, 0, 0)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AWAITING_CONFIRMATION"));
        confirm(managerToken, grn).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        detail(ownerToken, order).andExpect(jsonPath("$.status").value("FULLY_RECEIVED"));
        // Nothing is left to receive: the PO can no longer get a receiving task (MSG121).
        giveReceiving(managerToken, order, staff).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG121"));
    }

    @Test
    void aReplacementByStaffStillChecksItsCases() throws Exception {
        holdReceiving("SUPPLIER_RECEIVING", order, staff.getId(), manager.getId());
        createReceiptAs(staffToken, order, deliveryNote("PX-002", 999_999L))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG133"));
        assertThat(taskStatus("SUPPLIER_RECEIVING", "purchase_order_id", order)).isEqualTo("ASSIGNED");
    }

    @Test
    void cancellingThePoCancelsItsReceiving() throws Exception {
        giveReceiving(managerToken, order, staff).andExpect(status().isOk());
        cancel(ownerToken, order, "Nhà cung cấp hết hàng").andExpect(status().isOk());
        assertThat(taskStatus("SUPPLIER_RECEIVING", "purchase_order_id", order)).isEqualTo("CANCELLED");
        createReceiptAs(staffToken, order, deliveryNote("PX-001")).andExpect(status().isForbidden());
    }
}
