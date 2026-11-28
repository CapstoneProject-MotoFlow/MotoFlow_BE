package fu.hl.g64.motorflow.receiving;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC-46 Step 3_Goods cannot be returned: damaged goods of a supplier delivery that stay at the store are written off
 * by a STOCK_LOSS request; once applied they settle the discrepancy case (APPROVED_LOSS), which may then close.
 */
class HeldGoodsWriteOffApiTest extends GoodsReceiptTestData {

    private ResultActions writeOff(String token, Long receipt, String status, int quantity) throws Exception {
        Map<String, Object> line = new HashMap<>();
        line.put("productId", pad);
        line.put("stockStatus", status);
        line.put("quantity", quantity);
        Map<String, Object> body = new HashMap<>();
        body.put("goodsReceiptNoteId", receipt);
        body.put("reason", "Má phanh bể, nhà cung cấp không nhận lại");
        body.put("lines", List.of(line));
        body.put("attachments", List.of(uploaded("ma-phanh-be.jpg")));
        return mvc.perform(post("/api/v1/adjustment-requests/stock-loss").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    @Test
    void writtenOffDamagedGoodsSettleTheirCase() throws Exception {
        long grn = receive("PX-001");
        assign(grn, staff);
        record(staffToken, grn, findings(counted(lineOf(grn, pad), 10, 10, 0, 2),
                counted(lineOf(grn, oil), 24, 24, 0, 0))).andExpect(status().isOk());
        confirm(managerToken, grn).andExpect(status().isOk());
        long found = caseOf(grn, pad);
        // Not replaced, not returned: only the two damaged pads wait for a decision.
        // GVHD 24/11: the store keeps them, nothing replaced: the Owner's direction (3).
        handle(ownerToken, grn, List.of(direction(handling(found, 0, null), "ACCEPT_NO_REPLACEMENT")))
                .andExpect(status().isOk());

        writeOff(managerToken, grn, "AVAILABLE", 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG259"));
        writeOff(managerToken, grn, "DAMAGED", 3).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG257"));
        String created = writeOff(managerToken, grn, "DAMAGED", 2).andExpect(status().isCreated())
                .andExpect(jsonPath("$.source.type").value("GOODS_RECEIPT_NOTE"))
                .andReturn().getResponse().getContentAsString();
        long request = ((Number) JsonPath.read(created, "$.id")).longValue();
        writeOff(managerToken, grn, "DAMAGED", 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG257"));
        discrepancy(managerToken, found).andExpect(jsonPath("$.status").value("OPEN"));

        mvc.perform(post("/api/v1/adjustment-requests/" + request + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPLIED"));
        assertThat(stock(giaLam, pad, "DAMAGED")).isZero();
        assertStockAgrees(giaLam, pad);
        discrepancy(managerToken, found)
                .andExpect(jsonPath("$.heldUndecidedQty").value(0))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.events[*].type", contains("APPROVED_LOSS")));
    }

    @Test
    void aLossFoundInTheWarehouseCannotTakeTheHeldGoodsOfAnOpenCase() throws Exception {
        long grn = receive("PX-002");
        assign(grn, staff);
        record(staffToken, grn, findings(counted(lineOf(grn, pad), 10, 10, 0, 2),
                counted(lineOf(grn, oil), 24, 24, 0, 0))).andExpect(status().isOk());
        confirm(managerToken, grn).andExpect(status().isOk());
        long found = caseOf(grn, pad);
        assertThat(stock(giaLam, pad, "DAMAGED")).isEqualTo(2);

        // Review 17/11 R-06: the two damaged pads are the case's while it waits for a decision.
        writeOff(managerToken, null, "DAMAGED", 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG257"));
        // Written off through their delivery instead, so the case records it.
        // GVHD 24/11: the store keeps them, nothing replaced: the Owner's direction (3).
        handle(ownerToken, grn, List.of(direction(handling(found, 0, null), "ACCEPT_NO_REPLACEMENT")))
                .andExpect(status().isOk());
        writeOff(managerToken, null, "DAMAGED", 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG257"));
        writeOff(managerToken, grn, "DAMAGED", 2).andExpect(status().isCreated());
    }

    @Test
    void writtenOffHeldGoodsOfTwoPartsSettleBothCases() throws Exception {
        long grn = receive("PX-003");
        assign(grn, staff);
        record(staffToken, grn, findings(counted(lineOf(grn, pad), 10, 10, 0, 2),
                counted(lineOf(grn, oil), 24, 24, 0, 3))).andExpect(status().isOk());
        confirm(managerToken, grn).andExpect(status().isOk());
        long padCase = caseOf(grn, pad);
        long oilCase = caseOf(grn, oil);
        // GVHD 24/11: the store keeps the damaged goods, nothing replaced: the Owner's direction (3).
        handle(ownerToken, grn, List.of(direction(handling(padCase, 0, null), "ACCEPT_NO_REPLACEMENT"),
                direction(handling(oilCase, 0, null), "ACCEPT_NO_REPLACEMENT"))).andExpect(status().isOk());

        Map<String, Object> body = new HashMap<>();
        body.put("goodsReceiptNoteId", grn);
        body.put("reason", "Hàng hỏng, nhà cung cấp không nhận lại");
        body.put("lines", List.of(Map.of("productId", pad, "stockStatus", "DAMAGED", "quantity", 2),
                Map.of("productId", oil, "stockStatus", "DAMAGED", "quantity", 3)));
        body.put("attachments", List.of(uploaded("hang-hong.jpg")));
        String created = mvc.perform(post("/api/v1/adjustment-requests/stock-loss")
                        .header(HttpHeaders.AUTHORIZATION, managerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long request = ((Number) JsonPath.read(created, "$.id")).longValue();

        mvc.perform(post("/api/v1/adjustment-requests/" + request + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPLIED"));
        assertThat(stock(giaLam, pad, "DAMAGED")).isZero();
        assertThat(stock(giaLam, oil, "DAMAGED")).isZero();
        assertStockAgrees(giaLam, pad);
        assertStockAgrees(giaLam, oil);
        discrepancy(managerToken, padCase).andExpect(jsonPath("$.status").value("CLOSED"));
        discrepancy(managerToken, oilCase).andExpect(jsonPath("$.status").value("CLOSED"));
    }
}
