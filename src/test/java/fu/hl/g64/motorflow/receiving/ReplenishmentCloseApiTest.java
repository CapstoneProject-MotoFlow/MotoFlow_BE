package fu.hl.g64.motorflow.receiving;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-77 step 6 with UC-106: a replenishment request bought from purchasing closes when its PO is fully received. */
class ReplenishmentCloseApiTest extends GoodsReceiptTestData {

    /** A request of Gia Lâm for {@code requested} pads, of which {@code toPurchase} are passed to purchasing. */
    private long request(int requested, int toPurchase) {
        long request = id("INSERT INTO replenishment_request (requesting_store_id, status, created_by) "
                + "VALUES (?, 'IN_PROGRESS', ?) RETURNING id", giaLam, manager.getId());
        jdbc.update("INSERT INTO replenishment_request_line (replenishment_request_id, product_id, requested_qty, "
                + "to_purchase_qty) VALUES (?, ?, ?, ?)", request, pad, requested, toPurchase);
        return request;
    }

    /** A PO of {@code quantity} pads bought for the request, to Gia Lâm. */
    private long purchaseFor(long request, int quantity) throws Exception {
        Map<String, Object> body = order(honda, giaLam, TODAY, null, line(pad, quantity, 120_000));
        body.put("replenishmentRequestId", request);
        body.put("includeUnallocated", false);
        return created(body);
    }

    /** UC-106 and UC-43: the delivery of the whole PO is recorded and inspected, and the manager is ready to confirm. */
    private long inspected(long purchaseOrder, int quantity) throws Exception {
        String response = createReceipt(managerToken, purchaseOrder, deliveryNote("PX-" + purchaseOrder))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long grn = ((Number) JsonPath.read(response, "$.id")).longValue();
        assign(grn, manager);
        record(managerToken, grn, findings(counted(lineOf(grn, pad), quantity, quantity, 0, 0)))
                .andExpect(status().isOk());
        return grn;
    }

    private String requestStatus(long request) {
        return jdbc.queryForObject("SELECT status FROM replenishment_request WHERE id = ?", String.class, request);
    }

    @Test
    void aRequestBoughtWhollyClosesWhenItsPoIsFullyReceived() throws Exception {
        long request = request(10, 10);
        long grn = inspected(purchaseFor(request, 10), 10);
        assertThat(requestStatus(request)).isEqualTo("IN_PROGRESS");

        confirm(managerToken, grn).andExpect(status().isOk());

        assertThat(requestStatus(request)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT new_data->>'trigger' FROM audit_log WHERE entity_type = "
                + "'REPLENISHMENT_REQUEST' AND action = 'REPLENISHMENT_REQUEST_CLOSED' AND entity_id = ?", String.class,
                request)).isEqualTo("PURCHASE_ORDER_FULLY_RECEIVED");
    }

    @Test
    void aRequestWithAClosedTransferClosesOnlyOnceItsPoIsFullyReceived() throws Exception {
        long request = request(10, 10);
        long transfer = id("INSERT INTO transfer_order (replenishment_request_id, source_store_id, dest_store_id, "
                + "created_by) VALUES (?, ?, ?, ?) RETURNING id", request, longBien, giaLam, owner.getId());
        jdbc.update("UPDATE transfer_order SET status = 'CLOSED', closed_by = ?, closed_at = now() WHERE id = ?",
                owner.getId(), transfer);
        long grn = inspected(purchaseFor(request, 10), 10);
        assertThat(requestStatus(request)).isEqualTo("IN_PROGRESS");

        confirm(managerToken, grn).andExpect(status().isOk());

        assertThat(requestStatus(request)).isEqualTo("CLOSED");
    }

    @Test
    void aCancelledPoDoesNotCloseItsRequestAndTheNextPoDoes() throws Exception {
        long request = request(10, 10);
        long cancelled = purchaseFor(request, 10);
        cancel(ownerToken, cancelled, "Nhà cung cấp hết hàng").andExpect(status().isOk());
        assertThat(requestStatus(request)).isEqualTo("IN_PROGRESS");

        long grn = inspected(purchaseFor(request, 10), 10);
        assertThat(requestStatus(request)).isEqualTo("IN_PROGRESS");

        confirm(managerToken, grn).andExpect(status().isOk());

        assertThat(requestStatus(request)).isEqualTo("CLOSED");
    }
}
