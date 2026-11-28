package fu.hl.g64.motorflow.adjustment;

import fu.hl.g64.motorflow.inventory.StockDocumentChecks;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Stock documents (Q8) of an applied stock loss: one ADJUSTMENT document out of the lot, checked against its detail. */
class StockDocumentAdjustmentApiTest extends AdjustmentTestData {

    @Test
    void appliedAdjustmentIsOneAdjustmentDocumentOutOfTheLot() throws Exception {
        long id = submitted(managerToken, loss(null, line(oil, oilLot1, "AVAILABLE", 2)));
        act(ownerToken, id, "approve").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPLIED"));

        mvc.perform(get("/api/v1/stock-documents").header(HttpHeaders.AUTHORIZATION, ownerToken)
                        .param("kind", "ADJUSTMENT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].code").value("DCH-%04d".formatted(id)))
                .andExpect(jsonPath("$.items[0].direction").value("OUT"))
                .andExpect(jsonPath("$.items[0].storeId").value(store))
                .andExpect(jsonPath("$.items[0].lineCount").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(2));
        assertThat(StockDocumentChecks.assertDetailsMatchTheList(mvc, jsonMapper, ownerToken)).isEqualTo(1);
    }
}
