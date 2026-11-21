package fu.hl.g64.motorflow.receiving;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Góp ý UI 21/11 (items 18, 21): every way a supplier discrepancy case is settled ends with the case closed, and the
 * return is agreed per kind of goods (wrong items, damaged goods), so the goods kept can still be taken in by Owner.
 * The oil line is ordered 24.
 */
class DiscrepancyClosureApiTest extends GoodsReceiptTestData {

    // S1 (only short, replaced in full) is aReplacementDeliverySettlesAShortage in DiscrepancyApiTest, and S7
    // (excess waiting for Owner) is aCaseWaitsForTheExcessDecisionOfItsLineBeforeItCloses there.

    @Test
    void s2WrongItemsReturnedAndReplacedCloseTheCase() throws Exception {
        long grn = delivered(24, 2, 0);
        long found = caseOf(grn, oil);
        handle(managerToken, grn, List.of(byKind(found, 2, 0, null))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].wrongReturnRequiredQty").value(2))
                .andExpect(jsonPath("$[0].damagedReturnRequiredQty").value(0))
                .andExpect(jsonPath("$[0].returnRequiredQty").value(2))
                .andExpect(jsonPath("$[0].replacementRequiredQty").value(2))
                .andExpect(jsonPath("$[0].wrongOutstandingReturnQty").value(2));
        returned(found, 2, "WRONG_ITEM").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.wrongOutstandingReturnQty").value(0))
                .andExpect(jsonPath("$.outstandingReplacementQty").value(2))
                .andExpect(jsonPath("$.events[1].goodsKind").value("WRONG_ITEM"));
        // S8: the replacement arrives after the return; its confirmation closes the case.
        replaced(found, 2);
        discrepancy(managerToken, found).andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closureType").value("ALL_SETTLED"));
        assertStockAgrees(giaLam, oil);
    }

    @Test
    void s3WrongItemsOwnerTakesAsAnotherPartCloseTheCase() throws Exception {
        long grn = delivered(24, 2, 0);
        long found = caseOf(grn, oil);
        receiveWrong(found, 2, false).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.heldUndecidedQty").value(0));
    }

    @Test
    void s4DamagedGoodsOwnerTakesAsTheVariantCloseTheCase() throws Exception {
        long grn = delivered(24, 0, 1);
        long found = caseOf(grn, oil);
        acceptAsVariant(found, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void s5ShortWrongAndDamagedCloseOnlyOnceTheKeptDamagedGoodsAreDecided() throws Exception {
        // 5 short, 2 wrong, 1 damaged: the PO still lacks 8.
        long grn = delivered(19, 2, 1);
        long found = caseOf(grn, oil);
        handle(managerToken, grn, List.of(byKind(found, 2, 0, 8))).andExpect(status().isOk());
        returned(found, 2, "WRONG_ITEM").andExpect(status().isOk());
        replaced(found, 8);
        // The damaged one is still held and not decided: the case stays open and says so.
        discrepancy(managerToken, found)
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.outstandingReturnQty").value(0))
                .andExpect(jsonPath("$.outstandingReplacementQty").value(0))
                .andExpect(jsonPath("$.heldUndecidedQty").value(1))
                .andExpect(jsonPath("$.damagedUndecidedQty").value(1))
                .andExpect(jsonPath("$.wrongUndecidedQty").value(0));
        acceptAsVariant(found, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void s6DamagedGoodsReturnedAndWrongItemsKeptCloseTheCase() throws Exception {
        long grn = delivered(24, 1, 1);
        long found = caseOf(grn, oil);
        // Before V21 the return took the wrong item first, so Owner could not take it in (MSG309) and the case stuck.
        handle(managerToken, grn, List.of(byKind(found, 0, 1, 0))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].wrongUndecidedQty").value(1))
                .andExpect(jsonPath("$[0].damagedUndecidedQty").value(0));
        assertStockAgrees(giaLam, oil);
        returned(found, 1, "WRONG_ITEM").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG135"));
        returned(found, 1, "DAMAGED").andExpect(status().isOk())
                .andExpect(jsonPath("$.damagedOutstandingReturnQty").value(0));
        // What went back of a kind cannot be taken back.
        handle(managerToken, grn, List.of(byKind(found, 1, 0, 0))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG134"));
        receiveWrong(found, 1, false).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.events[*].type", contains("MOVED_TO_AWAITING_RETURN", "RETURNED_TO_SUPPLIER",
                        "RECEIVED_AS_OTHER_PRODUCT")));
        assertStockAgrees(giaLam, oil);
    }

    @Test
    void theOlderSingleReturnFigureStillTakesWrongItemsFirst() throws Exception {
        long grn = delivered(24, 1, 1);
        long found = caseOf(grn, oil);
        handle(managerToken, grn, found, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].wrongReturnRequiredQty").value(1))
                .andExpect(jsonPath("$[0].damagedReturnRequiredQty").value(0));
        // A return without a kind is read in the same order.
        supplierReturn(managerToken, found, Map.of("quantity", 1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.wrongOutstandingReturnQty").value(0))
                .andExpect(jsonPath("$.events[1].goodsKind").doesNotExist());
        handle(managerToken, grn, List.of(byKind(found, 0, 1, null))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG134"));
        handle(managerToken, grn, List.of(byKind(found, null, 1, null))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].wrongReturnRequiredQty").value(1))
                .andExpect(jsonPath("$[0].damagedReturnRequiredQty").value(1))
                .andExpect(jsonPath("$[0].damagedOutstandingReturnQty").value(1));
        handle(managerToken, grn, List.of(byKind(found, 2, 0, null))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG35"));
    }

    /** A delivery of 10 pads and {@code delivered} oils of the 24 ordered, some wrong or damaged, confirmed. */
    private long delivered(int delivered, int wrong, int damaged) throws Exception {
        long grn = receive("PX-001");
        assign(grn, staff);
        record(staffToken, grn, findings(counted(lineOf(grn, pad), 10, 10, 0, 0),
                counted(lineOf(grn, oil), 24, delivered, wrong, damaged))).andExpect(status().isOk());
        confirm(managerToken, grn).andExpect(status().isOk());
        return grn;
    }

    /** A replacement delivery for the case, bringing {@code quantity} good oils, confirmed. */
    private void replaced(long found, int quantity) throws Exception {
        long replacement = receive("PX-002", found);
        assign(replacement, staff);
        record(staffToken, replacement, findings(counted(lineOf(replacement, oil), quantity, quantity, 0, 0)))
                .andExpect(status().isOk());
        confirm(managerToken, replacement).andExpect(status().isOk());
    }

    private static Map<String, Object> byKind(long found, Integer wrong, Integer damaged, Integer replacement) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("caseId", found);
        line.put("wrongReturnQty", wrong);
        line.put("damagedReturnQty", damaged);
        line.put("replacementRequiredQty", replacement);
        return line;
    }

    private ResultActions returned(long found, int quantity, String kind) throws Exception {
        return supplierReturn(managerToken, found, Map.of("quantity", quantity, "goodsKind", kind));
    }

    private ResultActions acceptAsVariant(long found, int quantity) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("quantity", quantity);
        body.put("unitCost", 60_000);
        body.put("salePrice", 90_000);
        return mvc.perform(post("/api/v1/discrepancy-cases/" + found + "/damaged/accept-as-variant")
                .header(HttpHeaders.AUTHORIZATION, ownerToken).contentType(MediaType.APPLICATION_JSON)
                .content(json(body)));
    }

    private ResultActions receiveWrong(long found, int quantity, boolean replacement) throws Exception {
        return mvc.perform(post("/api/v1/discrepancy-cases/" + found + "/wrong-items/receive")
                .header(HttpHeaders.AUTHORIZATION, ownerToken).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("productId", lamp, "quantity", quantity, "unitCost", 70_000,
                        "replacementNeeded", replacement))));
    }
}
