package fu.hl.g64.motorflow.transfer;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PM 23/11 (plan "sai lệch điều chuyển" bản 2): shortage, excess, wrong items, losses and a receiver's miscount of a
 * transfer. X is the brake ordered, Y the chain sent by mistake; the source is Cầu Giấy, the destination Long Biên.
 */
class TransferDiscrepancyRulesApiTest extends TransferFlowTestData {

    /** (1) 3 X + 2 Y for 5 X: short 2, wrong 2; a wrong item never counts as the part that did not come. */
    @Test
    void wrongItemsAreCountedApartFromTheShortage() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = dispatchedOrder(brake, 5);
        confirm("bmlb", order, received(order, 3, 2, 0)).andExpect(status().isOk());

        view("owner", "/" + order).andExpect(jsonPath("$.lines[0].receivedQty").value(3))
                .andExpect(jsonPath("$.lines[0].shortageQty").value(2))
                .andExpect(jsonPath("$.lines[0].unresolvedQty").value(2))
                .andExpect(jsonPath("$.lines[0].wrongItemQty").value(2))
                .andExpect(jsonPath("$.lines[0].wrongPendingQty").value(2))
                .andExpect(jsonPath("$.discrepancies[0].shortQty").value(2))
                .andExpect(jsonPath("$.discrepancies[0].wrongItemQty").value(2));
        closingPreview(order).andExpect(jsonPath("$.lines[0].wrongPendingQty").value(2))
                .andExpect(jsonPath("$.lines[0].toLossQty").value(2))
                .andExpect(jsonPath("$.blockedBy", contains("MSG336", "MSG342")));
    }

    /** (2), (3) The receiving BM records the wrong items; only the Owner's confirmation moves Y, X is untouched. */
    @Test
    void theOwnerConfirmsAWrongItemReceiptAndOnlyThePartReceivedMoves() throws Exception {
        long brakes = lot(source, brake, 10, 100_000, null, "A");
        long older = lot(source, chain, 2, 200_000, today.plusDays(30), "X1");
        long matching = lot(source, chain, 5, 210_000, today.plusDays(90), "X2");
        long order = dispatchedOrder(brake, 5);
        confirm("bmlb", order, received(order, 3, 2, 0)).andExpect(status().isOk());

        record("bmlb", order, chain, 2, today.plusDays(90)).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].wrongPendingQty").value(0))
                .andExpect(jsonPath("$.lines[0].wrongAwaitingOwnerQty").value(2))
                .andExpect(jsonPath("$.discrepancies[0].events[0].resolutionType").value("RECEIVED_AS_OTHER_PRODUCT"))
                .andExpect(jsonPath("$.discrepancies[0].events[0].receivedSku").value("XICH-XE"))
                .andExpect(jsonPath("$.discrepancies[0].events[0].status").value("AWAITING_OWNER"));
        assertThat(stock(dest, chain, "available_qty")).isZero();
        assertThat(stock(source, chain, "available_qty")).isEqualTo(7);
        assertThat(recipients("TRANSFER_WRONG_ITEM")).contains(owner.getId());

        long eventId = eventIdOf(pending().andExpect(status().isOk())
                .andExpect(jsonPath("$[0].transferOrderId").value(order))
                .andExpect(jsonPath("$[0].receivedSku").value("XICH-XE"))
                .andExpect(jsonPath("$[0].quantity").value(2))
                .andExpect(jsonPath("$[0].lots[0].lotId").value(matching))
                .andExpect(jsonPath("$[0].blockedCode").doesNotExist()));
        confirmWrongItem("bmlb", order, eventId).andExpect(status().isForbidden());
        confirmWrongItem("owner", order, eventId).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].wrongAwaitingOwnerQty").value(0))
                .andExpect(jsonPath("$.lines[0].shortageQty").value(2))
                .andExpect(jsonPath("$.discrepancies[0].events[0].status").value("CONFIRMED"));

        assertThat(lotStock(matching, source, "AVAILABLE")).isEqualTo(3);
        assertThat(lotStock(older, source, "AVAILABLE")).isEqualTo(2);
        assertThat(lotStock(matching, dest, "AVAILABLE")).isEqualTo(2);
        // X is not touched: the 2 short stay in transit, the source keeps what is left after the dispatch.
        assertThat(lotStock(brakes, source, "AVAILABLE")).isEqualTo(5);
        assertThat(stock(dest, brake, "in_transit_qty")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE movement_type = 'TRANSFER_WRONG_ITEM'",
                Long.class)).isEqualTo(2);
        pending().andExpect(jsonPath("$").isEmpty());
        confirmWrongItem("owner", order, eventId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG29"));
    }

    /** (4) What open sales orders hold at the source is never given away. */
    @Test
    void stockHeldForASalesOrderIsNotGiven() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        lot(source, chain, 5, 200_000, null, "X1");
        long sale = jdbc.queryForObject("INSERT INTO sales_order (store_id, status, price_type, created_by) "
                + "VALUES (?, 'AWAITING_PICKING', 'RETAIL', ?) RETURNING id", Long.class, source, owner.getId());
        jdbc.update("INSERT INTO sales_order_line (sales_order_id, line_no, product_id, requested_qty, reference_price, "
                + "actual_unit_price) VALUES (?, 1, ?, 4, 300000, 300000)", sale, chain);
        long order = dispatchedOrder(brake, 5);
        confirm("bmlb", order, received(order, 3, 2, 0)).andExpect(status().isOk());
        record("bmlb", order, chain, 2, null).andExpect(status().isOk());

        long eventId = eventIdOf(pending().andExpect(jsonPath("$[0].blockedCode").value("MSG339"))
                .andExpect(jsonPath("$[0].sourceSellableQty").value(1)));
        confirmWrongItem("owner", order, eventId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG339"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("còn 1, cần 2")));
        assertThat(stock(source, chain, "available_qty")).isEqualTo(5);
        assertThat(stock(dest, chain, "available_qty")).isZero();
    }

    /** (5) Not to be replaced: on closing the short X goes back to the source's available stock, the lots dispatched. */
    @Test
    void whatIsNotToBeReplacedGoesBackToTheSourceOnClosing() throws Exception {
        long soon = lot(source, brake, 3, 100_000, today.plusDays(30), "SOON");
        long later = lot(source, brake, 5, 100_000, today.plusDays(60), "LATER");
        long order = dispatchedOrder(brake, 5);
        confirm("bmlb", order, received(order, 3, 0, 0)).andExpect(status().isOk());

        decide("bmlb", order, "NO_REPLACEMENT", 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].noReplacementQty").value(2))
                .andExpect(jsonPath("$.lines[0].shortageQty").value(0))
                .andExpect(jsonPath("$.lines[0].unresolvedQty").value(0));
        closingPreview(order).andExpect(jsonPath("$.canClose").value(true))
                .andExpect(jsonPath("$.lines[0].toSourceQty").value(2))
                .andExpect(jsonPath("$.lines[0].toLossQty").value(0));
        close(order).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.lines[0].lossQty").value(0));

        assertThat(lotStock(soon, source, "AVAILABLE")).isZero();
        assertThat(lotStock(later, source, "AVAILABLE")).isEqualTo(5);
        assertThat(stock(dest, brake, "in_transit_qty")).isZero();
        assertThat(stock(dest, brake, "available_qty")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE movement_type = "
                + "'TRANSFER_SHORT_RETURNED'", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM discrepancy_resolution_event WHERE resolution_type = "
                + "'NO_REPLACEMENT' AND confirmed_at IS NOT NULL", Long.class)).isEqualTo(1);
    }

    /** (6) What nobody chose anything for is lost on closing; the Owner was told on the acknowledgement. */
    @Test
    void theShortPartNobodyDecidedOnIsLostOnClosing() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = dispatchedOrder(brake, 5);
        confirm("bmlb", order, received(order, 3, 0, 0)).andExpect(status().isOk());

        close(order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG336"));
        action("bmlb", order, "/discrepancy-acknowledgement").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT message FROM notification WHERE notification_type = "
                + "'TRANSFER_DISCREPANCY_ACKNOWLEDGED' AND recipient_id = ?", String.class, owner.getId()))
                .contains("Sẽ tính là mất khi đóng: MA-PHANH x2");
        close(order).andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].lossQty").value(2))
                .andExpect(jsonPath("$.lines[0].shortageQty").value(0));

        assertThat(stock(dest, brake, "in_transit_qty")).isZero();
        assertThat(stock(dest, brake, "available_qty")).isEqualTo(3);
        assertThat(stock(source, brake, "available_qty")).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT reason FROM stock_movement WHERE movement_type = 'ADJUSTMENT' "
                + "AND transfer_order_id = ?", String.class, order)).isEqualTo("Mất khi điều chuyển");
    }

    /** (7) 5 X + 1 Y: nothing short; the order closes once the wrong item's receipt is confirmed. */
    @Test
    void aWrongItemOnTopOfAFullDeliveryOnlyWaitsForItsReceipt() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        lot(source, chain, 3, 200_000, null, "X1");
        long order = dispatchedOrder(brake, 5);
        confirm("bmlb", order, received(order, 5, 1, 0)).andExpect(status().isOk());
        view("owner", "/" + order).andExpect(jsonPath("$.lines[0].shortageQty").value(0))
                .andExpect(jsonPath("$.lines[0].wrongItemQty").value(1));

        action("bmlb", order, "/discrepancy-acknowledgement").andExpect(status().isOk());
        close(order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG342"));
        record("bmlb", order, chain, 1, null).andExpect(status().isOk());
        close(order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG342"));
        confirmWrongItem("owner", order, eventIdOf(pending())).andExpect(status().isOk());
        close(order).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));

        assertThat(stock(dest, brake, "available_qty")).isEqualTo(5);
        assertThat(stock(dest, chain, "available_qty")).isEqualTo(1);
        assertThat(stock(source, chain, "available_qty")).isEqualTo(2);
    }

    /** (8) Excess is accepted on closing when the source has it, and blocks closing when it does not. */
    @Test
    void excessIsAcceptedOnClosingOrBlocksItWhenTheSourceIsShort() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = dispatchedOrder(brake, 5);
        confirm("bmlb", order, received(order, 6, 0, 0)).andExpect(status().isOk());
        closingPreview(order).andExpect(jsonPath("$.canClose").value(true))
                .andExpect(jsonPath("$.lines[0].excessToAcceptQty").value(1));
        close(order).andExpect(status().isOk());
        assertThat(stock(dest, brake, "available_qty")).isEqualTo(6);
        assertThat(stock(source, brake, "available_qty")).isEqualTo(4);

        long empty = dispatchedOrder(brake, 4);
        confirm("bmlb", empty, received(empty, 5, 0, 0)).andExpect(status().isOk());
        closingPreview(empty).andExpect(jsonPath("$.canClose").value(false))
                .andExpect(jsonPath("$.blockedBy", contains("MSG339")));
        close(empty).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG339"));
        assertThat(stock(dest, brake, "available_qty")).isEqualTo(10);
    }

    /** (9) The receiver miscounted: the destination gets what was dispatched, the source and the receipt stay. */
    @Test
    void theOwnerCorrectsALineTheReceiverMiscounted() throws Exception {
        long brakes = lot(source, brake, 10, 100_000, null, "A");
        lot(source, chain, 5, 200_000, null, "X1");
        long order = dispatchedOrder(brake, 5);
        long receipt = received(order, 3, 1, 1);
        confirm("bmlb", order, receipt).andExpect(status().isOk());
        record("bmlb", order, chain, 1, null).andExpect(status().isOk());
        long caseId = jdbc.queryForObject("SELECT id FROM discrepancy_case WHERE transfer_order_id = ?", Long.class,
                order);

        miscount("owner", order, caseId, "  ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG343"));
        miscount("bmlb", order, caseId, "Đếm nhầm").andExpect(status().isForbidden());
        miscount("owner", order, caseId, "Đếm nhầm").andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].receivedQty").value(5))
                .andExpect(jsonPath("$.lines[0].receiverMiscounted").value(true))
                .andExpect(jsonPath("$.lines[0].shortageQty").value(0))
                .andExpect(jsonPath("$.lines[0].damagedQty").value(0))
                .andExpect(jsonPath("$.lines[0].wrongItemQty").value(0))
                .andExpect(jsonPath("$.discrepancies[0].status").value("CLOSED"));

        assertThat(lotStock(brakes, dest, "AVAILABLE")).isEqualTo(5);
        assertThat(stock(dest, brake, "in_transit_qty")).isZero();
        assertThat(stock(source, brake, "available_qty")).isEqualTo(5);
        assertThat(stock(dest, chain, "available_qty")).isZero();
        assertThat(stock(source, chain, "available_qty")).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT status FROM discrepancy_resolution_event WHERE resolution_type = "
                + "'RECEIVED_AS_OTHER_PRODUCT'", String.class)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForMap("SELECT passed_qty, damaged_qty, wrong_item_qty FROM goods_receipt_note_line "
                + "WHERE goods_receipt_note_id = ?", receipt)).containsEntry("passed_qty", 3)
                .containsEntry("damaged_qty", 1).containsEntry("wrong_item_qty", 1);
        assertThat(recipients("TRANSFER_RECEIVER_MISCOUNT")).contains(destManager.getId(), sourceManager.getId());
        pending().andExpect(jsonPath("$").isEmpty());
        close(order).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        assertThat(stock(dest, brake, "available_qty")).isEqualTo(5);
    }

    /** (9) Once Y has moved on a confirmed wrong item receipt, the line can no longer be corrected. */
    @Test
    void aConfirmedWrongItemReceiptStopsTheCorrection() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        lot(source, chain, 5, 200_000, null, "X1");
        long order = dispatchedOrder(brake, 5);
        confirm("bmlb", order, received(order, 4, 1, 0)).andExpect(status().isOk());
        record("bmlb", order, chain, 1, null).andExpect(status().isOk());
        confirmWrongItem("owner", order, eventIdOf(pending())).andExpect(status().isOk());
        long caseId = jdbc.queryForObject("SELECT id FROM discrepancy_case WHERE transfer_order_id = ?", Long.class,
                order);

        miscount("owner", order, caseId, "Đếm nhầm").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG344"));
        assertThat(stock(dest, brake, "in_transit_qty")).isEqualTo(1);
    }

    /** (10), (11) Returns and losses are no longer decided; only the receiving store's BM or the Owner records. */
    @Test
    void whoMayDecideAndRecordAndWhatIsNoLongerOffered() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = dispatchedOrder(brake, 5);
        confirm("bmlb", order, received(order, 3, 2, 0)).andExpect(status().isOk());

        decide("owner", order, "RETURN_TO_SOURCE", 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG345"));
        decide("bmlb", order, "LOSS", 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG345"));
        decide("bmcg", order, "LATE_DELIVERY", 1).andExpect(status().isForbidden());
        record("bmcg", order, chain, 1, null).andExpect(status().isForbidden());
        record("nvlb", order, chain, 1, null).andExpect(status().isForbidden());
        record("bmlb", order, brake, 1, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG310"));
        record("bmlb", order, chain, 3, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG309"));
        mvc.perform(get(ORDERS + "/wrong-items/pending").header(HttpHeaders.AUTHORIZATION, signIn("bmlb")))
                .andExpect(status().isForbidden());
    }

    /** Arrives and is inspected: {@code passed} X good, {@code damaged} X damaged, {@code wrong} wrong items. */
    private long received(long order, int passed, int wrong, int damaged) throws Exception {
        long receipt = arrive("bmlb", order);
        assign("bmlb", "INSPECTION", receipt, destStaff).andExpect(status().isOk());
        mvc.perform(put(ORDERS + "/" + order + "/receipts/" + receipt + "/result")
                        .header(HttpHeaders.AUTHORIZATION, signIn("nvlb")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("inspectionNote", "Kiểm nhận có sai lệch.", "lines", List.of(Map.of(
                                "productId", brake, "passedQty", passed, "wrongItemQty", wrong,
                                "damagedQty", damaged))))))
                .andExpect(status().isOk());
        return receipt;
    }

    private ResultActions record(String username, long order, long received, int quantity, LocalDate expiry)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productId", brake);
        body.put("receivedProductId", received);
        body.put("quantity", quantity);
        if (expiry != null) {
            body.put("expiryDate", expiry.toString());
        }
        return mvc.perform(post(ORDERS + "/" + order + "/wrong-items").header(HttpHeaders.AUTHORIZATION,
                signIn(username)).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions decide(String username, long order, String decision, int quantity) throws Exception {
        return mvc.perform(post(ORDERS + "/" + order + "/discrepancy/decisions")
                .header(HttpHeaders.AUTHORIZATION, signIn(username)).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("productId", brake, "decision", decision, "quantity", quantity))));
    }

    private ResultActions miscount(String username, long order, long caseId, String reason) throws Exception {
        return mvc.perform(post(ORDERS + "/" + order + "/discrepancies/" + caseId + "/receiver-miscount")
                .header(HttpHeaders.AUTHORIZATION, signIn(username)).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("reason", reason))));
    }

    private ResultActions pending() throws Exception {
        return mvc.perform(get(ORDERS + "/wrong-items/pending").header(HttpHeaders.AUTHORIZATION, signIn("owner")));
    }

    private ResultActions confirmWrongItem(String username, long order, long eventId) throws Exception {
        return mvc.perform(post(ORDERS + "/" + order + "/wrong-items/" + eventId + "/confirm")
                .header(HttpHeaders.AUTHORIZATION, signIn(username)));
    }

    private ResultActions closingPreview(long order) throws Exception {
        return view("owner", "/" + order + "/closing-preview");
    }

    private ResultActions close(long order) throws Exception {
        return action("owner", order, "/close");
    }

    private ResultActions action(String username, long order, String action) throws Exception {
        return mvc.perform(post(ORDERS + "/" + order + action).header(HttpHeaders.AUTHORIZATION, signIn(username)));
    }

    private List<Long> recipients(String type) {
        return jdbc.queryForList("SELECT recipient_id FROM notification WHERE notification_type = ?", Long.class, type);
    }

    private static long eventIdOf(ResultActions pending) throws Exception {
        String body = pending.andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$[0].eventId")).longValue();
    }
}
