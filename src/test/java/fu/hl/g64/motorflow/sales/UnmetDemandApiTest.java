package fu.hl.g64.motorflow.sales;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-59 View Unmet Demand. */
class UnmetDemandApiTest extends SalesOrderTestData {

    @Test
    void listsWhatCompletedOrdersDidNotDeliverAndWhatCancelledOrdersAskedFor() throws Exception {
        // 4 pads and 2 oils asked for, 3 pads delivered: 470,000 đ.
        long shortOrder = createOrder(pad, 4, oil, 2);
        assign(managerToken, "PICKING", shortOrder, staff).andExpect(status().isOk());
        pick(staffToken, shortOrder, 1, pad, 3, "Thiếu hàng", oil, 2, null).andExpect(status().isOk());
        confirm(managerToken, shortOrder, 1, pad, 3, oil, 2).andExpect(status().isOk());
        pay(shortOrder, 470_000).andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true));

        // Delivered in full: not listed.
        clock.advance(Duration.ofMinutes(5));
        long fullOrder = createOrder(pad, 1);
        assign(managerToken, "PICKING", fullOrder, staff).andExpect(status().isOk());
        pick(staffToken, fullOrder, 1, pad, 1, null).andExpect(status().isOk());
        confirm(managerToken, fullOrder, 1, pad, 1).andExpect(status().isOk());
        pay(fullOrder, 100_000).andExpect(jsonPath("$.completed").value(true));

        // Cancelled before anything was picked: everything it asked for is unmet.
        clock.advance(Duration.ofMinutes(5));
        long cancelled = createOrder(oil, 1);
        cancel(cancelled, "Khách mua ở nơi khác").andExpect(status().isOk());

        // Still open: not listed.
        createOrder(oil, 1);

        unmetDemand(managerToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].orderId").value(cancelled))
                .andExpect(jsonPath("$.items[0].orderStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.items[0].occurredAt", notNullValue()))
                .andExpect(jsonPath("$.items[0].storeName").value("Gia Lâm"))
                .andExpect(jsonPath("$.items[0].customerName").value("Anh Tuấn"))
                .andExpect(jsonPath("$.items[0].customerPhone").value("0912345678"))
                .andExpect(jsonPath("$.items[0].sku").value("NHOT-01"))
                .andExpect(jsonPath("$.items[0].requestedQty").value(1))
                .andExpect(jsonPath("$.items[0].deliveredQty").value(0))
                .andExpect(jsonPath("$.items[0].missingQty").value(1))
                .andExpect(jsonPath("$.items[0].sellableQty").value(2))
                .andExpect(jsonPath("$.items[1].orderId").value(shortOrder))
                .andExpect(jsonPath("$.items[1].orderStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.items[1].sku").value("MA-PHANH-01"))
                .andExpect(jsonPath("$.items[1].partName").value("Má phanh trước"))
                .andExpect(jsonPath("$.items[1].baseUnit").value("cái"))
                .andExpect(jsonPath("$.items[1].requestedQty").value(4))
                .andExpect(jsonPath("$.items[1].deliveredQty").value(3))
                .andExpect(jsonPath("$.items[1].missingQty").value(1))
                // 8 unexpired pads, 4 sold; the expired lot is not sellable (BR-76).
                .andExpect(jsonPath("$.items[1].sellableQty").value(4));

        unmetDemand(managerToken, "productId", String.valueOf(pad))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].orderId").value(shortOrder));
        unmetDemand(managerToken, "q", " nhot ")
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].orderId").value(cancelled));
        unmetDemand(managerToken, "q", "0912345")
                .andExpect(jsonPath("$.items", hasSize(2)));
        unmetDemand(managerToken, "customerId", String.valueOf(customer))
                .andExpect(jsonPath("$.items", hasSize(2)));
        unmetDemand(managerToken, "customerId", String.valueOf(customer + 100))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalItems").value(0));
        unmetDemand(managerToken, "page", "1", "size", "1")
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].orderId").value(shortOrder))
                .andExpect(jsonPath("$.totalItems").value(2));

        // The days are the completion or cancellation days.
        unmetDemand(managerToken, "from", TODAY.plusDays(1).toString())
                .andExpect(jsonPath("$.items", hasSize(0)));
        unmetDemand(managerToken, "to", TODAY.minusDays(1).toString())
                .andExpect(jsonPath("$.items", hasSize(0)));
        unmetDemand(managerToken, "from", TODAY.toString(), "to", TODAY.toString())
                .andExpect(jsonPath("$.items", hasSize(2)));
    }

    @Test
    void showsTheLastThirtyDaysByDefault() throws Exception {
        long cancelled = createOrder(pad, 2);
        cancel(cancelled, "Khách đổi ý").andExpect(status().isOk());

        clock.advance(Duration.ofDays(29));
        signInAll();
        unmetDemand(ownerToken).andExpect(jsonPath("$.items", hasSize(1)));
        clock.advance(Duration.ofDays(1));
        signInAll();
        unmetDemand(ownerToken).andExpect(jsonPath("$.items", hasSize(0)));
        unmetDemand(ownerToken, "from", TODAY.toString())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].requestedQty").value(2));
    }

    @Test
    void aBranchManagerSeesTheirOwnStoreOnly() throws Exception {
        long cancelled = createOrder(pad, 1);
        cancel(cancelled, "Khách đổi ý").andExpect(status().isOk());

        unmetDemand(ownerToken).andExpect(jsonPath("$.items", hasSize(1)));
        unmetDemand(ownerToken, "storeId", String.valueOf(store)).andExpect(jsonPath("$.items", hasSize(1)));
        unmetDemand(ownerToken, "storeId", String.valueOf(otherStore)).andExpect(jsonPath("$.items", hasSize(0)));
        unmetDemand(managerToken, "storeId", String.valueOf(store)).andExpect(jsonPath("$.items", hasSize(1)));
        unmetDemand(otherManagerToken).andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)));
        unmetDemand(otherManagerToken, "storeId", String.valueOf(store))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        unmetDemand(staffToken).andExpect(status().isForbidden());
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private ResultActions unmetDemand(String token, String... parameters) throws Exception {
        var request = get("/api/v1/unmet-demand").header(HttpHeaders.AUTHORIZATION, token);
        for (int i = 0; i < parameters.length; i += 2) {
            request.param(parameters[i], parameters[i + 1]);
        }
        return mvc.perform(request);
    }

    private ResultActions pay(long order, long amount) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount);
        body.put("paymentMethod", "CASH");
        body.put("verified", true);
        body.put("expectedPayable", moneyNow(order, "payableNow"));
        return mvc.perform(post("/api/v1/sales-orders/" + order + "/payments")
                .header(HttpHeaders.AUTHORIZATION, managerToken)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions cancel(long order, String reason) throws Exception {
        return mvc.perform(post("/api/v1/sales-orders/" + order + "/cancel")
                .header(HttpHeaders.AUTHORIZATION, managerToken)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("reason", reason))));
    }
}
