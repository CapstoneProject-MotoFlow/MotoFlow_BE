package fu.hl.g64.motorflow.sales;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.inventory.dto.NewLotPosting;
import fu.hl.g64.motorflow.inventory.entity.StockMovement.SourceRef;
import fu.hl.g64.motorflow.inventory.entity.enums.LotSourceType;
import fu.hl.g64.motorflow.inventory.entity.enums.MovementType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC-59 View Unmet Demand, GVHD 24/11: only orders that waited for stock, in 4 states. The store has 8 sellable pads
 * and 4 oils.
 */
class UnmetDemandApiTest extends SalesOrderTestData {

    @Test
    void listsOnlyOrdersThatWaitedForStock() throws Exception {
        createOrder(pad, 8);
        // A completed order that delivered less, an ordinary cancelled order and the part a customer declined to
        // wait for are not waiting orders.
        long cancelled = createOrder(oil, 1);
        cancel(cancelled, "Khách mua ở nơi khác").andExpect(status().isOk());
        create(managerToken, order(null, customer, "RETAIL", null, List.of(line(oil, 5, 3, false))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.split.declined[0].quantity").value(2));
        long waiting = waitingOrder(lines(pad, 2));

        unmetDemand(managerToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].orderId").value(waiting))
                .andExpect(jsonPath("$.items[0].orderStatus").value("AWAITING_STOCK"))
                .andExpect(jsonPath("$.items[0].state").value("WAITING"))
                .andExpect(jsonPath("$.items[0].occurredAt", notNullValue()))
                .andExpect(jsonPath("$.items[0].storeName").value("Gia Lâm"))
                .andExpect(jsonPath("$.items[0].customerName").value("Anh Tuấn"))
                .andExpect(jsonPath("$.items[0].customerPhone").value("0912345678"))
                .andExpect(jsonPath("$.items[0].lineId", notNullValue()))
                .andExpect(jsonPath("$.items[0].sku").value("MA-PHANH-01"))
                .andExpect(jsonPath("$.items[0].partName").value("Má phanh trước"))
                .andExpect(jsonPath("$.items[0].baseUnit").value("cái"))
                .andExpect(jsonPath("$.items[0].requestedQty").value(2))
                .andExpect(jsonPath("$.items[0].deliveredQty").value(0))
                .andExpect(jsonPath("$.items[0].missingQty").value(2))
                .andExpect(jsonPath("$.items[0].sellableQty").value(0))
                .andExpect(jsonPath("$.items[0].readyQty").value(0))
                .andExpect(jsonPath("$.items[0].orderReady").value(false))
                .andExpect(jsonPath("$.items[0].overdue").value(false));
        unmetDemand(managerToken, "state", "CANCELLED").andExpect(jsonPath("$.items", hasSize(0)));
        unmetDemand(managerToken, "state", "RELEASED").andExpect(jsonPath("$.items", hasSize(0)));

        unmetDemand(managerToken, "q", "anh tuan").andExpect(jsonPath("$.items", hasSize(1)));
        unmetDemand(managerToken, "q", "0912345").andExpect(jsonPath("$.items", hasSize(1)));
        unmetDemand(managerToken, "q", " ma phanh ").andExpect(jsonPath("$.items", hasSize(1)));
        unmetDemand(managerToken, "q", "nhot").andExpect(jsonPath("$.items", hasSize(0)));
        unmetDemand(managerToken, "productId", String.valueOf(oil)).andExpect(jsonPath("$.items", hasSize(0)));
        unmetDemand(managerToken, "customerId", String.valueOf(customer + 100))
                .andExpect(jsonPath("$.items", hasSize(0))).andExpect(jsonPath("$.totalItems").value(0));
        unmetDemand(managerToken, "from", TODAY.plusDays(1).toString()).andExpect(jsonPath("$.items", hasSize(0)));
        unmetDemand(managerToken, "from", TODAY.toString(), "to", TODAY.toString())
                .andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void ownerSeesEveryStoresSellableAndReadyPerLine() throws Exception {
        // Store A (Gia Lâm): 8 pads in stock. An open order holds 3; an 8-pad order sells the 5 left and waits for 3.
        createOrder(pad, 3);
        create(managerToken, order(null, customer, "RETAIL", null, lines(pad, 8)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("AWAITING_PICKING"))
                .andExpect(jsonPath("$.split.waitingOrderId").isNumber());
        // Store B (Long Biên): an order for 3 pads waits while no pad is in stock; then 4 expired and 2 valid arrive.
        create(ownerToken, order(otherStore, customer, "RETAIL", null, lines(pad, 3)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("AWAITING_STOCK"));
        supplierLot(otherStore, pad, 4, 60_000, TODAY.minusDays(3), "B-EXP");
        supplierLot(otherStore, pad, 2, 60_000, LocalDate.of(2026, 12, 30), "B-OK");
        // Part Q waits at store A and is in stock nowhere.
        long filter = part("FILTER-01", "Lọc gió", id("SELECT min(id) FROM part_category"));
        price(filter, 40_000, TODAY.minusDays(12).atStartOfDay());
        waitingOrder(lines(filter, 1));

        unmetDemand(ownerToken, "state", "WAITING")
                .andExpect(jsonPath("$.items[?(@.storeId == " + store + " && @.productId == " + pad + ")]"
                        + ".sellableQty", contains(0)))
                .andExpect(jsonPath("$.items[?(@.storeId == " + store + " && @.productId == " + pad + ")]"
                        + ".readyQty", contains(0)))
                .andExpect(jsonPath("$.items[?(@.storeId == " + store + " && @.productId == " + pad + ")]"
                        + ".state", contains("WAITING")))
                .andExpect(jsonPath("$.items[?(@.storeId == " + otherStore + " && @.productId == " + pad + ")]"
                        + ".sellableQty", contains(2)))
                .andExpect(jsonPath("$.items[?(@.storeId == " + otherStore + " && @.productId == " + pad + ")]"
                        + ".readyQty", contains(2)))
                .andExpect(jsonPath("$.items[?(@.storeId == " + otherStore + " && @.productId == " + pad + ")]"
                        + ".state", contains("WAITING")))
                .andExpect(jsonPath("$.items[?(@.storeId == " + store + " && @.productId == " + filter + ")]"
                        + ".sellableQty", contains(0)))
                .andExpect(jsonPath("$.items[?(@.storeId == " + store + " && @.productId == " + filter + ")]"
                        + ".readyQty", contains(0)))
                .andExpect(jsonPath("$.items[?(@.storeId == " + store + " && @.productId == " + filter + ")]"
                        + ".state", contains("WAITING")))
                .andExpect(jsonPath("$.totalItems").value(3));
    }

    @Test
    void releasedAndCancelledLinesShowWhatTheirStoreSellsNowOnTwoStores() throws Exception {
        // Store A: all 8 pads held by an open order; a waiting order of 2 pads is cancelled.
        createOrder(pad, 8);
        long cancelled = waitingOrder(lines(pad, 2));
        cancel(cancelled, "Khách đổi ý").andExpect(status().isOk());
        // Store B: a waiting order of 3 pads; 4 expired and 2 valid pads arrive; the store releases 1 pad now.
        String body = create(ownerToken, order(otherStore, customer, "RETAIL", null, lines(pad, 3)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("AWAITING_STOCK"))
                .andReturn().getResponse().getContentAsString();
        long waitingB = ((Number) JsonPath.read(body, "$.id")).longValue();
        supplierLot(otherStore, pad, 4, 60_000, TODAY.minusDays(3), "B-EXP");
        supplierLot(otherStore, pad, 2, 60_000, LocalDate.of(2026, 12, 30), "B-OK");
        mvc.perform(post("/api/v1/sales-orders/" + waitingB + "/release")
                        .header(HttpHeaders.AUTHORIZATION, otherManagerToken).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("lines", List.of(Map.of("lineId", lineOf(waitingB), "nowQty", 1))))))
                .andExpect(status().isOk());

        unmetDemand(ownerToken, "state", "CANCELLED")
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].storeId").value((int) store))
                .andExpect(jsonPath("$.items[0].requestedQty").value(2))
                .andExpect(jsonPath("$.items[0].sellableQty").value(0));
        unmetDemand(ownerToken, "state", "RELEASED")
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].storeId").value((int) otherStore))
                .andExpect(jsonPath("$.items[0].requestedQty").value(1))
                .andExpect(jsonPath("$.items[0].sellableQty").value(1));
        // The rest of the order of B waits: 2 pads asked, 1 valid pad left in stock.
        unmetDemand(ownerToken, "state", "WAITING")
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].storeId").value((int) otherStore))
                .andExpect(jsonPath("$.items[0].sellableQty").value(1))
                .andExpect(jsonPath("$.items[0].readyQty").value(1));
    }

    @Test
    void aStoreWhoseOpenOrdersHoldMoreThanItStillSellsHasNothingSellable() throws Exception {
        // An open order holds all 8 pads; then the lot of 3 expiring on 01/12 expires: 8 are held of the 5 left.
        createOrder(pad, 8);
        waitingOrder(lines(pad, 2));
        clock.advance(Duration.ofDays(60));
        signInAll();

        unmetDemand(ownerToken, "state", "WAITING")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].sellableQty").value(0))
                .andExpect(jsonPath("$.items[0].readyQty").value(0));
    }

    @Test
    void stockIsSharedOutAmongWaitingOrdersOldestFirst() throws Exception {
        long hold = createOrder(pad, 8);
        long first = waitingOrder(lines(pad, 4));
        clock.advance(Duration.ofMinutes(1));
        long second = waitingOrder(lines(pad, 4));
        clock.advance(Duration.ofMinutes(1));
        long third = waitingOrder(lines(pad, 4));

        // 4 pads come free: only the oldest of the three orders of 4 is ready (BR-90).
        update(managerToken, hold, 1, order(null, customer, "RETAIL", null, lines(pad, 4))).andExpect(status().isOk());
        unmetDemand(managerToken)
                .andExpect(jsonPath("$.items[*].orderId", contains((int) first, (int) second, (int) third)))
                .andExpect(jsonPath("$.items[*].state", contains("READY", "WAITING", "WAITING")))
                .andExpect(jsonPath("$.items[*].readyQty", contains(4, 0, 0)))
                .andExpect(jsonPath("$.items[*].orderReady", contains(true, false, false)))
                .andExpect(jsonPath("$.items[*].sellableQty", contains(4, 4, 4)));
        unmetDemand(managerToken, "state", "READY")
                .andExpect(jsonPath("$.items[*].orderId", contains((int) first)));
        unmetDemand(managerToken, "state", "WAITING")
                .andExpect(jsonPath("$.items[*].orderId", contains((int) second, (int) third)));

        // 2 more: the second order can take 2 of its 4 now.
        update(managerToken, hold, 2, order(null, customer, "RETAIL", null, lines(pad, 2))).andExpect(status().isOk());
        unmetDemand(managerToken).andExpect(jsonPath("$.items[*].readyQty", contains(4, 2, 0)));
        // Review 25/11 (R-08): the lines of one order, whatever page they are on.
        unmetDemand(managerToken, "orderId", String.valueOf(second), "size", "1")
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].orderId").value((int) second))
                .andExpect(jsonPath("$.items[0].readyQty").value(2));

        // The first is released in full and holds its 4; the third is cancelled.
        release(first, lineOf(first), 4).andExpect(status().isOk());
        cancel(third, "Khách không lấy nữa").andExpect(status().isOk());
        unmetDemand(managerToken)
                .andExpect(jsonPath("$.items[*].orderId", contains((int) second)))
                .andExpect(jsonPath("$.items[0].readyQty").value(2));
        unmetDemand(managerToken, "state", "RELEASED")
                .andExpect(jsonPath("$.items[*].orderId", contains((int) first)))
                .andExpect(jsonPath("$.items[0].orderStatus").value("AWAITING_PICKING"))
                .andExpect(jsonPath("$.items[0].missingQty").value(0));
        unmetDemand(managerToken, "state", "CANCELLED")
                .andExpect(jsonPath("$.items[*].orderId", contains((int) third)))
                .andExpect(jsonPath("$.items[0].missingQty").value(4));
        unmetDemand(managerToken, "state", "WAITING", "state", "READY", "state", "RELEASED", "state", "CANCELLED",
                "size", "2", "page", "1")
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items[*].orderId", contains((int) first)));
    }

    @Test
    void anOrderIsReadyOnlyWhenEveryLineIsCovered() throws Exception {
        long padHold = createOrder(pad, 8);
        createOrder(oil, 4);
        long waiting = waitingOrder(lines(pad, 2, oil, 1));
        update(managerToken, padHold, 1, order(null, customer, "RETAIL", null, lines(pad, 6)))
                .andExpect(status().isOk());
        unmetDemand(managerToken)
                .andExpect(jsonPath("$.items[*].orderId", contains((int) waiting, (int) waiting)))
                .andExpect(jsonPath("$.items[*].state", contains("WAITING", "WAITING")))
                .andExpect(jsonPath("$.items[*].readyQty", contains(2, 0)));
    }

    @Test
    void aWaitingOrderCarriesTheDateTheStoreExpectsItsGoods() throws Exception {
        long hold = createOrder(pad, 8);
        Map<String, Object> early = order(null, customer, "RETAIL", null, lines(pad, 2));
        early.put("expectedReadyDate", TODAY.minusDays(1).toString());
        create(managerToken, early).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG349"));

        Map<String, Object> body = order(null, customer, "RETAIL", null, lines(pad, 2));
        body.put("expectedReadyDate", TODAY.plusDays(1).toString());
        long waiting = ((Number) JsonPath.read(create(managerToken, body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AWAITING_STOCK"))
                .andExpect(jsonPath("$.waitedForStock").value(true))
                .andExpect(jsonPath("$.expectedReadyDate").value(TODAY.plusDays(1).toString()))
                .andReturn().getResponse().getContentAsString(), "$.id")).longValue();
        unmetDemand(managerToken)
                .andExpect(jsonPath("$.items[0].expectedReadyDate").value(TODAY.plusDays(1).toString()))
                .andExpect(jsonPath("$.items[0].overdue").value(false));

        expectedDate(waiting, TODAY.minusDays(1).toString())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG349"));
        expectedDate(waiting, TODAY.toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedReadyDate").value(TODAY.toString()));
        expectedDate(hold, TODAY.toString())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG352"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log "
                + "WHERE entity_id = ? AND action = 'SALES_ORDER_EXPECTED_DATE_CHANGED'", Long.class, waiting))
                .isEqualTo(1);

        // The day passed and the goods did not come: overdue.
        clock.advance(Duration.ofDays(1));
        signInAll();
        unmetDemand(managerToken).andExpect(jsonPath("$.items[0].overdue").value(true));
        expectedDate(waiting, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedReadyDate").doesNotExist());
        unmetDemand(managerToken).andExpect(jsonPath("$.items[0].overdue").value(false));

        // Nothing waits: the date is ignored.
        Map<String, Object> inStock = order(null, customer, "RETAIL", null, lines(oil, 1));
        inStock.put("expectedReadyDate", TODAY.plusDays(5).toString());
        create(managerToken, inStock).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AWAITING_PICKING"))
                .andExpect(jsonPath("$.waitedForStock").value(false))
                .andExpect(jsonPath("$.expectedReadyDate").doesNotExist());
    }

    @Test
    void theStoreIsToldOnceWhenAWaitingOrderBecomesReady() throws Exception {
        long hold = createOrder(pad, 8);
        // Owner sells: the store's Branch Manager and Owner, the creator, are both told.
        String body = create(ownerToken, order(store, customer, "RETAIL", null, lines(pad, 4)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("AWAITING_STOCK"))
                .andReturn().getResponse().getContentAsString();
        long waiting = ((Number) JsonPath.read(body, "$.id")).longValue();
        assertThat(notifications(manager, "WAITING_ORDER_READY")).isZero();

        // 4 pads come free.
        update(managerToken, hold, 1, order(null, customer, "RETAIL", null, lines(pad, 4))).andExpect(status().isOk());
        assertThat(notifications(manager, "WAITING_ORDER_READY")).isEqualTo(1);
        assertThat(notifications(owner, "WAITING_ORDER_READY")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT message FROM notification WHERE recipient_id = ? "
                + "AND notification_type = 'WAITING_ORDER_READY'", String.class, manager.getId()))
                .isEqualTo("Đơn chờ #" + waiting + " của khách Anh Tuấn đã đủ hàng. Vui lòng gọi khách và chuyển "
                        + "đơn sang soạn.");

        // More goods arrive (a stock movement into AVAILABLE): still ready, not told again.
        lot(pad, 2, 60_000, null);
        assertThat(notifications(manager, "WAITING_ORDER_READY")).isEqualTo(1);

        // Another order takes the goods: no longer ready. When they are free again, the store is told again.
        long other = createOrder(pad, 6);
        assertThat(jdbc.queryForObject("SELECT ready_notified_at FROM sales_order WHERE id = ?", Object.class,
                waiting)).isNull();
        cancel(other, "Khách đổi ý").andExpect(status().isOk());
        assertThat(notifications(manager, "WAITING_ORDER_READY")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT ready_notified_at FROM sales_order WHERE id = ?", Object.class,
                waiting)).isNotNull();
    }

    @Test
    void showsReleasedAndCancelledOrdersOfTheLastThirtyDaysByDefault() throws Exception {
        createOrder(pad, 8);
        long waiting = waitingOrder(lines(pad, 2));
        cancel(waiting, "Khách đổi ý").andExpect(status().isOk());

        clock.advance(Duration.ofDays(29));
        signInAll();
        unmetDemand(ownerToken, "state", "CANCELLED").andExpect(jsonPath("$.items", hasSize(1)));
        clock.advance(Duration.ofDays(1));
        signInAll();
        unmetDemand(ownerToken, "state", "CANCELLED").andExpect(jsonPath("$.items", hasSize(0)));
        unmetDemand(ownerToken, "state", "CANCELLED", "from", TODAY.toString())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].requestedQty").value(2));
    }

    @Test
    void aBranchManagerSeesTheirOwnStoreOnly() throws Exception {
        createOrder(pad, 8);
        waitingOrder(lines(pad, 1));

        unmetDemand(ownerToken).andExpect(jsonPath("$.items", hasSize(1)));
        unmetDemand(ownerToken, "storeId", String.valueOf(store)).andExpect(jsonPath("$.items", hasSize(1)));
        unmetDemand(ownerToken, "storeId", String.valueOf(otherStore)).andExpect(jsonPath("$.items", hasSize(0)));
        unmetDemand(managerToken, "storeId", String.valueOf(store)).andExpect(jsonPath("$.items", hasSize(1)));
        unmetDemand(otherManagerToken).andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)));
        unmetDemand(otherManagerToken, "storeId", String.valueOf(store))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        unmetDemand(staffToken).andExpect(status().isForbidden());
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    /** A new order of a customer that nothing can be sold of now, so it waits as a whole. */
    private long waitingOrder(List<Map<String, Object>> lines) throws Exception {
        String body = create(managerToken, order(null, customer, "RETAIL", null, lines))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("AWAITING_STOCK"))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private static Map<String, Object> line(long product, int quantity, Integer nowQty, Boolean waitForRest) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("productId", product);
        line.put("quantity", quantity);
        line.put("nowQty", nowQty);
        line.put("waitForRest", waitForRest);
        return line;
    }

    private ResultActions unmetDemand(String token, String... parameters) throws Exception {
        var request = get("/api/v1/unmet-demand").header(HttpHeaders.AUTHORIZATION, token);
        for (int i = 0; i < parameters.length; i += 2) {
            request.param(parameters[i], parameters[i + 1]);
        }
        return mvc.perform(request);
    }

    private ResultActions expectedDate(long order, String date) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedReadyDate", date);
        return mvc.perform(put("/api/v1/sales-orders/" + order + "/expected-ready-date")
                .header(HttpHeaders.AUTHORIZATION, managerToken).contentType(MediaType.APPLICATION_JSON)
                .content(json(body)));
    }

    /** A supplier lot at {@code storeId}, received as the receiving flow posts it (the base helper posts to one store). */
    private void supplierLot(long storeId, long product, int quantity, long unitPrice, LocalDate expiry, String lotNo) {
        long vendor = id("SELECT id FROM supplier WHERE supplier_name = 'Honda'");
        long purchase = id("INSERT INTO purchase_order (supplier_id, store_id, created_by) VALUES (?, ?, ?) "
                + "RETURNING id", vendor, storeId, owner.getId());
        long receipt = id("INSERT INTO goods_receipt_note (receipt_type, purchase_order_id, delivery_document_no, "
                + "arrived_at) VALUES ('SUPPLIER_DELIVERY', ?, ?, now()) RETURNING id", purchase, "PX-" + lotNo);
        long line = id("INSERT INTO goods_receipt_note_line (goods_receipt_note_id, product_id, document_qty, "
                + "delivered_qty, passed_qty) VALUES (?, ?, ?, ?, ?) RETURNING id", receipt, product, quantity,
                quantity, quantity);
        tx.execute(status -> posting.receiveNewLot(new NewLotPosting(storeId, product, LotSourceType.RECEIPT, line,
                vendor, TODAY.minusDays(20), unitPrice, lotNo, null, expiry,
                Map.of(StockStatus.AVAILABLE, quantity), MovementType.RECEIPT_CONFIRMED,
                SourceRef.goodsReceiptNote(receipt), owner.getId(), null)));
    }

    private long lineOf(long order) {
        return jdbc.queryForObject("SELECT id FROM sales_order_line WHERE sales_order_id = ? ORDER BY line_no LIMIT 1",
                Long.class, order);
    }

    private ResultActions release(long order, long line, int nowQty) throws Exception {
        return mvc.perform(post("/api/v1/sales-orders/" + order + "/release")
                .header(HttpHeaders.AUTHORIZATION, managerToken).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("lines", List.of(Map.of("lineId", line, "nowQty", nowQty))))));
    }

    private ResultActions cancel(long order, String reason) throws Exception {
        return mvc.perform(post("/api/v1/sales-orders/" + order + "/cancel")
                .header(HttpHeaders.AUTHORIZATION, managerToken)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("reason", reason))));
    }
}
