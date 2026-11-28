package fu.hl.g64.motorflow.inventory;

import fu.hl.g64.motorflow.receiving.GoodsReceiptTestData;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stock documents (Q8) of a supplier delivery: the confirmed receipt (IN) and the wrong items sent back to the supplier
 * (a discrepancy resolution event, OUT).
 */
class StockDocumentReceiptApiTest extends GoodsReceiptTestData {

    @Test
    void confirmedReceiptAndTheSupplierReturnAreTwoDocuments() throws Exception {
        long grn = receive("PX-001");
        assign(grn, staff);
        record(staffToken, grn, findings(counted(lineOf(grn, pad), 10, 10, 0, 0),
                counted(lineOf(grn, oil), 24, 24, 2, 0))).andExpect(status().isOk());
        confirm(managerToken, grn).andExpect(status().isOk());
        long found = caseOf(grn, oil);
        handle(managerToken, grn, found, 2).andExpect(status().isOk());
        supplierReturn(managerToken, found, Map.of("quantity", 2)).andExpect(status().isOk());

        mvc.perform(get("/api/v1/stock-documents").header(HttpHeaders.AUTHORIZATION, ownerToken)
                        .param("type", "GOODS_RECEIPT_NOTE").param("direction", "IN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].kind").value("SUPPLIER_RECEIPT"))
                .andExpect(jsonPath("$.items[0].sourceId").value(grn))
                .andExpect(jsonPath("$.items[0].storeId").value(giaLam));
        mvc.perform(get("/api/v1/stock-documents").header(HttpHeaders.AUTHORIZATION, ownerToken)
                        .param("type", "DISCREPANCY_RESOLUTION_EVENT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].kind").value("SUPPLIER_RETURN"))
                .andExpect(jsonPath("$.items[0].direction").value("OUT"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].discrepancyCaseId").value(found))
                .andExpect(jsonPath("$.items[0].discrepancyReceiptId").value(grn))
                .andExpect(jsonPath("$.items[0].storeId").value(giaLam));
        assertThat(StockDocumentChecks.assertDetailsMatchTheList(mvc, jsonMapper, ownerToken)).isEqualTo(2);
    }
}
