package fu.hl.g64.motorflow.receiving;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC-45 Approve Excess and UC-46 Resolve Supplier Discrepancy, with BR-27 and BR-67: what a supplier owes after a
 * delivery, how it is returned, replaced or waived, and when a case closes.
 */
class DiscrepancyApiTest extends GoodsReceiptTestData {

    @Test
    void rejectedExcessAndDamagedGoodsGoBackToTheSupplierAndOwnerWaivesTheReplacement() throws Exception {
        long grn = receive("PX-001");
        assign(grn, staff);
        long padLine = lineOf(grn, pad);
        // 13 pads come for the 10 ordered, one of them damaged.
        record(staffToken, grn, findings(counted(padLine, 13, 13, 0, 1), counted(lineOf(grn, oil), 24, 24, 0, 0)))
                .andExpect(status().isOk());
        confirm(managerToken, grn)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[*].toStockQty", contains(10, 24)))
                .andExpect(jsonPath("$.lines[*].excessQty", contains(2, 0)))
                .andExpect(jsonPath("$.openDiscrepancies").value(1));
        long found = caseOf(grn, pad);
        discrepancy(managerToken, found)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goodsReceiptNoteId").value(grn))
                .andExpect(jsonPath("$.replacementDelivery").value(false))
                .andExpect(jsonPath("$.purchaseOrderId").value(order))
                .andExpect(jsonPath("$.supplierName").value("Honda Việt Nam"))
                .andExpect(jsonPath("$.storeName").value("Gia Lâm"))
                .andExpect(jsonPath("$.sku").value("MA-PHANH-01"))
                .andExpect(jsonPath("$.partName").value("Má phanh trước"))
                .andExpect(jsonPath("$.baseUnit").value("cái"))
                .andExpect(jsonPath("$.shortQty").value(0))
                .andExpect(jsonPath("$.damagedQty").value(1))
                .andExpect(jsonPath("$.returnRequiredQty").value(0))
                .andExpect(jsonPath("$.replacementRequiredQty").value(0))
                .andExpect(jsonPath("$.heldUndecidedQty").value(1))
                .andExpect(jsonPath("$.excessAwaitingDecision").value(true))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.closedByName").value(nullValue()))
                .andExpect(jsonPath("$.canHandle").value(true))
                .andExpect(jsonPath("$.canRecordReturn").value(false))
                .andExpect(jsonPath("$.events", hasSize(0)));

        // UC-45: the two pads the PO did not ask for go back to the supplier too (BR-67).
        decide(ownerToken, grn, padLine, "REJECTED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].excessDecision").value("REJECTED"));
        assertThat(stock(giaLam, pad, "AWAITING_SUPPLIER_RETURN")).isEqualTo(2);
        assertThat(stock(giaLam, pad, "AVAILABLE")).isEqualTo(10);
        assertThat(movementsOf(pad)).endsWith(List.of("EXCESS_REJECTED", "AWAITING_SUPPLIER_RETURN", 2));
        discrepancy(ownerToken, found)
                .andExpect(jsonPath("$.rejectedExcessQty").value(2))
                .andExpect(jsonPath("$.returnRequiredQty").value(2))
                .andExpect(jsonPath("$.replacementRequiredQty").value(0))
                .andExpect(jsonPath("$.outstandingReturnQty").value(2))
                .andExpect(jsonPath("$.excessAwaitingDecision").value(false));

        // UC-46 Step 5_Invalid or stale quantities: goods already awaiting return stay to be returned.
        handle(managerToken, grn, found, 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG134"))
                .andExpect(jsonPath("$.message").value("Số lượng sẽ trả lại của dòng 1 không được nhỏ hơn 2, là số đã "
                        + "chuyển sang chờ trả nhà cung cấp."));
        handle(managerToken, grn, found, 4)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG35"))
                .andExpect(jsonPath("$.message").value("Số lượng trả lại hoặc giao bù vượt quá số lượng sai lệch của "
                        + "dòng 1."));
        handle(managerToken, grn, 999_999L, 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG136"));
        handle(otherManagerToken, grn, found, 3)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        handle(staffToken, grn, found, 3).andExpect(status().isForbidden());

        // The damaged pad goes back as well, so it is to be replaced; the rejected excess is not (BR-27).
        handle(managerToken, grn, found, 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains(ids(found))))
                .andExpect(jsonPath("$[0].returnRequiredQty").value(3))
                .andExpect(jsonPath("$[0].replacementRequiredQty").value(1))
                .andExpect(jsonPath("$[0].outstandingReturnQty").value(3))
                .andExpect(jsonPath("$[0].openReplacementQty").value(1))
                .andExpect(jsonPath("$[0].heldUndecidedQty").value(0))
                .andExpect(jsonPath("$[0].canHandle").value(false))
                .andExpect(jsonPath("$[0].canRecordReturn").value(true))
                .andExpect(jsonPath("$[0].canCreateReplacement").value(true))
                .andExpect(jsonPath("$[0].canWaiveReplacement").value(false))
                .andExpect(jsonPath("$[0].events[*].type", contains("MOVED_TO_AWAITING_RETURN")))
                .andExpect(jsonPath("$[0].events[0].quantity").value(1))
                .andExpect(jsonPath("$[0].events[0].reason").value("Đã thống nhất với NCC"))
                .andExpect(jsonPath("$[0].events[0].recordedByName").value("Test bm"));
        assertThat(stock(giaLam, pad, "DAMAGED")).isZero();
        assertThat(stock(giaLam, pad, "AWAITING_SUPPLIER_RETURN")).isEqualTo(3);
        assertThat(movementsOf(pad)).endsWith(List.of("RETURN_HOLD", "DAMAGED", -1),
                List.of("RETURN_HOLD", "AWAITING_SUPPLIER_RETURN", 1));
        assertThat(jdbc.queryForList("SELECT concat_ws(',', actor_id, old_data ->> 'return_required_qty', "
                + "old_data ->> 'replacement_required_qty', new_data ->> 'return_required_qty', "
                + "new_data ->> 'replacement_required_qty', reason) FROM audit_log WHERE entity_type = "
                + "'DISCREPANCY_CASE' AND entity_id = ? AND action = 'DISCREPANCY_HANDLING_SAVED'", String.class, found))
                .containsExactly(manager.getId() + ",2,0,3,1,Đã thống nhất với NCC");
        // Saving the same handling again moves nothing.
        handle(ownerToken, grn, found, 3).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM discrepancy_resolution_event", Long.class)).isEqualTo(1);

        // UC-46 Step 1_Actual supplier return.
        Map<String, Object> handover = new LinkedHashMap<>();
        handover.put("quantity", 4);
        handover.put("note", "Giao cho tài xế NCC");
        supplierReturn(managerToken, found, handover)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG135"))
                .andExpect(jsonPath("$.message").value("Số lượng trả nhà cung cấp phải từ 1 đến 3, là số còn phải "
                        + "trả."));
        handover.put("quantity", 3);
        supplierReturn(ownerToken, found, handover)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        supplierReturn(otherManagerToken, found, handover).andExpect(status().isForbidden());
        handover.put("evidence", List.of(Map.of("storageKey", "motoflow/attachments/bien-ban", "fileName",
                "bien-ban.pdf", "contentType", "application/pdf", "fileSize", 50_000)));
        supplierReturn(managerToken, found, handover)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnedQty").value(3))
                .andExpect(jsonPath("$.outstandingReturnQty").value(0))
                .andExpect(jsonPath("$.outstandingReplacementQty").value(1))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.canRecordReturn").value(false))
                .andExpect(jsonPath("$.events[*].type", contains("MOVED_TO_AWAITING_RETURN", "RETURNED_TO_SUPPLIER")))
                .andExpect(jsonPath("$.events[1].quantity").value(3))
                .andExpect(jsonPath("$.events[1].reason").value("Giao cho tài xế NCC"))
                .andExpect(jsonPath("$.events[1].evidence[*].fileName", contains("bien-ban.pdf")));
        assertThat(stock(giaLam, pad, "AWAITING_SUPPLIER_RETURN")).isZero();
        assertThat(movementsOf(pad)).endsWith(List.of("SUPPLIER_RETURN", "AWAITING_SUPPLIER_RETURN", -3));
        supplierReturn(managerToken, found, Map.of("quantity", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG29"));

        // UC-46 Step 3_Shortage settled without replacement: Owner only, for no more than is still owed.
        waive(managerToken, found, null, "NCC trừ vào công nợ").andExpect(status().isForbidden());
        waive(ownerToken, found, 2, "NCC trừ vào công nợ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG144"))
                .andExpect(jsonPath("$.message").value("Số lượng không giao bù phải từ 1 đến 1, là số còn chờ giao "
                        + "bù."));
        waive(ownerToken, found, null, "  ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"))
                .andExpect(jsonPath("$.fieldErrors.reason").value("Vui lòng nhập lý do không giao bù."));
        waive(ownerToken, found, null, "  NCC trừ vào công nợ  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waivedQty").value(1))
                .andExpect(jsonPath("$.outstandingReplacementQty").value(0))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closureType").value("CLOSED_BY_AGREEMENT"))
                .andExpect(jsonPath("$.closureReason").value("NCC trừ vào công nợ"))
                .andExpect(jsonPath("$.closedById").value(owner.getId()))
                .andExpect(jsonPath("$.closedByName").value("Test owner"))
                .andExpect(jsonPath("$.closedAt").value("2026-10-13T08:00:00"))
                .andExpect(jsonPath("$.events[*].type", contains("MOVED_TO_AWAITING_RETURN", "RETURNED_TO_SUPPLIER",
                        "CLOSED_BY_AGREEMENT")))
                .andExpect(jsonPath("$.canWaiveReplacement").value(false));
        waive(ownerToken, found, null, "Lần nữa")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG29"));
        handle(managerToken, grn, found, 3)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG29"));

        // Goods awaiting return are not valued, so none of this changed the average cost (BR-74).
        assertThat(averageCost(pad)).isEqualByComparingTo("120000");
        assertStockAgrees(giaLam, pad);
        detail(managerToken, order)
                .andExpect(jsonPath("$.openDiscrepancies").value(0))
                .andExpect(jsonPath("$.discrepancies[*].status", contains("CLOSED")))
                .andExpect(jsonPath("$.canCreateReplacement").value(false));
    }

    @Test
    void aReplacementDeliverySettlesAShortage() throws Exception {
        long grn = receive("PX-001");
        assign(grn, staff);
        // Four oils of the document did not come.
        record(staffToken, grn, findings(counted(lineOf(grn, pad), 10, 10, 0, 0),
                counted(lineOf(grn, oil), 24, 20, 0, 0))).andExpect(status().isOk());
        confirm(managerToken, grn).andExpect(status().isOk());
        long shortage = caseOf(grn, oil);
        discrepancy(ownerToken, shortage)
                .andExpect(jsonPath("$.shortQty").value(4))
                .andExpect(jsonPath("$.replacementRequiredQty").value(4))
                .andExpect(jsonPath("$.openReplacementQty").value(4))
                .andExpect(jsonPath("$.canHandle").value(false))
                .andExpect(jsonPath("$.canWaiveReplacement").value(true))
                .andExpect(jsonPath("$.canCreateReplacement").value(false));
        discrepancy(managerToken, shortage)
                .andExpect(jsonPath("$.canWaiveReplacement").value(false))
                .andExpect(jsonPath("$.canCreateReplacement").value(true));

        // The PO expects nothing more of its own (BR-22): the four oils come as a replacement.
        createReceipt(managerToken, order, deliveryNote("PX-002"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG29"));
        createReceipt(managerToken, order, deliveryNote("PX-002", 999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG133"));
        String created = createReceipt(managerToken, order, deliveryNote("PX-002", shortage, shortage))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replacement").value(true))
                .andExpect(jsonPath("$.replacementForCaseIds", contains(ids(shortage))))
                .andExpect(jsonPath("$.lines[*].sku", contains("NHOT-01")))
                .andExpect(jsonPath("$.lines[*].expectedQty", contains(4)))
                .andReturn().getResponse().getContentAsString();
        long replacement = ((Number) JsonPath.read(created, "$.id")).longValue();
        discrepancy(managerToken, shortage)
                .andExpect(jsonPath("$.pendingReplacementQty").value(4))
                .andExpect(jsonPath("$.outstandingReplacementQty").value(4))
                .andExpect(jsonPath("$.openReplacementQty").value(0))
                .andExpect(jsonPath("$.canCreateReplacement").value(false))
                .andExpect(jsonPath("$.events[*].type", contains("REPLACEMENT_RECEIVED")))
                .andExpect(jsonPath("$.events[0].goodsReceiptNoteId").value(replacement))
                .andExpect(jsonPath("$.events[0].confirmed").value(false));
        // Replacement already on its way is neither expected twice nor waived.
        createReceipt(managerToken, order, deliveryNote("PX-003", shortage))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG141"))
                .andExpect(jsonPath("$.message").value("Hồ sơ sai lệch #" + shortage + " không còn số lượng chờ giao "
                        + "bù."));
        waive(ownerToken, shortage, null, "NCC trừ vào công nợ")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG141"));

        // Five oils come: the one more than owed waits for Owner (BR-67).
        assign(replacement, staff2);
        record(staff2Token, replacement, findings(counted(lineOf(replacement, oil), 5, 5, 0, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].toStockQty").value(4))
                .andExpect(jsonPath("$.lines[0].excessQty").value(1));
        confirm(managerToken, replacement)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].toStockQty").value(4))
                .andExpect(jsonPath("$.lines[0].excessDecision").value("AWAITING_OWNER"))
                .andExpect(jsonPath("$.openDiscrepancies").value(0));
        discrepancy(managerToken, shortage)
                .andExpect(jsonPath("$.replacedQty").value(4))
                .andExpect(jsonPath("$.pendingReplacementQty").value(0))
                .andExpect(jsonPath("$.outstandingReplacementQty").value(0))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closureType").value("ALL_SETTLED"))
                .andExpect(jsonPath("$.closureReason").value(nullValue()))
                .andExpect(jsonPath("$.closedById").value(manager.getId()))
                .andExpect(jsonPath("$.events[0].confirmed").value(true))
                .andExpect(jsonPath("$.events[0].quantity").value(4))
                .andExpect(jsonPath("$.events[0].confirmedAt").value("2026-10-13T08:00:00"));

        assertThat(stock(giaLam, oil, "AVAILABLE")).isEqualTo(24);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lot WHERE product_id = ?", Long.class, oil)).isEqualTo(2);
        assertStockAgrees(giaLam, oil);
        detail(managerToken, order)
                .andExpect(jsonPath("$.status").value("FULLY_RECEIVED"))
                .andExpect(jsonPath("$.lines[*].receivedQty", contains(10, 24)))
                .andExpect(jsonPath("$.lines[*].outstandingQty", contains(0, 0)))
                .andExpect(jsonPath("$.receipts[*].replacement", contains(false, true)))
                .andExpect(jsonPath("$.discrepancies[*].status", contains("CLOSED")))
                .andExpect(jsonPath("$.canCreateReplacement").value(false));
    }

    @Test
    void whatAReplacementDeliveryLacksStaysOwedOnTheOriginalCase() throws Exception {
        long grn = receive("PX-001");
        assign(grn, staff);
        record(staffToken, grn, findings(counted(lineOf(grn, pad), 10, 10, 0, 0),
                counted(lineOf(grn, oil), 24, 20, 0, 0))).andExpect(status().isOk());
        confirm(managerToken, grn).andExpect(status().isOk());
        long shortage = caseOf(grn, oil);

        long replacement = receive("PX-002", shortage);
        assign(replacement, staff);
        // One of the four replacement oils is damaged.
        record(staffToken, replacement, findings(counted(lineOf(replacement, oil), 4, 4, 0, 1)))
                .andExpect(status().isOk());
        confirm(managerToken, replacement)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].toStockQty").value(3))
                .andExpect(jsonPath("$.openDiscrepancies").value(1));
        discrepancy(managerToken, shortage)
                .andExpect(jsonPath("$.replacedQty").value(3))
                .andExpect(jsonPath("$.outstandingReplacementQty").value(1))
                .andExpect(jsonPath("$.openReplacementQty").value(1))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.canCreateReplacement").value(true))
                .andExpect(jsonPath("$.events[0].quantity").value(3))
                .andExpect(jsonPath("$.events[0].confirmed").value(true));

        // The case of the replacement only tracks the damaged oil going back.
        long damaged = caseOf(replacement, oil);
        handle(managerToken, replacement, damaged, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].replacementDelivery").value(true))
                .andExpect(jsonPath("$[0].shortQty").value(0))
                .andExpect(jsonPath("$[0].damagedQty").value(1))
                .andExpect(jsonPath("$[0].returnRequiredQty").value(1))
                .andExpect(jsonPath("$[0].replacementRequiredQty").value(0))
                .andExpect(jsonPath("$[0].canCreateReplacement").value(false));
        supplierReturn(managerToken, damaged, Map.of("quantity", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closureType").value("ALL_SETTLED"));
        assertThat(stock(giaLam, oil, "AVAILABLE")).isEqualTo(23);
        assertStockAgrees(giaLam, oil);
        detail(managerToken, order)
                .andExpect(jsonPath("$.lines[*].receivedQty", contains(10, 23)))
                .andExpect(jsonPath("$.discrepancies[*].id", contains(ids(shortage, damaged))))
                .andExpect(jsonPath("$.discrepancies[*].openReplacementQty", contains(1, 0)))
                .andExpect(jsonPath("$.canCreateReplacement").value(true));

        // The last oil comes in another replacement.
        long again = receive("PX-003", shortage);
        receipt(managerToken, again).andExpect(jsonPath("$.lines[*].expectedQty", contains(1)));
    }

    @Test
    void aCaseWaitsForTheExcessDecisionOfItsLineBeforeItCloses() throws Exception {
        long grn = receive("PX-001");
        assign(grn, staff);
        long padLine = lineOf(grn, pad);
        // 12 pads for the 10 ordered, one damaged: one pad is excess.
        record(staffToken, grn, findings(counted(padLine, 12, 12, 0, 1), counted(lineOf(grn, oil), 24, 24, 0, 0)))
                .andExpect(status().isOk());
        confirm(managerToken, grn).andExpect(jsonPath("$.lines[0].excessQty").value(1));
        long found = caseOf(grn, pad);
        handle(managerToken, grn, found, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].replacementRequiredQty").value(1));
        supplierReturn(managerToken, found, Map.of("quantity", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
        waive(ownerToken, found, 1, "Bù bằng má phanh giao thừa")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outstandingReplacementQty").value(0))
                // The extra pad of the same line may still be rejected and go back (UC-45).
                .andExpect(jsonPath("$.excessAwaitingDecision").value(true))
                .andExpect(jsonPath("$.status").value("OPEN"));

        decide(ownerToken, grn, padLine, "ACCEPTED").andExpect(status().isOk());
        discrepancy(ownerToken, found)
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closureType").value("CLOSED_BY_AGREEMENT"))
                .andExpect(jsonPath("$.closureReason").value("Bù bằng má phanh giao thừa"))
                .andExpect(jsonPath("$.closedById").value(owner.getId()));
        assertThat(stock(giaLam, pad, "AVAILABLE")).isEqualTo(11);
        assertStockAgrees(giaLam, pad);
    }

    @Test
    void ownerSeesEveryCaseAndABranchManagerOnlyTheCasesOfTheirStore() throws Exception {
        long grn = receive("PX-001");
        assign(grn, staff);
        // One pad short, two oils damaged.
        record(staffToken, grn, findings(counted(lineOf(grn, pad), 10, 9, 0, 0),
                counted(lineOf(grn, oil), 24, 24, 0, 2))).andExpect(status().isOk());
        confirm(managerToken, grn).andExpect(status().isOk());
        long padCase = caseOf(grn, pad);
        long oilCase = caseOf(grn, oil);

        UserAccount theirStaff = createUser("staff3", Role.STAFF, longBien);
        String theirStaffToken = signIn("staff3");
        long elsewhere = created(order(yamaha, longBien, TODAY, null, line(filter, 3, 50_000)));
        String response = createReceipt(otherManagerToken, elsewhere, deliveryNote("YM-1"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long theirs = ((Number) JsonPath.read(response, "$.id")).longValue();
        assign(otherManagerToken, theirs, theirStaff);
        record(theirStaffToken, theirs, findings(counted(lineOf(theirs, filter), 3, 2, 0, 0)))
                .andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(5));
        confirm(otherManagerToken, theirs).andExpect(status().isOk());
        long filterCase = caseOf(theirs, filter);

        // Newest first; the list carries no history.
        discrepancies(managerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", contains(ids(oilCase, padCase))))
                .andExpect(jsonPath("$.items[*].canHandle", contains(true, false)))
                .andExpect(jsonPath("$.items[0].events", hasSize(0)));
        discrepancies(managerToken, "storeId", String.valueOf(longBien))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        discrepancies(otherManagerToken).andExpect(jsonPath("$.items[*].id", contains(ids(filterCase))));
        discrepancies(ownerToken).andExpect(jsonPath("$.items[*].id", contains(ids(filterCase, oilCase, padCase))));
        discrepancies(ownerToken, "storeId", String.valueOf(giaLam))
                .andExpect(jsonPath("$.items[*].id", contains(ids(oilCase, padCase))));
        discrepancies(ownerToken, "purchaseOrderId", String.valueOf(elsewhere))
                .andExpect(jsonPath("$.items[*].id", contains(ids(filterCase))));
        discrepancies(ownerToken, "goodsReceiptNoteId", String.valueOf(grn))
                .andExpect(jsonPath("$.items[*].id", contains(ids(oilCase, padCase))));
        discrepancies(staffToken).andExpect(status().isForbidden());

        discrepancy(otherManagerToken, padCase)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        discrepancy(staffToken, padCase).andExpect(status().isForbidden());
        discrepancy(managerToken, 999_999)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG133"));
        // Nothing to return yet, and nothing to replace on goods kept at the store.
        supplierReturn(managerToken, oilCase, Map.of("quantity", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG29"));
        waive(ownerToken, oilCase, null, "NCC trừ vào công nợ")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG141"));

        waive(ownerToken, padCase, null, "NCC trừ vào công nợ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        discrepancies(ownerToken, "status", "OPEN")
                .andExpect(jsonPath("$.items[*].id", contains(ids(filterCase, oilCase))));
        discrepancies(ownerToken, "status", "CLOSED")
                .andExpect(jsonPath("$.items[*].id", contains(ids(padCase))));
    }
}
