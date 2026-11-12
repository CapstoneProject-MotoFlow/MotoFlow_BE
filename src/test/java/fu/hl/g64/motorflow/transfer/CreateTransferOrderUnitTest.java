package fu.hl.g64.motorflow.transfer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.ResultActions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static fu.hl.g64.motorflow.support.Report51Support.as;
import static fu.hl.g64.motorflow.support.Report51Support.expectError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Report 5.1, function CreateTransferOrder: TransferOrderService.create, POST /api/v1/transfer-orders (UC-71). */
@Tag("report51")
class CreateTransferOrderUnitTest extends TransferFlowTestData {

    @Test
    @DisplayName("UTC001 Normal: Owner transfers 5 of 10 sellable units to an active store")
    void utc001() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");

        create(signIn("owner"), source, dest, brake, 5).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AWAITING_DISPATCH"));

        assertOneOrderPlanning();
        assertThat(stock(source, brake, "available_qty")).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE movement_type = 'TRANSFER_DISPATCH'",
                Long.class)).isZero();
    }

    @Test
    @DisplayName("UTC002 Boundary: planned quantity exceeds the sellable quantity by one")
    void utc002() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");

        create(signIn("owner"), source, dest, brake, 11).andExpect(expectError(HttpStatus.BAD_REQUEST, "MSG201"));

        assertNothingCreated();
    }

    @Test
    @DisplayName("UTC003 Abnormal: source and destination are the same store")
    void utc003() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");

        create(signIn("owner"), dest, dest, brake, 5).andExpect(expectError(HttpStatus.BAD_REQUEST, "MSG200"));

        assertNothingCreated();
    }

    @Test
    @DisplayName("UTC004 Abnormal: Branch Manager of the destination store is not the central warehouse manager")
    void utc004() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");

        create(signIn("bmlb"), source, dest, brake, 5).andExpect(expectError(HttpStatus.FORBIDDEN, "MSG05"));

        assertNothingCreated();
    }

    private ResultActions create(String token, long from, long to, long product, int plannedQty) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("replenishmentRequestId", null);
        body.put("sourceStoreId", from);
        body.put("destStoreId", to);
        body.put("lines", List.of(Map.of("productId", product, "plannedQty", plannedQty)));
        return mvc.perform(as(token, post(ORDERS), json(body)));
    }

    private void assertOneOrderPlanning() {
        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM transfer_order");
        assertThat(order).containsEntry("source_store_id", source).containsEntry("dest_store_id", dest)
                .containsEntry("status", "AWAITING_DISPATCH").containsEntry("created_by", owner.getId());
        assertThat(order.get("replenishment_request_id")).isNull();
        Map<String, Object> line = jdbc.queryForMap("SELECT * FROM transfer_order_line WHERE transfer_order_id = ?",
                order.get("id"));
        assertThat(line).containsEntry("product_id", brake).containsEntry("planned_qty", 5);
        assertThat(line.get("prepared_qty")).isNull();
    }

    private void assertNothingCreated() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_order", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_order_line", Long.class)).isZero();
    }
}
