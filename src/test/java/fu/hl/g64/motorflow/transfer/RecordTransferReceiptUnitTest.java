package fu.hl.g64.motorflow.transfer;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Report 5.1, function RecordTransferReceipt: TransferReceiptService.recordResult,
 * PUT /api/v1/transfer-orders/{orderId}/receipts/{receiptId}/result (UC-43 for a transfer).
 */
@Tag("report51")
class RecordTransferReceiptUnitTest extends TransferFlowTestData {

    @Test
    @DisplayName("UTC016 Normal: all 5 expected units passed")
    void utc016() throws Exception {
        long[] receipt = assignedReceipt(brake, 5);

        record(signIn("nvlb"), receipt, brake, 5, 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_CONFIRMATION"));

        assertRecorded(receipt[1], brake, 5, 5, 0);
    }

    @Test
    @DisplayName("UTC017 Normal: 3 units passed and 2 damaged")
    void utc017() throws Exception {
        long[] receipt = assignedReceipt(brake, 5);

        record(signIn("nvlb"), receipt, brake, 3, 2).andExpect(status().isOk());

        assertRecorded(receipt[1], brake, 5, 3, 2);
    }

    @Test
    @DisplayName("UTC018 Boundary: damaged quantity 6 exceeds the expected 5")
    void utc018() throws Exception {
        long[] receipt = assignedReceipt(brake, 5);

        record(signIn("nvlb"), receipt, brake, 0, 6).andExpect(expectError(HttpStatus.BAD_REQUEST, "MSG212"));

        assertNothingRecorded(receipt[1]);
    }

    @Test
    @DisplayName("UTC019 Abnormal: P2 is left out of the inspection result")
    void utc019() throws Exception {
        long[] receipt = assignedReceipt(brake, 5, chain, 2);

        record(signIn("nvlb"), receipt, brake, 5, 0).andExpect(expectError(HttpStatus.BAD_REQUEST, "MSG167"));

        assertNothingRecorded(receipt[1]);
    }

    @Test
    @DisplayName("UTC020 Abnormal: the result of the receipt was already submitted")
    void utc020() throws Exception {
        long[] receipt = assignedReceipt(brake, 5);
        record(signIn("nvlb"), receipt, brake, 5, 0).andExpect(status().isOk());

        record(signIn("nvlb"), receipt, brake, 4, 1).andExpect(expectError(HttpStatus.FORBIDDEN, "MSG05"));

        assertRecorded(receipt[1], brake, 5, 5, 0);
    }

    @Test
    @DisplayName("UTC021 Abnormal: received back 6 (5 passed + 1 damaged) of the 5 being returned")
    void utc021() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = dispatchedOrder(brake, 5);
        confirm("bmlb", order, inspected(order, brake, 0, 0)).andExpect(status().isOk());
        mvc.perform(post(ORDERS + "/" + order + "/discrepancy/decisions")
                        .header(HttpHeaders.AUTHORIZATION, signIn("owner")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("productId", brake, "decision", "RETURN_TO_SOURCE", "quantity", 5))))
                .andExpect(status().isOk());
        mvc.perform(post(ORDERS + "/" + order + "/returns").header(HttpHeaders.AUTHORIZATION, signIn("bmcg"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("lines", List.of(Map.of("productId", brake, "quantity", 5))))))
                .andExpect(status().isCreated());
        long back = jdbc.queryForObject("SELECT max(id) FROM goods_receipt_note", Long.class);
        assign("bmcg", "INSPECTION", back, sourceStaff).andExpect(status().isOk());

        record(signIn("nvcg"), new long[] {order, back}, brake, 5, 1)
                .andExpect(expectError(HttpStatus.BAD_REQUEST, "MSG220"));

        assertNothingRecorded(back);
    }

    @Test
    @DisplayName("UTC022 Abnormal: Staff of the destination store who was not assigned to inspect")
    void utc022() throws Exception {
        long[] receipt = assignedReceipt(brake, 5);
        createUser("nvlb2", Role.STAFF, dest);

        record(signIn("nvlb2"), receipt, brake, 5, 0).andExpect(expectError(HttpStatus.FORBIDDEN, "MSG05"));

        assertNothingRecorded(receipt[1]);
    }

    @Test
    @DisplayName("UTC023 Abnormal: not signed in")
    void utc023() throws Exception {
        long[] receipt = assignedReceipt(brake, 5);

        record(NO_TOKEN, receipt, brake, 5, 0).andExpect(expectError(HttpStatus.UNAUTHORIZED, "MSG06"));

        assertNothingRecorded(receipt[1]);
    }

    @Test
    @DisplayName("UTC024 Abnormal: Owner may not record a receipt result")
    void utc024() throws Exception {
        long[] receipt = assignedReceipt(brake, 5);

        record(signIn("owner"), receipt, brake, 5, 0).andExpect(expectError(HttpStatus.FORBIDDEN, "MSG05"));

        assertNothingRecorded(receipt[1]);
    }

    @Test
    @DisplayName("UTC025 Abnormal: concurrency, assigned Staff submits the result twice at the same time")
    void utc025() throws Exception {
        long[] receipt = assignedReceipt(brake, 5);
        String token = signIn("nvlb");

        List<MvcResult> results = atTheSameTime(() -> record(token, receipt, brake, 5, 0).andReturn(),
                () -> record(token, receipt, brake, 5, 0).andReturn());

        assertThat(results).extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(200, 403);
        MvcResult loser = results.stream().filter(result -> result.getResponse().getStatus() != 200).findFirst()
                .orElseThrow();
        assertThat(JsonPath.<String>read(loser.getResponse().getContentAsString(), "$.code")).isEqualTo("MSG05");
        assertRecorded(receipt[1], brake, 5, 5, 0);
    }

    /**
     * A transfer in transit to the destination whose TRANSFER_IN receipt is given to the destination Staff member:
     * [productId, quantity, ...]. Returns [orderId, receiptId].
     */
    private long[] assignedReceipt(long... lines) throws Exception {
        for (int i = 0; i < lines.length; i += 2) {
            lot(source, lines[i], 10, 100_000, null, "L" + lines[i]);
        }
        long order = dispatchedOrder(lines);
        long receipt = arrive("bmlb", order);
        assign("bmlb", "INSPECTION", receipt, destStaff).andExpect(status().isOk());
        return new long[] {order, receipt};
    }

    private ResultActions record(String token, long[] receipt, long... lines) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < lines.length; i += 3) {
            rows.add(Map.of("productId", lines[i], "passedQty", lines[i + 1], "damagedQty", lines[i + 2]));
        }
        return mvc.perform(as(token, put(ORDERS + "/" + receipt[0] + "/receipts/" + receipt[1] + "/result"),
                json(Map.of("lines", rows))));
    }

    private void assertRecorded(long receipt, long product, int expected, int passed, int damaged) {
        assertThat(jdbc.queryForObject("SELECT status FROM goods_receipt_note WHERE id = ?", String.class, receipt))
                .isEqualTo("AWAITING_CONFIRMATION");
        Map<String, Object> line = jdbc.queryForMap("SELECT * FROM goods_receipt_note_line "
                + "WHERE goods_receipt_note_id = ? AND product_id = ?", receipt, product);
        assertThat(line).containsEntry("document_qty", expected).containsEntry("passed_qty", passed)
                .containsEntry("damaged_qty", damaged).containsEntry("delivered_qty", passed + damaged);
        assertThat(stock(dest, product, "available_qty")).isZero();
        assertThat(stock(dest, product, "in_transit_qty")).isEqualTo(expected);
    }

    private void assertNothingRecorded(long receipt) {
        assertThat(jdbc.queryForObject("SELECT status FROM goods_receipt_note WHERE id = ?", String.class, receipt))
                .isEqualTo("AWAITING_INSPECTION");
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(delivered_qty), 0) FROM goods_receipt_note_line "
                + "WHERE goods_receipt_note_id = ?", Long.class, receipt)).isZero();
    }
}
