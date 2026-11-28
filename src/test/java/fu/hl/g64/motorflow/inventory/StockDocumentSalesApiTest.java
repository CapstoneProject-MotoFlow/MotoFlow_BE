package fu.hl.g64.motorflow.inventory;

import fu.hl.g64.motorflow.sales.SalesOrderTestData;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

/** Stock documents (Q8) of a completed sale: one SALE document out of the store, checked against its detail. */
class StockDocumentSalesApiTest extends SalesOrderTestData {

    @Test
    void completedSaleIsOneSaleDocumentOutOfTheStore() throws Exception {
        long orderId = createOrder(pad, 2, oil, 1);
        assign(managerToken, "PICKING", orderId, staff).andExpect(status().isOk());
        pick(staffToken, orderId, 1, pad, 2, null, oil, 1, null).andExpect(status().isOk());
        confirm(managerToken, orderId, 1, pad, 2, oil, 1).andExpect(status().isOk());
        mvc.perform(post("/api/v1/sales-orders/" + orderId + "/payments")
                        .header(HttpHeaders.AUTHORIZATION, managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "amount", 285_000,
                                "paymentMethod", "CASH",
                                "verified", true,
                                "expectedPayable", 285_000))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/stock-documents").header(HttpHeaders.AUTHORIZATION, ownerToken)
                        .param("type", "SALES_ORDER"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].code").value("SO-" + orderId))
                .andExpect(jsonPath("$.items[0].kind").value("SALE"))
                .andExpect(jsonPath("$.items[0].direction").value("OUT"))
                .andExpect(jsonPath("$.items[0].storeId").value(store))
                .andExpect(jsonPath("$.items[0].lineCount").value(2))
                .andExpect(jsonPath("$.items[0].quantity").value(3));
        // Four supplier lots are seeded by createSalesData (IN) besides the sale (OUT): five documents in all.
        assertThat(StockDocumentChecks.assertDetailsMatchTheList(mvc, jsonMapper, ownerToken)).isEqualTo(5);
    }
}
