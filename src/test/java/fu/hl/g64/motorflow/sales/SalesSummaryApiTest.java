package fu.hl.g64.motorflow.sales;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sales summary for the dashboards (FE UI fixes 12/11). Today is 13/10/2026. */
class SalesSummaryApiTest extends SalesOrderTestData {

    @Test
    void addsUpCompletedOrdersPaymentsCreditAndDebtCollectionPerStore() throws Exception {
        // Gia Lâm today: paid order 2 × 95,000, paid 200,000 then refunded 10,000; one rejected payment does not count.
        long paid = completed(store, "PAID", "2026-10-13 09:00", pad, 2, 95_000);
        payment("ORDER_PAYMENT", paid, 200_000, "VERIFIED");
        payment("REFUND", paid, 10_000, "VERIFIED");
        payment("ORDER_PAYMENT", paid, 5_000, "REJECTED");
        // Gia Lâm today: credit order 85,000, 35,000 paid, 50,000 left on credit.
        long credit = completed(store, "CREDIT", "2026-10-13 10:00", oil, 1, 85_000);
        payment("ORDER_PAYMENT", credit, 35_000, "VERIFIED");
        long receivable = id("INSERT INTO receivable (origin_type, customer_id, store_id, sales_order_id, "
                + "original_amount, recognized_by, recognized_at) VALUES ('CREDIT_SALE', ?, ?, ?, 50000, ?, "
                + "'2026-10-13 10:00') RETURNING id", customer, store, credit, owner.getId());
        // Debt collected today 30,000 and yesterday 20,000.
        debtPayment(receivable, 30_000, "2026-10-13 11:00");
        debtPayment(receivable, 20_000, "2026-10-12 11:00");
        // Gia Lâm yesterday: 100,000 paid. Long Biên today: 85,000 paid. A cancelled order counts nowhere.
        payment("ORDER_PAYMENT", completed(store, "PAID", "2026-10-12 15:00", pad, 1, 100_000), 100_000, "VERIFIED");
        payment("ORDER_PAYMENT", completed(otherStore, "PAID", "2026-10-13 08:30", oil, 1, 85_000), 85_000,
                "VERIFIED");
        jdbc.update("INSERT INTO sales_order (store_id, price_type, created_by, status, cancelled_by, cancelled_at, "
                + "cancellation_reason) VALUES (?, 'RETAIL', ?, 'CANCELLED', ?, '2026-10-13 09:30', 'Khách đổi ý')",
                store, owner.getId(), owner.getId());

        // Today by default.
        summary(ownerToken, "").andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-10-13"))
                .andExpect(jsonPath("$.to").value("2026-10-13"))
                .andExpect(jsonPath("$.stores[*].storeName", contains("Gia Lâm", "Long Biên")))
                .andExpect(jsonPath("$.stores[0].figures.completedOrders").value(2))
                .andExpect(jsonPath("$.stores[0].figures.revenue").value(275_000))
                .andExpect(jsonPath("$.stores[0].figures.collectedAtSale").value(225_000))
                .andExpect(jsonPath("$.stores[0].figures.creditOrders").value(1))
                .andExpect(jsonPath("$.stores[0].figures.creditSales").value(50_000))
                .andExpect(jsonPath("$.stores[0].figures.debtCollected").value(30_000))
                .andExpect(jsonPath("$.stores[0].figures.totalCollected").value(255_000))
                .andExpect(jsonPath("$.stores[1].figures.revenue").value(85_000))
                .andExpect(jsonPath("$.total.completedOrders").value(3))
                .andExpect(jsonPath("$.total.revenue").value(360_000))
                .andExpect(jsonPath("$.total.totalCollected").value(340_000));

        // Last 7 days, one store.
        summary(ownerToken, "?storeId=" + store + "&from=2026-10-07&to=2026-10-13")
                .andExpect(jsonPath("$.stores[*].storeName", contains("Gia Lâm")))
                .andExpect(jsonPath("$.total.completedOrders").value(3))
                .andExpect(jsonPath("$.total.revenue").value(375_000))
                .andExpect(jsonPath("$.total.debtCollected").value(50_000));

        // A Branch Manager sees their own store only; a day with nothing still lists it with zeros.
        summary(otherManagerToken, "?from=2026-10-12&to=2026-10-12")
                .andExpect(jsonPath("$.stores[*].storeName", contains("Long Biên")))
                .andExpect(jsonPath("$.total.revenue").value(0));
        summary(otherManagerToken, "?storeId=" + store).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        summary(staffToken, "").andExpect(status().isForbidden());
    }

    @Test
    void refusesAWrongPeriod() throws Exception {
        summary(ownerToken, "?from=2026-10-14&to=2026-10-13").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG264"));
        summary(ownerToken, "?from=2025-10-12&to=2026-10-13").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG264"));
        summary(ownerToken, "?from=2025-10-13&to=2026-10-13").andExpect(status().isOk());
        summary(ownerToken, "?storeId=9999").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG52"));
    }

    private long completed(long storeId, String completionType, String at, long product, int quantity, long unitPrice) {
        long order = id("INSERT INTO sales_order (store_id, customer_id, price_type, created_by, status, "
                + "completion_type, completed_by, completed_at) VALUES (?, ?, 'RETAIL', ?, 'COMPLETED', ?, ?, "
                + "CAST(? AS TIMESTAMP)) RETURNING id", storeId, customer, owner.getId(), completionType,
                owner.getId(), at);
        jdbc.update("INSERT INTO sales_order_line (sales_order_id, line_no, product_id, requested_qty, picked_qty, "
                + "delivered_qty, reference_price, actual_unit_price) VALUES (?, 1, ?, ?, ?, ?, ?, ?)", order, product,
                quantity, quantity, quantity, unitPrice, unitPrice);
        return order;
    }

    private void payment(String type, long order, long amount, String status) {
        boolean verified = "VERIFIED".equals(status);
        jdbc.update("INSERT INTO payment (payment_type, sales_order_id, amount, payment_method, status, recorded_by, "
                + "recorded_at, verified_by, verified_at, rejection_reason) VALUES (?, ?, ?, 'CASH', ?, ?, "
                + "'2026-10-13 08:00', ?, ?, ?)", type, order, amount, status, owner.getId(),
                verified ? owner.getId() : null, verified ? java.sql.Timestamp.valueOf("2026-10-13 08:00:00") : null,
                "REJECTED".equals(status) ? "Không nhận được tiền" : null);
    }

    private void debtPayment(long receivable, long amount, String at) {
        long payment = id("INSERT INTO payment (payment_type, amount, payment_method, status, recorded_by, "
                + "recorded_at, verified_by, verified_at) VALUES ('DEBT_PAYMENT', ?, 'CASH', 'VERIFIED', ?, "
                + "CAST(? AS TIMESTAMP), ?, CAST(? AS TIMESTAMP)) RETURNING id", amount, owner.getId(), at,
                owner.getId(), at);
        jdbc.update("INSERT INTO payment_allocation (payment_id, receivable_id, allocated_amount) VALUES (?, ?, ?)",
                payment, receivable, amount);
    }

    private ResultActions summary(String token, String query) throws Exception {
        return mvc.perform(get("/api/v1/sales-orders/summary" + query).header(HttpHeaders.AUTHORIZATION, token));
    }
}
