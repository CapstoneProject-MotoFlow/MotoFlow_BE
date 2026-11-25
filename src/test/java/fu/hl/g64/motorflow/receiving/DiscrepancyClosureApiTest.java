package fu.hl.g64.motorflow.receiving;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
                .andExpect(jsonPath("$.events[?(@.type == 'RETURNED_TO_SUPPLIER')].goodsKind",
                        contains("WRONG_ITEM")));
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
        // GVHD 24/11: replacing 8 (not short + returned = 7) is the Owner's to decide.
        handle(ownerToken, grn, List.of(byKind(found, 2, 0, 8))).andExpect(status().isOk());
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
        handle(ownerToken, grn, List.of(byKind(found, 0, 1, 0))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].wrongUndecidedQty").value(1))
                .andExpect(jsonPath("$[0].damagedUndecidedQty").value(0));
        assertStockAgrees(giaLam, oil);
        returned(found, 1, "WRONG_ITEM").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG135"));
        returned(found, 1, "DAMAGED").andExpect(status().isOk())
                .andExpect(jsonPath("$.damagedOutstandingReturnQty").value(0));
        // GVHD 24/11: once goods went back the direction holds (MSG355).
        handle(ownerToken, grn, List.of(byKind(found, 1, 0, 0))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG355"));
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
        // A return without a kind is read in the same order, and stored with the kind it was read as.
        supplierReturn(managerToken, found, Map.of("quantity", 1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.wrongOutstandingReturnQty").value(0))
                .andExpect(jsonPath("$.events[?(@.type == 'RETURNED_TO_SUPPLIER')].goodsKind",
                        contains("WRONG_ITEM")));
        // GVHD 24/11: once goods went back the direction holds (MSG355); quantities above what is held stay MSG35.
        handle(managerToken, grn, List.of(byKind(found, 0, 1, null))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG355"));
        handle(managerToken, grn, List.of(byKind(found, null, 1, null))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG355"));
        handle(managerToken, grn, List.of(byKind(found, 2, 0, null))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG35"));
        // The same line again changes nothing.
        handle(managerToken, grn, found, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].wrongReturnRequiredQty").value(1));
    }

    @Test
    void aReturnWithoutAKindIsReadByTheAgreedKindAndFixesTheDirection() throws Exception {
        long grn = delivered(24, 1, 1);
        long found = caseOf(grn, oil);
        handle(ownerToken, grn, List.of(byKind(found, 0, 1, 0))).andExpect(status().isOk());
        // Read as the damaged goods: only they are agreed to go back.
        supplierReturn(managerToken, found, Map.of("quantity", 1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.events[?(@.type == 'RETURNED_TO_SUPPLIER')].goodsKind", contains("DAMAGED")));
        // GVHD 24/11: sending the wrong item back too is a new direction, refused once goods went back.
        handle(ownerToken, grn, List.of(byKind(found, 1, 1, 0))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG355"));
        receiveWrong(found, 1, false).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        assertStockAgrees(giaLam, oil);
    }

    @Test
    void aReturnRecordedBeforeV21IsNotMovedToAnotherKind() throws Exception {
        long grn = delivered(24, 1, 1);
        long found = caseOf(grn, oil);
        handle(ownerToken, grn, List.of(byKind(found, 0, 1, 0))).andExpect(status().isOk());
        returned(found, 1, "DAMAGED").andExpect(status().isOk());
        // As a return recorded before V21: no kind, read by the agreed wrong items, so the damaged goods now.
        jdbc.update("UPDATE discrepancy_resolution_event SET goods_kind = NULL WHERE discrepancy_case_id = ?", found);
        discrepancy(managerToken, found).andExpect(jsonPath("$.damagedOutstandingReturnQty").value(0));
        // Agreeing to send the wrong item back too would read that return as the wrong item; GVHD 24/11: the
        // direction holds once goods went back.
        handle(ownerToken, grn, List.of(byKind(found, 1, 1, 0))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG355"));
        discrepancy(managerToken, found).andExpect(jsonPath("$.damagedOutstandingReturnQty").value(0))
                .andExpect(jsonPath("$.wrongReturnRequiredQty").value(0));
    }

    @Test
    void aReturnWithoutAKindAcrossKindsIsStoredPerKind() throws Exception {
        long grn = delivered(24, 1, 1);
        long found = caseOf(grn, oil);
        handle(ownerToken, grn, List.of(byKind(found, 1, 1, 0))).andExpect(status().isOk());
        supplierReturn(managerToken, found, Map.of("quantity", 2)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.events[?(@.type == 'RETURNED_TO_SUPPLIER')].goodsKind",
                        contains("WRONG_ITEM", "DAMAGED")));
        assertStockAgrees(giaLam, oil);
    }

    /**
     * GVHD 24/11 (1): 3 short and 2 damaged; the Branch Manager proposes returning the damaged oils without any
     * replacement. Nothing moves and nothing can be done until the Owner approves; then the goods are held for return,
     * the replacement is waived and the return closes the case.
     */
    @Test
    void s9ReturnWithoutReplacementWaitsForTheOwnerThenCloses() throws Exception {
        long grn = delivered(21, 0, 2);
        long found = caseOf(grn, oil);
        handle(managerToken, grn, List.of(direction(byKind(found, 0, 2, 5), "RETURN_NO_REPLACEMENT")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG354"));
        handle(managerToken, grn, List.of(direction(byKind(found, 0, 2, null), "RETURN_NO_REPLACEMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].decisionStatus").value("AWAITING_OWNER"))
                .andExpect(jsonPath("$[0].damagedReturnRequiredQty").value(0))
                .andExpect(jsonPath("$[0].proposedDamagedReturnQty").value(2))
                .andExpect(jsonPath("$[0].canApproveDecision").value(false));
        assertThat(stock(giaLam, oil, "AWAITING_SUPPLIER_RETURN")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE notification_type = "
                + "'DISCREPANCY_DECISION_REQUESTED' AND recipient_id = ?", Long.class, owner.getId())).isEqualTo(1);
        returned(found, 1, "DAMAGED").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG359"));
        acceptAsVariant(found, 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG359"));
        createReceipt(managerToken, order, deliveryNote("PX-002", found)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG359"));
        mvc.perform(post("/api/v1/goods-receipt-notes/" + grn + "/discrepancies/approve")
                .header(HttpHeaders.AUTHORIZATION, managerToken)).andExpect(status().isForbidden());

        approveDecisions(grn).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].decisionStatus").value("DECIDED"))
                .andExpect(jsonPath("$[0].decisionType").value("RETURN_NO_REPLACEMENT"))
                .andExpect(jsonPath("$[0].damagedReturnRequiredQty").value(2))
                .andExpect(jsonPath("$[0].waivedQty").value(3))
                .andExpect(jsonPath("$[0].openReplacementQty").value(0))
                .andExpect(jsonPath("$[0].decisionLocked").value(true));
        assertThat(stock(giaLam, oil, "AWAITING_SUPPLIER_RETURN")).isEqualTo(2);
        approveDecisions(grn).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG360"));
        returned(found, 2, "DAMAGED").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closureType").value("CLOSED_BY_AGREEMENT"));
        assertStockAgrees(giaLam, oil);
    }

    /** GVHD 24/11 (3): the store keeps the damaged oil; once the Owner approves, the Owner takes it in as the variant. */
    @Test
    void s10KeepingTheGoodsWaitsForTheOwnerWhoThenTakesThemIn() throws Exception {
        long grn = delivered(24, 0, 1);
        long found = caseOf(grn, oil);
        handle(managerToken, grn, List.of(direction(byKind(found, null, null, null), "ACCEPT_NO_REPLACEMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].decisionStatus").value("AWAITING_OWNER"));
        approveDecisions(grn).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].decisionType").value("ACCEPT_NO_REPLACEMENT"))
                .andExpect(jsonPath("$[0].approvedByName").value("Test owner"))
                .andExpect(jsonPath("$[0].waivedQty").value(0))
                .andExpect(jsonPath("$[0].damagedUndecidedQty").value(1));
        acceptAsVariant(found, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    /** GVHD 24/11 (plan B4): the Owner taking goods of an undecided case in decides it as (3). */
    @Test
    void s11TheOwnerTakingGoodsInDecidesTheDirection() throws Exception {
        long grn = delivered(24, 1, 0);
        long found = caseOf(grn, oil);
        receiveWrong(found, 1, false).andExpect(status().isOk())
                .andExpect(jsonPath("$.decisionStatus").value("DECIDED"))
                .andExpect(jsonPath("$.decisionType").value("ACCEPT_NO_REPLACEMENT"))
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    /** Review 25/11: a proposal over a decided case keeps that decision in force; rejected, it stands again. */
    @Test
    void s12RejectingAProposalKeepsTheEarlierDecision() throws Exception {
        long grn = delivered(21, 0, 2);
        long found = caseOf(grn, oil);
        handle(managerToken, grn, List.of(direction(byKind(found, 0, 2, null), "RETURN_AND_REPLACE")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].decisionStatus").value("DECIDED"))
                .andExpect(jsonPath("$[0].replacementRequiredQty").value(5));
        assertThat(stock(giaLam, oil, "AWAITING_SUPPLIER_RETURN")).isEqualTo(2);
        // Sending the same line again changes nothing.
        handle(managerToken, grn, List.of(direction(byKind(found, 0, 2, 5), "RETURN_AND_REPLACE")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].decisionStatus").value("DECIDED"));

        handle(managerToken, grn, List.of(direction(byKind(found, 0, 2, null), "RETURN_NO_REPLACEMENT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].decisionStatus").value("AWAITING_OWNER"));
        assertThat(stock(giaLam, oil, "AWAITING_SUPPLIER_RETURN")).isEqualTo(2);
        rejectDecisions(grn, "Vẫn yêu cầu NCC giao bù").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].decisionStatus").value("DECIDED"))
                .andExpect(jsonPath("$[0].decisionType").value("RETURN_AND_REPLACE"))
                .andExpect(jsonPath("$[0].damagedReturnRequiredQty").value(2))
                .andExpect(jsonPath("$[0].replacementRequiredQty").value(5));
        assertThat(stock(giaLam, oil, "AWAITING_SUPPLIER_RETURN")).isEqualTo(2);
        returned(found, 2, "DAMAGED").andExpect(status().isOk());
        assertStockAgrees(giaLam, oil);
    }

    /** Review 25/11: the Owner waives on an undecided case only when no wrong or damaged goods await a direction. */
    @Test
    void s13WaivingNeedsTheDirectionWhenGoodsAreHeld() throws Exception {
        long grn = delivered(21, 0, 2);
        long found = caseOf(grn, oil);
        waive(ownerToken, found, null, "NCC hết hàng").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG357"));
    }

    /** GVHD 24/11: the Owner waiving an undecided shortage decides it as (1). */
    @Test
    void s14WaivingAShortageDecidesIt() throws Exception {
        long shortOnly = caseOf(delivered(21, 0, 0), oil);
        waive(ownerToken, shortOnly, null, "NCC hết hàng").andExpect(status().isOk())
                .andExpect(jsonPath("$.decisionType").value("RETURN_NO_REPLACEMENT"))
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    /** Review 25/11 (R-09): the Owner taking only part of the held goods leaves the rest for the Branch Manager. */
    @Test
    void s15TakingPartOfTheGoodsInLeavesTheDirectionOpen() throws Exception {
        long grn = delivered(24, 0, 2);
        long found = caseOf(grn, oil);
        acceptAsVariant(found, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.decisionStatus").value("UNDECIDED"))
                .andExpect(jsonPath("$.decisionLocked").value(false))
                .andExpect(jsonPath("$.status").value("OPEN"));
        handle(managerToken, grn, List.of(direction(byKind(found, 0, 1, null), "RETURN_AND_REPLACE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].decisionStatus").value("DECIDED"))
                .andExpect(jsonPath("$[0].decisionType").value("RETURN_AND_REPLACE"))
                .andExpect(jsonPath("$[0].damagedReturnRequiredQty").value(1));
        returned(found, 1, "DAMAGED").andExpect(status().isOk());
        assertStockAgrees(giaLam, oil);
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
