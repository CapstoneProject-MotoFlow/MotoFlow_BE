package fu.hl.g64.motorflow.transfer;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static fu.hl.g64.motorflow.support.Report51Support.NO_TOKEN;
import static fu.hl.g64.motorflow.support.Report51Support.as;
import static fu.hl.g64.motorflow.support.Report51Support.atTheSameTime;
import static fu.hl.g64.motorflow.support.Report51Support.expectError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-76, TransferDiscrepancyService.decide: UTC026-038. */
@Tag("report51")
class DecideTransferShortageUnitTest extends TransferFlowTestData {

    private static final String DECIDED = "TRANSFER_DISCREPANCY_DECIDED";

    @Test
    @DisplayName("UTC026 Normal: Owner decides a late delivery, the destination BM is notified and the shortage stays open")
    void utc026() throws Exception {
        long order = shortOrder();
        String owner = signIn("owner");

        mvc.perform(as(owner, post(decisionsUrl(order)), body(brake, "LATE_DELIVERY", 2, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].unresolvedQty").value(2));

        assertThat(count("SELECT count(*) FROM audit_log WHERE action = '" + DECIDED + "' AND entity_id = " + order))
                .isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notification WHERE notification_type = ?", Long.class,
                DECIDED)).containsExactly(destManager.getId());
        assertThat(jdbc.queryForObject("SELECT status FROM discrepancy_case WHERE transfer_order_id = ?", String.class,
                order)).isEqualTo("OPEN");
        assertThat(resolutionEvents(order)).isEmpty();
    }

    @Test
    @DisplayName("UTC027 Normal: central BM decides a return, the decision is saved and both stores are notified")
    void utc027() throws Exception {
        long order = shortOrder();
        String centralManager = signIn("bmkt");

        mvc.perform(as(centralManager, post(decisionsUrl(order)),
                        body(brake, "RETURN_TO_SOURCE", 2, null, "Gui nham")))
                .andExpect(status().isOk());

        List<Map<String, Object>> events = resolutionEvents(order);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("resolution_type")).isEqualTo("RECEIVED_BACK_AT_SOURCE");
        assertThat(events.get(0).get("quantity")).isEqualTo(2);
        assertThat(events.get(0).get("goods_receipt_note_id")).isNull();
        assertThat(events.get(0).get("confirmed_at")).isNull();
        assertThat(jdbc.queryForObject("SELECT reason FROM audit_log WHERE action = ?", String.class, DECIDED))
                .isEqualTo("Gui nham");
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notification WHERE notification_type = ?", Long.class,
                DECIDED)).containsExactlyInAnyOrder(destManager.getId(), sourceManager.getId(),
                otherSourceManager.getId());
    }

    @Test
    @DisplayName("UTC028 Normal: Owner links a loss decision to the draft write-off adjustment")
    void utc028() throws Exception {
        long order = shortOrder();
        long adjustment = transitLoss(order, 2, "DRAFT");
        String owner = signIn("owner");

        mvc.perform(as(owner, post(decisionsUrl(order)), body(brake, "LOSS", 2, adjustment, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discrepancies[0].events[0].resolutionType").value("APPROVED_LOSS"))
                .andExpect(jsonPath("$.discrepancies[0].events[0].adjustmentRequestId").value(adjustment));

        List<Map<String, Object>> events = resolutionEvents(order);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("resolution_type")).isEqualTo("APPROVED_LOSS");
        assertThat(events.get(0).get("quantity")).isEqualTo(2);
        assertThat(events.get(0).get("adjustment_request_id")).isEqualTo(adjustment);
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = '" + DECIDED + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("UTC029 Boundary: a quantity above the 2 still uncovered is refused")
    void utc029() throws Exception {
        long order = shortOrder();
        String owner = signIn("owner");

        mvc.perform(as(owner, post(decisionsUrl(order)), body(brake, "LATE_DELIVERY", 3, null, null)))
                .andExpect(expectError(HttpStatus.BAD_REQUEST, "MSG217"));

        assertNothingDecided(order);
    }

    @Test
    @DisplayName("UTC030 Boundary: a return decided but not opened is released for the late delivery")
    void utc030() throws Exception {
        long order = shortOrder();
        String owner = signIn("owner");
        mvc.perform(as(owner, post(decisionsUrl(order)), body(brake, "RETURN_TO_SOURCE", 2, null, null)))
                .andExpect(status().isOk());
        assertThat(resolutionEvents(order)).hasSize(1);

        mvc.perform(as(owner, post(decisionsUrl(order)), body(brake, "LATE_DELIVERY", 2, null, null)))
                .andExpect(status().isOk());

        assertThat(resolutionEvents(order)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT new_data::text FROM audit_log WHERE action = ? ORDER BY id DESC LIMIT 1",
                String.class, DECIDED)).contains("\"released_return_qty\": 2");
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = '" + DECIDED + "'")).isEqualTo(2);
    }

    @Test
    @DisplayName("UTC031 Abnormal: an order still in transit does not accept a decision")
    void utc031() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = dispatchedOrder(brake, 5);
        String owner = signIn("owner");

        mvc.perform(as(owner, post(decisionsUrl(order)), body(brake, "LATE_DELIVERY", 2, null, null)))
                .andExpect(expectError(HttpStatus.CONFLICT, "MSG29"));

        assertThat(count("SELECT count(*) FROM audit_log WHERE action = '" + DECIDED + "'")).isZero();
    }

    @Test
    @DisplayName("UTC032 Abnormal: a part received in full has no open shortage case")
    void utc032() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        lot(source, chain, 10, 100_000, null, "B");
        long order = dispatchedOrder(brake, 5, chain, 2);
        confirm("bmlb", order, inspected(order, brake, 3, 0, chain, 2, 0)).andExpect(status().isOk());
        String owner = signIn("owner");

        mvc.perform(as(owner, post(decisionsUrl(order)), body(chain, "LATE_DELIVERY", 2, null, null)))
                .andExpect(expectError(HttpStatus.BAD_REQUEST, "MSG216"));

        assertNothingDecided(order);
    }

    @Test
    @DisplayName("UTC033 Abnormal: a loss decision needs the adjustment request")
    void utc033() throws Exception {
        long order = shortOrder();
        String owner = signIn("owner");

        mvc.perform(as(owner, post(decisionsUrl(order)), body(brake, "LOSS", 2, null, null)))
                .andExpect(expectError(HttpStatus.BAD_REQUEST, "MSG218"));

        assertNothingDecided(order);
    }

    @Test
    @DisplayName("UTC034 Abnormal: an adjustment request of another transfer is refused")
    void utc034() throws Exception {
        long order = shortOrder();
        long otherOrder = createOrder(brake, 1);
        long adjustment = transitLoss(otherOrder, 2, "PENDING");
        String owner = signIn("owner");

        mvc.perform(as(owner, post(decisionsUrl(order)), body(brake, "LOSS", 2, adjustment, null)))
                .andExpect(expectError(HttpStatus.BAD_REQUEST, "MSG219"));

        assertNothingDecided(order);
    }

    @Test
    @DisplayName("UTC035 Abnormal: BM of the destination store is not a coordinator")
    void utc035() throws Exception {
        long order = shortOrder();
        String destinationManager = signIn("bmlb");

        mvc.perform(as(destinationManager, post(decisionsUrl(order)), body(brake, "LATE_DELIVERY", 2, null, null)))
                .andExpect(expectError(HttpStatus.FORBIDDEN, "MSG05"));

        assertNothingDecided(order);
    }

    @Test
    @DisplayName("UTC036 Abnormal: nobody signed in")
    void utc036() throws Exception {
        long order = shortOrder();

        mvc.perform(as(NO_TOKEN, post(decisionsUrl(order)), body(brake, "LATE_DELIVERY", 2, null, null)))
                .andExpect(expectError(HttpStatus.UNAUTHORIZED, "MSG06"));

        assertNothingDecided(order);
    }

    @Test
    @DisplayName("UTC037 Abnormal: Staff of the central warehouse cannot decide")
    void utc037() throws Exception {
        long order = shortOrder();
        createUser("nvkt", Role.STAFF, central);
        String centralStaff = signIn("nvkt");

        mvc.perform(as(centralStaff, post(decisionsUrl(order)), body(brake, "LATE_DELIVERY", 2, null, null)))
                .andExpect(expectError(HttpStatus.FORBIDDEN, "MSG05"));

        assertNothingDecided(order);
    }

    @Test
    @DisplayName("UTC038 Abnormal: concurrency, Owner and central BM decide the same shortage at the same time")
    void utc038() throws Exception {
        long order = shortOrder();
        String owner = signIn("owner");
        String centralManager = signIn("bmkt");

        List<MvcResult> results = atTheSameTime(
                () -> mvc.perform(as(owner, post(decisionsUrl(order)), body(brake, "LATE_DELIVERY", 2, null, null)))
                        .andReturn(),
                () -> mvc.perform(as(centralManager, post(decisionsUrl(order)),
                        body(brake, "RETURN_TO_SOURCE", 2, null, null))).andReturn());

        // The order row is locked, so the two decisions are applied one after the other. A late delivery only writes
        // history and does not cover the quantity, so both succeed. The audit trail tells which one ran second: a late
        // delivery second released the pending return (no event left, released_return_qty 2 in its audit row); a
        // return second stands as the only event (one return of 2, no receipt yet).
        assertThat(results).extracting(result -> result.getResponse().getStatus()).containsExactly(200, 200);
        List<String> decisions = jdbc.queryForList("SELECT new_data->>'decision' FROM audit_log WHERE action = ? "
                + "ORDER BY id", String.class, DECIDED);
        assertThat(decisions).containsExactlyInAnyOrder("LATE_DELIVERY", "RETURN_TO_SOURCE");
        List<Map<String, Object>> events = resolutionEvents(order);
        if (decisions.get(1).equals("LATE_DELIVERY")) {
            assertThat(events).isEmpty();
            assertThat(jdbc.queryForObject("SELECT new_data->>'released_return_qty' FROM audit_log WHERE action = ? "
                    + "ORDER BY id DESC LIMIT 1", String.class, DECIDED)).isEqualTo("2");
        } else {
            assertThat(events).hasSize(1);
            assertThat(events.get(0).get("resolution_type")).isEqualTo("RECEIVED_BACK_AT_SOURCE");
            assertThat(events.get(0).get("quantity")).isEqualTo(2);
            assertThat(events.get(0).get("goods_receipt_note_id")).isNull();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM transfer_order WHERE id = ?", String.class, order))
                .isEqualTo("RECONCILING");
    }

    /** P1 (brake): 5 dispatched, 3 received and confirmed; the missing 2 are an open shortage case. */
    private long shortOrder() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = dispatchedOrder(brake, 5);
        confirm("bmlb", order, inspected(order, brake, 3, 0)).andExpect(status().isOk());
        return order;
    }

    /** A write-off of {@code quantity} brakes in transit at the destination, linked to {@code order}. */
    private long transitLoss(long order, int quantity, String adjustmentStatus) {
        long adjustment = jdbc.queryForObject("INSERT INTO adjustment_request (adjustment_type, store_id, "
                + "transfer_order_id, reason, status, requested_by) VALUES ('STOCK_LOSS', ?, ?, 'Mất khi vận chuyển', ?, "
                + "?) RETURNING id", Long.class, dest, order, adjustmentStatus, destManager.getId());
        jdbc.update("INSERT INTO adjustment_stock_line (adjustment_request_id, store_id, product_id, stock_status, "
                + "quantity_change) VALUES (?, ?, ?, 'IN_TRANSIT', ?)", adjustment, dest, brake, -quantity);
        return adjustment;
    }

    private void assertNothingDecided(long order) {
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = '" + DECIDED + "'")).isZero();
        assertThat(resolutionEvents(order)).isEmpty();
    }

    private List<Map<String, Object>> resolutionEvents(long order) {
        return jdbc.queryForList("SELECT e.* FROM discrepancy_resolution_event e JOIN discrepancy_case c "
                + "ON c.id = e.discrepancy_case_id WHERE c.transfer_order_id = ? ORDER BY e.id", order);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private static String decisionsUrl(long order) {
        return ORDERS + "/" + order + "/discrepancy/decisions";
    }

    private String body(long product, String decision, int quantity, Long adjustment, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("productId", product);
        body.put("decision", decision);
        body.put("quantity", quantity);
        body.put("adjustmentRequestId", adjustment);
        body.put("reason", reason);
        return json(body);
    }
}
