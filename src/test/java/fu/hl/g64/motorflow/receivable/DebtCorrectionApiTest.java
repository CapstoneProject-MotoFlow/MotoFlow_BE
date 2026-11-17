package fu.hl.g64.motorflow.receivable;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.support.ApiTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * UC-81 on debts: a debt payment's amount (BR-71: allocated again over the same receivables in BR-70 order) and an
 * opening debt's amount. Nothing changes until Owner's approval is applied.
 */
class DebtCorrectionApiTest extends ApiTestUsers {

    private static final String CORRECTIONS = "/api/v1/adjustment-requests/corrections";

    private UserAccount owner;
    private long giaLam;
    private String ownerToken;
    private String managerToken;
    private String otherManagerToken;
    private long hung;
    private long opening;
    private long creditSale;
    private long payment;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabaseAndClock();
        owner = createUser("owner", Role.OWNER, null);
        giaLam = createStore("Gia Lâm");
        long longBien = createStore("Long Biên");
        createUser("bm-gialam", Role.BRANCH_MANAGER, giaLam);
        createUser("bm-longbien", Role.BRANCH_MANAGER, longBien);
        ownerToken = signIn("owner");
        managerToken = signIn("bm-gialam");
        otherManagerToken = signIn("bm-longbien");
        hung = jdbc.queryForObject("INSERT INTO customer (customer_name, phone, created_by) VALUES ('Garage Hùng', "
                + "'0912345678', ?) RETURNING id", Long.class, owner.getId());

        Map<String, Object> debt = new LinkedHashMap<>();
        debt.put("storeId", giaLam);
        debt.put("amount", 500_000);
        debt.put("dueDate", "2026-12-31");
        debt.put("note", "Nợ ghi sổ tay");
        debt.put("evidence", List.of(file()));
        opening = idOf(send(post("/api/v1/customers/{id}/opening-debts", hung), ownerToken, debt)
                .andExpect(status().isCreated()));
        long order = jdbc.queryForObject("INSERT INTO sales_order (store_id, customer_id, status, completion_type, "
                        + "price_type, created_by, completed_by, completed_at, credit_decision, approved_credit_amount, "
                        + "credit_decided_by, credit_decided_at) VALUES (?, ?, 'COMPLETED', 'CREDIT', 'RETAIL', ?, ?, ?, "
                        + "'APPROVED', 1000000, ?, ?) RETURNING id", Long.class, giaLam, hung, owner.getId(),
                owner.getId(), LocalDateTime.of(2026, 10, 5, 8, 0), owner.getId(), LocalDateTime.of(2026, 10, 5, 8, 0));
        creditSale = jdbc.queryForObject("INSERT INTO receivable (origin_type, customer_id, store_id, sales_order_id, "
                + "original_amount, due_date, recognized_by, recognized_at) VALUES ('CREDIT_SALE', ?, ?, ?, 1000000, ?, "
                + "?, ?) RETURNING id", Long.class, hung, giaLam, order, LocalDate.of(2026, 11, 5), owner.getId(),
                LocalDateTime.of(2026, 10, 5, 8, 0));

        // 1,200,000 đ: the opening debt in full, then 700,000 of the credit sale (BR-70).
        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("amount", 1_200_000);
        pay.put("paymentMethod", "CASH");
        pay.put("expectedAllocations", List.of(
                Map.of("receivableId", opening, "remainingBefore", 500_000, "amount", 500_000),
                Map.of("receivableId", creditSale, "remainingBefore", 1_000_000, "amount", 700_000)));
        payment = idOf(send(post("/api/v1/customers/{id}/debt-payments", hung), managerToken, pay)
                .andExpect(status().isCreated()));
    }

    @Test
    void aCorrectedDebtPaymentIsAllocatedAgainOverTheSameDebts() throws Exception {
        // Owner's capacity: 500,000 + 1,000,000 can be paid by this payment, no more.
        send(post(CORRECTIONS + "/preview"), managerToken, amount("DEBT_PAYMENT", payment, 1_600_000))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG288"))
                .andExpect(jsonPath("$.message").value("Số tiền đúng vượt số nợ mà khoản thu này có thể trả: tối đa "
                        + "1.500.000 đ."));
        send(post(CORRECTIONS + "/preview"), managerToken, amount("DEBT_PAYMENT", payment, 1_200_000))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG282"));
        send(post(CORRECTIONS), managerToken, amount("DEBT_PAYMENT", payment, 0))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG291"));
        send(post(CORRECTIONS + "/preview"), otherManagerToken, amount("DEBT_PAYMENT", payment, 1_000_000))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG287"));

        // Only 1,000,000 đ was received. The request holds the payment's amount; the debts are allocated again when
        // it is applied (PaymentReallocation): the opening debt keeps its 500,000, the credit sale gets 500,000.
        send(post(CORRECTIONS + "/preview"), managerToken, amount("DEBT_PAYMENT", payment, 1_000_000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountLines[*].paymentId", contains((int) payment)))
                .andExpect(jsonPath("$.amountLines[*].before", contains(1_200_000)))
                .andExpect(jsonPath("$.amountLines[*].after", contains(1_000_000)));
        long request = idOf(send(post(CORRECTIONS), managerToken, amount("DEBT_PAYMENT", payment, 1_000_000))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING")));
        debts().andExpect(jsonPath("$.debt").value(300_000));

        send(post("/api/v1/adjustment-requests/" + request + "/approve"), ownerToken)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.correction.before.amount").value(1_200_000))
                .andExpect(jsonPath("$.correction.after.amount").value(1_000_000));
        debts().andExpect(jsonPath("$.debt").value(500_000))
                .andExpect(jsonPath("$.debts[*].id", contains((int) opening, (int) creditSale)))
                .andExpect(jsonPath("$.debts[*].collectedAmount", contains(500_000, 500_000)))
                .andExpect(jsonPath("$.debts[*].correctionAmount", contains(0, 0)))
                .andExpect(jsonPath("$.debts[*].status", contains("SETTLED", "PARTIALLY_PAID")));

        // A second correction starts from the corrected figures: 1,500,000 pays both in full.
        long again = idOf(send(post(CORRECTIONS), managerToken, amount("DEBT_PAYMENT", payment, 1_500_000))
                .andExpect(status().isCreated()));
        send(post("/api/v1/adjustment-requests/" + again + "/approve"), ownerToken).andExpect(status().isOk());
        debts().andExpect(jsonPath("$.debt").value(0))
                .andExpect(jsonPath("$.debts[*].collectedAmount", contains(500_000, 1_000_000)));
        assertThat(jdbc.queryForObject("SELECT settled_at IS NOT NULL FROM receivable WHERE id = ?", Boolean.class,
                creditSale)).isTrue();
        // BR-71: the first rows stay for history, reversed by the corrections; only the last split counts.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_allocation WHERE payment_id = ? "
                + "AND reversed_at IS NOT NULL", Long.class, payment)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT sum(allocated_amount) FROM payment_allocation WHERE payment_id = ? "
                + "AND reversed_at IS NULL", Long.class, payment)).isEqualTo(1_500_000);
    }

    @Test
    void anOpeningDebtIsNeverCorrectedBelowWhatWasPaidOnIt() throws Exception {
        send(post(CORRECTIONS), managerToken, amount("OPENING_RECEIVABLE", opening, 400_000))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG284"))
                .andExpect(jsonPath("$.message").value("Số tiền sau khi sửa không được nhỏ hơn số khách đã trả: "
                        + "500.000 đ."));
        send(post(CORRECTIONS), managerToken, amount("OPENING_RECEIVABLE", creditSale, 900_000))
                .andExpect(status().isNotFound());

        long request = idOf(send(post(CORRECTIONS), managerToken, amount("OPENING_RECEIVABLE", opening, 600_000))
                .andExpect(status().isCreated()));
        send(post("/api/v1/adjustment-requests/" + request + "/approve"), ownerToken).andExpect(status().isOk());
        debts().andExpect(jsonPath("$.debts[0].correctionAmount").value(100_000))
                .andExpect(jsonPath("$.debts[0].remainingAmount").value(100_000))
                .andExpect(jsonPath("$.debts[0].status").value("PARTIALLY_PAID"));
        assertThat(jdbc.queryForObject("SELECT settled_at IS NULL FROM receivable WHERE id = ?", Boolean.class,
                opening)).isTrue();
    }

    private ResultActions debts() throws Exception {
        return send(get("/api/v1/customers/{id}/debts", hung), managerToken).andExpect(status().isOk());
    }

    private static Map<String, Object> amount(String type, long id, long corrected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("documentType", type);
        body.put("documentId", id);
        body.put("correctedAmount", corrected);
        body.put("reason", "Ghi nhầm số tiền");
        body.put("attachments", List.of(file()));
        return body;
    }

    private static Map<String, Object> file() {
        return Map.of("storageKey", "motoflow/attachments/" + UUID.randomUUID(), "fileName", "bien-lai.jpg",
                "contentType", "image/jpeg", "fileSize", 1000);
    }

    private long idOf(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, token));
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String token, Map<String, Object> body)
            throws Exception {
        return send(request.contentType(MediaType.APPLICATION_JSON).content(json(body)), token);
    }
}
