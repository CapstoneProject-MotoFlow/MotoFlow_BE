package fu.hl.g64.motorflow.receivable;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.support.ApiTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-62 to UC-67: credit eligibility, opening debts, the debt list and detail, and debt payments (BR-70). */
class CustomerDebtApiTest extends ApiTestUsers {

    /** The clock starts on 13/10/2026 (Vietnam time), so a debt due on 10/10 is overdue and one due on 13/10 is not. */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    @Autowired
    private DataSource dataSource;

    private UserAccount owner;
    private UserAccount manager;
    /** CH01 holds the central warehouse; the customers buy at CH02 (Gia Lâm) and CH03 (Long Biên). */
    private long central;
    private long giaLam;
    private long longBien;
    private String ownerToken;
    /** Branch Manager of Gia Lâm. */
    private String managerToken;
    private String longBienManagerToken;
    private String centralManagerToken;
    private String staffToken;
    private long hung;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabaseAndClock();
        owner = createUser("owner", Role.OWNER, null);
        central = createStore("Kho tổng");
        jdbc.update("UPDATE store SET is_central = TRUE WHERE id = ?", central);
        giaLam = createStore("Gia Lâm");
        longBien = createStore("Long Biên");
        manager = createUser("bm-gialam", Role.BRANCH_MANAGER, giaLam);
        createUser("bm-longbien", Role.BRANCH_MANAGER, longBien);
        createUser("bm-central", Role.BRANCH_MANAGER, central);
        createUser("staff-gialam", Role.STAFF, giaLam);
        ownerToken = signIn("owner");
        managerToken = signIn("bm-gialam");
        longBienManagerToken = signIn("bm-longbien");
        centralManagerToken = signIn("bm-central");
        staffToken = signIn("staff-gialam");
        hung = customer("Garage Hùng", "0912 345 678");
    }

    @Test
    void onlyOwnerGrantsAndRevokesCreditWithAReasonAndRepeatsChangeNothing() throws Exception {
        send(post("/api/v1/customers/{id}/credit/grant", hung), managerToken, reason("Khách quen"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        send(post("/api/v1/customers/{id}/credit/grant", hung), ownerToken, reason(" "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"))
                .andExpect(jsonPath("$.fieldErrors.reason").value("Vui lòng nhập lý do."));
        send(post("/api/v1/customers/{id}/credit/revoke", hung), ownerToken, reason("Trả chậm"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG146"))
                .andExpect(jsonPath("$.message")
                        .value("Khách hàng Garage Hùng chưa được cấp quyền mua nợ nên không có gì để thu hồi."));
        send(post("/api/v1/customers/{id}/credit/grant", 999_999), ownerToken, reason("Khách quen"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG74"));

        send(post("/api/v1/customers/{id}/credit/grant", hung), ownerToken, reason(" Khách quen 5 năm "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditStatus").value("ELIGIBLE"))
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.lastDecision.eventType").value("GRANTED"))
                .andExpect(jsonPath("$.lastDecision.reason").value("Khách quen 5 năm"))
                .andExpect(jsonPath("$.lastDecision.decidedBy").value("Test owner"))
                .andExpect(jsonPath("$.totalDebt").value(0));
        // Step 2_Already eligible: the existing grant stands and nothing is recorded again.
        send(post("/api/v1/customers/{id}/credit/grant", hung), ownerToken, reason("Lần nữa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(false))
                .andExpect(jsonPath("$.lastDecision.reason").value("Khách quen 5 năm"));
        // The customer profile (UC-28) shows the decision as its credit history.
        send(get("/api/v1/customers/{id}", hung), managerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.creditStatus").value("ELIGIBLE"))
                .andExpect(jsonPath("$.creditHistory[*].eventType", contains("GRANTED")))
                .andExpect(jsonPath("$.creditHistory[0].reason").value("Khách quen 5 năm"));

        creditSale(hung, giaLam, 2_000_000, at(5), LocalDate.of(2026, 10, 10));
        send(post("/api/v1/customers/{id}/credit/revoke", hung), ownerToken, reason("Trả chậm nhiều lần"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditStatus").value("REVOKED"))
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.lastDecision.eventType").value("REVOKED"))
                // BR-43: the debt stays to be collected.
                .andExpect(jsonPath("$.totalDebt").value(2_000_000))
                .andExpect(jsonPath("$.overdueDebt").value(2_000_000));
        send(post("/api/v1/customers/{id}/credit/revoke", hung), ownerToken, reason("Lần nữa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(false))
                .andExpect(jsonPath("$.lastDecision.reason").value("Trả chậm nhiều lần"));
        // Owner may grant it again after a revocation.
        send(post("/api/v1/customers/{id}/credit/grant", hung), ownerToken, reason("Đã trả đúng hẹn"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditStatus").value("ELIGIBLE"))
                .andExpect(jsonPath("$.changed").value(true));

        assertThat(jdbc.queryForList("SELECT action || ',' || reason || ',' || (old_data->>'credit_status') || ',' "
                        + "|| (new_data->>'credit_status') || ',' || actor_id FROM audit_log WHERE entity_type = 'CUSTOMER' "
                        + "AND entity_id = ? ORDER BY id", String.class, hung))
                .containsExactly("CREDIT_GRANTED,Khách quen 5 năm,NOT_GRANTED,ELIGIBLE," + owner.getId(),
                        "CREDIT_REVOKED,Trả chậm nhiều lần,ELIGIBLE,REVOKED," + owner.getId(),
                        "CREDIT_GRANTED,Đã trả đúng hẹn,REVOKED,ELIGIBLE," + owner.getId());
    }

    @Test
    void ownerRecordsAnOpeningDebtWithItsEvidenceOnce() throws Exception {
        send(post("/api/v1/customers/{id}/opening-debts", hung), managerToken, openingDebt(giaLam, 1_500_000, "2026-09-30"))
                .andExpect(status().isForbidden());
        send(post("/api/v1/customers/{id}/opening-debts", hung), ownerToken, Map.of("note", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"))
                .andExpect(jsonPath("$.fieldErrors.storeId").value("Vui lòng chọn cửa hàng."))
                .andExpect(jsonPath("$.fieldErrors.amount").value("Vui lòng nhập số tiền nợ."))
                .andExpect(jsonPath("$.fieldErrors.dueDate").value("Vui lòng nhập hạn trả."))
                .andExpect(jsonPath("$.fieldErrors.note").value("Vui lòng nhập diễn giải khoản nợ."))
                .andExpect(jsonPath("$.fieldErrors.evidence").value("Vui lòng đính kèm chứng từ của khoản nợ."));
        long planned = createStore("Hà Đông");
        jdbc.update("UPDATE store SET status = 'PLANNED' WHERE id = ?", planned);
        send(post("/api/v1/customers/{id}/opening-debts", hung), ownerToken, openingDebt(planned, 1_500_000, "2026-09-30"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG46"));
        send(post("/api/v1/customers/{id}/opening-debts", 999_999), ownerToken, openingDebt(giaLam, 1_500_000, "2026-09-30"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG74"));

        // A due date in the past is allowed; the debt is shown overdue at once.
        String body = send(post("/api/v1/customers/{id}/opening-debts", hung), ownerToken,
                openingDebt(giaLam, 1_500_000, "2026-09-30"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/customers/" + hung + "/debts?storeId=" + giaLam))
                .andExpect(jsonPath("$.originType").value("OPENING_BALANCE"))
                .andExpect(jsonPath("$.storeName").value("Gia Lâm"))
                .andExpect(jsonPath("$.salesOrderId").doesNotExist())
                .andExpect(jsonPath("$.originalAmount").value(1_500_000))
                .andExpect(jsonPath("$.remainingAmount").value(1_500_000))
                .andExpect(jsonPath("$.status").value("OUTSTANDING"))
                .andExpect(jsonPath("$.dueDate").value("2026-09-30"))
                .andExpect(jsonPath("$.overdue").value(true))
                .andExpect(jsonPath("$.note").value("Nợ ghi sổ tay năm 2025"))
                .andExpect(jsonPath("$.recognizedBy").value("Test owner"))
                .andExpect(jsonPath("$.evidence[*].fileName", contains("so-no.jpg")))
                .andReturn().getResponse().getContentAsString();
        long debt = ((Number) JsonPath.read(body, "$.id")).longValue();

        // Step 5_Debt already recorded: the same amount and due date again is refused.
        Map<String, Object> again = openingDebt(giaLam, 1_500_000, "2026-09-30");
        again.put("evidence", List.of(file("so-no-2.jpg")));
        send(post("/api/v1/customers/{id}/opening-debts", hung), ownerToken, again)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG147"))
                .andExpect(jsonPath("$.message").value("Khoản nợ đầu kỳ này đã được ghi (khoản nợ số " + debt
                        + "). Vui lòng mở khoản nợ đó thay vì ghi lại."));
        Map<String, Object> other = openingDebt(longBien, 1_500_000, "2026-09-30");
        other.put("evidence", List.of(file("so-no-3.jpg")));
        send(post("/api/v1/customers/{id}/opening-debts", hung), ownerToken, other).andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM receivable WHERE customer_id = ?", Long.class, hung))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT new_data->>'amount' || ',' || (new_data->>'due_date') || ',' || actor_id "
                        + "FROM audit_log WHERE entity_type = 'RECEIVABLE' AND entity_id = ? AND action = 'OPENING_DEBT_RECORDED'",
                String.class, debt)).isEqualTo("1500000,2026-09-30," + owner.getId());
        // BR-52: an opening debt is not revenue and moves no stock.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Long.class)).isZero();
        send(get("/api/v1/customers?q=Hùng"), managerToken)
                .andExpect(jsonPath("$.items[0].totalDebt").value(3_000_000))
                .andExpect(jsonPath("$.items[0].debtAtOwnStore").value(1_500_000));
    }

    @Test
    void aPaymentSettlesOpeningDebtsFirstThenTheOldestCreditSales() throws Exception {
        // Order B was completed after order A but has the smaller number: completion time decides (BR-70).
        long orderB = creditSale(hung, giaLam, 600_000, at(8), LocalDate.of(2026, 10, 10));
        long orderA = creditSale(hung, giaLam, 1_000_000, at(5), LocalDate.of(2026, 10, 20));
        long elsewhere = creditSale(hung, longBien, 300_000, at(1), LocalDate.of(2026, 10, 2));
        long opening = openingDebtId(giaLam, 500_000, "2026-12-31");
        long a = receivableOf(orderA);
        long b = receivableOf(orderB);

        send(post("/api/v1/customers/{id}/debt-payments/preview", hung), managerToken, Map.of("amount", 1_800_000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(giaLam))
                .andExpect(jsonPath("$.storeName").value("Gia Lâm"))
                .andExpect(jsonPath("$.eligibleTotal").value(2_100_000))
                .andExpect(jsonPath("$.allocations[*].receivableId", contains(ids(opening, a, b))))
                .andExpect(jsonPath("$.allocations[*].remainingBefore", contains(500_000, 1_000_000, 600_000)))
                .andExpect(jsonPath("$.allocations[*].allocatedAmount", contains(500_000, 1_000_000, 300_000)))
                .andExpect(jsonPath("$.allocations[*].remainingAfter", contains(0, 0, 300_000)))
                .andExpect(jsonPath("$.allocations[*].overdue", contains(false, false, true)));
        send(post("/api/v1/customers/{id}/debt-payments/preview", hung), managerToken, Map.of("amount", 2_200_000))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG150"))
                .andExpect(jsonPath("$.message").value(
                        "Số tiền thu nợ không được lớn hơn 2.100.000 đ, là số còn nợ của các khoản được trả."));

        Map<String, Object> payment = cash(1_800_000, 2_000_000L, line(opening, 500_000, 500_000),
                line(a, 1_000_000, 1_000_000), line(b, 600_000, 200_000));
        send(post("/api/v1/customers/{id}/debt-payments", hung), managerToken, payment)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG152"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment", Long.class)).isZero();

        payment = cash(1_800_000, 2_000_000L, line(b, 600_000, 300_000), line(opening, 500_000, 500_000),
                line(a, 1_000_000, 1_000_000));
        String body = send(post("/api/v1/customers/{id}/debt-payments", hung), managerToken, payment)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId").value(hung))
                .andExpect(jsonPath("$.storeId").value(giaLam))
                .andExpect(jsonPath("$.amount").value(1_800_000))
                .andExpect(jsonPath("$.tenderedAmount").value(2_000_000))
                .andExpect(jsonPath("$.changeAmount").value(200_000))
                .andExpect(jsonPath("$.paymentMethod").value("CASH"))
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.recordedBy").value("Test bm-gialam"))
                .andExpect(jsonPath("$.allocations[*].receivableId", contains(ids(opening, a, b))))
                .andExpect(jsonPath("$.allocations[*].allocatedAmount", contains(500_000, 1_000_000, 300_000)))
                .andExpect(jsonPath("$.allocations[*].remainingAmount", contains(0, 0, 300_000)))
                .andReturn().getResponse().getContentAsString();
        long paymentId = ((Number) JsonPath.read(body, "$.id")).longValue();

        assertThat(jdbc.queryForMap("SELECT payment_type, sales_order_id, status, recorded_by, verified_by "
                + "FROM payment WHERE id = ?", paymentId))
                .containsEntry("payment_type", "DEBT_PAYMENT").containsEntry("status", "VERIFIED")
                .containsEntry("recorded_by", manager.getId()).containsEntry("verified_by", manager.getId())
                .containsEntry("sales_order_id", null);
        assertThat(jdbc.queryForList("SELECT id FROM receivable WHERE settled_at IS NOT NULL ORDER BY id", Long.class))
                .containsExactlyInAnyOrder(opening, a);
        assertThat(jdbc.queryForObject("SELECT new_data->>'store_id' FROM audit_log WHERE entity_type = 'PAYMENT' "
                + "AND action = 'DEBT_PAYMENT_RECORDED' AND entity_id = ?", String.class, paymentId))
                .isEqualTo(String.valueOf(giaLam));
        // BR-52: collecting a debt creates no revenue and moves no stock.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Long.class)).isZero();

        send(get("/api/v1/customers/{id}/debts", hung), managerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(giaLam))
                .andExpect(jsonPath("$.totalDebt").value(600_000))
                .andExpect(jsonPath("$.debt").value(300_000))
                .andExpect(jsonPath("$.overdueDebt").value(300_000))
                .andExpect(jsonPath("$.debts[*].id", contains(ids(opening, a, b))))
                .andExpect(jsonPath("$.debts[*].status", contains("SETTLED", "SETTLED", "PARTIALLY_PAID")))
                .andExpect(jsonPath("$.debts[*].collectedAmount", contains(500_000, 1_000_000, 300_000)))
                .andExpect(jsonPath("$.debts[*].salesOrderId", contains(null, (int) orderA, (int) orderB)))
                .andExpect(jsonPath("$.debts[2].overdue").value(true))
                .andExpect(jsonPath("$.payments", hasSize(1)))
                .andExpect(jsonPath("$.payments[0].allocations", hasSize(3)));
        send(get("/api/v1/receivables/customers"), managerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].customerId").value(hung))
                .andExpect(jsonPath("$.items[0].debt").value(300_000))
                .andExpect(jsonPath("$.items[0].totalDebt").value(600_000))
                .andExpect(jsonPath("$.items[0].overdueDebt").value(300_000))
                .andExpect(jsonPath("$.items[0].openDebtCount").value(1))
                .andExpect(jsonPath("$.items[0].earliestDueDate").value("2026-10-10"));

        // The same payment sent twice: the debts it would pay are already paid.
        send(post("/api/v1/customers/{id}/debt-payments", hung), managerToken, payment)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG150"))
                .andExpect(jsonPath("$.message").value(startsWith("Số tiền thu nợ không được lớn hơn 300.000 đ")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment", Long.class)).isEqualTo(1);
        assertThat(receivableOf(elsewhere)).isPositive();
    }

    @Test
    void theCustomerCanNameTheDebtsAndATransferIsCountedOnce() throws Exception {
        long opening = openingDebtId(giaLam, 400_000, "2026-11-30");
        long c = receivableOf(creditSale(hung, giaLam, 700_000, at(3), LocalDate.of(2026, 11, 3)));
        long d = receivableOf(creditSale(hung, giaLam, 900_000, at(6), LocalDate.of(2026, 11, 6)));
        long elsewhere = receivableOf(creditSale(hung, longBien, 300_000, at(1), LocalDate.of(2026, 11, 1)));

        // Within the named debts the same order applies; the opening debt is left alone.
        Map<String, Object> named = new LinkedHashMap<>(Map.of("amount", 1_000_000, "receivableIds", List.of(d, c)));
        send(post("/api/v1/customers/{id}/debt-payments/preview", hung), managerToken, named)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibleTotal").value(1_600_000))
                .andExpect(jsonPath("$.allocations[*].receivableId", contains(ids(c, d))))
                .andExpect(jsonPath("$.allocations[*].allocatedAmount", contains(700_000, 300_000)));
        named.put("receivableIds", List.of(c, elsewhere));
        send(post("/api/v1/customers/{id}/debt-payments/preview", hung), managerToken, named)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG148"))
                .andExpect(jsonPath("$.message").value("Khoản nợ số " + elsewhere
                        + " không phải khoản nợ còn lại của khách hàng này tại cửa hàng đã chọn."));

        Map<String, Object> transfer = payment("BANK_TRANSFER", 1_000_000, null, List.of(d, c),
                line(c, 700_000, 700_000), line(d, 900_000, 300_000));
        send(post("/api/v1/customers/{id}/debt-payments", hung), managerToken, transfer)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG154"));
        transfer.put("tenderedAmount", 1_000_000);
        transfer.put("transactionReference", "FT2610130001");
        send(post("/api/v1/customers/{id}/debt-payments", hung), managerToken, transfer)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG153"))
                .andExpect(jsonPath("$.message").value(
                        "Tiền khách đưa chỉ ghi khi thu tiền mặt và không được nhỏ hơn số tiền thu nợ."));
        transfer.remove("tenderedAmount");
        transfer.put("evidence", List.of(file("chuyen-khoan.png")));
        String body = send(post("/api/v1/customers/{id}/debt-payments", hung), managerToken, transfer)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionReference").value("FT2610130001"))
                .andExpect(jsonPath("$.tenderedAmount").doesNotExist())
                .andExpect(jsonPath("$.changeAmount").doesNotExist())
                .andExpect(jsonPath("$.evidence[*].fileName", contains("chuyen-khoan.png")))
                .andReturn().getResponse().getContentAsString();
        long first = ((Number) JsonPath.read(body, "$.id")).longValue();

        // BR-45: the same transfer, typed again, is not counted twice.
        Map<String, Object> repeat = payment("BANK_TRANSFER", 100_000, null, List.of(opening),
                line(opening, 400_000, 100_000));
        repeat.put("transactionReference", " ft2610130001 ");
        send(post("/api/v1/customers/{id}/debt-payments", hung), managerToken, repeat)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG151"))
                .andExpect(jsonPath("$.message").value("Mã giao dịch ft2610130001 đã được ghi ở phiếu thu số " + first + "."));
        Map<String, Object> shortCash = payment("CASH", 100_000, null, List.of(opening),
                line(opening, 400_000, 100_000));
        shortCash.put("tenderedAmount", 50_000);
        send(post("/api/v1/customers/{id}/debt-payments", hung), managerToken, shortCash)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG153"));

        send(get("/api/v1/customers/{id}/debts", hung), managerToken)
                .andExpect(jsonPath("$.debts[*].id", contains(ids(opening, c, d))))
                .andExpect(jsonPath("$.debts[*].status", contains("OUTSTANDING", "SETTLED", "PARTIALLY_PAID")))
                .andExpect(jsonPath("$.debts[*].remainingAmount", contains(400_000, 0, 600_000)))
                .andExpect(jsonPath("$.debts[0].evidence[*].fileName", contains("so-no.jpg")))
                .andExpect(jsonPath("$.payments[0].evidence[*].fileName", contains("chuyen-khoan.png")))
                .andExpect(jsonPath("$.debt").value(1_000_000))
                .andExpect(jsonPath("$.totalDebt").value(1_300_000));
    }

    @Test
    void eachCollectorWorksForTheirStoreAndOwnerChoosesOne() throws Exception {
        long gl = receivableOf(creditSale(hung, giaLam, 800_000, at(2), LocalDate.of(2026, 10, 9)));
        long lb = receivableOf(creditSale(hung, longBien, 500_000, at(4), LocalDate.of(2026, 10, 30)));
        long binh = customer("Nguyễn Văn Bình", "0987654321");
        long binhDebt = receivableOf(creditSale(binh, longBien, 2_000_000, at(7), LocalDate.of(2026, 10, 31)));

        send(post("/api/v1/customers/{id}/debt-payments/preview", hung), ownerToken, Map.of("amount", 100_000))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG155"))
                .andExpect(jsonPath("$.message").value("Vui lòng chọn cửa hàng nơi khách trả nợ."));
        send(post("/api/v1/customers/{id}/debt-payments/preview", hung), ownerToken,
                Map.of("amount", 100_000, "storeId", 999_999))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG52"));
        send(post("/api/v1/customers/{id}/debt-payments/preview", hung), managerToken,
                Map.of("amount", 100_000, "storeId", longBien))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        send(post("/api/v1/customers/{id}/debt-payments/preview", hung), staffToken, Map.of("amount", 100_000))
                .andExpect(status().isForbidden());
        send(post("/api/v1/customers/{id}/debt-payments/preview", binh), managerToken, Map.of("amount", 100_000))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG149"))
                .andExpect(jsonPath("$.message").value("Khách hàng không còn khoản nợ nào tại cửa hàng này."));
        send(post("/api/v1/customers/{id}/debt-payments/preview", 999_999), managerToken, Map.of("amount", 100_000))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG74"));
        send(post("/api/v1/customers/{id}/debt-payments", hung), managerToken, Map.of("amount", 0,
                "expectedAllocations", List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"))
                .andExpect(jsonPath("$.fieldErrors.amount").value("Số tiền thu nợ phải lớn hơn 0."))
                .andExpect(jsonPath("$.fieldErrors.paymentMethod").value("Vui lòng chọn hình thức thanh toán."))
                .andExpect(jsonPath("$.fieldErrors.expectedAllocations").value("Vui lòng xem phần phân bổ trước khi ghi."));

        // A Branch Manager sees their own store; Owner and the central-warehouse Branch Manager any store (BR-03).
        send(get("/api/v1/customers/{id}/debts", hung), managerToken)
                .andExpect(jsonPath("$.debts[*].id", contains(ids(gl))))
                .andExpect(jsonPath("$.totalDebt").value(1_300_000))
                .andExpect(jsonPath("$.debt").value(800_000))
                .andExpect(jsonPath("$.overdueDebt").value(800_000));
        send(get("/api/v1/customers/{id}/debts?storeId={s}", hung, longBien), managerToken)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        send(get("/api/v1/customers/{id}/debts?storeId={s}", hung, longBien), centralManagerToken)
                .andExpect(status().isOk()).andExpect(jsonPath("$.debts[*].id", contains(ids(lb))));
        send(get("/api/v1/customers/{id}/debts", hung), ownerToken)
                .andExpect(jsonPath("$.storeId").doesNotExist())
                .andExpect(jsonPath("$.debts[*].id", contains(ids(gl, lb))))
                .andExpect(jsonPath("$.debts[*].storeName", contains("Gia Lâm", "Long Biên")));
        send(get("/api/v1/customers/{id}/debts", 999_999), ownerToken)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG74"));
        send(get("/api/v1/receivables/customers"), staffToken).andExpect(status().isForbidden());

        // The debt list: overdue first, then the largest debt.
        send(get("/api/v1/receivables/customers"), ownerToken)
                .andExpect(jsonPath("$.items[*].customerId", contains(ids(hung, binh))))
                .andExpect(jsonPath("$.items[*].debt", contains(1_300_000, 2_000_000)))
                .andExpect(jsonPath("$.items[*].overdueDebt", contains(800_000, 0)))
                .andExpect(jsonPath("$.items[*].openDebtCount", contains(2, 1)));
        send(get("/api/v1/receivables/customers?storeId={s}", longBien), ownerToken)
                .andExpect(jsonPath("$.items[*].customerId", contains(ids(binh, hung))))
                .andExpect(jsonPath("$.items[*].debt", contains(2_000_000, 500_000)))
                .andExpect(jsonPath("$.items[*].totalDebt", contains(2_000_000, 1_300_000)));
        send(get("/api/v1/receivables/customers?overdueOnly=true"), ownerToken)
                .andExpect(jsonPath("$.items[*].customerId", contains(ids(hung))));
        send(get("/api/v1/receivables/customers").param("q", "0987 654"), ownerToken)
                .andExpect(jsonPath("$.items[*].customerId", contains(ids(binh))));
        send(get("/api/v1/receivables/customers?size=1&page=1"), ownerToken)
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[*].customerId", contains(ids(binh))));
        send(get("/api/v1/receivables/customers"), longBienManagerToken)
                .andExpect(jsonPath("$.items[*].customerId", contains(ids(binh, hung))));

        // Owner collects at the store they name.
        send(post("/api/v1/customers/{id}/debt-payments", binh), ownerToken, Map.of("storeId", longBien,
                "amount", 2_000_000, "paymentMethod", "CASH", "expectedAllocations", List.of(line(binhDebt, 2_000_000, 2_000_000))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storeId").value(longBien))
                .andExpect(jsonPath("$.allocations[0].remainingAmount").value(0));
        send(get("/api/v1/receivables/customers?storeId={s}", longBien), ownerToken)
                .andExpect(jsonPath("$.items[*].customerId", contains(ids(hung))));
    }

    @Test
    void aPartCashPaymentSentTwiceIsRecordedOnce() throws Exception {
        long opening = openingDebtId(giaLam, 1_000_000, "2026-11-30");
        long sale = receivableOf(creditSale(hung, giaLam, 500_000, at(3), LocalDate.of(2026, 11, 3)));

        // The preview's line allocated 0 may be sent back as it is.
        send(post("/api/v1/customers/{id}/debt-payments/preview", hung), managerToken, Map.of("amount", 300_000))
                .andExpect(jsonPath("$.allocations[*].receivableId", contains(ids(opening, sale))))
                .andExpect(jsonPath("$.allocations[*].allocatedAmount", contains(300_000, 0)));
        Map<String, Object> payment = cash(300_000, null, line(opening, 1_000_000, 300_000), line(sale, 500_000, 0));
        send(post("/api/v1/customers/{id}/debt-payments", hung), managerToken, payment)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allocations[*].receivableId", contains(ids(opening))))
                .andExpect(jsonPath("$.allocations[*].remainingAmount", contains(700_000)));

        // Sent again (a double click or a retried request): the debt would still take 300.000 the same way, but the
        // balance the collector saw has changed (BR-45).
        send(post("/api/v1/customers/{id}/debt-payments", hung), managerToken, payment)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG152"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment", Long.class)).isEqualTo(1);
        send(get("/api/v1/customers/{id}/debts", hung), managerToken)
                .andExpect(jsonPath("$.debts[*].remainingAmount", contains(700_000, 500_000)));

        // A Branch Manager collects only at their own store.
        Map<String, Object> elsewhere = cash(100_000, null, line(opening, 700_000, 100_000));
        elsewhere.put("storeId", longBien);
        send(post("/api/v1/customers/{id}/debt-payments", hung), managerToken, elsewhere)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        send(get("/api/v1/receivables/customers?storeId={s}", longBien), managerToken)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment", Long.class)).isEqualTo(1);
    }

    @Test
    void aPaymentRecordedMeanwhileByAnotherCollectorMakesTheConfirmedAllocationStale() throws Exception {
        long opening = openingDebtId(giaLam, 300_000, "2026-11-30");
        long sale = receivableOf(creditSale(hung, giaLam, 1_000_000, at(3), LocalDate.of(2026, 11, 3)));
        Map<String, Object> ours = cash(500_000, null, line(opening, 300_000, 300_000),
                line(sale, 1_000_000, 200_000));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection other = dataSource.getConnection()) {
            // Another collector pays the opening debt and holds the customer, as recording a payment does.
            other.setAutoCommit(false);
            try (PreparedStatement lock = other.prepareStatement("SELECT id FROM customer WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, hung);
                lock.executeQuery().close();
            }
            long theirs;
            try (PreparedStatement insert = other.prepareStatement("INSERT INTO payment (payment_type, amount, "
                    + "payment_method, status, recorded_by, recorded_at, verified_by, verified_at) "
                    + "VALUES ('DEBT_PAYMENT', 300000, 'CASH', 'VERIFIED', ?, ?, ?, ?) RETURNING id")) {
                insert.setLong(1, owner.getId());
                insert.setObject(2, LocalDateTime.now(clock));
                insert.setLong(3, owner.getId());
                insert.setObject(4, LocalDateTime.now(clock));
                try (ResultSet created = insert.executeQuery()) {
                    created.next();
                    theirs = created.getLong(1);
                }
            }
            try (PreparedStatement allocate = other.prepareStatement("INSERT INTO payment_allocation (payment_id, "
                    + "receivable_id, allocated_amount) VALUES (?, ?, 300000)")) {
                allocate.setLong(1, theirs);
                allocate.setLong(2, opening);
                allocate.executeUpdate();
            }
            Future<MvcResult> sent = executor.submit(() ->
                    send(post("/api/v1/customers/{id}/debt-payments", hung), managerToken, ours).andReturn());
            // Our payment waits for the customer; let the other payment commit first.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!sent.isDone() && System.nanoTime() < deadline && jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'", Long.class) == 0) {
                Thread.sleep(20);
            }
            other.commit();

            MvcResult result = sent.get(30, TimeUnit.SECONDS);
            String body = result.getResponse().getContentAsString();
            assertThat(result.getResponse().getStatus()).as(body).isEqualTo(409);
            assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo("MSG152");
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment", Long.class)).isEqualTo(1);
        send(post("/api/v1/customers/{id}/debt-payments/preview", hung), managerToken, Map.of("amount", 500_000))
                .andExpect(jsonPath("$.allocations[*].receivableId", contains(ids(sale))))
                .andExpect(jsonPath("$.allocations[*].allocatedAmount", contains(500_000)));
    }

    private long customer(String name, String phone) {
        return jdbc.queryForObject("INSERT INTO customer (customer_name, phone, created_by) VALUES (?, ?, ?) RETURNING id",
                Long.class, name, phone, owner.getId());
    }

    /** An order completed on credit at {@code completedAt}, with its receivable, as UC-61 (Đợt 7) will record them. */
    private long creditSale(long customerId, long storeId, long amount, LocalDateTime completedAt, LocalDate dueDate) {
        long order = jdbc.queryForObject("INSERT INTO sales_order (store_id, customer_id, status, completion_type, "
                + "price_type, created_by, completed_by, completed_at, credit_decision, approved_credit_amount, "
                + "credit_decided_by, credit_decided_at) VALUES (?, ?, 'COMPLETED', 'CREDIT', 'RETAIL', ?, ?, ?, "
                + "'APPROVED', ?, ?, ?) RETURNING id", Long.class, storeId, customerId, owner.getId(), owner.getId(),
                completedAt, amount, owner.getId(), completedAt);
        jdbc.update("INSERT INTO receivable (origin_type, customer_id, store_id, sales_order_id, original_amount, due_date, "
                + "recognized_by, recognized_at) VALUES ('CREDIT_SALE', ?, ?, ?, ?, ?, ?, ?)", customerId, storeId, order,
                amount, dueDate, owner.getId(), completedAt);
        return order;
    }

    private long receivableOf(long salesOrder) {
        return jdbc.queryForObject("SELECT id FROM receivable WHERE sales_order_id = ?", Long.class, salesOrder);
    }

    private long openingDebtId(long storeId, long amount, String dueDate) throws Exception {
        String body = send(post("/api/v1/customers/{id}/opening-debts", hung), ownerToken,
                openingDebt(storeId, amount, dueDate))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    /** 8:00 on that day of October 2026, Vietnam time. */
    private static LocalDateTime at(int day) {
        return LocalDateTime.of(2026, 10, day, 8, 0);
    }

    private static Map<String, Object> reason(String reason) {
        return Map.of("reason", reason);
    }

    private static Map<String, Object> openingDebt(long storeId, long amount, String dueDate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("storeId", storeId);
        body.put("amount", amount);
        body.put("dueDate", dueDate);
        body.put("note", " Nợ ghi sổ tay năm 2025 ");
        body.put("evidence", List.of(file("so-no.jpg")));
        return body;
    }

    private static Map<String, Object> file(String name) {
        return Map.of("storageKey", "motoflow/attachments/" + name + "-" + System.nanoTime(), "fileName", name,
                "contentType", name.endsWith(".png") ? "image/png" : "image/jpeg", "fileSize", 120_000);
    }

    private static Map<String, Object> line(long receivableId, long remainingBefore, long amount) {
        return Map.of("receivableId", receivableId, "remainingBefore", remainingBefore, "amount", amount);
    }

    @SafeVarargs
    private static Map<String, Object> cash(long amount, Long tendered, Map<String, Object>... expected) {
        return payment("CASH", amount, tendered, null, expected);
    }

    @SafeVarargs
    private static Map<String, Object> payment(String method, long amount, Long tendered, List<Long> receivableIds,
                                               Map<String, Object>... expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount);
        body.put("paymentMethod", method);
        if (tendered != null) {
            body.put("tenderedAmount", tendered);
        }
        if (receivableIds != null) {
            body.put("receivableIds", receivableIds);
        }
        body.put("expectedAllocations", List.of(expected));
        return body;
    }

    private static Integer[] ids(long... ids) {
        List<Integer> values = new ArrayList<>();
        for (long id : ids) {
            values.add((int) id);
        }
        return values.toArray(Integer[]::new);
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               String token) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, token));
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               String token, Map<String, Object> body) throws Exception {
        return send(request.contentType(MediaType.APPLICATION_JSON).content(json(body)), token);
    }
}
