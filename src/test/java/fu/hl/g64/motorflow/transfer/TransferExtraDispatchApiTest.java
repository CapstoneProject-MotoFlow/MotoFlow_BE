package fu.hl.g64.motorflow.transfer;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-75. */
class TransferExtraDispatchApiTest extends TransferFlowTestData {

    @Test
    void sourceConfirmsTheWholeExcessWithoutPostingStock() throws Exception {
        long order = receivedWithExcess(2);

        extra("bmcg", order, null, brake, 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].extraDispatchedQty").value(2))
                .andExpect(jsonPath("$.lines[0].dispatchedQty").value(3))
                .andExpect(jsonPath("$.extraDispatchConfirmedBy.id").value(sourceManager.getId()))
                .andExpect(jsonPath("$.attachments").isEmpty());

        assertThat(stock(source, brake, "available_qty")).isEqualTo(7);
        assertThat(stock(dest, brake, "available_qty")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE movement_type = "
                + "'TRANSFER_EXTRA_DISPATCH'", Long.class)).isZero();
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notification WHERE notification_type = "
                + "'TRANSFER_EXTRA_DISPATCH_CONFIRMED'", Long.class)).hasSize(1);
        extra("bmcg", order, null, brake, 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG213"));
    }

    @Test
    void partialConfirmationsAddUpToTheExcess() throws Exception {
        long order = receivedWithExcess(3);

        extra("bmcg", order, null, brake, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].extraDispatchedQty").value(1));
        extra("bmcg2", order, null, brake, 3).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG214"));
        extra("bmcg2", order, null, brake, 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].extraDispatchedQty").value(3));
        assertThat(jdbc.queryForObject("SELECT extra_dispatched_qty FROM transfer_order_line WHERE transfer_order_id = ?",
                Integer.class, order)).isEqualTo(3);
    }

    @Test
    void confirmingNothingNeedsAReason() throws Exception {
        long order = receivedWithExcess(1);

        extra("bmcg", order, " ", brake, 0).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG215"));
        extra("bmcg", order, "Không xuất thêm, kho đích đếm nhầm", brake, 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].extraDispatchedQty").value(0));
        assertThat(jdbc.queryForObject("SELECT reason FROM audit_log WHERE action = 'TRANSFER_EXTRA_DISPATCH_CONFIRMED'",
                String.class)).isEqualTo("Không xuất thêm, kho đích đếm nhầm");
        extra("bmcg", order, null, brake, 1).andExpect(status().isOk());
    }

    @Test
    void onlyTheSourceConfirmsAfterTheReceipt() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long inTransit = dispatchedOrder(brake, 2);
        extra("bmcg", inTransit, null, brake, 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG29"));

        long order = receivedWithExcess(1);
        extra("owner", order, null, brake, 1).andExpect(status().isForbidden());
        extra("bmkt", order, null, brake, 1).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        extra("bmlb", order, null, brake, 1).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        extra("bmcg", order, null, chain, 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG166"));
    }

    /** 3 dispatched, 3 + {@code excess} received and confirmed at the destination. */
    private long receivedWithExcess(int excess) throws Exception {
        if (stock(source, brake, "available_qty") == 0) {
            lot(source, brake, 10, 100_000, null, "A");
        }
        long order = dispatchedOrder(brake, 3);
        confirm("bmlb", order, inspected(order, brake, 3 + excess, 0)).andExpect(status().isOk());
        return order;
    }

    private ResultActions extra(String manager, long order, String reason, long... lines) throws Exception {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < lines.length; i += 2) {
            rows.add(Map.of("productId", lines[i], "extraQty", lines[i + 1]));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("lines", rows);
        body.put("reason", reason);
        return mvc.perform(post(ORDERS + "/" + order + "/extra-dispatch").header(HttpHeaders.AUTHORIZATION,
                signIn(manager)).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }
}
