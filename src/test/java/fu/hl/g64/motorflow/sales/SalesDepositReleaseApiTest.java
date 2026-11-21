package fu.hl.g64.motorflow.sales;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PM 21/11 (BE-5 to BE-8): a waiting order's deposit goes into the orders its releases open, the rest goes back to the
 * customer at the last release, and the store keeps it when the order is cancelled or the deposit expires. Today is
 * 13/10/2026; the store has 8 sellable pads at 100,000 each.
 */
class SalesDepositReleaseApiTest extends SalesOrderTestData {

    @Test
    void releasesTakeTheDepositIntoTheirOrdersAndTheLastOneGivesTheRestBack() throws Exception {
        long waiting = waitingOrder();
        paidDeposit(waiting, 400_000, "2026-10-16");

        // 1 pad now (100,000): at most 100,000 of the 400,000 deposit goes into it.
        release(waiting, 1, 150_000L, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG327"))
                .andExpect(jsonPath("$.message").value(
                        "Tiền cọc dùng cho đơn tối đa 100.000 đ (không quá tiền cọc còn lại và tổng tiền đơn)."));
        String body = release(waiting, 1, 100_000L, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.split.waitingOrderId").value((int) waiting))
                .andExpect(jsonPath("$.money.depositApplied").value(100_000))
                .andExpect(jsonPath("$.money.netCollected").value(100_000))
                .andExpect(jsonPath("$.money.remainingPayable").value(0))
                .andReturn().getResponse().getContentAsString();
        long sold = ((Number) JsonPath.read(body, "$.id")).longValue();
        detail(managerToken, waiting)
                .andExpect(jsonPath("$.deposit.applied").value(100_000))
                .andExpect(jsonPath("$.deposit.remaining").value(300_000))
                .andExpect(jsonPath("$.deposit.status").value("ACTIVE"));

        // The last 2 pads (200,000) take 200,000 of the 300,000 left; the other 100,000 goes back to the customer.
        release(waiting, 2, null, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG328"))
                .andExpect(jsonPath("$.message").value(
                        "Tiền cọc còn lại vượt tổng tiền đơn 100.000 đ. Vui lòng chọn cách trả lại khách phần thừa này."));
        release(waiting, 2, 150_000L, refund("CASH", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG326"));
        release(waiting, 2, null, refund("BANK_TRANSFER", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG154"));
        release(waiting, 2, 200_000L, refund("CASH", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) waiting))
                .andExpect(jsonPath("$.status").value("AWAITING_PICKING"))
                .andExpect(jsonPath("$.money.depositApplied").value(200_000))
                .andExpect(jsonPath("$.money.verifiedPaid").value(0))
                .andExpect(jsonPath("$.money.verifiedRefunded").value(0))
                .andExpect(jsonPath("$.money.netCollected").value(200_000))
                .andExpect(jsonPath("$.money.remainingPayable").value(0))
                .andExpect(jsonPath("$.deposit.total").value(400_000))
                .andExpect(jsonPath("$.deposit.applied").value(300_000))
                .andExpect(jsonPath("$.deposit.refunded").value(100_000))
                .andExpect(jsonPath("$.deposit.remaining").value(0))
                .andExpect(jsonPath("$.deposit.status").value("SETTLED"));
        assertThat(jdbc.queryForList("SELECT payment_type || ' ' || amount || ' ' || status FROM payment "
                + "WHERE sales_order_id = ? ORDER BY id", String.class, waiting))
                .containsExactly("DEPOSIT 400000 VERIFIED", "DEPOSIT_REFUND 100000 VERIFIED");
        assertThat(jdbc.queryForList("SELECT sales_order_id FROM sales_deposit_application WHERE waiting_order_id = ? "
                + "ORDER BY id", Long.class, waiting)).containsExactly(sold, waiting);

        // The released order is paid by the deposit: once confirmed it completes with no payment.
        assign(managerToken, "PICKING", sold, staff).andExpect(status().isOk());
        pick(staffToken, sold, 1, pad, 1, null).andExpect(status().isOk());
        confirm(managerToken, sold, 1, pad, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.actions.canRecordPayment").value(false))
                .andExpect(jsonPath("$.actions.canComplete").value(true));
        int version = JsonPath.read(detail(managerToken, sold).andReturn().getResponse().getContentAsString(),
                "$.version");
        send(managerToken, "/api/v1/sales-orders/" + sold + "/complete", Map.of("version", version))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.closing.completionType").value("PAID"));

        // Sales report: the deposit came in once and 100,000 went back; what releases applied is shown apart.
        report("/sales-collection?storeId=" + store + "&from=2026-10-13&to=2026-10-13")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total.depositsReceived").value(400_000))
                .andExpect(jsonPath("$.total.depositsRefunded").value(100_000))
                .andExpect(jsonPath("$.total.depositsApplied").value(300_000))
                .andExpect(jsonPath("$.total.depositsForfeited").value(0))
                .andExpect(jsonPath("$.total.orderPayments").value(0))
                .andExpect(jsonPath("$.total.totalCollected").value(300_000));
    }

    @Test
    void aWholeReleaseNeedsEveryDepositVerifiedAndLeavesTheRestToPay() throws Exception {
        long waiting = waitingOrder();
        paidDeposit(waiting, 50_000, "2026-10-16");
        long unverified = deposit(waiting, 20_000, false, null, 50_000);

        release(waiting, 3, null, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG325"))
                .andExpect(jsonPath("$.message").value(
                        "Đơn còn 20.000 đ tiền cọc chưa xác minh. Vui lòng xác minh hoặc từ chối khoản cọc đó trước."));
        // A partial release may go ahead: only verified deposit is used.
        send(managerToken, "/api/v1/sales-orders/" + waiting + "/payments/" + unverified + "/reject",
                Map.of("reason", "Không thấy tiền về")).andExpect(status().isOk());

        release(waiting, 3, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) waiting))
                .andExpect(jsonPath("$.money.depositApplied").value(50_000))
                .andExpect(jsonPath("$.money.netCollected").value(50_000))
                .andExpect(jsonPath("$.money.remainingPayable").value(250_000))
                .andExpect(jsonPath("$.deposit.status").value("SETTLED"))
                .andExpect(jsonPath("$.actions.canRecordDeposit").value(false));
        list(managerToken, "depositStatus", "SETTLED")
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value((int) waiting))
                .andExpect(jsonPath("$.items[0].depositRemaining").value(0))
                .andExpect(jsonPath("$.items[0].depositExpiresOn").value("2026-10-16"));
        // Staff see no deposit and the filter is not applied for them.
        assign(managerToken, "PICKING", waiting, staff).andExpect(status().isOk());
        list(staffToken, "depositStatus", "EXPIRED").andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].depositStatus").value(nullValue()))
                .andExpect(jsonPath("$.items[0].depositRemaining").value(nullValue()));
    }

    @Test
    void theStoreKeepsTheDepositOfACancelledOrderOrOneThatExpired() throws Exception {
        long expiring = waitingOrder();
        paidDeposit(expiring, 200_000, "2026-10-14");
        long cancelled = waitingOrder();
        paidDeposit(cancelled, 100_000, "2026-10-20");
        long none = waitingOrder();

        forfeit(managerToken, expiring)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG329"))
                .andExpect(jsonPath("$.message").value("Cọc của đơn còn hạn đến 14/10/2026 nên chưa xử lý mất cọc được."));
        forfeit(managerToken, none)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG330"));
        forfeit(ownerToken, expiring).andExpect(status().isForbidden());
        forfeit(otherManagerToken, expiring)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));

        // Cancelling before the expiry: nothing to refund, the deposit is kept.
        send(managerToken, "/api/v1/sales-orders/" + cancelled + "/cancel", Map.of("reason", "Khách đổi ý"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.deposit.status").value("FORFEITED"))
                .andExpect(jsonPath("$.deposit.forfeitedAmount").value(100_000))
                .andExpect(jsonPath("$.deposit.forfeitedBy").value(manager.getFullName()))
                .andExpect(jsonPath("$.deposit.remaining").value(0));

        // 15/10: the first deposit has expired. It is listed, cannot be released and the Branch Manager forfeits it.
        clock.advance(Duration.ofDays(2));
        signInAll();
        list(managerToken, "depositStatus", "EXPIRED")
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value((int) expiring))
                .andExpect(jsonPath("$.items[0].depositStatus").value("EXPIRED"))
                .andExpect(jsonPath("$.items[0].depositRemaining").value(200_000));
        list(managerToken, "depositStatus", "NONE")
                .andExpect(jsonPath("$.items[*].id").value(containsInAnyOrder((int) none)));
        release(expiring, 3, null, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG321"));
        detail(managerToken, expiring).andExpect(jsonPath("$.actions.canForfeitDeposit").value(true));
        detail(ownerToken, expiring).andExpect(jsonPath("$.actions.canForfeitDeposit").value(false));

        forfeit(managerToken, expiring)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.closing.cancellationReason").value("Hết hạn cọc"))
                .andExpect(jsonPath("$.deposit.status").value("FORFEITED"))
                .andExpect(jsonPath("$.deposit.forfeitedAmount").value(200_000));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE payment_type IN ('REFUND', "
                + "'DEPOSIT_REFUND')", Long.class)).isZero();
        list(managerToken, "view", "ALL", "createdFrom", "2026-10-13", "depositStatus", "FORFEITED")
                .andExpect(jsonPath("$.items[*].id").value(containsInAnyOrder((int) expiring, (int) cancelled)));

        report("/sales-collection?storeId=" + store + "&from=2026-10-13&to=2026-10-15")
                .andExpect(jsonPath("$.total.depositsReceived").value(300_000))
                .andExpect(jsonPath("$.total.depositsForfeited").value(300_000))
                .andExpect(jsonPath("$.total.totalCollected").value(300_000));
    }

    /** A new order of the customer that takes none of 3 pads now and waits for them. */
    private long waitingOrder() throws Exception {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("productId", pad);
        line.put("quantity", 3);
        line.put("nowQty", 0);
        line.put("waitForRest", true);
        String body = create(managerToken, order(null, customer, "RETAIL", null, List.of(line)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private long paidDeposit(long order, long amount, String expiresOn) throws Exception {
        return deposit(order, amount, true, expiresOn, 0);
    }

    private long deposit(long order, long amount, boolean verified, String expiresOn, long recorded) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount);
        body.put("paymentMethod", "CASH");
        body.put("verified", verified);
        body.put("depositExpiresOn", expiresOn);
        body.put("expectedDepositRecorded", recorded);
        String result = send(managerToken, "/api/v1/sales-orders/" + order + "/deposits", body)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(result, "$.paymentId")).longValue();
    }

    private static Map<String, Object> refund(String method, String reference) {
        Map<String, Object> refund = new LinkedHashMap<>();
        refund.put("paymentMethod", method);
        refund.put("transactionReference", reference);
        refund.put("paidToCustomer", true);
        return refund;
    }

    private ResultActions release(long order, int nowQty, Long depositAmount, Map<String, Object> refund)
            throws Exception {
        long line = jdbc.queryForObject("SELECT id FROM sales_order_line WHERE sales_order_id = ? ORDER BY line_no "
                + "LIMIT 1", Long.class, order);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lines", List.of(Map.of("lineId", line, "nowQty", nowQty)));
        body.put("depositAmount", depositAmount);
        body.put("depositRefund", refund);
        return send(managerToken, "/api/v1/sales-orders/" + order + "/release", body);
    }

    private ResultActions forfeit(String token, long order) throws Exception {
        return mvc.perform(post("/api/v1/sales-orders/" + order + "/forfeit-deposit")
                .header(HttpHeaders.AUTHORIZATION, token));
    }

    private ResultActions report(String path) throws Exception {
        return mvc.perform(get("/api/v1/reports" + path).header(HttpHeaders.AUTHORIZATION, ownerToken));
    }

    private ResultActions send(String token, String url, Map<String, Object> body) throws Exception {
        return mvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }
}
