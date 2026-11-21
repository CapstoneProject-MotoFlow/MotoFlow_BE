package fu.hl.g64.motorflow.receiving;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-40 with BR-55: a PO bought for the shortage of a replenishment request (review of FE PR #31, R-01). */
class PurchaseOrderReplenishmentApiTest extends PurchaseOrderTestData {

    /**
     * Gia Lâm asks for 10 brake pads (2 already passed to purchase), 5 oil and 4 lamps; a transfer order from Long Biên
     * plans 6 pads and 4 lamps. Unallocated: 2 pads and 5 oil.
     */
    private long request(String status) {
        long request = id("INSERT INTO replenishment_request (requesting_store_id, status, created_by) "
                + "VALUES (?, ?, ?) RETURNING id", giaLam, status, manager.getId());
        jdbc.update("INSERT INTO replenishment_request_line (replenishment_request_id, product_id, requested_qty, "
                + "to_purchase_qty) VALUES (?, ?, 10, 2), (?, ?, 5, 0), (?, ?, 4, 0)",
                request, pad, request, oil, request, lamp);
        long transfer = id("INSERT INTO transfer_order (replenishment_request_id, source_store_id, dest_store_id, "
                + "created_by) VALUES (?, ?, ?, ?) RETURNING id", request, longBien, giaLam, owner.getId());
        jdbc.update("INSERT INTO transfer_order_line (transfer_order_id, product_id, planned_qty) "
                + "VALUES (?, ?, 6), (?, ?, 4)", transfer, pad, transfer, lamp);
        return request;
    }

    private Map<String, Object> purchase(long store, Long request, boolean includeUnallocated) {
        Map<String, Object> body = order(honda, store, null, null, line(pad, 4, 120_000), line(oil, 5, 85_000));
        body.put("replenishmentRequestId", request);
        body.put("includeUnallocated", includeUnallocated);
        return body;
    }

    private List<Integer> toPurchase(long request) {
        return jdbc.queryForList("SELECT to_purchase_qty FROM replenishment_request_line "
                + "WHERE replenishment_request_id = ? ORDER BY id", Integer.class, request);
    }

    @Test
    void thePoIsLinkedAndTheUnallocatedPartIsPassedToPurchase() throws Exception {
        long request = request("AWAITING_COORDINATION");

        create(ownerToken, purchase(giaLam, request, true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replenishmentRequestId").value(request));

        assertThat(toPurchase(request)).containsExactly(4, 5, 0);
        assertThat(jdbc.queryForObject("SELECT status FROM replenishment_request WHERE id = ?", String.class, request))
                .isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_type = 'REPLENISHMENT_REQUEST' "
                + "AND action = 'REPLENISHMENT_TO_PURCHASE_SET'", Long.class)).isEqualTo(1);
    }

    @Test
    void withoutIncludeUnallocatedOnlyTheLinkIsKept() throws Exception {
        long request = request("IN_PROGRESS");

        create(ownerToken, purchase(giaLam, request, false))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replenishmentRequestId").value(request));

        assertThat(toPurchase(request)).containsExactly(2, 0, 0);
    }

    @Test
    void aSecondPoIsRefusedUntilTheFirstIsCancelled() throws Exception {
        long request = request("IN_PROGRESS");
        long first = created(purchase(giaLam, request, true));

        create(ownerToken, purchase(giaLam, request, true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG261"))
                .andExpect(jsonPath("$.message")
                        .value("Yêu cầu bổ sung #" + request + " đã có đơn nhập hàng chưa hủy."));

        cancel(ownerToken, first, "Nhà cung cấp hết hàng").andExpect(status().isOk());
        create(ownerToken, purchase(giaLam, request, true)).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_order WHERE replenishment_request_id = ?",
                Long.class, request)).isEqualTo(2);
    }

    @Test
    void aTransferPlannedWhileThePoWasOpenRefusesThePoAndChangesNothing() throws Exception {
        long request = request("IN_PROGRESS");
        long transfer = id("INSERT INTO transfer_order (replenishment_request_id, source_store_id, dest_store_id, "
                + "created_by) VALUES (?, ?, ?, ?) RETURNING id", request, longBien, giaLam, owner.getId());
        jdbc.update("INSERT INTO transfer_order_line (transfer_order_id, product_id, planned_qty) VALUES (?, ?, 3)",
                transfer, oil);

        // The Owner's screen buys only 1 oil, but 2 are short now (5 requested, 3 on the transfer).
        Map<String, Object> body = purchase(giaLam, request, true);
        body.put("lines", List.of(line(pad, 4, 120_000), line(oil, 1, 85_000)));
        create(ownerToken, body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG262"))
                .andExpect(jsonPath("$.message").value("Yêu cầu bổ sung #" + request
                        + " vừa thay đổi nên số mua của phụ tùng NHOT-01 phải ít nhất 2. Vui lòng tải lại."));

        assertThat(toPurchase(request)).containsExactly(2, 0, 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_order", Long.class)).isZero();
    }

    @Test
    void thePoMayOnlyBuyPartsOfTheRequest() throws Exception {
        long request = request("IN_PROGRESS");
        Map<String, Object> body = purchase(giaLam, request, false);
        body.put("lines", List.of(line(pad, 4, 120_000), line(filter, 1, 50_000)));

        create(ownerToken, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG204"))
                .andExpect(jsonPath("$.message").value("Phụ tùng ở dòng 2 không có trong yêu cầu bổ sung."));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_order", Long.class)).isZero();
    }

    @Test
    void theRequestMustBeOpenExistAndBelongToTheReceivingWarehouse() throws Exception {
        long request = request("IN_PROGRESS");
        create(ownerToken, purchase(longBien, request, true))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG260"));

        long closed = request("CLOSED");
        create(ownerToken, purchase(giaLam, closed, true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG29"));

        create(ownerToken, purchase(giaLam, 999_999L, true))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG95"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_order", Long.class)).isZero();
        assertThat(toPurchase(request)).containsExactly(2, 0, 0);
    }

    /** Gia Lâm asks for 10 brake pads, nothing planned or passed to purchase yet. */
    private long padRequest() {
        long request = id("INSERT INTO replenishment_request (requesting_store_id, status, created_by) "
                + "VALUES (?, 'AWAITING_COORDINATION', ?) RETURNING id", giaLam, manager.getId());
        jdbc.update("INSERT INTO replenishment_request_line (replenishment_request_id, product_id, requested_qty, "
                + "to_purchase_qty) VALUES (?, ?, 10, 0)", request, pad);
        return request;
    }

    private Map<String, Object> padPurchase(long request, int quantity) {
        Map<String, Object> body = order(honda, giaLam, null, null, line(pad, quantity, 120_000));
        body.put("replenishmentRequestId", request);
        body.put("includeUnallocated", true);
        return body;
    }

    // GVHD 20/11: the PO may order more than the request is short of.
    @Test
    void aPoMayOrderMoreThanTheRequestNeeds() throws Exception {
        long request = padRequest();

        create(ownerToken, padPurchase(request, 20)).andExpect(status().isCreated());

        assertThat(jdbc.queryForList("SELECT ordered_qty FROM purchase_order_line", Integer.class)).containsExactly(20);
        assertThat(toPurchase(request)).containsExactly(10);
    }

    @Test
    void aPoMayNotOrderLessThanWhatIsToPurchase() throws Exception {
        long request = padRequest();

        create(ownerToken, padPurchase(request, 5))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG262"))
                .andExpect(jsonPath("$.message").value("Yêu cầu bổ sung #" + request
                        + " vừa thay đổi nên số mua của phụ tùng MA-PHANH-01 phải ít nhất 10. Vui lòng tải lại."));

        assertThat(toPurchase(request)).containsExactly(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_order", Long.class)).isZero();
    }

    @Test
    void theAllocationStillCannotExceedWhatIsNotOnATransfer() throws Exception {
        long request = request("IN_PROGRESS");

        // 10 requested, 6 on a transfer order: at most 4 can be passed to purchase, whatever the PO orders.
        mvc.perform(put("/api/v1/replenishment-requests/" + request + "/to-purchase")
                        .header(HttpHeaders.AUTHORIZATION, ownerToken).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("lines", List.of(Map.of("productId", pad, "toPurchaseQty", 5))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG205"));

        assertThat(toPurchase(request)).containsExactly(2, 0, 0);
    }

    @Test
    void theRequestClosesAfterAPoThatOrderedMoreIsFullyReceived() throws Exception {
        long request = padRequest();
        long order = created(padPurchase(request, 20));
        long receipt = delivery(order, "PX-020", TODAY.atTime(9, 0), "CONFIRMED", staff, manager);
        result(receipt, pad, 20, 20, 0, 0, 0, "NOT_APPLICABLE");
        jdbc.update("UPDATE purchase_order SET status = 'FULLY_RECEIVED' WHERE id = ?", order);
        // The request closes with its last transfer order; this one carries nothing the PO has not covered.
        long transfer = id("INSERT INTO transfer_order (replenishment_request_id, source_store_id, dest_store_id, "
                + "status, created_by) VALUES (?, ?, ?, 'RECONCILING', ?) RETURNING id", request, longBien, giaLam,
                owner.getId());

        mvc.perform(post("/api/v1/transfer-orders/" + transfer + "/close").header(HttpHeaders.AUTHORIZATION, ownerToken))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT status FROM replenishment_request WHERE id = ?", String.class, request))
                .isEqualTo("CLOSED");
        assertThat(toPurchase(request)).containsExactly(10);
    }
}
