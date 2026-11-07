package fu.hl.g64.motorflow.transfer;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BR-60, BR-68: the figures of a transfer match what was posted out of transit, whatever the order of receipts. */
class TransferConsistencyApiTest extends TransferFlowTestData {

    @Test
    void lateReceiptAgainstAnOpenReturnCountsAtMostWhatItExpects() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = dispatchedOrder(brake, 4);
        int afterDispatch = stock(source, brake, "available_qty");
        assertInvariant(order, 4);

        confirm("bmlb", order, inspected(order, brake, 1, 0)).andExpect(status().isOk());
        assertInvariant(order, 3);

        long back = arriveReturn(order, 2);
        assertInvariant(order, 3);
        long late = arrive("bmlb", order);
        assertThat(jdbc.queryForObject("SELECT document_qty FROM goods_receipt_note_line WHERE goods_receipt_note_id = ?",
                Integer.class, late)).isEqualTo(1);
        assign("bmlb", "INSPECTION", late, destStaff).andExpect(status().isOk());
        result("nvlb", order, late, brake, 3, 0).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG224"));
        result("nvlb", order, late, brake, 1, 0).andExpect(status().isOk());
        confirm("bmlb", order, late).andExpect(status().isOk());
        assertInvariant(order, 2);

        assign("bmcg", "INSPECTION", back, sourceStaff).andExpect(status().isOk());
        result("nvcg", order, back, brake, 0, 0).andExpect(status().isOk());
        confirm("bmcg", order, back).andExpect(status().isOk());
        assertInvariant(order, 2);

        // R-06: the return decision stays pending after a return recorded as 0, so it still claims the 2 units.
        arrive(order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG210"));
        // The coordinator gives it up for a late delivery, which frees the units.
        mvc.perform(post(ORDERS + "/" + order + "/discrepancy/decisions")
                        .header(HttpHeaders.AUTHORIZATION, signIn("owner")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("productId", brake, "decision", "LATE_DELIVERY", "quantity", 2))))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM discrepancy_resolution_event WHERE resolution_type = "
                + "'RECEIVED_BACK_AT_SOURCE' AND confirmed_at IS NULL", Long.class)).isZero();
        long again = arrive("bmlb", order);
        assign("bmlb", "INSPECTION", again, destStaff).andExpect(status().isOk());
        result("nvlb", order, again, brake, 2, 0).andExpect(status().isOk());
        confirm("bmlb", order, again).andExpect(status().isOk());
        assertInvariant(order, 0);

        mvc.perform(post(ORDERS + "/" + order + "/close").header(HttpHeaders.AUTHORIZATION, signIn("owner")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        assertThat(stock(dest, brake, "available_qty")).isEqualTo(4);
        assertThat(stock(dest, brake, "in_transit_qty")).isZero();
        assertThat(stock(source, brake, "available_qty")).isEqualTo(afterDispatch);
    }

    @Test
    void aPendingLossAlsoCapsALateReceipt() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = dispatchedOrder(brake, 4);
        confirm("bmlb", order, inspected(order, brake, 1, 0)).andExpect(status().isOk());
        long adjustment = jdbc.queryForObject("INSERT INTO adjustment_request (adjustment_type, store_id, "
                + "transfer_order_id, reason, status, requested_by) VALUES ('STOCK_LOSS', ?, ?, 'Mất khi vận chuyển', "
                + "'PENDING', ?) RETURNING id", Long.class, dest, order, destManager.getId());
        jdbc.update("INSERT INTO adjustment_stock_line (adjustment_request_id, store_id, product_id, stock_status, "
                + "quantity_change) VALUES (?, ?, ?, 'IN_TRANSIT', -1)", adjustment, dest, brake);
        mvc.perform(post(ORDERS + "/" + order + "/discrepancy/decisions")
                        .header(HttpHeaders.AUTHORIZATION, signIn("owner")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("productId", brake, "decision", "LOSS", "quantity", 1,
                                "adjustmentRequestId", adjustment))))
                .andExpect(status().isOk());
        long late = arrive("bmlb", order);
        assign("bmlb", "INSPECTION", late, destStaff).andExpect(status().isOk());

        result("nvlb", order, late, brake, 3, 0).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG224"));
        result("nvlb", order, late, brake, 2, 0).andExpect(status().isOk());
    }

    @Test
    void genuineExcessIsStillReportedAndConfirmedByTheSource() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = dispatchedOrder(brake, 2);
        confirm("bmlb", order, inspected(order, brake, 3, 0)).andExpect(status().isOk());

        view("owner", "/" + order).andExpect(jsonPath("$.lines[0].receivedQty").value(2))
                .andExpect(jsonPath("$.lines[0].extraReceivedQty").value(1))
                .andExpect(jsonPath("$.lines[0].unresolvedQty").value(0));
        assertInvariant(order, 0);
        close(order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG222"));
        mvc.perform(post(ORDERS + "/" + order + "/extra-dispatch").header(HttpHeaders.AUTHORIZATION, signIn("bmcg"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("lines", List.of(Map.of("productId", brake, "extraQty", 1))))))
                .andExpect(status().isOk());
        close(order).andExpect(status().isOk());

        assertThat(stock(dest, brake, "available_qty")).isEqualTo(3);
        assertThat(stock(source, brake, "available_qty")).isEqualTo(7);
    }

    @Test
    void anOpenReceiptBlocksClosing() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = dispatchedOrder(brake, 4);
        confirm("bmlb", order, inspected(order, brake, 1, 0)).andExpect(status().isOk());
        arriveReturn(order, 3);

        mvc.perform(get(ORDERS + "/" + order + "/closing-preview").header(HttpHeaders.AUTHORIZATION, signIn("owner")))
                .andExpect(jsonPath("$.canClose").value(false))
                .andExpect(jsonPath("$.blockedBy", contains("MSG221", "MSG225")));
        close(order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG221"));
    }

    @Test
    void anOpenReceiptBlocksClosingEvenWhenEveryPartHasItsResult() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = dispatchedOrder(brake, 2);
        confirm("bmlb", order, inspected(order, brake, 2, 0)).andExpect(status().isOk());
        long open = jdbc.queryForObject("INSERT INTO goods_receipt_note (receipt_type, transfer_order_id, arrived_at) "
                + "VALUES ('TRANSFER_IN', ?, now()) RETURNING id", Long.class, order);
        jdbc.update("INSERT INTO goods_receipt_note_line (goods_receipt_note_id, product_id, document_qty, "
                + "delivered_qty, passed_qty) VALUES (?, ?, 1, 0, 0)", open, brake);

        mvc.perform(get(ORDERS + "/" + order + "/closing-preview").header(HttpHeaders.AUTHORIZATION, signIn("owner")))
                .andExpect(jsonPath("$.blockedBy", contains("MSG225")));
        close(order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG225"));
    }

    @Test
    void nothingIsRecordedOrConfirmedOnAClosedOrder() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = dispatchedOrder(brake, 4);
        confirm("bmlb", order, inspected(order, brake, 1, 0)).andExpect(status().isOk());
        long back = arriveReturn(order, 2);
        assign("bmcg", "INSPECTION", back, sourceStaff).andExpect(status().isOk());
        long late = arrive("bmlb", order);
        assign("bmlb", "INSPECTION", late, destStaff).andExpect(status().isOk());
        result("nvlb", order, late, brake, 1, 0).andExpect(status().isOk());
        jdbc.update("UPDATE transfer_order SET status = 'CLOSED', closed_by = ?, closed_at = now() WHERE id = ?",
                owner.getId(), order);

        result("nvcg", order, back, brake, 0, 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG29"));
        mvc.perform(post(ORDERS + "/" + order + "/receipts/" + late + "/recount")
                        .header(HttpHeaders.AUTHORIZATION, signIn("bmlb")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "Đếm lại"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG29"));
        confirm("bmlb", order, late).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG29"));
        assertThat(stock(dest, brake, "in_transit_qty")).isEqualTo(3);
    }

    /** UC-76 Step 3_Return to source of {@code quantity} brakes by the source Branch Manager; the receipt's id. */
    private long arriveReturn(long order, int quantity) throws Exception {
        // R-06: the coordinator decides the return before the source may open it.
        mvc.perform(post(ORDERS + "/" + order + "/discrepancy/decisions")
                        .header(HttpHeaders.AUTHORIZATION, signIn("owner")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("productId", brake, "decision", "RETURN_TO_SOURCE",
                                "quantity", quantity))))
                .andExpect(status().isOk());
        mvc.perform(post(ORDERS + "/" + order + "/returns").header(HttpHeaders.AUTHORIZATION, signIn("bmcg"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("lines", List.of(Map.of("productId", brake, "quantity", quantity))))))
                .andExpect(status().isCreated());
        return jdbc.queryForObject("SELECT max(id) FROM goods_receipt_note", Long.class);
    }

    private ResultActions close(long order) throws Exception {
        return mvc.perform(post(ORDERS + "/" + order + "/close").header(HttpHeaders.AUTHORIZATION, signIn("owner")));
    }

    /** Destination IN_TRANSIT of the brakes = dispatched - received - returned - applied loss, as the order shows them. */
    private void assertInvariant(long order, int inTransit) throws Exception {
        String body = view("owner", "/" + order).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        int expected = (int) JsonPath.read(body, "$.lines[0].dispatchedQty")
                - (int) JsonPath.read(body, "$.lines[0].receivedQty")
                - (int) JsonPath.read(body, "$.lines[0].returnedQty")
                - (int) JsonPath.read(body, "$.lines[0].lossQty");
        assertThat(stock(dest, brake, "in_transit_qty")).isEqualTo(expected).isEqualTo(inTransit);
    }
}
