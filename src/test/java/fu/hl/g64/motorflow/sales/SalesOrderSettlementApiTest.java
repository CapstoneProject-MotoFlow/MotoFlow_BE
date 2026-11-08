package fu.hl.g64.motorflow.sales;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-60 Record Order Payment, UC-61 Record Order Credit, UC-56 Cancel Sales Order. */
class SalesOrderSettlementApiTest extends SalesOrderTestData {

    @Autowired
    private DataSource dataSource;

    @Test
    void verifiedPaymentsCompleteTheOrderAndTakeTheGoodsOutByLot() throws Exception {
        long order = confirmedOrder(pad, 2, oil, 1);   // 285,000 đ

        pay(staffToken, order, 100_000, null, "CASH", null, true).andExpect(status().isForbidden());
        pay(otherManagerToken, order, 100_000, null, "CASH", null, true)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        pay(managerToken, order, 300_000, null, "CASH", null, true)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG181"))
                .andExpect(jsonPath("$.message").value("Số tiền ghi thu cho đơn không được lớn hơn 285.000 đ, là số "
                        + "còn phải thu."));
        pay(managerToken, order, 100_000, 50_000L, "CASH", null, true)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG182"));
        pay(managerToken, order, 100_000, null, "BANK_TRANSFER", " ", true)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG154"));

        pay(managerToken, order, 100_000, 200_000L, "CASH", null, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.completionBlockedCode").value(nullValue()))
                .andExpect(jsonPath("$.order.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.order.money.total").value(285_000))
                .andExpect(jsonPath("$.order.money.netCollected").value(100_000))
                .andExpect(jsonPath("$.order.money.remainingPayable").value(185_000))
                .andExpect(jsonPath("$.order.money.payments[0].changeGiven").value(100_000))
                .andExpect(jsonPath("$.order.money.payments[0].status").value("VERIFIED"))
                .andExpect(jsonPath("$.order.money.payments[0].recordedBy").value("Test bm"))
                .andExpect(jsonPath("$.order.actions.canRecordPayment").value(true))
                .andExpect(jsonPath("$.order.actions.canComplete").value(false));
        assertThat(saleIssues()).isZero();
        // BR-45: the same cash payment sent again (a double click, a retried request) is not recorded twice.
        send(managerToken, "/api/v1/sales-orders/" + order + "/payments", Map.of("amount", 100_000,
                "paymentMethod", "CASH", "verified", true, "expectedPayable", 285_000))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG194"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE sales_order_id = ?", Integer.class, order))
                .isEqualTo(1);

        clock.advance(Duration.ofMinutes(5));
        pay(managerToken, order, 185_000, null, "BANK_TRANSFER", "FT26101300001", true).andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.order.status").value("COMPLETED"))
                .andExpect(jsonPath("$.order.closing.completionType").value("PAID"))
                .andExpect(jsonPath("$.order.closing.completedBy").value("Test bm"))
                .andExpect(jsonPath("$.order.money.netCollected").value(285_000))
                .andExpect(jsonPath("$.order.money.remainingPayable").value(0))
                .andExpect(jsonPath("$.order.actions.canCancel").value(false))
                .andExpect(jsonPath("$.order.history[0].action").value("COMPLETED"));

        // BR-75, BR-76: the earliest unexpired lot first, never the expired one; valued at the moving average.
        assertThat(jdbc.queryForList("SELECT lot_id, quantity_change, unit_cost::int AS cost FROM stock_movement "
                + "WHERE movement_type = 'SALE_ISSUE' AND sales_order_id = ? ORDER BY product_id, lot_id", order))
                .containsExactly(Map.of("lot_id", padSoon, "quantity_change", -2, "cost", 60_000),
                        Map.of("lot_id", jdbc.queryForObject("SELECT id FROM lot WHERE product_id = ?", Long.class,
                                oil), "quantity_change", -1, "cost", 70_000));
        assertThat(jdbc.queryForObject("SELECT quantity FROM lot_balance WHERE lot_id = ? AND stock_status = "
                + "'AVAILABLE'", Integer.class, padSoon)).isEqualTo(1);

        pay(managerToken, order, 1_000, null, "CASH", null, true)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG164"));
        complete(managerToken, order, 1)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG164"));
        // Another store learns nothing of the completed order.
        pay(otherManagerToken, order, 1_000, null, "CASH", null, true)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        complete(otherManagerToken, order, 1)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        cancel(otherManagerToken, order, "x", null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));

        // BR-45: one bank transfer is recorded once, on any order.
        long other = confirmedOrder(oil, 1);
        pay(managerToken, other, 85_000, null, "BANK_TRANSFER", "ft26101300001", true)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG151"));
        assertThat(saleIssues()).isEqualTo(2);
    }

    @Test
    void unverifiedMoneyDoesNotCountAndBlocksCompletionUntilVerifiedOrRejected() throws Exception {
        long order = confirmedOrder(pad, 2, oil, 1);   // 285,000 đ

        String body = pay(managerToken, order, 285_000, null, "BANK_TRANSFER", "FT1", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.order.money.netCollected").value(0))
                .andExpect(jsonPath("$.order.money.unverifiedCount").value(1))
                .andExpect(jsonPath("$.order.money.payableNow").value(0))
                .andExpect(jsonPath("$.order.money.payments[0].status").value("UNVERIFIED"))
                .andExpect(jsonPath("$.order.actions.canRecordPayment").value(false))
                .andExpect(jsonPath("$.order.actions.canComplete").value(false))
                .andReturn().getResponse().getContentAsString();
        long first = ((Number) JsonPath.read(body, "$.paymentId")).longValue();

        // Nobody records the same money twice while it waits.
        pay(managerToken, order, 1_000, null, "CASH", null, true)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG180"));
        complete(managerToken, order, 1)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG184"));
        cancel(managerToken, order, "Khách đổi ý", null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG184"));
        verify(managerToken, order, first + 100)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG191"));
        verify(otherManagerToken, order, first)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        reject(otherManagerToken, order, first, "x")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        credit(otherManagerToken, order, 1, 285_000, TODAY.plusDays(30).toString(), true)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));

        reject(managerToken, order, first, "Không thấy tiền về tài khoản").andExpect(status().isOk())
                .andExpect(jsonPath("$.money.payments[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.money.payments[0].rejectionReason").value("Không thấy tiền về tài khoản"))
                .andExpect(jsonPath("$.money.payableNow").value(285_000))
                .andExpect(jsonPath("$.history[0].action").value("PAYMENT_REJECTED"));
        verify(managerToken, order, first)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG192"));

        // A rejected transfer does not hold its reference.
        body = pay(ownerToken, order, 285_000, null, "BANK_TRANSFER", "FT1", false).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long second = ((Number) JsonPath.read(body, "$.paymentId")).longValue();
        verify(ownerToken, order, second).andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.order.status").value("COMPLETED"))
                .andExpect(jsonPath("$.order.money.payments[1].verifiedBy").value("Test owner"));
    }

    @Test
    void aFailedCompletionKeepsThePaymentAndCanBeRetried() throws Exception {
        long order = confirmedOrder(pad, 2);   // 200,000 đ
        // Meanwhile the unexpired pads left the store.
        jdbc.update("UPDATE lot_balance SET quantity = 0 WHERE lot_id IN (?, ?)", padSoon, padNoExpiry);

        pay(managerToken, order, 200_000, null, "CASH", null, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.completionBlockedCode").value("MSG171"))
                .andExpect(jsonPath("$.completionBlockedMessage").value("Không đủ hàng còn hạn tại cửa hàng để giao: "
                        + "MA-PHANH-01 cần 2, còn 0."))
                .andExpect(jsonPath("$.order.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.order.money.netCollected").value(200_000))
                .andExpect(jsonPath("$.order.actions.canComplete").value(true));
        assertThat(saleIssues()).isZero();

        jdbc.update("UPDATE lot_balance SET quantity = 3 WHERE lot_id = ?", padSoon);
        jdbc.update("UPDATE lot_balance SET quantity = 5 WHERE lot_id = ?", padNoExpiry);
        complete(managerToken, order, 2)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG165"));
        complete(managerToken, order, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.closing.completionType").value("PAID"));
        assertThat(saleIssues()).isEqualTo(1);
    }

    @Test
    void aTechnicalFailureWhileCompletingKeepsThePaymentAndSaysSo() throws Exception {
        long order = confirmedOrder(pad, 2);   // 200,000 đ
        // The stock cannot be written for a moment, as with a lock timeout or a lost connection.
        jdbc.execute("CREATE FUNCTION test_refuse_movement() RETURNS trigger AS $$ BEGIN "
                + "RAISE EXCEPTION 'stock is unavailable'; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER test_refuse_movement BEFORE INSERT ON stock_movement "
                + "FOR EACH ROW EXECUTE FUNCTION test_refuse_movement()");
        try {
            pay(managerToken, order, 200_000, null, "CASH", null, true).andExpect(status().isOk())
                    .andExpect(jsonPath("$.completed").value(false))
                    .andExpect(jsonPath("$.paymentId").isNumber())
                    .andExpect(jsonPath("$.completionBlockedCode").value("MSG195"))
                    .andExpect(jsonPath("$.completionBlockedMessage").value("Đã ghi khoản tiền nhưng chưa hoàn tất "
                            + "được đơn bán. Vui lòng bấm Hoàn tất để thử lại."))
                    .andExpect(jsonPath("$.order.status").value("CONFIRMED"))
                    .andExpect(jsonPath("$.order.money.netCollected").value(200_000));
        } finally {
            jdbc.execute("DROP TRIGGER test_refuse_movement ON stock_movement");
            jdbc.execute("DROP FUNCTION test_refuse_movement()");
        }
        assertThat(saleIssues()).isZero();

        // Completion re-checks the discount threshold in effect now.
        jdbc.update("UPDATE sales_order SET discount_rate = 5 WHERE id = ?", order);
        jdbc.update("DELETE FROM pricing_policy WHERE policy_type = 'DISCOUNT_THRESHOLD'");
        complete(managerToken, order, 1)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG163"));
        jdbc.update("UPDATE sales_order SET discount_rate = 0 WHERE id = ?", order);
        complete(managerToken, order, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(saleIssues()).isEqualTo(1);
    }

    @Test
    void anOverpaymentIsRefundedBeforeTheOrderCompletes() throws Exception {
        long order = confirmedOrder(pad, 2, oil, 1);   // 285,000 đ
        pay(managerToken, order, 200_000, null, "CASH", null, true).andExpect(status().isOk());

        // The customer now takes one pad less: a new version of 185,000 đ (UC-55 Step 4_Revised total below
        // collected money).
        update(managerToken, order, 1, order(null, null, "RETAIL", null, lines(pad, 2, oil, 1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PICKED"));
        pay(managerToken, order, 1_000, null, "CASH", null, true)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG179"));
        confirm(managerToken, order, 2, pad, 1, oil, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.money.total").value(185_000))
                .andExpect(jsonPath("$.money.overpaidAmount").value(15_000))
                .andExpect(jsonPath("$.money.remainingPayable").value(0))
                .andExpect(jsonPath("$.actions.canComplete").value(false))
                .andExpect(jsonPath("$.actions.canRefund").value(true));
        complete(managerToken, order, 3)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG187"))
                .andExpect(jsonPath("$.message").value("Đơn bán đã thu thừa 15.000 đ so với tổng tiền. Vui lòng hoàn "
                        + "lại phần thừa trước khi hoàn tất."));
        refund(managerToken, order, 250_000, "CASH", true)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG183"));

        Map<String, Object> part = Map.of("amount", 5_000, "paymentMethod", "CASH", "verified", true,
                "expectedRefundable", 200_000);
        send(managerToken, "/api/v1/sales-orders/" + order + "/refunds", part).andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.order.money.overpaidAmount").value(10_000));
        // BR-45: the same refund sent again is not recorded twice.
        send(managerToken, "/api/v1/sales-orders/" + order + "/refunds", part)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG194"));

        refund(managerToken, order, 10_000, "CASH", true).andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.order.status").value("COMPLETED"))
                .andExpect(jsonPath("$.order.money.verifiedRefunded").value(15_000))
                .andExpect(jsonPath("$.order.money.netCollected").value(185_000));
        assertThat(jdbc.queryForObject("SELECT sum(-quantity_change) FROM stock_movement WHERE sales_order_id = ?",
                Integer.class, order)).isEqualTo(2);
    }

    @Test
    void verifyingARefundLargerThanWhatTheOrderNowHoldsIsRefused() throws Exception {
        long order = confirmedOrder(pad, 2);   // 200,000 đ
        String body = pay(managerToken, order, 100_000, null, "CASH", null, true).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long paid = ((Number) JsonPath.read(body, "$.paymentId")).longValue();
        body = refund(managerToken, order, 60_000, "CASH", false).andExpect(status().isOk())
                .andExpect(jsonPath("$.order.money.refundable").value(40_000))
                .andReturn().getResponse().getContentAsString();
        long refund = ((Number) JsonPath.read(body, "$.paymentId")).longValue();

        // BR-49: an applied correction finds that only 50,000 đ was taken, so the order holds less than the refund.
        long correction = jdbc.queryForObject("INSERT INTO adjustment_request (adjustment_type, store_id, payment_id, "
                + "reason, status, requested_by, decided_by, decided_at, applied_at) VALUES ('DOCUMENT_CORRECTION', ?, "
                + "?, 'Ghi nhầm số tiền', 'APPLIED', ?, ?, ?, ?) RETURNING id", Long.class, store, paid, owner.getId(),
                owner.getId(), LocalDateTime.now(clock), LocalDateTime.now(clock));
        jdbc.update("INSERT INTO adjustment_amount_line (adjustment_request_id, payment_id, amount_change) "
                + "VALUES (?, ?, -50000)", correction, paid);

        verify(managerToken, order, refund)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG183"))
                .andExpect(jsonPath("$.message").value("Số tiền hoàn không được lớn hơn 50.000 đ, là số đơn bán đang "
                        + "giữ của khách."));
        assertThat(jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, refund))
                .isEqualTo("UNVERIFIED");
    }

    @Test
    void aVerificationAndACompletionAtTheSameMomentTakeTheGoodsOutOnce() throws Exception {
        long order = confirmedOrder(pad, 2, oil, 1);   // 285,000 đ, two lots
        String body = pay(managerToken, order, 285_000, null, "BANK_TRANSFER", "FT26101300777", false)
                .andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(false))
                .andReturn().getResponse().getContentAsString();
        long payment = ((Number) JsonPath.read(body, "$.paymentId")).longValue();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        MvcResult verified;
        MvcResult completed;
        try (Connection other = dataSource.getConnection()) {
            // Hold the order, so that both requests are waiting for it when it is let go.
            other.setAutoCommit(false);
            try (PreparedStatement lock = other.prepareStatement("SELECT id FROM sales_order WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, order);
                lock.executeQuery().close();
            }
            Future<MvcResult> verifying = executor.submit(() -> verify(managerToken, order, payment).andReturn());
            awaitLockWaiters(1, verifying);
            Future<MvcResult> completing = executor.submit(() -> complete(ownerToken, order, 1).andReturn());
            awaitLockWaiters(2, completing);
            other.commit();
            verified = verifying.get(30, TimeUnit.SECONDS);
            completed = completing.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        // Whichever request completes the order, the other one finds it completed (MSG164) or still unverified
        // (MSG184); the goods leave once.
        assertThat(verified.getResponse().getStatus()).as(verified.getResponse().getContentAsString()).isEqualTo(200);
        String completion = completed.getResponse().getContentAsString();
        if (completed.getResponse().getStatus() != 200) {
            assertThat(completed.getResponse().getStatus()).as(completion).isEqualTo(409);
            assertThat(JsonPath.<String>read(completion, "$.code")).isIn("MSG164", "MSG184");
        }
        assertThat(jdbc.queryForObject("SELECT status FROM sales_order WHERE id = ?", String.class, order))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE movement_type = 'SALE_ISSUE' "
                + "AND sales_order_id = ?", Integer.class, order)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT sum(-quantity_change) FROM stock_movement WHERE sales_order_id = ?",
                Integer.class, order)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT quantity FROM lot_balance WHERE lot_id = ? AND stock_status = "
                + "'AVAILABLE'", Integer.class, padSoon)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_type = 'SALES_ORDER' "
                + "AND entity_id = ? AND action = 'SALES_ORDER_COMPLETED'", Integer.class, order)).isEqualTo(1);
    }

    @Test
    void creditCompletesTheOrderWithOneReceivable() throws Exception {
        long order = confirmedOrder(pad, 2, oil, 1);   // 285,000 đ
        pay(managerToken, order, 85_000, null, "CASH", null, true).andExpect(status().isOk());
        String due = TODAY.plusDays(30).toString();

        credit(managerToken, order, 1, 200_000, due, true)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG189"));
        detail(managerToken, order).andExpect(jsonPath("$.actions.canRecordCredit").value(false));
        jdbc.update("UPDATE customer SET credit_status = 'ELIGIBLE' WHERE id = ?", customer);
        detail(managerToken, order).andExpect(jsonPath("$.actions.canRecordCredit").value(true));

        credit(managerToken, order, 1, 150_000, due, true)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG190"))
                .andExpect(jsonPath("$.message").value("Số tiền ghi nợ phải bằng 200.000 đ, là số còn phải thu của "
                        + "đơn. Vui lòng tải lại đơn."));
        credit(managerToken, order, 1, 200_000, TODAY.minusDays(1).toString(), true)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG178"));
        credit(managerToken, order, 1, 200_000, due, false)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG02"));
        credit(managerToken, order, 2, 200_000, due, true)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG165"));

        credit(managerToken, order, 1, 200_000, due, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.closing.completionType").value("CREDIT"))
                .andExpect(jsonPath("$.money.credit.decision").value("APPROVED"))
                .andExpect(jsonPath("$.money.credit.amount").value(200_000))
                .andExpect(jsonPath("$.money.receivable.originalAmount").value(200_000))
                .andExpect(jsonPath("$.money.receivable.remainingAmount").value(200_000))
                .andExpect(jsonPath("$.money.receivable.dueDate").value(due))
                .andExpect(jsonPath("$.money.remainingPayable").value(0));
        assertThat(jdbc.queryForMap("SELECT origin_type, customer_id, store_id, original_amount FROM receivable "
                + "WHERE sales_order_id = ?", order)).containsEntry("origin_type", "CREDIT_SALE")
                .containsEntry("customer_id", customer).containsEntry("store_id", store)
                .containsEntry("original_amount", 200_000L);
        assertThat(saleIssues()).isEqualTo(2);
        credit(managerToken, order, 1, 200_000, due, true)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG164"));

        // A walk-in sale has nobody to owe the money.
        String walkIn = create(managerToken, order(null, null, "RETAIL", null, lines(oil, 1)))
                .andReturn().getResponse().getContentAsString();
        long sale = ((Number) JsonPath.read(walkIn, "$.id")).longValue();
        pickAndConfirm(sale, oil, 1);
        credit(managerToken, sale, 1, 85_000, due, true)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG188"));
    }

    @Test
    void cancellingRefundsWhatTheOrderHoldsAndChangesNoStock() throws Exception {
        long order = confirmedOrder(pad, 2, oil, 1);   // 285,000 đ
        pay(managerToken, order, 100_000, null, "CASH", null, true).andExpect(status().isOk());
        refund(managerToken, order, 30_000, "CASH", true).andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.order.money.netCollected").value(70_000))
                .andExpect(jsonPath("$.order.money.refundable").value(70_000));

        cancel(managerToken, order, "Khách đổi ý", null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG185"))
                .andExpect(jsonPath("$.message").value("Đơn bán còn giữ 70.000 đ của khách. Vui lòng hoàn đủ số tiền "
                        + "này rồi mới hủy đơn."));
        cancel(managerToken, order, "Khách đổi ý", 100_000L)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG183"));
        cancel(managerToken, order, "Khách đổi ý", 50_000L)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG185"));
        cancel(managerToken, order, " ", 70_000L)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG02"));

        cancel(managerToken, order, " Khách đổi ý ", 70_000L).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.closing.cancellationReason").value("Khách đổi ý"))
                .andExpect(jsonPath("$.closing.cancelledBy").value("Test bm"))
                .andExpect(jsonPath("$.money.verifiedRefunded").value(100_000))
                .andExpect(jsonPath("$.money.netCollected").value(0))
                .andExpect(jsonPath("$.actions.canUpdate").value(false))
                .andExpect(jsonPath("$.history[0].action").value("CANCELLED"));
        assertThat(saleIssues()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE sales_order_id = ? AND payment_type = "
                + "'REFUND'", Integer.class, order)).isEqualTo(2);

        // Step 6_Order already cancelled: shown again, not repeated.
        cancel(managerToken, order, "Lần nữa", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.closing.cancellationReason").value("Khách đổi ý"));
        update(managerToken, order, 1, order(null, customer, "RETAIL", null, lines(pad, 1)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG164"));
        list(managerToken, "view", "CANCELLED").andExpect(jsonPath("$.items[*].id", hasItem((int) order)));
    }

    @Test
    void cancellingAnOrderBeingPickedTellsThePicker() throws Exception {
        long order = createOrder(pad, 1);
        assign(managerToken, "PICKING", order, staff).andExpect(status().isOk());

        cancel(ownerToken, order, "Hết nhu cầu", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(taskStatus("PICKING", order)).isEqualTo("CANCELLED");
        assertThat(notifications(staff, "SALES_ORDER_CANCELLED")).isEqualTo(1);
        list(staffToken).andExpect(jsonPath("$.items", hasSize(0)));
        pick(staffToken, order, 1, pad, 1, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG164"));
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    /** An order picked in full by Staff and confirmed by the Branch Manager, version 1. */
    private long confirmedOrder(long... productAndQuantity) throws Exception {
        long order = createOrder(productAndQuantity);
        pickAndConfirm(order, productAndQuantity);
        return order;
    }

    private void pickAndConfirm(long order, long... productAndQuantity) throws Exception {
        assign(managerToken, "PICKING", order, staff).andExpect(status().isOk());
        Object[] picked = new Object[productAndQuantity.length / 2 * 3];
        for (int i = 0, j = 0; i < productAndQuantity.length; i += 2, j += 3) {
            picked[j] = productAndQuantity[i];
            picked[j + 1] = productAndQuantity[i + 1];
        }
        pick(staffToken, order, 1, picked).andExpect(status().isOk());
        confirm(managerToken, order, 1, productAndQuantity).andExpect(status().isOk());
    }

    private ResultActions pay(String token, long order, long amount, Long tendered, String method, String reference,
                              boolean verified) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount);
        body.put("tenderedAmount", tendered);
        body.put("paymentMethod", method);
        body.put("transactionReference", reference);
        body.put("verified", verified);
        body.put("expectedPayable", moneyNow(order, "payableNow"));
        return send(token, "/api/v1/sales-orders/" + order + "/payments", body);
    }

    private ResultActions refund(String token, long order, long amount, String method, boolean verified)
            throws Exception {
        return send(token, "/api/v1/sales-orders/" + order + "/refunds", Map.of("amount", amount,
                "paymentMethod", method, "verified", verified, "expectedRefundable", moneyNow(order, "refundable")));
    }

    private ResultActions verify(String token, long order, long payment) throws Exception {
        return mvc.perform(post("/api/v1/sales-orders/" + order + "/payments/" + payment + "/verify")
                .header(HttpHeaders.AUTHORIZATION, token));
    }

    private ResultActions reject(String token, long order, long payment, String reason) throws Exception {
        return send(token, "/api/v1/sales-orders/" + order + "/payments/" + payment + "/reject",
                Map.of("reason", reason));
    }

    private ResultActions complete(String token, long order, int version) throws Exception {
        return send(token, "/api/v1/sales-orders/" + order + "/complete", Map.of("version", version));
    }

    private ResultActions credit(String token, long order, int version, long amount, String dueDate, boolean agreed)
            throws Exception {
        return send(token, "/api/v1/sales-orders/" + order + "/credit", Map.of("version", version,
                "creditAmount", amount, "dueDate", dueDate, "customerAgreed", agreed));
    }

    private ResultActions cancel(String token, long order, String reason, Long refund) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        if (refund != null) {
            body.put("refund", Map.of("amount", refund, "paymentMethod", "CASH", "paidToCustomer", true));
        }
        return send(token, "/api/v1/sales-orders/" + order + "/cancel", body);
    }

    private ResultActions send(String token, String path, Map<String, Object> body) throws Exception {
        return mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    /** Waits until {@code count} sessions wait for a lock, or the request has finished without waiting. */
    private void awaitLockWaiters(int count, Future<?> request) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!request.isDone() && System.nanoTime() < deadline && jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'", Integer.class) < count) {
            Thread.sleep(20);
        }
        assertThat(request.isDone()).as("the request waits for the order").isFalse();
    }

    private int saleIssues() {
        return jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE movement_type = 'SALE_ISSUE'",
                Integer.class);
    }
}
