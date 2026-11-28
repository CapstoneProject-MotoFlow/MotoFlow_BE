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

/** UC-76, PM 23/11: late delivery or no replacement; returns and losses decided before 23/11 still settle. */
class TransferDiscrepancyApiTest extends TransferFlowTestData {

    @Test
    void lateDeliveryIsSettledByTheLaterReceipt() throws Exception {
        long order = shortOrder(4, 1);

        decide("bmlb", order, brake, "LATE_DELIVERY", 3, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].shortageQty").value(3))
                .andExpect(jsonPath("$.lines[0].latePendingQty").value(3))
                .andExpect(jsonPath("$.lines[0].unresolvedQty").value(0));
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

        legacyDecision(order, brake, "RECEIVED_BACK_AT_SOURCE", 3, null);
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

        decide("bmlb", order, brake, "LATE_DELIVERY", 4, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG217"));
        decide("bmlb", order, chain, "LATE_DELIVERY", 1, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG216"));
        legacyDecision(order, brake, "RECEIVED_BACK_AT_SOURCE", 2, null);
        arriveReturn("bmcg", order, brake, 4).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG217"));
        arriveReturn("bmcg", order, brake, 2).andExpect(status().isCreated());
        // 2 already coming back: a late receipt expects only the last 1, and nothing more may return.
        long late = arrive("bmlb", order);
        assertThat(jdbc.queryForObject("SELECT document_qty FROM goods_receipt_note_line WHERE goods_receipt_note_id = ?",
                Integer.class, late)).isEqualTo(1);
        arriveReturn("bmcg", order, brake, 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG217"));
        // The last 1 is awaited by the open receipt, so nothing is left to decide on.
        decide("bmlb", order, brake, "NO_REPLACEMENT", 1, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG217"));
    }

    @Test
    void lossCountsOnlyOnceItsAdjustmentIsApplied() throws Exception {
        long order = shortOrder(4, 1);
        long adjustment = transitLoss(order, brake, 2);
        long other = transitLoss(order, brake, 3);

        legacyDecision(order, brake, "APPROVED_LOSS", 2, adjustment);
        jdbc.update("UPDATE adjustment_request SET status = 'REJECTED' WHERE id = ?", other);
        view("owner", "/" + order).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].lossQty").value(0))
                .andExpect(jsonPath("$.lines[0].shortageQty").value(3))
                .andExpect(jsonPath("$.lines[0].unresolvedQty").value(1))
                .andExpect(jsonPath("$.discrepancies[0].events[0].resolutionType").value("APPROVED_LOSS"))
                .andExpect(jsonPath("$.discrepancies[0].events[0].adjustmentRequestId").value(adjustment));
        decide("owner", order, brake, "LATE_DELIVERY", 2, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG217"));

        // The pending loss is spoken for: only 1 may still arrive late.
        long late = arrive("bmlb", order);
        assertThat(jdbc.queryForObject("SELECT document_qty FROM goods_receipt_note_line WHERE goods_receipt_note_id = ?",
                Integer.class, late)).isEqualTo(1);

        jdbc.update("UPDATE adjustment_request SET status = 'APPLIED', applied_at = now() WHERE id = ?", adjustment);
        view("owner", "/" + order).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].lossQty").value(2))
                .andExpect(jsonPath("$.lines[0].shortageQty").value(1));
    }

    @Test
    void aRejectedLossFreesTheQuantity() throws Exception {
        long order = shortOrder(4, 1);
        long adjustment = transitLoss(order, brake, 3);
        legacyDecision(order, brake, "APPROVED_LOSS", 3, adjustment);
        arrive(order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG210"));

        jdbc.update("UPDATE adjustment_request SET status = 'REJECTED' WHERE id = ?", adjustment);
        arrive(order).andExpect(status().isCreated());
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
    void onlyTheReceivingStoreOrTheOwnerDecidesAndOnlyTheSourceReceivesReturns() throws Exception {
        long order = shortOrder(4, 1);

        decide("bmkt", order, brake, "LATE_DELIVERY", 1, null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        decide("bmcg", order, brake, "LATE_DELIVERY", 1, null).andExpect(status().isForbidden());
        decide("nvlb", order, brake, "LATE_DELIVERY", 1, null).andExpect(status().isForbidden());
        decide("bmlb", order, brake, "LATE_DELIVERY", 1, null).andExpect(status().isOk());
        legacyDecision(order, brake, "RECEIVED_BACK_AT_SOURCE", 1, null);
        arriveReturn("bmlb", order, brake, 1).andExpect(status().isForbidden());
        arriveReturn("owner", order, brake, 1).andExpect(status().isForbidden());
        arriveReturn("bmcg", order, chain, 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG166"));
    }

    @Test
    void returnsAndLossesAreNoLongerDecided() throws Exception {
        long order = shortOrder(4, 1);

        arriveReturn("bmcg", order, brake, 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG345"));
        decide("owner", order, brake, "RETURN_TO_SOURCE", 1, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG345"));
        decide("bmlb", order, brake, "LOSS", 1, transitLoss(order, brake, 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG345"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM discrepancy_resolution_event", Long.class)).isZero();
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

        decide("owner", order, brake, "LATE_DELIVERY", 2, null).andExpect(status().isOk());
        decide("bmlb", order, brake, "NO_REPLACEMENT", 2, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG217"));
        decide("bmlb", order, brake, "NO_REPLACEMENT", 1, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].noReplacementQty").value(1))
                .andExpect(jsonPath("$.lines[0].shortageQty").value(2))
                .andExpect(jsonPath("$.lines[0].unresolvedQty").value(0));
    }

    @Test
    void onlyAPendingReturnMayLackAReceiptOnTheEvent() throws Exception {
        long order = shortOrder(4, 1);
        long shortage = jdbc.queryForObject("SELECT id FROM discrepancy_case WHERE transfer_order_id = ?", Long.class,
                order);

        jdbc.update("INSERT INTO discrepancy_resolution_event (discrepancy_case_id, resolution_type, quantity, "
                + "recorded_by, occurred_at) VALUES (?, 'RECEIVED_BACK_AT_SOURCE', 1, ?, now())", shortage,
                owner.getId());
        jdbc.update("INSERT INTO discrepancy_resolution_event (discrepancy_case_id, resolution_type, quantity, "
                + "recorded_by, occurred_at) VALUES (?, 'LATE_DELIVERY_RECEIVED', 1, ?, now())", shortage,
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
        legacyDecision(order, brake, "RECEIVED_BACK_AT_SOURCE", decided, null);
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
    void aDecisionFromBeforeStillBlocksClosingUntilItIsSettled() throws Exception {
        long order = shortOrder(3, 1);
        legacyDecision(order, brake, "RECEIVED_BACK_AT_SOURCE", 2, null);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(ORDERS + "/" + order
                        + "/closing-preview").header(HttpHeaders.AUTHORIZATION, signIn("owner")))
                .andExpect(jsonPath("$.blockedBy", org.hamcrest.Matchers.hasItem("MSG340")));

        long back = arriveReturn("bmcg", order, brake, 2).andExpect(status().isCreated()).andReturn().getResponse()
                .getHeader("Location").transform(location -> Long.parseLong(location.substring(location.lastIndexOf('/') + 1)));
        assign("bmcg", "INSPECTION", back, sourceStaff).andExpect(status().isOk());
        result("nvcg", order, back, brake, 2, 0).andExpect(status().isOk());
        confirm("bmcg", order, back).andExpect(status().isOk());
        mvc.perform(post(ORDERS + "/" + order + "/close").header(HttpHeaders.AUTHORIZATION, signIn("owner")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
    }

    /** C.2: a part's pending late-delivery decisions are consumed oldest first; one partial arrival splits one. */
    @Test
    void aLateArrivalConsumesThePartsPendingDecisionsOldestFirst() throws Exception {
        long order = shortOrder(4, 1);
        decide("bmlb", order, brake, "LATE_DELIVERY", 1, null).andExpect(status().isOk());
        decide("owner", order, brake, "LATE_DELIVERY", 2, null).andExpect(status().isOk());
        long late = inspected(order, brake, 2, 0);

        confirm("bmlb", order, late).andExpect(status().isOk());

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT e.quantity, e.goods_receipt_note_id, "
                + "e.confirmed_at IS NOT NULL AS done FROM discrepancy_resolution_event e "
                + "JOIN discrepancy_case c ON c.id = e.discrepancy_case_id "
                + "WHERE c.transfer_order_id = ? AND c.product_id = ? ORDER BY e.id", order, brake);
        assertThat(rows).hasSize(3);
        // The oldest decision is settled whole by the arrival; the younger one is reduced to what is still pending;
        // the record of the part the arrival split off is confirmed.
        assertThat(rows.get(0)).containsEntry("quantity", 1).containsEntry("goods_receipt_note_id", late)
                .containsEntry("done", true);
        assertThat(rows.get(1)).containsEntry("quantity", 1).containsEntry("done", false);
        assertThat(rows.get(1).get("goods_receipt_note_id")).isNull();
        assertThat(rows.get(2)).containsEntry("quantity", 1).containsEntry("goods_receipt_note_id", late)
                .containsEntry("done", true);
        assertThat(stock(dest, brake, "available_qty")).isEqualTo(3);
        assertThat(stock(dest, brake, "in_transit_qty")).isEqualTo(1);
    }

    /**
     * C.2: one late receipt covers two parts with pending decisions. Each part's decisions are consumed only by its own
     * line: brake's first decision settles whole and its second is split, chain's two settle whole.
     */
    @Test
    void aLateReceiptSettlesEachPartsOwnDecisions() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        lot(source, chain, 10, 50_000, null, "B");
        long order = dispatchedOrder(brake, 4, chain, 4);
        confirm("bmlb", order, inspected(order, brake, 1, 0, chain, 1, 0)).andExpect(status().isOk());
        decide("bmlb", order, brake, "LATE_DELIVERY", 1, null).andExpect(status().isOk());
        decide("bmlb", order, brake, "LATE_DELIVERY", 2, null).andExpect(status().isOk());
        decide("bmlb", order, chain, "LATE_DELIVERY", 1, null).andExpect(status().isOk());
        decide("bmlb", order, chain, "LATE_DELIVERY", 2, null).andExpect(status().isOk());

        long late = inspected(order, brake, 2, 0, chain, 3, 0);
        confirm("bmlb", order, late).andExpect(status().isOk());

        assertThat(eventsOfPart(order, brake)).containsExactly(
                Map.of("quantity", 1, "goods_receipt_note_id", late, "done", true),
                Map.of("quantity", 1, "done", false),
                Map.of("quantity", 1, "goods_receipt_note_id", late, "done", true));
        assertThat(eventsOfPart(order, chain)).containsExactly(
                Map.of("quantity", 1, "goods_receipt_note_id", late, "done", true),
                Map.of("quantity", 2, "goods_receipt_note_id", late, "done", true));
        assertThat(stock(dest, brake, "available_qty")).isEqualTo(3);
        assertThat(stock(dest, brake, "in_transit_qty")).isEqualTo(1);
        assertThat(stock(dest, chain, "available_qty")).isEqualTo(4);
        assertThat(stock(dest, chain, "in_transit_qty")).isZero();
    }

    /** The events of one part, oldest first, as {quantity, goods_receipt_note_id, done}; a null receipt is left out. */
    private List<Map<String, Object>> eventsOfPart(long order, long product) {
        return jdbc.queryForList("SELECT e.quantity, e.goods_receipt_note_id, e.confirmed_at IS NOT NULL AS done "
                + "FROM discrepancy_resolution_event e JOIN discrepancy_case c ON c.id = e.discrepancy_case_id "
                + "WHERE c.transfer_order_id = ? AND c.product_id = ? ORDER BY e.id", order, product).stream()
                .map(row -> {
                    Map<String, Object> view = new java.util.HashMap<>();
                    view.put("quantity", row.get("quantity"));
                    view.put("done", row.get("done"));
                    if (row.get("goods_receipt_note_id") != null) {
                        view.put("goods_receipt_note_id", row.get("goods_receipt_note_id"));
                    }
                    return view;
                }).toList();
    }

    /** C.1: closing returns each part's pending NO_REPLACEMENT decision once, on its own lots. */
    @Test
    void closingReturnsEachPartsNoReplacementOnce() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        lot(source, chain, 10, 50_000, null, "B");
        long order = dispatchedOrder(brake, 4, chain, 4);
        confirm("bmlb", order, inspected(order, brake, 1, 0, chain, 1, 0)).andExpect(status().isOk());
        decide("bmlb", order, brake, "NO_REPLACEMENT", 3, null).andExpect(status().isOk());
        decide("bmlb", order, chain, "NO_REPLACEMENT", 3, null).andExpect(status().isOk());

        mvc.perform(post(ORDERS + "/" + order + "/close").header(HttpHeaders.AUTHORIZATION, signIn("owner")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));

        assertThat(stock(source, brake, "available_qty")).isEqualTo(9);
        assertThat(stock(source, chain, "available_qty")).isEqualTo(9);
        assertThat(stock(dest, brake, "in_transit_qty")).isZero();
        assertThat(stock(dest, chain, "in_transit_qty")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM discrepancy_resolution_event e JOIN discrepancy_case c "
                + "ON c.id = e.discrepancy_case_id WHERE c.transfer_order_id = ? AND e.confirmed_at IS NULL",
                Long.class, order)).isZero();
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
