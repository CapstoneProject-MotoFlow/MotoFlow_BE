package fu.hl.g64.motorflow.sales;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.inventory.job.LowStockWarningJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GVHD 20/11 (PR 1): an open sales order holds its goods; what the store cannot sell now waits on a waiting order
 * (AWAITING_STOCK), released to picking in part or in full. The store has 8 sellable pads (2 of 10 expired) and 4 oils.
 */
class SalesWaitingOrderApiTest extends SalesOrderTestData {

    @Autowired
    private LowStockWarningJob lowStock;

    @Test
    void anOpenOrderHoldsItsGoodsAndWhatCannotBeSoldWaits() throws Exception {
        long first = createOrder(pad, 8);
        preview(lines(pad, 3))
                .andExpect(jsonPath("$.lines[0].sellableQty").value(0))
                .andExpect(jsonPath("$.lines[0].heldByOthersQty").value(8))
                .andExpect(jsonPath("$.lines[0].suggestedNowQty").value(0));

        // Nothing can be sold now: the order itself waits, holds nothing and is not picked.
        String body = create(managerToken, order(null, customer, "RETAIL", null, lines(pad, 3)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AWAITING_STOCK"))
                .andExpect(jsonPath("$.split.salesOrderId").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        long waiting = id(body);
        assertThat((Integer) JsonPath.read(body, "$.split.waitingOrderId")).isEqualTo((int) waiting);
        list(managerToken, "view", "WAITING")
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].nextAction").value("RELEASE"));
        assign(managerToken, "PICKING", waiting, staff)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG308"));
        release(waiting, lineOf(waiting), 3)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG171"))
                .andExpect(jsonPath("$.message").value("Không đủ hàng còn hạn tại cửa hàng để giao: "
                        + "MA-PHANH-01 cần 3, còn 0."));
        release(first, lineOf(first), 1)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG307"));

        // The first order is cut to 6: 2 pads are free, so 2 of the 3 are released on a new order.
        update(managerToken, first, 1, order(null, customer, "RETAIL", null, lines(pad, 6))).andExpect(status().isOk());
        String split = release(waiting, lineOf(waiting), 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_PICKING"))
                .andExpect(jsonPath("$.splitFromOrderId").value((int) waiting))
                .andExpect(jsonPath("$.lines[0].requestedQty").value(2))
                .andExpect(jsonPath("$.split.waitingOrderId").value((int) waiting))
                .andReturn().getResponse().getContentAsString();
        long released = id(split);
        detail(managerToken, waiting)
                .andExpect(jsonPath("$.status").value("AWAITING_STOCK"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.lines[0].requestedQty").value(1));

        // Cancelling the first order frees its pads: the rest of the waiting order goes to picking, same number.
        cancel(first).andExpect(status().isOk());
        release(waiting, lineOf(waiting), 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) waiting))
                .andExpect(jsonPath("$.status").value("AWAITING_PICKING"))
                .andExpect(jsonPath("$.split.waitingOrderId").value(nullValue()));
        assertThat(released).isNotEqualTo(waiting);
    }

    @Test
    void theCustomerTakesSomeNowAndWaitsForOrDeclinesTheRest() throws Exception {
        createOrder(pad, 6);

        // 7 wanted now, 2 sellable: 2 sold, 5 wait.
        String body = create(managerToken, order(null, customer, "RETAIL", null, List.of(line(pad, 7, 7, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AWAITING_PICKING"))
                .andExpect(jsonPath("$.lines[0].requestedQty").value(2))
                .andReturn().getResponse().getContentAsString();
        long waiting = ((Number) JsonPath.read(body, "$.split.waitingOrderId")).longValue();
        detail(managerToken, waiting).andExpect(jsonPath("$.lines[0].requestedQty").value(5));
    }

    @Test
    void withoutWaitingTheUnitsNotTakenAreDeclined() throws Exception {
        // 7 wanted, 3 taken now, no waiting: 3 sold, 4 declined, no waiting order.
        create(managerToken, order(null, customer, "RETAIL", null, List.of(line(pad, 7, 3, false))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].requestedQty").value(3))
                .andExpect(jsonPath("$.split.waitingOrderId").value(nullValue()))
                .andExpect(jsonPath("$.split.declined[0].quantity").value(4));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_order WHERE status = 'AWAITING_STOCK'",
                Long.class)).isZero();

        create(managerToken, order(null, customer, "RETAIL", null, List.of(line(pad, 2, 3, null))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG305"))
                .andExpect(jsonPath("$.message").value("Dòng 1: số lấy ngay phải từ 0 đến số khách cần (2)."));
        create(managerToken, order(null, customer, "RETAIL", null, List.of(line(pad, 2, 0, false))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG306"));
        // Only 5 pads left: 6 taken now without waiting sells 5 and the customer gives up the 6th.
        create(managerToken, order(null, customer, "RETAIL", null, List.of(line(pad, 6, 6, false))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].requestedQty").value(5))
                .andExpect(jsonPath("$.split.declined[0].quantity").value(1));
        // Nothing left: nothing is sold and nothing waits, so the order is refused with the figures.
        create(managerToken, order(null, customer, "RETAIL", null, List.of(line(pad, 1, 1, false))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG171"))
                .andExpect(jsonPath("$.message").value("Không đủ hàng còn hạn tại cửa hàng để giao: "
                        + "MA-PHANH-01 cần 1, còn 0."));
    }

    @Test
    void whatIsNotDeliveredMayWaitAndIsNotUnmetTwice() throws Exception {
        long order = createOrder(pad, 5);
        assign(managerToken, "PICKING", order, staff).andExpect(status().isOk());
        pick(staffToken, order, 1, pad, 5, null).andExpect(status().isOk());
        // The customer takes 3 today and comes back for 2 ("mai chị đến lấy nốt").
        String body = mvc.perform(post("/api/v1/sales-orders/" + order + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, managerToken).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 1, "customerAgreed", true, "lines", List.of(Map.of(
                                "productId", pad, "deliveredQty", 3, "waitForRest", true))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andReturn().getResponse().getContentAsString();
        long waiting = ((Number) JsonPath.read(body, "$.split.waitingOrderId")).longValue();
        detail(managerToken, waiting)
                .andExpect(jsonPath("$.status").value("AWAITING_STOCK"))
                .andExpect(jsonPath("$.splitFromOrderId").value((int) order))
                .andExpect(jsonPath("$.lines[0].requestedQty").value(2));
        // A waiting order is neither picked nor confirmed until it is released.
        mvc.perform(post("/api/v1/sales-orders/" + waiting + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, managerToken).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 1, "customerAgreed", true, "lines", List.of(Map.of(
                                "productId", pad, "deliveredQty", 2))))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG308"));

        // Confirming again cannot deliver the 2 that went to the waiting order (kept in the audit log).
        mvc.perform(post("/api/v1/sales-orders/" + order + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, managerToken).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 2, "customerAgreed", true, "lines", List.of(Map.of(
                                "productId", pad, "deliveredQty", 5))))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG169"));

        // The confirmed order holds its 3 delivered pads: 8 - 3 = 5 for others.
        preview(lines(pad, 1)).andExpect(jsonPath("$.lines[0].sellableQty").value(5));

        // Paid and completed: its own holding does not stop it. Unmet shows the 2 once, on the completed order;
        // the waiting order is not unmet demand.
        pay(order, 300_000).andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true));
        mvc.perform(get("/api/v1/unmet-demand").header(HttpHeaders.AUTHORIZATION, managerToken))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].orderId").value((int) order))
                .andExpect(jsonPath("$.items[0].orderStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.items[0].missingQty").value(2));
    }

    @Test
    void whatOpenOrdersHoldIsNeitherTransferableNorAboveTheLowStockThreshold() throws Exception {
        jdbc.update("UPDATE stock_balance SET low_stock_threshold = 5 WHERE store_id = ? AND product_id = ?",
                store, pad);
        assertThat(lowStock.run()).isZero();

        createOrder(pad, 5);
        // 8 sellable pads, 5 held: 3 to transfer (UC-48), below the threshold of 5 (JOB-02).
        mvc.perform(get("/api/v1/inventory/availability?productIds={p}", pad)
                        .header(HttpHeaders.AUTHORIZATION, ownerToken))
                .andExpect(jsonPath("$[?(@.storeId == " + store + ")].sellableQty", contains(3)));
        assertThat(lowStock.run()).isEqualTo(1);
    }

    private static Map<String, Object> line(long product, int quantity, Integer nowQty, Boolean waitForRest) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("productId", product);
        line.put("quantity", quantity);
        line.put("nowQty", nowQty);
        line.put("waitForRest", waitForRest);
        return line;
    }

    private ResultActions preview(List<Map<String, Object>> lines) throws Exception {
        return mvc.perform(post("/api/v1/sales-orders/preview").header(HttpHeaders.AUTHORIZATION, managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(order(null, customer, "RETAIL", null, lines))));
    }

    private ResultActions release(long order, long line, int nowQty) throws Exception {
        return mvc.perform(post("/api/v1/sales-orders/" + order + "/release")
                .header(HttpHeaders.AUTHORIZATION, managerToken).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("lines", List.of(Map.of("lineId", line, "nowQty", nowQty))))));
    }

    private ResultActions cancel(long order) throws Exception {
        return mvc.perform(post("/api/v1/sales-orders/" + order + "/cancel")
                .header(HttpHeaders.AUTHORIZATION, managerToken).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("reason", "Khách đổi ý"))));
    }

    private ResultActions pay(long order, long amount) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount);
        body.put("paymentMethod", "CASH");
        body.put("verified", true);
        body.put("expectedPayable", moneyNow(order, "payableNow"));
        return mvc.perform(post("/api/v1/sales-orders/" + order + "/payments")
                .header(HttpHeaders.AUTHORIZATION, managerToken)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private long lineOf(long order) {
        return jdbc.queryForObject("SELECT id FROM sales_order_line WHERE sales_order_id = ? ORDER BY line_no LIMIT 1",
                Long.class, order);
    }

    private static long id(String body) {
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }
}
