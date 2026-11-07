package fu.hl.g64.motorflow.transfer;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-76. */
class TransferDiscrepancyApiTest extends TransferFlowTestData {

    @Test
    void lateDeliveryIsSettledByTheLaterReceipt() throws Exception {
        long order = shortOrder(4, 1);

        decide("bmkt", order, brake, "LATE_DELIVERY", 3, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].unresolvedQty").value(3));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'TRANSFER_DISCREPANCY_DECIDED'",
                Long.class)).isEqualTo(1);
        long late = inspected(order, brake, 3, 0);
        confirm("bmlb", order, late).andExpect(status().isOk());

        view("owner", "/" + order).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].receivedQty").value(4))
                .andExpect(jsonPath("$.lines[0].unresolvedQty").value(0))
                .andExpect(jsonPath("$.discrepancies[0].status").value("CLOSED"))
                .andExpect(jsonPath("$.discrepancies[0].events[0].resolutionType").value("LATE_DELIVERY_RECEIVED"))
                .andExpect(jsonPath("$.discrepancies[0].events[0].quantity").value(3))
                .andExpect(jsonPath("$.discrepancies[0].events[0].goodsReceiptNoteId").value(late));
        assertThat(jdbc.queryForObject("SELECT closure_type FROM discrepancy_case WHERE transfer_order_id = ?",
                String.class, order)).isEqualTo("ALL_SETTLED");
        assertThat(stock(dest, brake, "available_qty")).isEqualTo(4);
        assertThat(stock(dest, brake, "in_transit_qty")).isZero();
    }

    @Test
    void returnPutsTheSameLotsBackAtTheSource() throws Exception {
        long soon = lot(source, brake, 2, 100_000, today.plusDays(30), "SOON");
        long later = lot(source, brake, 10, 100_000, today.plusDays(300), "LATER");
        long order = dispatchedOrder(brake, 4);
        confirm("bmlb", order, inspected(order, brake, 1, 0)).andExpect(status().isOk());

        decide("owner", order, brake, "RETURN_TO_SOURCE", 3, null).andExpect(status().isOk());
        long back = arriveReturn("bmcg", order, brake, 3).andExpect(status().isCreated())
                .andExpect(jsonPath("$.receiptType").value("RETURN_TO_SOURCE"))
                .andExpect(jsonPath("$.lines[0].documentQty").value(3)).andReturn().getResponse().getHeader("Location")
                .transform(location -> Long.parseLong(location.substring(location.lastIndexOf('/') + 1)));
        assign("bmcg", "INSPECTION", back, sourceStaff).andExpect(status().isOk());
        result("nvcg", order, back, brake, 3, 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG220"));
        result("nvcg", order, back, brake, 2, 1).andExpect(status().isOk());
        confirm("bmlb", order, back).andExpect(status().isForbidden());
        confirm("bmcg2", order, back).andExpect(status().isOk());

        // FEFO: 2 of SOON and 2 of LATER left, 1 of SOON arrived; back come 1 SOON and 2 LATER.
        assertThat(lotStock(soon, source, "AVAILABLE")).isEqualTo(1);
        assertThat(lotStock(later, source, "AVAILABLE")).isEqualTo(9);
        assertThat(lotStock(later, source, "DAMAGED")).isEqualTo(1);
        assertThat(stock(dest, brake, "in_transit_qty")).isZero();
        assertThat(stock(dest, brake, "available_qty")).isEqualTo(1);
        view("owner", "/" + order).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].returnedQty").value(3))
                .andExpect(jsonPath("$.lines[0].unresolvedQty").value(0))
                .andExpect(jsonPath("$.discrepancies[0].status").value("CLOSED"))
                .andExpect(jsonPath("$.discrepancies[0].events[0].resolutionType").value("RECEIVED_BACK_AT_SOURCE"))
                .andExpect(jsonPath("$.discrepancies[0].events[0].quantity").value(3));
    }

    @Test
    void nothingCoversMoreThanIsStillMissing() throws Exception {
        long order = shortOrder(4, 1);

        decide("bmkt", order, brake, "LATE_DELIVERY", 4, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG217"));
        decide("bmkt", order, chain, "LATE_DELIVERY", 1, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG216"));
        decide("bmkt", order, brake, "RETURN_TO_SOURCE", 2, null).andExpect(status().isOk());
        arriveReturn("bmcg", order, brake, 4).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG217"));
        arriveReturn("bmcg", order, brake, 2).andExpect(status().isCreated());
        // 2 already coming back: a late receipt expects only the last 1, and nothing more may return.
        long late = arrive("bmlb", order);
        assertThat(jdbc.queryForObject("SELECT document_qty FROM goods_receipt_note_line WHERE goods_receipt_note_id = ?",
                Integer.class, late)).isEqualTo(1);
        arriveReturn("bmcg", order, brake, 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG217"));
        decide("bmkt", order, brake, "RETURN_TO_SOURCE", 1, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG217"));
    }

    @Test
    void lossCountsOnlyOnceItsAdjustmentIsApplied() throws Exception {
        long order = shortOrder(4, 1);
        long adjustment = transitLoss(order, brake, 2);
        long other = transitLoss(order, brake, 3);

        decide("owner", order, brake, "LOSS", 2, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG218"));
        decide("owner", order, brake, "LOSS", 2, other).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG219"));
        decide("owner", order, brake, "LOSS", 2, adjustment).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].lossQty").value(0))
                .andExpect(jsonPath("$.lines[0].unresolvedQty").value(3))
                .andExpect(jsonPath("$.discrepancies[0].events[0].resolutionType").value("APPROVED_LOSS"))
                .andExpect(jsonPath("$.discrepancies[0].events[0].adjustmentRequestId").value(adjustment));
        decide("owner", order, brake, "LOSS", 2, adjustment).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG217"));

        // The pending loss is spoken for: only 1 may still arrive late.
        long late = arrive("bmlb", order);
        assertThat(jdbc.queryForObject("SELECT document_qty FROM goods_receipt_note_line WHERE goods_receipt_note_id = ?",
                Integer.class, late)).isEqualTo(1);

        jdbc.update("UPDATE adjustment_request SET status = 'APPLIED', applied_at = now() WHERE id = ?", adjustment);
        view("owner", "/" + order).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].lossQty").value(2))
                .andExpect(jsonPath("$.lines[0].unresolvedQty").value(1));
    }

    @Test
    void aRejectedLossFreesTheQuantity() throws Exception {
        long order = shortOrder(4, 1);
        long adjustment = transitLoss(order, brake, 3);
        decide("owner", order, brake, "LOSS", 3, adjustment).andExpect(status().isOk());
        arrive(order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG210"));

        jdbc.update("UPDATE adjustment_request SET status = 'REJECTED' WHERE id = ?", adjustment);
        arrive(order).andExpect(status().isCreated());
        decide("owner", order, brake, "LOSS", 3, adjustment).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG217"));
    }

    @Test
    void aMakeupShipmentDoesNotSettleTheOriginalShortage() throws Exception {
        long order = shortOrder(4, 1);
        long shortage = jdbc.queryForObject("SELECT id FROM discrepancy_case WHERE transfer_order_id = ?", Long.class,
                order);
        Map<String, Object> line = new HashMap<>(Map.of("productId", brake, "plannedQty", 3));
        line.put("makeupForCaseId", shortage);
        mvc.perform(post(ORDERS).header(HttpHeaders.AUTHORIZATION, signIn("bmkt"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("sourceStoreId", source, "destStoreId", dest, "lines", List.of(line)))))
                .andExpect(status().isCreated());
        long makeup = jdbc.queryForObject("SELECT max(id) FROM transfer_order", Long.class);
        assign("bmcg", "DISPATCH_PREPARATION", makeup, sourceStaff).andExpect(status().isOk());
        prepare("nvcg", makeup, brake, 3).andExpect(status().isOk());
        dispatch("bmcg", makeup, brake, 3).andExpect(status().isOk());
        confirm("bmlb", makeup, inspected(makeup, brake, 3, 0)).andExpect(status().isOk());

        view("owner", "/" + order).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].unresolvedQty").value(3))
                .andExpect(jsonPath("$.discrepancies[0].status").value("OPEN"));
    }

    @Test
    void onlyCoordinatorsDecideAndOnlyTheSourceReceivesReturns() throws Exception {
        long order = shortOrder(4, 1);

        decide("bmlb", order, brake, "LATE_DELIVERY", 1, null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        decide("bmcg", order, brake, "LATE_DELIVERY", 1, null).andExpect(status().isForbidden());
        decide("nvlb", order, brake, "LATE_DELIVERY", 1, null).andExpect(status().isForbidden());
        arriveReturn("bmlb", order, brake, 1).andExpect(status().isForbidden());
        arriveReturn("owner", order, brake, 1).andExpect(status().isForbidden());
        arriveReturn("bmcg", order, chain, 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG166"));
    }

    @Test
    void aReturnNeedsTheCoordinatorsDecision() throws Exception {
        long order = shortOrder(4, 1);

        arriveReturn("bmcg", order, brake, 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG226"));
    }

    @Test
    void aFullReturnSettlesTheDecisionWithItsReceipt() throws Exception {
        long order = shortOrder(4, 1);
        long back = decidedReturn(order, 2, 2);
        result("nvcg", order, back, brake, 2, 0).andExpect(status().isOk());
        confirm("bmcg2", order, back).andExpect(status().isOk());

        List<Map<String, Object>> events = returnEvents(order);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("quantity")).isEqualTo(2);
        assertThat(events.get(0).get("goods_receipt_note_id")).isEqualTo(back);
        assertThat(events.get(0).get("confirmed_at")).isNotNull();
    }

    @Test
    void aPartialReturnSplitsTheDecision() throws Exception {
        long order = shortOrder(4, 1);
        long back = decidedReturn(order, 2, 2);
        result("nvcg", order, back, brake, 1, 0).andExpect(status().isOk());
        confirm("bmcg2", order, back).andExpect(status().isOk());

        List<Map<String, Object>> events = returnEvents(order);
        assertThat(events).hasSize(2);
        Map<String, Object> pending = events.stream().filter(e -> e.get("confirmed_at") == null).findFirst().orElseThrow();
        Map<String, Object> done = events.stream().filter(e -> e.get("confirmed_at") != null).findFirst().orElseThrow();
        assertThat(pending.get("quantity")).isEqualTo(1);
        assertThat(pending.get("goods_receipt_note_id")).isNull();
        assertThat(done.get("quantity")).isEqualTo(1);
        assertThat(done.get("goods_receipt_note_id")).isEqualTo(back);
    }

    @Test
    void aReturnRecordedAsNothingLeavesTheDecisionToReturnAgain() throws Exception {
        long order = shortOrder(4, 1);
        long back = decidedReturn(order, 2, 2);
        result("nvcg", order, back, brake, 0, 0).andExpect(status().isOk());
        confirm("bmcg2", order, back).andExpect(status().isOk());

        List<Map<String, Object>> events = returnEvents(order);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("quantity")).isEqualTo(2);
        assertThat(events.get(0).get("confirmed_at")).isNull();
        arriveReturn("bmcg", order, brake, 2).andExpect(status().isCreated());
    }

    @Test
    void decisionsTogetherCannotExceedWhatIsUnresolved() throws Exception {
        long order = shortOrder(4, 1);

        decide("owner", order, brake, "RETURN_TO_SOURCE", 2, null).andExpect(status().isOk());
        decide("owner", order, brake, "RETURN_TO_SOURCE", 2, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG217"));
    }

    @Test
    void onlyAPendingReturnMayLackAReceiptOnTheEvent() throws Exception {
        long order = shortOrder(4, 1);
        long shortage = jdbc.queryForObject("SELECT id FROM discrepancy_case WHERE transfer_order_id = ?", Long.class,
                order);

        jdbc.update("INSERT INTO discrepancy_resolution_event (discrepancy_case_id, resolution_type, quantity, "
                + "recorded_by, occurred_at) VALUES (?, 'RECEIVED_BACK_AT_SOURCE', 1, ?, now())", shortage,
                owner.getId());
        assertThatThrownBy(() -> jdbc.update("INSERT INTO discrepancy_resolution_event (discrepancy_case_id, "
                + "resolution_type, quantity, recorded_by, occurred_at) VALUES (?, 'REPLACEMENT_RECEIVED', 1, ?, now())",
                shortage, owner.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO discrepancy_resolution_event (discrepancy_case_id, "
                + "resolution_type, quantity, recorded_by, occurred_at, confirmed_by, confirmed_at) "
                + "VALUES (?, 'RECEIVED_BACK_AT_SOURCE', 1, ?, now(), ?, now())", shortage, owner.getId(),
                owner.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** A RETURN_TO_SOURCE decision of {@code decided}, then a return of {@code quantity} opened and given to staff. */
    private long decidedReturn(long order, int decided, int quantity) throws Exception {
        decide("owner", order, brake, "RETURN_TO_SOURCE", decided, null).andExpect(status().isOk());
        arriveReturn("bmcg", order, brake, quantity).andExpect(status().isCreated());
        long back = jdbc.queryForObject("SELECT max(id) FROM goods_receipt_note", Long.class);
        assign("bmcg", "INSPECTION", back, sourceStaff).andExpect(status().isOk());
        return back;
    }

    private List<Map<String, Object>> returnEvents(long order) {
        return jdbc.queryForList("SELECT e.* FROM discrepancy_resolution_event e JOIN discrepancy_case c "
                + "ON c.id = e.discrepancy_case_id WHERE c.transfer_order_id = ? "
                + "AND e.resolution_type = 'RECEIVED_BACK_AT_SOURCE' ORDER BY e.id", order);
    }

    @Test
    void aReturnDecisionNotOpenedIsReleasedByALossAndTheOrderCloses() throws Exception {
        long order = shortOrder(3, 1);
        long back = decidedReturn(order, 2, 2);
        result("nvcg", order, back, brake, 0, 0).andExpect(status().isOk());
        confirm("bmcg2", order, back).andExpect(status().isOk());
        long adjustment = transitLoss(order, brake, 2);

        decide("owner", order, brake, "LOSS", 2, adjustment).andExpect(status().isOk());

        assertThat(returnEvents(order)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT new_data::text FROM audit_log WHERE action = "
                + "'TRANSFER_DISCREPANCY_DECIDED' ORDER BY id DESC LIMIT 1", String.class))
                .contains("released_return_qty");
        jdbc.update("UPDATE adjustment_request SET status = 'APPLIED', applied_at = now() WHERE id = ?", adjustment);
        mvc.perform(post(ORDERS + "/" + order + "/close").header(HttpHeaders.AUTHORIZATION, signIn("owner")))
                .andExpect(status().isOk());
    }

    @Test
    void anOpenReturnIsNotReleasedByALoss() throws Exception {
        long order = shortOrder(4, 1);
        decidedReturn(order, 2, 2);
        long adjustment = transitLoss(order, brake, 2);

        decide("owner", order, brake, "LOSS", 2, adjustment).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG217"));
    }

    /** Dispatched {@code sent} brakes, {@code received} confirmed at the destination: the rest is missing. */
    private long shortOrder(int sent, int received) throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = dispatchedOrder(brake, sent);
        confirm("bmlb", order, inspected(order, brake, received, 0)).andExpect(status().isOk());
        return order;
    }

    /** A pending STOCK_LOSS adjustment of the order writing {@code quantity} off transit at the destination. */
    private long transitLoss(long order, long product, int quantity) {
        long adjustment = jdbc.queryForObject("INSERT INTO adjustment_request (adjustment_type, store_id, "
                + "transfer_order_id, reason, status, requested_by) VALUES ('STOCK_LOSS', ?, ?, 'Mất khi vận chuyển', "
                + "'PENDING', ?) RETURNING id", Long.class, dest, order, destManager.getId());
        jdbc.update("INSERT INTO adjustment_stock_line (adjustment_request_id, store_id, product_id, stock_status, "
                + "quantity_change) VALUES (?, ?, ?, 'IN_TRANSIT', ?)", adjustment, dest, product, -quantity);
        return adjustment;
    }

    private ResultActions decide(String username, long order, long product, String decision, int quantity,
                                 Long adjustment) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("productId", product, "decision", decision,
                "quantity", quantity));
        body.put("adjustmentRequestId", adjustment);
        return mvc.perform(post(ORDERS + "/" + order + "/discrepancy/decisions")
                .header(HttpHeaders.AUTHORIZATION, signIn(username)).contentType(MediaType.APPLICATION_JSON)
                .content(json(body)));
    }

    private ResultActions arriveReturn(String username, long order, long product, int quantity) throws Exception {
        return mvc.perform(post(ORDERS + "/" + order + "/returns").header(HttpHeaders.AUTHORIZATION, signIn(username))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("lines", List.of(Map.of("productId", product, "quantity", quantity))))));
    }
}
