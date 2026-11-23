package fu.hl.g64.motorflow.transfer;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC-83 Step 1_Transit loss and UC-84 Step 4_Transit write-off: goods lost on the way are reported against their
 * transfer at the destination and settled once Owner's approval is applied (BR-66); PM 23/11 needs no loss decision.
 */
class TransitLossAdjustmentApiTest extends TransferFlowTestData {

    private static final String REQUESTS = "/api/v1/adjustment-requests";

    private ResultActions report(String username, long order, Long lot, int quantity) throws Exception {
        Map<String, Object> line = new HashMap<>();
        line.put("productId", brake);
        line.put("lotId", lot);
        line.put("stockStatus", "IN_TRANSIT");
        line.put("quantity", quantity);
        Map<String, Object> body = new HashMap<>();
        body.put("transferOrderId", order);
        body.put("reason", "Xe chở hàng làm rơi một thùng");
        body.put("lines", List.of(line));
        body.put("attachments", List.of(Map.of("storageKey", "motoflow/attachments/" + UUID.randomUUID(),
                "fileName", "bien-ban.jpg", "contentType", "image/jpeg", "fileSize", 1000)));
        return mvc.perform(post(REQUESTS + "/stock-loss").header(HttpHeaders.AUTHORIZATION, signIn(username))
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    @Test
    void aTransitLossIsWrittenOffTheOrdersOwnGoodsInTransitOnlyOnceApplied() throws Exception {
        long lot = lot(source, brake, 10, 100_000, null, "A");
        long order = dispatchedOrder(brake, 4);
        confirm("bmlb", order, inspected(order, brake, 3, 0)).andExpect(status().isOk());
        assertThat(stock(dest, brake, "in_transit_qty")).isEqualTo(1);

        // Only the destination (or the coordinator) reports it, only what is still in transit, and never a chosen lot.
        report("bmcg", order, null, 1).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG279"));
        report("bmlb", order, null, 2).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG257"))
                .andExpect(jsonPath("$.message").value("Số lượng của phụ tùng MA-PHANH vượt số còn có thể điều chỉnh: "
                        + "tối đa 1."));
        report("bmlb", order, lot, 1).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG259"));

        String created = report("bmlb", order, null, 1).andExpect(status().isCreated())
                .andExpect(jsonPath("$.source.type").value("TRANSFER_ORDER"))
                .andExpect(jsonPath("$.stockLines[0].currentQty").value(1))
                .andReturn().getResponse().getContentAsString();
        long request = ((Number) JsonPath.read(created, "$.id")).longValue();
        // The unit is spoken for.
        report("bmlb", order, null, 1).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG257"));

        // PM 23/11: no loss decision any more; the request itself speaks for the unit until it is applied.
        view("owner", "/" + order).andExpect(jsonPath("$.lines[0].shortageQty").value(1))
                .andExpect(jsonPath("$.lines[0].unresolvedQty").value(0));

        mvc.perform(post(REQUESTS + "/" + request + "/approve").header(HttpHeaders.AUTHORIZATION, signIn("owner")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPLIED"));
        assertThat(stock(dest, brake, "in_transit_qty")).isZero();
        // No second deduction at the source.
        assertThat(stock(source, brake, "available_qty")).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT quantity_change FROM stock_movement WHERE adjustment_request_id = ? "
                + "AND stock_status = 'IN_TRANSIT' AND store_id = ? AND lot_id = ?", Integer.class, request, dest, lot))
                .isEqualTo(-1);
        view("owner", "/" + order).andExpect(jsonPath("$.lines[0].shortageQty").value(0))
                .andExpect(jsonPath("$.lines[0].lossQty").value(1));
        mvc.perform(post(ORDERS + "/" + order + "/close").header(HttpHeaders.AUTHORIZATION, signIn("owner")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void theCentralWarehouseCoordinatorReportsATransitLossForTheReceivingStore() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = dispatchedOrder(brake, 4);
        confirm("bmlb", order, inspected(order, brake, 3, 0)).andExpect(status().isOk());

        // Review 17/11 R-05: bmkt (central warehouse) reports it for Long Biên, the destination of the transfer.
        String created = report("bmkt", order, null, 1).andExpect(status().isCreated())
                .andExpect(jsonPath("$.storeId").value(dest))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        long request = ((Number) JsonPath.read(created, "$.id")).longValue();

        // The coordinator sees it in the list and opens it; the destination's manager too; the source's does not.
        mvc.perform(get(REQUESTS).header(HttpHeaders.AUTHORIZATION, signIn("bmkt")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[*].id").value(contains((int) request)));
        mvc.perform(get(REQUESTS + "/" + request).header(HttpHeaders.AUTHORIZATION, signIn("bmkt")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.actions.canWithdraw").value(true))
                .andExpect(jsonPath("$.actions.canApprove").value(false));
        mvc.perform(get(REQUESTS + "/" + request).header(HttpHeaders.AUTHORIZATION, signIn("bmlb")))
                .andExpect(status().isOk());
        mvc.perform(get(REQUESTS + "/" + request).header(HttpHeaders.AUTHORIZATION, signIn("bmcg")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG253"));
        // Approving stays Owner's.
        mvc.perform(post(REQUESTS + "/" + request + "/approve").header(HttpHeaders.AUTHORIZATION, signIn("bmkt")))
                .andExpect(status().isForbidden());

        // Owner's approval settles it (PM 23/11: no loss decision to link any more).
        mvc.perform(post(REQUESTS + "/" + request + "/approve").header(HttpHeaders.AUTHORIZATION, signIn("owner")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPLIED"));
        assertThat(stock(dest, brake, "in_transit_qty")).isZero();
        view("owner", "/" + order).andExpect(jsonPath("$.lines[0].unresolvedQty").value(0));

        // The coordinator cannot name a store that is not the transfer's destination.
        long other = dispatchedOrder(brake, 1);
        Map<String, Object> line = Map.of("productId", brake, "stockStatus", "IN_TRANSIT", "quantity", 1);
        mvc.perform(post(REQUESTS + "/stock-loss").header(HttpHeaders.AUTHORIZATION, signIn("bmkt"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("transferOrderId", other,
                                "storeId", source, "reason", "Rơi", "lines", List.of(line),
                                "attachments", List.of(Map.of("storageKey", "motoflow/attachments/" + UUID.randomUUID(),
                                        "fileName", "a.jpg", "contentType", "image/jpeg", "fileSize", 1000))))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG279"));
    }
}
