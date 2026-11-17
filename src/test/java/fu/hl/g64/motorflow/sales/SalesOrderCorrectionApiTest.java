package fu.hl.g64.motorflow.sales;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * UC-81 on a completed sale: the delivered quantity was recorded wrong. The original order stays as it is (BR-65);
 * once Owner's approval is applied the stock and the order's money follow the corrected quantity.
 */
class SalesOrderCorrectionApiTest extends SalesOrderTestData {

    private static final String CORRECTIONS = "/api/v1/adjustment-requests/corrections";

    @BeforeEach
    void eligibleCustomer() {
        jdbc.update("UPDATE customer SET credit_status = 'ELIGIBLE' WHERE id = ?", customer);
    }

    /** Picked, confirmed and paid in full: pad 2 + oil 1 = 285,000 đ. */
    private long paidOrder() throws Exception {
        long order = confirmedOrder();
        send(managerToken, "/api/v1/sales-orders/" + order + "/payments", Map.of("amount", 285_000,
                "paymentMethod", "BANK_TRANSFER", "transactionReference", "FT" + System.nanoTime(),
                "verified", true, "expectedPayable", moneyNow(order, "payableNow")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true));
        return order;
    }

    /** Pad 2 + oil 1 = 285,000 đ: 85,000 paid, 200,000 on credit. */
    private long creditOrder() throws Exception {
        long order = confirmedOrder();
        send(managerToken, "/api/v1/sales-orders/" + order + "/payments", Map.of("amount", 85_000,
                "paymentMethod", "CASH", "verified", true, "expectedPayable", moneyNow(order, "payableNow")))
                .andExpect(status().isOk());
        send(managerToken, "/api/v1/sales-orders/" + order + "/credit", Map.of("version", 1, "creditAmount", 200_000,
                "dueDate", TODAY.plusDays(30).toString(), "customerAgreed", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        return order;
    }

    private long confirmedOrder() throws Exception {
        long order = createOrder(pad, 2, oil, 1);
        assign(managerToken, "PICKING", order, staff).andExpect(status().isOk());
        pick(staffToken, order, 1, pad, 2, null, oil, 1, null).andExpect(status().isOk());
        confirm(managerToken, order, 1, pad, 2, oil, 1).andExpect(status().isOk());
        return order;
    }

    private long lineOf(long order, long product) {
        return id("SELECT id FROM sales_order_line WHERE sales_order_id = ? AND product_id = ?", order, product);
    }

    private int storeAvailable(long product) {
        return jdbc.queryForObject("SELECT available_qty FROM stock_balance WHERE store_id = ? AND product_id = ?",
                Integer.class, store, product);
    }

    private static Map<String, Object> correction(long order, Object... lineAndQty) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 0; i < lineAndQty.length; i += 2) {
            lines.add(Map.of("lineId", lineAndQty[i], "correctedQty", lineAndQty[i + 1]));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("documentType", "SALES_ORDER");
        body.put("documentId", order);
        body.put("lines", lines);
        body.put("reason", "Thu ngân bấm nhầm số lượng");
        body.put("attachments", List.of(Map.of("storageKey", "motoflow/attachments/" + UUID.randomUUID(),
                "fileName", "phieu-xuat.jpg", "contentType", "image/jpeg", "fileSize", 1000)));
        return body;
    }

    private ResultActions send(String token, String path, Map<String, Object> body) throws Exception {
        return mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private long submitted(String token, Map<String, Object> body) throws Exception {
        String response = send(token, CORRECTIONS, body).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private ResultActions approve(long request) throws Exception {
        return mvc.perform(post("/api/v1/adjustment-requests/" + request + "/approve")
                .header(HttpHeaders.AUTHORIZATION, ownerToken));
    }

    private int lotAvailable(long lot) {
        return jdbc.queryForObject("SELECT quantity FROM lot_balance WHERE lot_id = ? AND stock_status = 'AVAILABLE'",
                Integer.class, lot);
    }

    @Test
    void oneUnitNotReallyDeliveredGoesBackIntoItsLotAndOffThePayment() throws Exception {
        long order = paidOrder();
        long padLine = lineOf(order, pad);
        int before = lotAvailable(padSoon);

        send(managerToken, CORRECTIONS + "/preview", correction(order, padLine, 1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.adjustmentType").value("DOCUMENT_CORRECTION"))
                .andExpect(jsonPath("$.source.type").value("SALES_ORDER"))
                .andExpect(jsonPath("$.stockLines[0].lotId").value(padSoon))
                .andExpect(jsonPath("$.stockLines[0].quantityChange").value(1))
                .andExpect(jsonPath("$.amountLines[0].before").value(285_000))
                .andExpect(jsonPath("$.amountLines[0].amountChange").value(-100_000))
                .andExpect(jsonPath("$.amountLines[0].after").value(185_000));

        // UC-81 step 4: a real change, of this order's lines only.
        send(managerToken, CORRECTIONS, correction(order, padLine, 2)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG282"));
        send(managerToken, CORRECTIONS, correction(order, 999_999L, 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG289"));
        send(otherManagerToken, CORRECTIONS, correction(order, padLine, 1)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG287"));
        Map<String, Object> noEvidence = correction(order, padLine, 1);
        noEvidence.put("attachments", List.of());
        send(managerToken, CORRECTIONS, noEvidence).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG255"));

        long request = submitted(managerToken, correction(order, padLine, 1));
        mvc.perform(get("/api/v1/adjustment-requests/" + request).header(HttpHeaders.AUTHORIZATION, managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correction.before.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.correction.after.lines[0].quantity").value(1))
                .andExpect(jsonPath("$.correction.after.lines[0].sku").value("MA-PHANH-01"));
        // One correction of a document at a time.
        send(managerToken, CORRECTIONS, correction(order, padLine, 0)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG283"));
        assertThat(lotAvailable(padSoon)).isEqualTo(before);

        approve(request).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.amountLines[0].after").value(185_000));
        assertThat(lotAvailable(padSoon)).isEqualTo(before + 1);
        // The order keeps what was recorded; its money follows the correction (BR-49).
        detail(managerToken, order).andExpect(jsonPath("$.lines[0].deliveredQty").value(2))
                .andExpect(jsonPath("$.money.netCollected").value(185_000));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE adjustment_request_id = ? "
                + "AND movement_type = 'ADJUSTMENT' AND quantity_change = 1", Integer.class, request)).isEqualTo(1);
    }

    @Test
    void anExtraUnitDeliveredOnCreditRaisesTheDebtAndADebtNeverGoesBelowWhatWasPaid() throws Exception {
        long order = creditOrder();
        long oilLine = lineOf(order, oil);
        long padLine = lineOf(order, pad);

        // Correcting both lines to nothing would leave the debt at -85,000 đ.
        send(managerToken, CORRECTIONS, correction(order, padLine, 0, oilLine, 0)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG284"));
        // Never more than the customer ordered (1 oil): buying more is a new sales order.
        send(managerToken, CORRECTIONS, correction(order, oilLine, 2)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG292"));

        approve(submitted(managerToken, correction(order, padLine, 1))).andExpect(status().isOk());
        detail(managerToken, order).andExpect(jsonPath("$.money.receivable.remainingAmount").value(100_000));
        int padBefore = storeAvailable(pad);

        // The pad really was delivered after all: back to 2, and the debt goes up again.
        long request = submitted(managerToken, correction(order, padLine, 2));
        approve(request).andExpect(status().isOk())
                .andExpect(jsonPath("$.amountLines[0].receivableId").isNumber())
                .andExpect(jsonPath("$.amountLines[*].after", contains(200_000)));
        assertThat(storeAvailable(pad)).isEqualTo(padBefore - 1);
        detail(managerToken, order).andExpect(jsonPath("$.money.receivable.remainingAmount").value(200_000));
    }

    @Test
    void aSecondCorrectionOfTheSameLineStartsFromTheFirstOne() throws Exception {
        long order = paidOrder();
        long padLine = lineOf(order, pad);
        int before = lotAvailable(padSoon);

        approve(submitted(managerToken, correction(order, padLine, 1))).andExpect(status().isOk());
        // R-01: the line now stands at 1, so asking for 1 again changes nothing.
        send(managerToken, CORRECTIONS, correction(order, padLine, 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG282"));
        // R-02: never more than the customer ordered (2).
        send(managerToken, CORRECTIONS, correction(order, padLine, 3)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG292"));

        send(managerToken, CORRECTIONS + "/preview", correction(order, padLine, 0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.stockLines[0].quantityChange").value(1))
                .andExpect(jsonPath("$.amountLines[0].before").value(185_000))
                .andExpect(jsonPath("$.amountLines[0].after").value(85_000));
        long second = submitted(managerToken, correction(order, padLine, 0));
        mvc.perform(get("/api/v1/adjustment-requests/" + second).header(HttpHeaders.AUTHORIZATION, managerToken))
                .andExpect(jsonPath("$.correction.before.lines[0].quantity").value(1))
                .andExpect(jsonPath("$.correction.after.lines[0].quantity").value(0));
        approve(second).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPLIED"));
        assertThat(lotAvailable(padSoon)).isEqualTo(before + 2);
        detail(managerToken, order).andExpect(jsonPath("$.money.netCollected").value(85_000));

        // Back up to 2 takes both units out of the store's stock again.
        int padInStore = storeAvailable(pad);
        approve(submitted(managerToken, correction(order, padLine, 2))).andExpect(status().isOk());
        assertThat(storeAvailable(pad)).isEqualTo(padInStore - 2);
        detail(managerToken, order).andExpect(jsonPath("$.money.netCollected").value(285_000));
    }
}
