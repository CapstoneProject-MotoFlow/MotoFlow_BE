package fu.hl.g64.motorflow.transfer;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static fu.hl.g64.motorflow.support.Report51Support.NO_TOKEN;
import static fu.hl.g64.motorflow.support.Report51Support.as;
import static fu.hl.g64.motorflow.support.Report51Support.atTheSameTime;
import static fu.hl.g64.motorflow.support.Report51Support.expectError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-77, TransferOrderService.close: UTC039-052. */
@Tag("report51")
class CloseTransferOrderUnitTest extends TransferFlowTestData {

    @Test
    @DisplayName("UTC039 Normal: Owner closes an order received in full")
    void utc039() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = received(5, 5);
        String owner = signIn("owner");

        mvc.perform(as(owner, post(closeUrl(order))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedBy.id").value(this.owner.getId()));

        assertThat(orderStatus(order)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT closed_by FROM transfer_order WHERE id = ?", Long.class, order))
                .isEqualTo(this.owner.getId());
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notification WHERE notification_type = 'TRANSFER_CLOSED'",
                Long.class)).containsExactlyInAnyOrder(sourceManager.getId(), otherSourceManager.getId(),
                destManager.getId());
        assertThat(closedAudits()).isEqualTo(1);
        assertThat(extraMovements()).isZero();
    }

    @Test
    @DisplayName("UTC040 Normal: central BM closes an order whose extra 1 was confirmed, the extra leaves the source")
    void utc040() throws Exception {
        long order = receivedWithConfirmedExtra(8, 1);
        assertThat(stock(source, brake, "available_qty")).isEqualTo(3);
        String centralManager = signIn("bmkt");

        mvc.perform(as(centralManager, post(closeUrl(order)))).andExpect(status().isOk());

        assertThat(stock(source, brake, "available_qty")).isEqualTo(2);
        assertThat(stock(dest, brake, "available_qty")).isEqualTo(6);
        assertThat(extraMovements()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE movement_type = "
                + "'TRANSFER_EXTRA_DISPATCH' AND transfer_order_id = ? AND store_id = ? AND quantity_change = -1",
                Long.class, order, source)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE movement_type = "
                + "'TRANSFER_EXTRA_DISPATCH' AND transfer_order_id = ? AND store_id = ? AND quantity_change = 1",
                Long.class, order, dest)).isEqualTo(1);
        assertThat(orderStatus(order)).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("UTC041 Normal: closing the last order of a replenishment request closes the request")
    void utc041() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long request = jdbc.queryForObject("INSERT INTO replenishment_request (requesting_store_id, created_by) "
                + "VALUES (?, ?) RETURNING id", Long.class, dest, destManager.getId());
        jdbc.update("INSERT INTO replenishment_request_line (replenishment_request_id, product_id, requested_qty) "
                + "VALUES (?, ?, 5)", request, brake);
        long order = linkedOrder(request, 5);
        assertThat(requestStatus(request)).isEqualTo("IN_PROGRESS");
        String owner = signIn("owner");

        mvc.perform(as(owner, post(closeUrl(order)))).andExpect(status().isOk());

        assertThat(requestStatus(request)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT closed_at IS NOT NULL FROM replenishment_request WHERE id = ?",
                Boolean.class, request)).isTrue();
    }

    @Test
    @DisplayName("UTC042 Boundary: closing an order that is already CLOSED posts nothing again")
    void utc042() throws Exception {
        long order = receivedWithConfirmedExtra(8, 1);
        String owner = signIn("owner");
        mvc.perform(as(owner, post(closeUrl(order)))).andExpect(status().isOk());
        long movements = extraMovements();
        long notifications = jdbc.queryForObject("SELECT count(*) FROM notification WHERE notification_type = "
                + "'TRANSFER_CLOSED'", Long.class);

        mvc.perform(as(owner, post(closeUrl(order))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        assertThat(extraMovements()).isEqualTo(movements);
        assertThat(stock(source, brake, "available_qty")).isEqualTo(2);
        assertThat(stock(dest, brake, "available_qty")).isEqualTo(6);
        assertThat(closedAudits()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE notification_type = 'TRANSFER_CLOSED'",
                Long.class)).isEqualTo(notifications);
    }

    @Test
    @DisplayName("UTC043 Abnormal: an order still IN_TRANSIT cannot be closed")
    void utc043() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = dispatchedOrder(brake, 5);
        String owner = signIn("owner");

        mvc.perform(as(owner, post(closeUrl(order)))).andExpect(expectError(HttpStatus.CONFLICT, "MSG29"));

        assertThat(orderStatus(order)).isEqualTo("IN_TRANSIT");
        assertThat(closedAudits()).isZero();
    }

    @Test
    @DisplayName("UTC044 Abnormal: 2 parts without a final result block closing")
    void utc044() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = received(5, 3);
        String owner = signIn("owner");

        mvc.perform(as(owner, post(closeUrl(order)))).andExpect(expectError(HttpStatus.CONFLICT, "MSG221"));

        assertNotClosed(order);
    }

    @Test
    @DisplayName("UTC045 Abnormal: an excess the source has not confirmed blocks closing")
    void utc045() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = received(5, 6);
        String owner = signIn("owner");

        mvc.perform(as(owner, post(closeUrl(order)))).andExpect(expectError(HttpStatus.CONFLICT, "MSG222"));

        assertNotClosed(order);
    }

    @Test
    @DisplayName("UTC046 Boundary: the source has 1 sellable but the confirmed extra is 2, nothing is posted")
    void utc046() throws Exception {
        long order = receivedWithConfirmedExtra(6, 2);
        assertThat(stock(source, brake, "available_qty")).isEqualTo(1);
        String owner = signIn("owner");

        mvc.perform(as(owner, post(closeUrl(order)))).andExpect(expectError(HttpStatus.CONFLICT, "MSG223"));

        assertNotClosed(order);
        assertThat(stock(source, brake, "available_qty")).isEqualTo(1);
    }

    @Test
    @DisplayName("UTC047 Boundary: the source has exactly the 2 sellable the confirmed extra needs")
    void utc047() throws Exception {
        long order = receivedWithConfirmedExtra(7, 2);
        assertThat(stock(source, brake, "available_qty")).isEqualTo(2);
        String owner = signIn("owner");

        mvc.perform(as(owner, post(closeUrl(order)))).andExpect(status().isOk());

        assertThat(stock(source, brake, "available_qty")).isZero();
        assertThat(stock(dest, brake, "available_qty")).isEqualTo(7);
        assertThat(orderStatus(order)).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("UTC048 Abnormal: a late receipt of 2 opened and not confirmed blocks closing")
    void utc048() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = received(5, 3);
        mvc.perform(as(signIn("owner"), post(ORDERS + "/" + order + "/discrepancy/decisions"),
                json(Map.of("productId", brake, "decision", "LATE_DELIVERY", "quantity", 2))))
                .andExpect(status().isOk());
        arrive("bmlb", order);
        String owner = signIn("owner");

        // The receipt is open but the 2 are still unresolved, and the close checks MSG221 first; the preview lists both.
        mvc.perform(as(owner, get(ORDERS + "/" + order + "/closing-preview")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blockedBy", contains("MSG221", "MSG225")));
        mvc.perform(as(owner, post(closeUrl(order)))).andExpect(expectError(HttpStatus.CONFLICT, "MSG221"));

        assertNotClosed(order);
    }

    @Test
    @DisplayName("UTC049 Abnormal: BM of the destination store is not a coordinator")
    void utc049() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = received(5, 5);
        String destinationManager = signIn("bmlb");

        mvc.perform(as(destinationManager, post(closeUrl(order))))
                .andExpect(expectError(HttpStatus.FORBIDDEN, "MSG05"));

        assertNotClosed(order);
    }

    @Test
    @DisplayName("UTC050 Abnormal: nobody signed in")
    void utc050() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = received(5, 5);

        mvc.perform(as(NO_TOKEN, post(closeUrl(order)))).andExpect(expectError(HttpStatus.UNAUTHORIZED, "MSG06"));

        assertNotClosed(order);
    }

    @Test
    @DisplayName("UTC051 Abnormal: Staff of the central warehouse cannot close")
    void utc051() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = received(5, 5);
        createUser("nvkt", Role.STAFF, central);
        String centralStaff = signIn("nvkt");

        mvc.perform(as(centralStaff, post(closeUrl(order)))).andExpect(expectError(HttpStatus.FORBIDDEN, "MSG05"));

        assertNotClosed(order);
    }

    @Test
    @DisplayName("UTC052 Abnormal: concurrency, Owner and central BM close the same order at the same time")
    void utc052() throws Exception {
        long order = receivedWithConfirmedExtra(8, 1);
        String owner = signIn("owner");
        String centralManager = signIn("bmkt");

        List<MvcResult> results = atTheSameTime(
                () -> mvc.perform(as(owner, post(closeUrl(order)))).andReturn(),
                () -> mvc.perform(as(centralManager, post(closeUrl(order)))).andReturn());

        assertThat(results).extracting(result -> result.getResponse().getStatus()).containsExactly(200, 200);
        assertThat(stock(source, brake, "available_qty")).isEqualTo(2);
        assertThat(stock(dest, brake, "available_qty")).isEqualTo(6);
        assertThat(extraMovements()).isEqualTo(2);
        assertThat(orderStatus(order)).isEqualTo("CLOSED");
        assertThat(closedAudits()).isEqualTo(1);
    }

    /** {@code sent} dispatched from the source, {@code arrived} of them confirmed at the destination as passed. */
    private long received(int sent, int arrived) throws Exception {
        long order = dispatchedOrder(brake, sent);
        confirm("bmlb", order, inspected(order, brake, arrived, 0)).andExpect(status().isOk());
        return order;
    }

    /**
     * 5 dispatched, 5 + {@code extra} received, the source confirmed the {@code extra}; the source lot held
     * {@code lotQuantity}, so {@code lotQuantity - 5} is still sellable there.
     */
    private long receivedWithConfirmedExtra(int lotQuantity, int extra) throws Exception {
        lot(source, brake, lotQuantity, 100_000, null, "A");
        long order = received(5, 5 + extra);
        mvc.perform(as(signIn("bmcg"), post(ORDERS + "/" + order + "/extra-dispatch"),
                json(Map.of("lines", List.of(Map.of("productId", brake, "extraQty", extra))))))
                .andExpect(status().isOk());
        return order;
    }

    /** A transfer of {@code quantity} brakes for the request, dispatched and fully received. */
    private long linkedOrder(long request, int quantity) throws Exception {
        mvc.perform(post(ORDERS).header(HttpHeaders.AUTHORIZATION, signIn("bmkt")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("replenishmentRequestId", request, "sourceStoreId", source,
                                "destStoreId", dest, "lines", List.of(Map.of("productId", brake,
                                        "plannedQty", quantity))))))
                .andExpect(status().isCreated());
        long order = jdbc.queryForObject("SELECT max(id) FROM transfer_order", Long.class);
        assign("bmcg", "DISPATCH_PREPARATION", order, sourceStaff).andExpect(status().isOk());
        prepare("nvcg", order, brake, quantity).andExpect(status().isOk());
        dispatch("bmcg", order, brake, quantity).andExpect(status().isOk());
        confirm("bmlb", order, inspected(order, brake, quantity, 0)).andExpect(status().isOk());
        return order;
    }

    private void assertNotClosed(long order) {
        assertThat(orderStatus(order)).isEqualTo("RECONCILING");
        assertThat(closedAudits()).isZero();
        assertThat(extraMovements()).isZero();
    }

    private String orderStatus(long order) {
        return jdbc.queryForObject("SELECT status FROM transfer_order WHERE id = ?", String.class, order);
    }

    private String requestStatus(long request) {
        return jdbc.queryForObject("SELECT status FROM replenishment_request WHERE id = ?", String.class, request);
    }

    private long closedAudits() {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'TRANSFER_ORDER_CLOSED'", Long.class);
    }

    private long extraMovements() {
        return jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE movement_type = 'TRANSFER_EXTRA_DISPATCH'",
                Long.class);
    }

    private static String closeUrl(long order) {
        return ORDERS + "/" + order + "/close";
    }
}
