package fu.hl.g64.motorflow.receiving;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-81 on a confirmed supplier delivery: one pad was counted in but never arrived; the stock follows on approval. */
class ReceiptCorrectionApiTest extends GoodsReceiptTestData {

    private ResultActions correct(String token, long receipt, long line, int qty) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("documentType", "GOODS_RECEIPT_NOTE");
        body.put("documentId", receipt);
        body.put("lines", List.of(Map.of("lineId", line, "correctedQty", qty)));
        body.put("reason", "Đếm nhầm, thực nhận 9 bộ");
        body.put("attachments", List.of(uploaded("bien-ban.jpg")));
        return mvc.perform(post("/api/v1/adjustment-requests/corrections").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    @Test
    void aPadCountedInByMistakeLeavesTheStockOnApproval() throws Exception {
        long grn = receive("PX-009");
        assign(grn, staff);
        record(staffToken, grn, findings(counted(lineOf(grn, pad), 10, 10, 0, 0),
                counted(lineOf(grn, oil), 24, 24, 0, 0))).andExpect(status().isOk());
        confirm(managerToken, grn).andExpect(status().isOk());
        int before = stock(giaLam, pad, "AVAILABLE");

        correct(managerToken, grn, lineOf(grn, pad), 10).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG282"));
        String created = correct(managerToken, grn, lineOf(grn, pad), 9).andExpect(status().isCreated())
                .andExpect(jsonPath("$.source.type").value("GOODS_RECEIPT_NOTE"))
                .andExpect(jsonPath("$.stockLines[0].quantityChange").value(-1))
                .andReturn().getResponse().getContentAsString();
        long request = ((Number) JsonPath.read(created, "$.id")).longValue();
        assertThat(stock(giaLam, pad, "AVAILABLE")).isEqualTo(before);

        mvc.perform(post("/api/v1/adjustment-requests/" + request + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPLIED"));
        assertThat(stock(giaLam, pad, "AVAILABLE")).isEqualTo(before - 1);
        assertStockAgrees(giaLam, pad);
    }
}
