package fu.hl.g64.motorflow.transfer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
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

/** Report 5.1, function DispatchTransferOrder: TransferOrderService.dispatch, POST /api/v1/transfer-orders/{id}/dispatch. */
@Tag("report51")
class DispatchTransferOrderUnitTest extends TransferFlowTestData {

    @Test
    @DisplayName("UTC005 Normal: source Branch Manager confirms the prepared 5 units")
    void utc005() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = preparedOrder(brake, 5);

        dispatchAs(signIn("bmcg"), order, brake, 5).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));

        assertThat(stock(source, brake, "available_qty")).isEqualTo(5);
        assertThat(stock(dest, brake, "in_transit_qty")).isEqualTo(5);
        assertThat(orderStatus(order)).isEqualTo("IN_TRANSIT");
        assertThat(jdbc.queryForObject("SELECT dispatched_qty FROM transfer_order_line WHERE transfer_order_id = ?",
                Integer.class, order)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT dispatched_by FROM transfer_order WHERE id = ?", Long.class, order))
                .isEqualTo(sourceManager.getId());
        assertThat(dispatchMovements(order)).isEqualTo(2);
    }

    @Test
    @DisplayName("UTC006 Normal: confirming an order already dispatched changes nothing")
    void utc006() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = dispatchedOrder(brake, 5);

        dispatchAs(signIn("bmcg"), order, brake, 5).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));

        assertThat(stock(source, brake, "available_qty")).isEqualTo(5);
        assertThat(stock(dest, brake, "in_transit_qty")).isEqualTo(5);
        assertThat(dispatchMovements(order)).isEqualTo(2);
    }

    @Test
    @DisplayName("UTC007 Abnormal: transfer order 99999 does not exist")
    void utc007() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = preparedOrder(brake, 5);

        dispatchAs(signIn("bmcg"), 99999, brake, 5).andExpect(expectError(HttpStatus.NOT_FOUND, "MSG175"));

        assertNotDispatched(order);
    }

    @Test
    @DisplayName("UTC008 Abnormal: Branch Manager of the destination store")
    void utc008() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = preparedOrder(brake, 5);

        dispatchAs(signIn("bmlb"), order, brake, 5).andExpect(expectError(HttpStatus.FORBIDDEN, "MSG05"));

        assertNotDispatched(order);
    }

    @Test
    @DisplayName("UTC009 Abnormal: no preparation result has been submitted yet")
    void utc009() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = createOrder(brake, 5);

        dispatchAs(signIn("bmcg"), order, brake, 5).andExpect(expectError(HttpStatus.CONFLICT, "MSG207"));

        assertNotDispatched(order);
    }

    @Test
    @DisplayName("UTC010 Abnormal: dispatched quantity 4 differs from the prepared quantity 5")
    void utc010() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = preparedOrder(brake, 5);

        dispatchAs(signIn("bmcg"), order, brake, 4).andExpect(expectError(HttpStatus.BAD_REQUEST, "MSG208"));

        assertNotDispatched(order);
    }

    @Test
    @DisplayName("UTC011 Abnormal: P2 is left out of the dispatched quantities")
    void utc011() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        lot(source, chain, 10, 100_000, null, "B");
        long order = preparedOrder(brake, 5, chain, 3);

        dispatchAs(signIn("bmcg"), order, brake, 5).andExpect(expectError(HttpStatus.BAD_REQUEST, "MSG167"));

        assertNotDispatched(order);
    }

    @Test
    @DisplayName("UTC012 Boundary: nothing was prepared (P1 = 0)")
    void utc012() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = createOrder(brake, 5);
        assign("bmcg", "DISPATCH_PREPARATION", order, sourceStaff).andExpect(status().isOk());
        prepare("nvcg", order, brake, 0).andExpect(status().isOk());

        dispatchAs(signIn("bmcg"), order, brake, 0).andExpect(expectError(HttpStatus.BAD_REQUEST, "MSG209"));

        assertNotDispatched(order);
    }

    @Test
    @DisplayName("UTC013 Abnormal: not signed in")
    void utc013() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = preparedOrder(brake, 5);

        dispatchAs(NO_TOKEN, order, brake, 5).andExpect(expectError(HttpStatus.UNAUTHORIZED, "MSG06"));

        assertNotDispatched(order);
    }

    @Test
    @DisplayName("UTC014 Abnormal: Owner may not confirm a dispatch")
    void utc014() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = preparedOrder(brake, 5);

        dispatchAs(signIn("owner"), order, brake, 5).andExpect(expectError(HttpStatus.FORBIDDEN, "MSG05"));

        assertNotDispatched(order);
    }

    @Test
    @DisplayName("UTC015 Abnormal: concurrency, source Branch Manager clicks Dispatch twice at the same time")
    void utc015() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = preparedOrder(brake, 5);
        String token = signIn("bmcg");

        List<MvcResult> results = atTheSameTime(() -> dispatchAs(token, order, brake, 5).andReturn(),
                () -> dispatchAs(token, order, brake, 5).andReturn());

        assertThat(results).extracting(result -> result.getResponse().getStatus()).containsExactly(200, 200);
        assertThat(stock(source, brake, "available_qty")).isEqualTo(5);
        assertThat(stock(dest, brake, "in_transit_qty")).isEqualTo(5);
        assertThat(orderStatus(order)).isEqualTo("IN_TRANSIT");
        // One dispatch posts one pair of movements (out of the source, into transit at the destination).
        assertThat(dispatchMovements(order)).isEqualTo(2);
    }

    /** Created, given to the source Staff member who submitted the prepared quantities: [productId, preparedQty, ...]. */
    private long preparedOrder(long... lines) throws Exception {
        long order = createOrder(lines);
        assign("bmcg", "DISPATCH_PREPARATION", order, sourceStaff).andExpect(status().isOk());
        prepare("nvcg", order, lines).andExpect(status().isOk());
        return order;
    }

    private ResultActions dispatchAs(String token, long order, long... lines) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < lines.length; i += 2) {
            rows.add(Map.of("productId", lines[i], "dispatchedQty", lines[i + 1]));
        }
        return mvc.perform(as(token, post(ORDERS + "/" + order + "/dispatch"), json(Map.of("lines", rows))));
    }

    private String orderStatus(long order) {
        return jdbc.queryForObject("SELECT status FROM transfer_order WHERE id = ?", String.class, order);
    }

    private long dispatchMovements(long order) {
        return jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE movement_type = 'TRANSFER_DISPATCH' "
                + "AND transfer_order_id = ?", Long.class, order);
    }

    /** The order is still awaiting dispatch and the source still holds its 10 units of P1. */
    private void assertNotDispatched(long order) {
        assertThat(orderStatus(order)).isEqualTo("AWAITING_DISPATCH");
        assertThat(stock(source, brake, "available_qty")).isEqualTo(10);
        assertThat(stock(dest, brake, "in_transit_qty")).isZero();
        assertThat(dispatchMovements(order)).isZero();
    }
}
