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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PM 21/11 (BE-3, BE-4): a deposit on a waiting order of a customer with a profile. Today is 13/10/2026; the store has
 * 8 sellable pads, so an order that takes none now and waits for 3 is a waiting order.
 */
class SalesDepositApiTest extends SalesOrderTestData {

    @Test
    void aDepositIsRecordedOnAWaitingOrderAndCountsOnceVerified() throws Exception {
        long order = waitingOrder(customer);
        detail(managerToken, order)
                .andExpect(jsonPath("$.deposit.status").value("NONE"))
                .andExpect(jsonPath("$.actions.canRecordDeposit").value(true))
                .andExpect(jsonPath("$.actions.canChangeDepositExpiry").value(false));

        String body = deposit(managerToken, order, 1_000_000, "BANK_TRANSFER", "CK-COC-1", false, "2026-10-16", 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.order.deposit.total").value(0))
                .andExpect(jsonPath("$.order.deposit.unverified").value(1_000_000))
                .andExpect(jsonPath("$.order.deposit.expiresOn").value("2026-10-16"))
                .andExpect(jsonPath("$.order.deposit.status").value("NONE"))
                .andReturn().getResponse().getContentAsString();
        long first = ((Number) JsonPath.read(body, "$.paymentId")).longValue();
        assertThat(jdbc.queryForObject("SELECT payment_type FROM payment WHERE id = ?", String.class, first))
                .isEqualTo("DEPOSIT");

        // The same deposit sent again sees 1,000,000 already recorded: nothing is saved twice.
        deposit(managerToken, order, 1_000_000, "CASH", null, true, null, 0)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG194"));

        mvc.perform(post("/api/v1/sales-orders/" + order + "/payments/" + first + "/verify")
                        .header(HttpHeaders.AUTHORIZATION, managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.order.status").value("AWAITING_STOCK"));

        // A second deposit in cash, with change; it keeps the expiry date set by the first.
        deposit(ownerToken, order, 2_000_000, 2_500_000L, "CASH", null, true, null, 1_000_000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.deposit.total").value(3_000_000))
                .andExpect(jsonPath("$.order.deposit.unverified").value(0))
                .andExpect(jsonPath("$.order.deposit.remaining").value(3_000_000))
                .andExpect(jsonPath("$.order.deposit.status").value("ACTIVE"))
                .andExpect(jsonPath("$.order.money.payments", hasSize(2)))
                .andExpect(jsonPath("$.order.actions.canRefund").value(false))
                .andExpect(jsonPath("$.order.actions.canRecordPayment").value(false));
        deposit(managerToken, order, 1, "CASH", null, true, "2026-10-20", 3_000_000)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG323"))
                .andExpect(jsonPath("$.message").value(
                        "Đơn đã có hạn cọc 16/10/2026. Muốn đổi hạn, Branch Manager dùng chức năng Sửa hạn cọc."));

        // Any other money on a waiting order is still refused (GVHD 20/11).
        send(managerToken, "/api/v1/sales-orders/" + order + "/payments", Map.of("amount", 100_000,
                "paymentMethod", "CASH", "verified", true, "expectedPayable", 0))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG308"));
        send(managerToken, "/api/v1/sales-orders/" + order + "/refunds", Map.of("amount", 100_000,
                "paymentMethod", "CASH", "verified", true, "expectedRefundable", 3_000_000))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG308"));
    }

    @Test
    void aDepositNeedsAWaitingOrderOfACustomerProfileAndAnExpiryDateAfterToday() throws Exception {
        // Góp ý UI 21/11: a walk-in customer can no longer leave anything waiting; an older walk-in waiting order
        // still takes no deposit.
        long walkIn = waitingOrder(customer);
        jdbc.update("UPDATE sales_order SET customer_id = NULL, customer_name_snapshot = 'Chị Lan' WHERE id = ?",
                walkIn);
        deposit(managerToken, walkIn, 500_000, "CASH", null, true, "2026-10-16", 0)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG320"));
        detail(managerToken, walkIn).andExpect(jsonPath("$.actions.canRecordDeposit").value(false));

        long order = waitingOrder(customer);
        deposit(managerToken, order, 500_000, "CASH", null, true, null, 0)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG322"));
        deposit(managerToken, order, 500_000, "CASH", null, true, "2026-10-13", 0)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG322"));
        deposit(managerToken, order, 500_000, "BANK_TRANSFER", null, true, "2026-10-16", 0)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG154"));
        deposit(managerToken, order, 500_000, 400_000L, "CASH", null, true, "2026-10-16", 0)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG182"));
        deposit(otherManagerToken, order, 500_000, "CASH", null, true, "2026-10-16", 0)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        deposit(staffToken, order, 500_000, "CASH", null, true, "2026-10-16", 0)
                .andExpect(status().isForbidden());

        long now = createOrder(pad, 1);
        deposit(managerToken, now, 500_000, "CASH", null, true, "2026-10-16", 0)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG319"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE sales_order_id = ? "
                + "AND payment_type = 'DEPOSIT'", Long.class, now)).isZero();
    }

    @Test
    void theBranchManagerMovesTheExpiryDateUntilItHasPassed() throws Exception {
        long rejectedOrder = waitingOrder(customer);
        String rejected = deposit(managerToken, rejectedOrder, 500_000, "CASH", null, false, "2026-10-16", 0)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long rejectedId = ((Number) JsonPath.read(rejected, "$.paymentId")).longValue();
        send(managerToken, "/api/v1/sales-orders/" + rejectedOrder + "/payments/" + rejectedId + "/reject",
                Map.of("reason", "Không thấy tiền về")).andExpect(status().isOk());
        detail(managerToken, rejectedOrder)
                .andExpect(jsonPath("$.deposit.status").value("NONE"))
                .andExpect(jsonPath("$.deposit.expiresOn").value("2026-10-16"))
                .andExpect(jsonPath("$.actions.canChangeDepositExpiry").value(false));
        expiry(managerToken, rejectedOrder, "2026-10-20")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG324"));
        // Nothing was verified, so the next deposit may choose its own date.
        deposit(managerToken, rejectedOrder, 200_000, "CASH", null, true, "2026-10-20", 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.deposit.status").value("ACTIVE"))
                .andExpect(jsonPath("$.order.deposit.expiresOn").value("2026-10-20"))
                .andExpect(jsonPath("$.order.deposit.total").value(200_000));

        long order = waitingOrder(customer);
        expiry(managerToken, order, "2026-10-20")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG324"));
        deposit(managerToken, order, 1_000_000, "CASH", null, true, "2026-10-15", 0).andExpect(status().isOk());
        detail(managerToken, order).andExpect(jsonPath("$.actions.canChangeDepositExpiry").value(true));
        detail(ownerToken, order).andExpect(jsonPath("$.actions.canChangeDepositExpiry").value(true));

        expiry(managerToken, order, "2026-10-18").andExpect(status().isOk())
                .andExpect(jsonPath("$.deposit.expiresOn").value("2026-10-18"));
        expiry(ownerToken, order, "2026-10-14").andExpect(status().isOk())
                .andExpect(jsonPath("$.deposit.expiresOn").value("2026-10-14"))
                .andExpect(jsonPath("$.deposit.expiryChanges", hasSize(2)))
                .andExpect(jsonPath("$.deposit.expiryChanges[0].oldDate").value("2026-10-15"))
                .andExpect(jsonPath("$.deposit.expiryChanges[0].newDate").value("2026-10-18"))
                .andExpect(jsonPath("$.deposit.expiryChanges[1].newDate").value("2026-10-14"))
                .andExpect(jsonPath("$.deposit.expiryChanges[1].changedBy").value(owner.getFullName()));
        expiry(managerToken, order, "2026-10-13")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG322"));
        expiry(staffToken, order, "2026-10-19").andExpect(status().isForbidden());
        expiry(otherManagerToken, order, "2026-10-19")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));

        // 15/10 is after the last day (14/10): the deposit has expired, read from the date, nothing stored.
        clock.advance(Duration.ofDays(2));
        signInAll();
        detail(managerToken, order)
                .andExpect(jsonPath("$.deposit.status").value("EXPIRED"))
                .andExpect(jsonPath("$.actions.canRecordDeposit").value(false))
                .andExpect(jsonPath("$.actions.canChangeDepositExpiry").value(false));
        expiry(managerToken, order, "2026-10-30")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG321"))
                .andExpect(jsonPath("$.message").value(
                        "Đơn đã quá hạn cọc (14/10/2026) nên không thu thêm cọc, sửa hạn hay chuyển sang soạn được."));
        deposit(managerToken, order, 100_000, "CASH", null, true, null, 1_000_000)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG321"));
        // Plan 25/11: the changes live in audit_log, the dates as yyyy-MM-dd.
        assertThat(jdbc.queryForList("SELECT old_data ->> 'deposit_expires_on' || ' ' || (new_data ->> "
                + "'deposit_expires_on') FROM audit_log WHERE entity_type = 'SALES_ORDER' AND entity_id = ? "
                + "AND action = 'SALES_ORDER_DEPOSIT_EXPIRY_CHANGED' ORDER BY id", String.class, order))
                .containsExactly("2026-10-15 2026-10-18", "2026-10-18 2026-10-14");
        // They are not steps of the sales order document.
        mvc.perform(post("/api/v1/sales-orders/" + order + "/cancel").header(HttpHeaders.AUTHORIZATION, managerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("reason", "Hết hạn cọc"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/documents/SALES_ORDER/" + order).header(HttpHeaders.AUTHORIZATION, managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history[*].action", hasItem("SALES_ORDER_CANCELLED")))
                .andExpect(jsonPath("$.history[*].action", not(hasItem("SALES_ORDER_DEPOSIT_EXPIRY_CHANGED"))));
    }

    @Test
    void aRejectedDepositDoesNotExpireTheOrder() throws Exception {
        long order = waitingOrder(customer);
        String body = deposit(managerToken, order, 500_000, "CASH", null, false, "2026-10-14", 0)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long paymentId = ((Number) JsonPath.read(body, "$.paymentId")).longValue();
        send(managerToken, "/api/v1/sales-orders/" + order + "/payments/" + paymentId + "/reject",
                Map.of("reason", "Không thấy tiền về")).andExpect(status().isOk());

        // 15/10 is after the date the rejected deposit left behind. There is still no deposit.
        clock.advance(Duration.ofDays(2));
        signInAll();
        detail(managerToken, order)
                .andExpect(jsonPath("$.deposit.status").value("NONE"))
                .andExpect(jsonPath("$.actions.canRecordDeposit").value(true));
        deposit(managerToken, order, 300_000, "CASH", null, true, "2026-10-20", 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.deposit.status").value("ACTIVE"))
                .andExpect(jsonPath("$.order.deposit.expiresOn").value("2026-10-20"));
    }

    @Test
    void splitDepositRecordsBothVerifiedReceiptsInOneCall() throws Exception {
        long waiting = waitingOrder(customer);
        Map<String, Object> split = Map.of("cashAmount", 300_000, "transferAmount", 700_000,
                "transactionReference", "QA-DEPOSIT-SPLIT", "depositExpiresOn", "2026-10-20",
                "expectedDepositRecorded", 0);
        String path = "/api/v1/sales-orders/" + waiting + "/deposits/split";
        send(managerToken, path, split).andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentIds.length()").value(2))
                .andExpect(jsonPath("$.order.deposit.total").value(1_000_000))
                .andExpect(jsonPath("$.order.deposit.unverified").value(0));
        send(managerToken, path, split).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG194"));
    }

    @Test
    void retailCannotPreorderOrAddADepositToAnExistingWaitingOrder() throws Exception {
        Map<String, Object> line = Map.of("productId", pad, "quantity", 3, "nowQty", 0, "waitForRest", true);
        create(managerToken, order(null, customer, "RETAIL", null, List.of(line)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG335"));

        long waiting = waitingOrder(customer);
        jdbc.update("UPDATE sales_order SET price_type = 'RETAIL' WHERE id = ?", waiting);
        detail(managerToken, waiting).andExpect(jsonPath("$.actions.canRecordDeposit").value(false));
        deposit(managerToken, waiting, 50_000, "CASH", null, true, "2026-10-20", 0)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG335"));
    }

    /** A new order that takes none of 3 pads now and waits for them. */
    private long waitingOrder(Long customerId) throws Exception {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("productId", pad);
        line.put("quantity", 3);
        line.put("nowQty", 0);
        line.put("waitForRest", true);
        String body = create(managerToken, order(null, customerId, "WHOLESALE", null, List.of(line)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AWAITING_STOCK"))
                .andReturn().getResponse().getContentAsString();
        return id(body);
    }

    private static long id(String body) {
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private ResultActions deposit(String token, long order, long amount, String method, String reference,
                                  boolean verified, String expiresOn, long recorded) throws Exception {
        return deposit(token, order, amount, null, method, reference, verified, expiresOn, recorded);
    }

    private ResultActions deposit(String token, long order, long amount, Long tendered, String method,
                                  String reference, boolean verified, String expiresOn, long recorded)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount);
        body.put("tenderedAmount", tendered);
        body.put("paymentMethod", method);
        body.put("transactionReference", reference);
        body.put("verified", verified);
        body.put("depositExpiresOn", expiresOn);
        body.put("expectedDepositRecorded", recorded);
        return send(token, "/api/v1/sales-orders/" + order + "/deposits", body);
    }

    private ResultActions expiry(String token, long order, String date) throws Exception {
        return mvc.perform(patch("/api/v1/sales-orders/" + order + "/deposit-expiry")
                .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("depositExpiresOn", date))));
    }

    private ResultActions send(String token, String url, Map<String, Object> body) throws Exception {
        return mvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }
}
