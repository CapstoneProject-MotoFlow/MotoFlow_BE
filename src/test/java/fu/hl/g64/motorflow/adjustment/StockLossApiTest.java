package fu.hl.g64.motorflow.adjustment;

import fu.hl.g64.motorflow.support.QueryCounter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-83 report stock loss or damage, UC-82 list, UC-84 approve, reject, apply again, withdraw. */
class StockLossApiTest extends AdjustmentTestData {

    @Autowired
    private QueryCounter queries;

    @Test
    void aReportedLossChangesNoStockUntilOwnerApprovesItThenItIsPostedOnceAtTheAverageCost() throws Exception {
        Map<String, Object> body = loss(null, line(oil, oilLot1, "AVAILABLE", 2), line(filter, null, "AVAILABLE", 3));

        // UC-83 step 5: the Branch Manager sees quantities, Owner sees the value too (BR-72).
        preview(managerToken, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.storeName").value("Gia Lâm"))
                .andExpect(jsonPath("$.postingDate").value("2026-10-13"))
                .andExpect(jsonPath("$.stockLines[*].quantityChange", contains(-2, -3)))
                .andExpect(jsonPath("$.stockLines[*].currentQty", contains(5, 9)))
                .andExpect(jsonPath("$.stockLines[*].afterQty", contains(3, 6)))
                .andExpect(jsonPath("$.stockLines[*].unitCost", everyItem(nullValue())))
                .andExpect(jsonPath("$.totalValue").value(nullValue()));
        Map<String, Object> asOwner = loss(store, line(oil, oilLot1, "AVAILABLE", 2), line(filter, null, "AVAILABLE", 3));
        preview(ownerToken, asOwner).andExpect(status().isOk())
                .andExpect(jsonPath("$.stockLines[*].unitCost", contains(85_000, 50_000)))
                .andExpect(jsonPath("$.totalValue").value(-320_000));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM adjustment_request", Long.class)).isZero();

        // Evidence is required (plan 6.6).
        Map<String, Object> noEvidence = loss(null, line(oil, oilLot1, "AVAILABLE", 2));
        noEvidence.put("attachments", List.of());
        submit(managerToken, noEvidence).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG255"));

        long id = submitted(managerToken, body);
        detail(managerToken, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value("#" + id))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.adjustmentType").value("STOCK_LOSS"))
                .andExpect(jsonPath("$.source").value(nullValue()))
                .andExpect(jsonPath("$.requestedBy.id").value(manager.getId()))
                .andExpect(jsonPath("$.evidence[0].fileName").value("anh-hong.jpg"))
                .andExpect(jsonPath("$.history[*].action", contains("ADJUSTMENT_SUBMITTED")))
                .andExpect(jsonPath("$.actions.canApprove").value(false))
                .andExpect(jsonPath("$.actions.canWithdraw").value(true));
        assertThat(available(oil)).isEqualTo(12);
        assertThat(available(filter)).isEqualTo(9);
        assertThat(notifications(owner, "ADJUSTMENT_AWAITING_APPROVAL")).isEqualTo(1);

        // UC-82: the store's own list; another store's manager sees nothing and cannot open it.
        list(managerToken).andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].lineCount").value(2))
                .andExpect(jsonPath("$.items[0].totalValue").value(nullValue()));
        list(otherManagerToken).andExpect(jsonPath("$.totalItems").value(0));
        detail(otherManagerToken, id).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG253"));
        list(ownerToken, "status", "PENDING", "type", "STOCK_LOSS").andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].storeName").value("Gia Lâm"))
                .andExpect(jsonPath("$.items[0].totalValue").value(-320_000));
        list(ownerToken, "status", "APPLIED").andExpect(jsonPath("$.totalItems").value(0));

        // Only Owner decides.
        act(managerToken, id, "approve").andExpect(status().isForbidden());
        act(staffToken, id, "approve").andExpect(status().isForbidden());
        detail(ownerToken, id).andExpect(jsonPath("$.actions.canApprove").value(true))
                .andExpect(jsonPath("$.actions.canWithdraw").value(false));

        act(ownerToken, id, "approve").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.decidedBy.id").value(owner.getId()))
                .andExpect(jsonPath("$.appliedAt").value("2026-10-13T08:00:00"))
                .andExpect(jsonPath("$.totalValue").value(-320_000))
                .andExpect(jsonPath("$.history[*].action",
                        contains("ADJUSTMENT_SUBMITTED", "ADJUSTMENT_APPROVED", "ADJUSTMENT_APPLIED")));
        assertThat(available(oil)).isEqualTo(10);
        assertThat(lotQty(oilLot1)).isEqualTo(3);
        // No lot named: FEFO, the lot expiring first goes first.
        assertThat(available(filter)).isEqualTo(6);
        assertThat(lotQty(filterLotA)).isEqualTo(1);
        assertThat(lotQty(filterLotB)).isEqualTo(3);
        assertThat(movements(id)).containsExactly("ADJUSTMENT,-2,-170000", "ADJUSTMENT,-3,-150000");
        assertThat(notifications(manager, "ADJUSTMENT_APPLIED")).isEqualTo(1);

        // UC-84 Step 7_Already applied: shown again, nothing repeated.
        act(ownerToken, id, "approve").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPLIED"));
        act(ownerToken, id, "apply").andExpect(status().isOk());
        assertThat(movements(id)).hasSize(2);
        reject(ownerToken, id, "Không đủ bằng chứng").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG281"));
    }

    @Test
    void aLossCannotTakeMoreThanTheStockOtherOpenRequestsLeave() throws Exception {
        submitted(managerToken, loss(null, line(oil, oilLot1, "AVAILABLE", 4)));
        // Lot 1 has 5, 4 are spoken for.
        submit(manager2Token, loss(null, line(oil, oilLot1, "AVAILABLE", 2))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG257"))
                .andExpect(jsonPath("$.message").value("Số lượng của phụ tùng NHOT-01 vượt số còn có thể điều chỉnh: "
                        + "tối đa 1."));
        // The part has 12, 4 are spoken for.
        submit(manager2Token, loss(null, line(oil, null, "AVAILABLE", 9))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Số lượng của phụ tùng NHOT-01 vượt số còn có thể điều chỉnh: "
                        + "tối đa 8."));
        submitted(manager2Token, loss(null, line(oil, null, "AVAILABLE", 8)));
        // Nothing is damaged.
        submit(managerToken, loss(null, line(filter, null, "DAMAGED", 1))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG257"));
    }

    @Test
    void theLinesTheStoreAndTheCallerAreChecked() throws Exception {
        submit(managerToken, loss(null, line(oil, filterLotA, "AVAILABLE", 1))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG258"));
        submit(managerToken, loss(null, line(oil, oilLot1, "AVAILABLE", 1), line(oil, oilLot1, "AVAILABLE", 1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG256"))
                .andExpect(jsonPath("$.message").value(endsWith("(dòng 2).")));
        submit(managerToken, loss(null, line(oil, oilLot1, "AVAILABLE", 1), line(oil, null, "AVAILABLE", 1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG256"));
        // Goods in transit are written off from their transfer; goods being inspected are not the store's yet.
        submit(managerToken, loss(null, line(oil, null, "IN_TRANSIT", 1))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG259"));
        submit(managerToken, loss(null, line(oil, null, "AWAITING_INSPECTION", 1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG259"));
        Map<String, Object> twoSources = loss(null, line(oil, null, "AVAILABLE", 1));
        twoSources.put("transferOrderId", 1);
        twoSources.put("goodsReceiptNoteId", 1);
        submit(managerToken, twoSources).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG280"));
        Map<String, Object> noReason = loss(null, line(oil, null, "AVAILABLE", 1));
        noReason.put("reason", " ");
        submit(managerToken, noReason).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.reason").value("Vui lòng nhập mô tả sự việc."));
        submit(managerToken, loss(null, line(oil, null, "AVAILABLE", 0))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['lines[0].quantity']").value("Số lượng phải lớn hơn 0."));

        // A Branch Manager reports for their store only; Owner names the store.
        submit(managerToken, loss(otherStore, line(oil, null, "AVAILABLE", 1))).andExpect(status().isForbidden());
        submit(ownerToken, loss(null, line(oil, null, "AVAILABLE", 1))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG254"));
        submit(ownerToken, loss(otherStore, line(oil, null, "AVAILABLE", 1))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG257"));
        submit(staffToken, loss(null, line(oil, null, "AVAILABLE", 1))).andExpect(status().isForbidden());
        list(staffToken).andExpect(status().isForbidden());
        list(managerToken, "storeId", String.valueOf(otherStore)).andExpect(status().isForbidden());
        list(managerToken, "from", "2026-10-14", "to", "2026-10-13").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG264"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM adjustment_request", Long.class)).isZero();
    }

    @Test
    void ownerRejectsWithAReasonAndTheRequesterMayWithdrawWhatIsStillPending() throws Exception {
        long rejected = submitted(managerToken, loss(null, line(oil, oilLot1, "AVAILABLE", 5)));
        reject(ownerToken, rejected, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.reason").value("Vui lòng nhập lý do từ chối."));
        reject(managerToken, rejected, "Không").andExpect(status().isForbidden());
        reject(ownerToken, rejected, "Ảnh không thấy hàng hỏng").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decisionReason").value("Ảnh không thấy hàng hỏng"))
                .andExpect(jsonPath("$.postingDate").value(nullValue()))
                .andExpect(jsonPath("$.history[1].reason").value("Ảnh không thấy hàng hỏng"));
        assertThat(notifications(manager, "ADJUSTMENT_REJECTED")).isEqualTo(1);
        act(ownerToken, rejected, "approve").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG281"))
                .andExpect(jsonPath("$.message").value("Đề nghị #" + rejected + " đang ở trạng thái REJECTED nên "
                        + "không thể thực hiện thao tác này."));
        assertThat(available(oil)).isEqualTo(12);

        // A rejected request holds nothing: the whole lot can be reported again.
        long withdrawn = submitted(managerToken, loss(null, line(oil, oilLot1, "AVAILABLE", 5)));
        act(manager2Token, withdrawn, "withdraw").andExpect(status().isForbidden());
        act(ownerToken, withdrawn, "withdraw").andExpect(status().isForbidden());
        act(managerToken, withdrawn, "withdraw").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.actions.canWithdraw").value(false));
        act(managerToken, withdrawn, "withdraw").andExpect(status().isConflict());
        act(ownerToken, withdrawn, "approve").andExpect(status().isConflict());
        submitted(manager2Token, loss(null, line(oil, oilLot1, "AVAILABLE", 5)));
        list(managerToken, "mine", "true").andExpect(jsonPath("$.totalItems").value(2));
        list(managerToken).andExpect(jsonPath("$.items[*].status", contains("PENDING", "WITHDRAWN", "REJECTED")));
        // Review 17/11 R-08: one tab, several statuses.
        list(managerToken, "status", "REJECTED", "status", "WITHDRAWN")
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[*].id", contains((int) withdrawn, (int) rejected)));
        list(managerToken, "status", "PENDING").andExpect(jsonPath("$.items[*].status", contains("PENDING")));
    }

    @Test
    void ownerMayReportAndApproveTheirOwnRequestAndIsRecordedInBothRoles() throws Exception {
        long id = submitted(ownerToken, loss(store, line(filter, filterLotPlain, "AVAILABLE", 2)));
        assertThat(notifications(owner, "ADJUSTMENT_AWAITING_APPROVAL")).isZero();
        act(ownerToken, id, "approve").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.requestedBy.id").value(owner.getId()))
                .andExpect(jsonPath("$.decidedBy.id").value(owner.getId()))
                .andExpect(jsonPath("$.history[*].actor.id", everyItem(org.hamcrest.Matchers.is(
                        owner.getId().intValue()))));
        assertThat(lotQty(filterLotPlain)).isZero();
        assertThat(notifications(owner, "ADJUSTMENT_APPLIED")).isZero();
    }

    @Test
    void aClosedMonthOrStockThatMovedSinceKeepsTheRequestFromBeingApplied() throws Exception {
        long id = submitted(managerToken, loss(null, line(oil, oilLot1, "AVAILABLE", 4)));

        // UC-84 Step 4_Balances or dependencies changed: three of the lot were sold meanwhile; nothing is approved.
        takeFromLot(oilLot1, 3);
        act(ownerToken, id, "approve").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG257"));
        detail(ownerToken, id).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.stockLines[0].currentQty").value(2))
                .andExpect(jsonPath("$.stockLines[0].afterQty").value(-2));

        // Application happens on today's date in the open period (BR-65): a closed month refuses it.
        jdbc.update("INSERT INTO inventory_period (period_month, from_date, to_date, status, closed_at) "
                + "VALUES ('2026-10-01', '2026-10-01', '2026-10-31', 'CLOSED', ?)", LocalDateTime.now(clock));
        jdbc.update("UPDATE lot_balance SET quantity = quantity + 3 WHERE lot_id = ? AND stock_status = 'AVAILABLE'",
                oilLot1);
        jdbc.update("UPDATE stock_balance SET available_qty = available_qty + 3 WHERE store_id = ? AND product_id = ?",
                store, oil);
        act(ownerToken, id, "approve").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG85"));
        detail(ownerToken, id).andExpect(jsonPath("$.status").value("PENDING"));

        // UC-84 Step 7_Application fails: an approval already recorded stays APPROVED and is applied again later.
        jdbc.update("UPDATE inventory_period SET status = 'OPEN', closed_at = NULL");
        jdbc.update("UPDATE adjustment_request SET status = 'APPROVED', decided_by = ?, decided_at = ? WHERE id = ?",
                owner.getId(), LocalDateTime.now(clock), id);
        takeFromLot(oilLot1, 3);
        act(ownerToken, id, "apply").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG257"));
        detail(ownerToken, id).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.actions.canApply").value(true))
                .andExpect(jsonPath("$.actions.canApprove").value(false));
        assertThat(movements(id)).isEmpty();
        act(managerToken, id, "apply").andExpect(status().isForbidden());
        jdbc.update("UPDATE lot_balance SET quantity = quantity + 3 WHERE lot_id = ? AND stock_status = 'AVAILABLE'",
                oilLot1);
        jdbc.update("UPDATE stock_balance SET available_qty = available_qty + 3 WHERE store_id = ? AND product_id = ?",
                store, oil);
        act(ownerToken, id, "apply").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPLIED"));
        assertThat(lotQty(oilLot1)).isEqualTo(1);
        assertThat(LocalDate.now(clock)).isEqualTo(LocalDate.of(2026, 10, 13));
    }

    @Test
    void theDetailReadsTheStockOfEveryLineTogether() throws Exception {
        long one = submitted(managerToken, loss(null, line(oil, oilLot1, "AVAILABLE", 1)));
        long three = submitted(manager2Token, loss(null, line(oil, oilLot1, "AVAILABLE", 1),
                line(oil, oilLot2, "AVAILABLE", 2), line(filter, null, "AVAILABLE", 3)));

        queries.reset();
        detail(managerToken, one).andExpect(status().isOk());
        long forOne = queries.prepared();

        queries.reset();
        detail(manager2Token, three).andExpect(status().isOk())
                .andExpect(jsonPath("$.stockLines[*].currentQty", contains(5, 7, 9)))
                .andExpect(jsonPath("$.stockLines[*].afterQty", contains(4, 5, 6)));
        assertThat(queries.prepared()).isEqualTo(forOne);
    }

    @Test
    void twoLotsOfOnePartAreCheckedEachAndTogether() throws Exception {
        submitted(managerToken, loss(null, line(oil, oilLot1, "AVAILABLE", 4)));
        // Lot 1 has 1 left, lot 2 has 7; the part has 12 - 4 = 8.
        submitted(manager2Token, loss(null, line(oil, oilLot1, "AVAILABLE", 1), line(oil, oilLot2, "AVAILABLE", 7)));
        submit(managerToken, loss(null, line(oil, oilLot2, "AVAILABLE", 1))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG257"))
                .andExpect(jsonPath("$.message").value("Số lượng của phụ tùng NHOT-01 vượt số còn có thể điều chỉnh: "
                        + "tối đa 0."));
        submit(managerToken, loss(null, line(oil, null, "AVAILABLE", 1))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG257"));
    }

    @Test
    void thePreviewChecksTheStockOfEveryLineTogether() throws Exception {
        queries.reset();
        preview(managerToken, loss(null, line(oil, oilLot1, "AVAILABLE", 1))).andExpect(status().isOk());
        long forOne = queries.prepared();

        queries.reset();
        preview(managerToken, loss(null, line(oil, oilLot1, "AVAILABLE", 1), line(oil, oilLot2, "AVAILABLE", 2),
                line(filter, null, "AVAILABLE", 3))).andExpect(status().isOk());
        assertThat(queries.prepared()).isEqualTo(forOne);
    }

    /** "type,quantity,value" of the movements the request posted. */
    private List<String> movements(long id) {
        return jdbc.queryForList("SELECT movement_type || ',' || quantity_change || ',' || value FROM stock_movement "
                + "WHERE adjustment_request_id = ? ORDER BY product_id, id", String.class, id);
    }
}
