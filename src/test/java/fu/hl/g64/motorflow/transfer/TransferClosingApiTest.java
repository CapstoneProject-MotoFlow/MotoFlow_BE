package fu.hl.g64.motorflow.transfer;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-77. */
class TransferClosingApiTest extends TransferFlowTestData {

    @Test
    void closingWithoutExcessMovesNoStock() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = received(3, 3);

        preview("owner", order).andExpect(status().isOk())
                .andExpect(jsonPath("$.canClose").value(true))
                .andExpect(jsonPath("$.blockedBy", empty()));
        close("bmkt", order).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedBy.id").isNumber());

        assertThat(extraMovements()).isZero();
        assertThat(stock(source, brake, "available_qty")).isEqualTo(7);
        assertThat(stock(dest, brake, "available_qty")).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notification WHERE notification_type = 'TRANSFER_CLOSED'",
                Long.class)).containsExactlyInAnyOrder(sourceManager.getId(), otherSourceManager.getId(),
                destManager.getId());
        close("owner", order).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'TRANSFER_ORDER_CLOSED'",
                Long.class)).isEqualTo(1);
        preview("owner", order).andExpect(jsonPath("$.canClose").value(false));
    }

    @Test
    void confirmedExcessMovesFromTheSourceOnce() throws Exception {
        long soon = lot(source, brake, 4, 100_000, today.plusDays(30), "SOON");
        long later = lot(source, brake, 10, 100_000, today.plusDays(300), "LATER");
        long order = received(3, 5);
        extra(order, 2);

        preview("bmkt", order).andExpect(status().isOk())
                .andExpect(jsonPath("$.canClose").value(true))
                .andExpect(jsonPath("$.lines[0].extraReceivedQty").value(2))
                .andExpect(jsonPath("$.lines[0].extraDispatchedQty").value(2))
                .andExpect(jsonPath("$.lines[0].sourceSellableQty").value(11));
        close("owner", order).andExpect(status().isOk());
        close("owner", order).andExpect(status().isOk());

        // FEFO: the dispatch took 3 of SOON, the excess takes its last 1 and 1 of LATER.
        assertThat(lotStock(soon, source, "AVAILABLE")).isZero();
        assertThat(lotStock(later, source, "AVAILABLE")).isEqualTo(9);
        assertThat(lotStock(soon, dest, "AVAILABLE")).isEqualTo(4);
        assertThat(lotStock(later, dest, "AVAILABLE")).isEqualTo(1);
        assertThat(stock(dest, brake, "available_qty")).isEqualTo(5);
        assertThat(extraMovements()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE movement_type = "
                + "'TRANSFER_EXTRA_DISPATCH' AND transfer_order_id = ?", Long.class, order)).isEqualTo(4);
    }

    @Test
    void partsWithoutAFinalResultBlockClosing() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = received(4, 1);

        preview("owner", order).andExpect(jsonPath("$.canClose").value(false))
                .andExpect(jsonPath("$.blockedBy", contains("MSG221")))
                .andExpect(jsonPath("$.lines[0].unresolvedQty").value(3));
        close("owner", order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG221"));
        assertThat(jdbc.queryForObject("SELECT status FROM transfer_order WHERE id = ?", String.class, order))
                .isEqualTo("RECONCILING");
    }

    @Test
    void excessNotConfirmedByTheSourceBlocksClosing() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = received(3, 4);

        close("owner", order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG222"));
        extra(order, 1);
        close("owner", order).andExpect(status().isOk());
    }

    @Test
    void sourceShortOfTheConfirmedExcessBlocksClosing() throws Exception {
        lot(source, brake, 3, 100_000, null, "A");
        long order = received(3, 5);
        extra(order, 2);

        preview("owner", order).andExpect(jsonPath("$.blockedBy", contains("MSG223")))
                .andExpect(jsonPath("$.lines[0].sourceSellableQty").value(0));
        close("owner", order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG223"));
        assertThat(extraMovements()).isZero();
    }

    @Test
    void theRequestClosesWithItsLastOrder() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long request = jdbc.queryForObject("INSERT INTO replenishment_request (requesting_store_id, created_by) "
                + "VALUES (?, ?) RETURNING id", Long.class, dest, destManager.getId());
        jdbc.update("INSERT INTO replenishment_request_line (replenishment_request_id, product_id, requested_qty, "
                + "to_purchase_qty) VALUES (?, ?, 5, 2)", request, brake);
        long first = linkedOrder(request, 1);
        long second = linkedOrder(request, 2);

        close("owner", first).andExpect(status().isOk());
        assertThat(requestStatus(request)).isEqualTo("IN_PROGRESS");
        close("owner", second).andExpect(status().isOk());
        assertThat(requestStatus(request)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT closed_at IS NOT NULL FROM replenishment_request WHERE id = ?",
                Boolean.class, request)).isTrue();
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notification WHERE notification_type = "
                + "'REPLENISHMENT_CLOSED'", Long.class)).containsExactly(destManager.getId());
    }

    @Test
    void aRequestWithPartsStillNeededStaysOpen() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long request = jdbc.queryForObject("INSERT INTO replenishment_request (requesting_store_id, created_by) "
                + "VALUES (?, ?) RETURNING id", Long.class, dest, destManager.getId());
        jdbc.update("INSERT INTO replenishment_request_line (replenishment_request_id, product_id, requested_qty) "
                + "VALUES (?, ?, 5)", request, brake);
        close("owner", linkedOrder(request, 3)).andExpect(status().isOk());
        assertThat(requestStatus(request)).isEqualTo("IN_PROGRESS");
    }

    @Test
    void onlyCoordinatorsCloseAReconcilingOrder() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long inTransit = dispatchedOrder(brake, 2);
        close("owner", inTransit).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG29"));

        long order = received(3, 3);
        close("bmcg", order).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        close("bmlb", order).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        close("nvlb", order).andExpect(status().isForbidden());
        preview("bmlb", order).andExpect(status().isForbidden());
        close("bmkt", 999_999).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG175"));
    }

    /** {@code sent} brakes dispatched, {@code arrived} of them confirmed at the destination as passed. */
    private long received(int sent, int arrived) throws Exception {
        long order = dispatchedOrder(brake, sent);
        confirm("bmlb", order, inspected(order, brake, arrived, 0)).andExpect(status().isOk());
        return order;
    }

    /** A transfer of {@code quantity} brakes for the request, dispatched and fully received. */
    private long linkedOrder(long request, int quantity) throws Exception {
        mvc.perform(post(ORDERS).header(HttpHeaders.AUTHORIZATION, signIn("bmkt"))
                        .contentType(MediaType.APPLICATION_JSON)
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

    private void extra(long order, int quantity) throws Exception {
        mvc.perform(post(ORDERS + "/" + order + "/extra-dispatch").header(HttpHeaders.AUTHORIZATION, signIn("bmcg"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("lines", List.of(Map.of("productId", brake, "extraQty", quantity))))))
                .andExpect(status().isOk());
    }

    private ResultActions preview(String username, long order) throws Exception {
        return mvc.perform(get(ORDERS + "/" + order + "/closing-preview")
                .header(HttpHeaders.AUTHORIZATION, signIn(username)));
    }

    private ResultActions close(String username, long order) throws Exception {
        return mvc.perform(post(ORDERS + "/" + order + "/close").header(HttpHeaders.AUTHORIZATION, signIn(username)));
    }

    private long extraMovements() {
        return jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE movement_type = 'TRANSFER_EXTRA_DISPATCH'",
                Long.class);
    }

    private String requestStatus(long request) {
        return jdbc.queryForObject("SELECT status FROM replenishment_request WHERE id = ?", String.class, request);
    }
}
