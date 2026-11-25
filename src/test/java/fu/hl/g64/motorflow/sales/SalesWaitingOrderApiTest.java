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
import static org.hamcrest.Matchers.containsInAnyOrder;
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
    void declinedUnitsDoNotLiftTheMarginOfWhatIsSoldNow() throws Exception {
        createOrder(pad, 8);
        // BR-88 at 30%: oil sells at 85,000 on a 70,000 cost, pads at 100,000 on 60,000.
        jdbc.update("UPDATE pricing_policy SET rate = 30 WHERE policy_type = 'MINIMUM_MARGIN'");
        clearCaches();
        // No pad left to sell: the 10 pads are declined, only the 4 oils are sold (340,000 < 4 x 70,000 x 1.3).
        create(managerToken, order(null, customer, "RETAIL", null,
                List.of(line(oil, 4, 4, false), line(pad, 10, 10, false))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG172"));
        create(ownerToken, order(store, customer, "RETAIL", null, List.of(line(oil, 4, 4, false))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG173"));
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
    void whatTheCustomerDeclinedIsKeptAsACancelledOrderButIsNotAWaitingOrder() throws Exception {
        createOrder(pad, 3);
        // 7 wanted, 3 taken now, no waiting, 5 sellable: 3 sold, 4 declined and kept on a cancelled order.
        String body = create(managerToken, order(null, customer, "RETAIL", null, List.of(line(pad, 7, 3, false))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AWAITING_PICKING"))
                .andExpect(jsonPath("$.lines[0].requestedQty").value(3))
                .andExpect(jsonPath("$.split.waitingOrderId").value(nullValue()))
                .andExpect(jsonPath("$.split.declined[0].quantity").value(4))
                .andReturn().getResponse().getContentAsString();
        long sold = id(body);
        long declined = ((Number) JsonPath.read(body, "$.split.declinedOrderId")).longValue();
        assertThat(declined).isNotEqualTo(sold);
        detail(managerToken, declined)
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.splitFromOrderId").value((int) sold))
                .andExpect(jsonPath("$.lines[0].requestedQty").value(4));
        assertThat(jdbc.queryForObject("SELECT cancellation_reason FROM sales_order WHERE id = ?", String.class,
                declined)).isEqualTo("Khách không chờ hàng");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_type = 'SALES_ORDER' "
                + "AND entity_id = ? AND action = 'SALES_ORDER_CANCELLED'", Long.class, declined)).isEqualTo(1);
        // It holds nothing and nobody is told to pick it: 8 - 3 - 3 = 2.
        preview(lines(pad, 1)).andExpect(jsonPath("$.lines[0].sellableQty").value(2));
        // GVHD 24/11 (Q1): the customer did not wait, so it is not in "Đơn chưa đáp ứng đủ", whatever the state.
        unmetDemand().andExpect(jsonPath("$.items", hasSize(0)));
        unmetDemand("state", "CANCELLED").andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void aConfirmationNoLongerMovesTheRestToAWaitingOrder() throws Exception {
        // Góp ý UI 21/11: what the customer does not take is given up, even if an old client still asks to wait.
        long order = pickedOrder(pad, 5);
        confirmWaiting(order, 1, pad, 3).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.split").value(nullValue()));
        detail(managerToken, order)
                .andExpect(jsonPath("$.lines[0].requestedQty").value(5))
                .andExpect(jsonPath("$.lines[0].deliveredQty").value(3));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_order WHERE status = 'AWAITING_STOCK'",
                Long.class)).isZero();
        String audit = jdbc.queryForObject("SELECT new_data::text FROM audit_log WHERE entity_type = 'SALES_ORDER' "
                + "AND entity_id = ? AND action = 'SALES_ORDER_CONFIRMED'", String.class, order);
        assertThat(audit).doesNotContain("waiting_order_id").doesNotContain("requested_before");
    }

    @Test
    void aWaitingOrderIsUnmetDemandUntilItIsReleased() throws Exception {
        long first = createOrder(pad, 8);
        long waiting = id(create(managerToken, order(null, customer, "RETAIL", null, lines(pad, 3)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("AWAITING_STOCK"))
                .andReturn().getResponse().getContentAsString());
        unmetDemand()
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].orderId").value((int) waiting))
                .andExpect(jsonPath("$.items[0].orderStatus").value("AWAITING_STOCK"))
                .andExpect(jsonPath("$.items[0].state").value("WAITING"))
                .andExpect(jsonPath("$.items[0].occurredAt").exists())
                .andExpect(jsonPath("$.items[0].deliveredQty").value(0))
                .andExpect(jsonPath("$.items[0].missingQty").value(3));

        // 2 of the 3 are released on their own order: only the 1 still waiting is unmet, counted once.
        update(managerToken, first, 1, order(null, customer, "RETAIL", null, lines(pad, 6))).andExpect(status().isOk());
        long sold = id(release(waiting, lineOf(waiting), 3).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        unmetDemand()
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].orderId").value((int) waiting))
                .andExpect(jsonPath("$.items[0].missingQty").value(1));

        // Released in full, it goes to picking and leaves the waiting list. GVHD 24/11: the cancelled first order was
        // never a waiting order, so it is not listed; the two released parts are under RELEASED.
        cancel(first).andExpect(status().isOk());
        release(waiting, lineOf(waiting), 1).andExpect(status().isOk());
        unmetDemand().andExpect(jsonPath("$.items", hasSize(0)));
        unmetDemand("state", "RELEASED", "state", "CANCELLED")
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[*].orderId", containsInAnyOrder((int) waiting, (int) sold)))
                .andExpect(jsonPath("$.items[*].state", contains("RELEASED", "RELEASED")))
                .andExpect(jsonPath("$.items[*].missingQty", contains(0, 0)));
    }

    @Test
    void aWalkInCustomerCanBuyOnlyWhatIsInStock() throws Exception {
        createOrder(pad, 6);
        // Góp ý UI 21/11: without a profile nothing may wait, MSG332 with the figures.
        create(managerToken, order(null, null, "RETAIL", null, lines(pad, 3)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG332"))
                .andExpect(jsonPath("$.message").value("Khách lẻ chỉ mua được số đang có: MA-PHANH-01 cần 3, "
                        + "còn 2. Vui lòng tạo hồ sơ khách hàng để chờ phần còn lại."));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_order", Long.class)).isEqualTo(1);
        // Review R-03: nor buy more than is in stock by leaving the rest as declined.
        create(managerToken, order(null, null, "RETAIL", null, List.of(line(pad, 3, 2, false))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG332"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_order", Long.class)).isEqualTo(1);
        // Review R-01: what is in stock is taken in full now, whatever nowQty says.
        create(managerToken, order(null, null, "RETAIL", null, List.of(line(pad, 2, 1, true))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("AWAITING_PICKING"))
                .andExpect(jsonPath("$.lines[0].requestedQty").value(2))
                .andExpect(jsonPath("$.split").value(nullValue()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_order", Long.class)).isEqualTo(2);
        // A customer with a profile still waits for the rest.
        create(managerToken, order(null, customer, "RETAIL", null, lines(pad, 1)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("AWAITING_STOCK"));
    }

    @Test
    void theCustomerWaitsForWhatTheWarehouseCouldNotPick() throws Exception {
        // GVHD 24/11 (A6): 5 asked for, only 3 picked (2 damaged on the shelf); the customer waits for the 2.
        long order = createOrder(pad, 5);
        assign(managerToken, "PICKING", order, staff).andExpect(status().isOk());
        pick(staffToken, order, 1, pad, 3, "Hàng hỏng").andExpect(status().isOk());
        String body = confirmShort(order, 1, pad, 3, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.lines[0].deliveredQty").value(3))
                .andExpect(jsonPath("$.split.salesOrderId").value((int) order))
                .andReturn().getResponse().getContentAsString();
        long waiting = ((Number) JsonPath.read(body, "$.split.waitingOrderId")).longValue();
        detail(managerToken, waiting)
                .andExpect(jsonPath("$.status").value("AWAITING_STOCK"))
                .andExpect(jsonPath("$.waitedForStock").value(true))
                .andExpect(jsonPath("$.splitFromOrderId").value((int) order))
                .andExpect(jsonPath("$.customerId").value((int) customer))
                .andExpect(jsonPath("$.lines[0].requestedQty").value(2));
        unmetDemand()
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].orderId").value((int) waiting))
                .andExpect(jsonPath("$.items[0].missingQty").value(2));
        // Nobody is told to pick a waiting order.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM warehouse_task WHERE sales_order_id = ?", Long.class,
                waiting)).isZero();

        // Confirmed again: the short part already waits.
        confirmShort(order, 2, pad, 2, true)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG353"))
                .andExpect(jsonPath("$.message").value("Phần thiếu của đơn này đã chuyển sang đơn chờ #" + waiting
                        + ". Vui lòng xử lý trên đơn chờ đó."));
        // Without the box, as before: nothing waits.
        confirmShort(order, 2, pad, 2, false).andExpect(status().isOk())
                .andExpect(jsonPath("$.split").value(nullValue()));
    }

    @Test
    void aWalkInCustomerCannotWaitForTheShortPart() throws Exception {
        String body = create(managerToken, order(null, null, "RETAIL", null, lines(pad, 2)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long order = id(body);
        assign(managerToken, "PICKING", order, staff).andExpect(status().isOk());
        pick(staffToken, order, 1, pad, 1, "Hàng hỏng").andExpect(status().isOk());
        confirmShort(order, 1, pad, 1, true)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG351"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_order WHERE status = 'AWAITING_STOCK'",
                Long.class)).isZero();
    }

    @Test
    void aShortDeliveryWithoutWaitingIsNotAWaitingOrder() throws Exception {
        long order = pickedOrder(pad, 5);
        confirm(managerToken, order, 1, pad, 3).andExpect(status().isOk());
        detail(managerToken, order).andExpect(jsonPath("$.lines[0].requestedQty").value(5));
        pay(order, 300_000).andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true));
        // GVHD 24/11: a completed order that delivered less is no longer listed.
        unmetDemand().andExpect(jsonPath("$.items", hasSize(0)));
        unmetDemand("state", "RELEASED", "state", "CANCELLED").andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void releaseChecksThePricesOfWhatItReleasesOnly() throws Exception {
        long padHold = createOrder(pad, 8);
        long oilHold = createOrder(oil, 4);
        String body = create(managerToken, order(null, customer, "RETAIL", null, lines(pad, 3, oil, 2)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("AWAITING_STOCK"))
                .andReturn().getResponse().getContentAsString();
        long waiting = id(body);
        cancel(padHold).andExpect(status().isOk());
        cancel(oilHold).andExpect(status().isOk());
        // The retail price of the oil ends: it can no longer be released, the pads still can.
        jdbc.update("UPDATE pricing_policy SET status = 'SUPERSEDED' WHERE policy_type = 'RETAIL_PRICE' "
                + "AND product_id = ?", oil);
        long oilLine = jdbc.queryForObject("SELECT id FROM sales_order_line WHERE sales_order_id = ? "
                + "AND product_id = ?", Long.class, waiting, oil);
        long padLine = jdbc.queryForObject("SELECT id FROM sales_order_line WHERE sales_order_id = ? "
                + "AND product_id = ?", Long.class, waiting, pad);
        release(waiting, oilLine, 2).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG160"));
        release(waiting, padLine, 3).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_PICKING"))
                .andExpect(jsonPath("$.lines[0].productId").value((int) pad))
                .andExpect(jsonPath("$.split.waitingOrderId").value((int) waiting));
        detail(managerToken, waiting).andExpect(jsonPath("$.status").value("AWAITING_STOCK"))
                .andExpect(jsonPath("$.lines", hasSize(1)));
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

    /** A new order of one part, assigned to staff and picked in full. */
    private long pickedOrder(long product, int quantity) throws Exception {
        long order = createOrder(product, quantity);
        assign(managerToken, "PICKING", order, staff).andExpect(status().isOk());
        pick(staffToken, order, 1, product, quantity, null).andExpect(status().isOk());
        return order;
    }

    /** Confirms one line, delivering {@code delivered} and waiting for the rest ("mai chị đến lấy nốt"). */
    private ResultActions confirmWaiting(long order, int version, long product, int delivered) throws Exception {
        return mvc.perform(post("/api/v1/sales-orders/" + order + "/confirm")
                .header(HttpHeaders.AUTHORIZATION, managerToken).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", version, "customerAgreed", true, "lines", List.of(Map.of(
                        "productId", product, "deliveredQty", delivered, "waitForRest", true))))));
    }

    /** Confirms one line, delivering {@code delivered}; {@code waitForShort} for the part not picked (GVHD 24/11). */
    private ResultActions confirmShort(long order, int version, long product, int delivered, boolean waitForShort)
            throws Exception {
        return mvc.perform(post("/api/v1/sales-orders/" + order + "/confirm")
                .header(HttpHeaders.AUTHORIZATION, managerToken).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", version, "customerAgreed", true, "lines", List.of(Map.of(
                        "productId", product, "deliveredQty", delivered, "waitForShort", waitForShort))))));
    }

    private ResultActions unmetDemand(String... parameters) throws Exception {
        var request = get("/api/v1/unmet-demand").header(HttpHeaders.AUTHORIZATION, managerToken);
        for (int i = 0; i < parameters.length; i += 2) {
            request.param(parameters[i], parameters[i + 1]);
        }
        return mvc.perform(request);
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
