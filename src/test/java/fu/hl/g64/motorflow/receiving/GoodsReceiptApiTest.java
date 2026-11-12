package fu.hl.g64.motorflow.receiving;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC-106 Create GRN, UC-43 Record Received Quantities and UC-44 Confirm GRN, with BR-21 to BR-26, BR-32, BR-67 and
 * BR-73: what a supplier delivery puts into stock, and only once.
 */
class GoodsReceiptApiTest extends GoodsReceiptTestData {

    private static final String MSG27 = "Số lượng đạt, giao sai và hư hỏng phải cộng bằng số lượng thực giao (dòng 1).";
    private static final String MSG136 = "Phụ tùng ở dòng 2 không có trong phiếu nhận hàng hoặc đã có ở dòng trên.";

    @Test
    void aDeliveryIsInspectedConfirmedAndPostedToStockOnce() throws Exception {
        String created = createReceipt(managerToken, order, deliveryNote("px-001"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/goods-receipt-notes/")))
                .andExpect(jsonPath("$.receiptType").value("SUPPLIER_DELIVERY"))
                .andExpect(jsonPath("$.status").value("AWAITING_INSPECTION"))
                .andExpect(jsonPath("$.replacement").value(false))
                .andExpect(jsonPath("$.replacementForCaseIds", hasSize(0)))
                .andExpect(jsonPath("$.purchaseOrderId").value(order))
                .andExpect(jsonPath("$.supplierName").value("Honda Việt Nam"))
                .andExpect(jsonPath("$.storeName").value("Gia Lâm"))
                .andExpect(jsonPath("$.deliveryDocumentNo").value("px-001"))
                .andExpect(jsonPath("$.deliveryDocumentDate").value("2026-10-13"))
                .andExpect(jsonPath("$.delivererName").value("Anh Tuấn"))
                .andExpect(jsonPath("$.vehiclePlateNo").value("29C-123.45"))
                .andExpect(jsonPath("$.arrivedAt").value("2026-10-13T08:00:00"))
                // Lecturer feedback 12/11: whoever records the GRN inspects it.
                .andExpect(jsonPath("$.assignee.id").value(manager.getId()))
                // UC-106 step 6: one line per outstanding PO line; a Branch Manager sees no price (BR-72).
                .andExpect(jsonPath("$.lines[*].sku", contains("MA-PHANH-01", "NHOT-01")))
                .andExpect(jsonPath("$.lines[*].expectedQty", contains(10, 24)))
                .andExpect(jsonPath("$.lines[*].deliveredQty", contains(0, 0)))
                .andExpect(jsonPath("$.lines[*].unitPrice", everyItem(nullValue())))
                .andExpect(jsonPath("$.canRecord").value(true))
                .andExpect(jsonPath("$.canConfirm").value(false))
                .andReturn().getResponse().getContentAsString();
        long grn = ((Number) JsonPath.read(created, "$.id")).longValue();
        assertThat(jdbc.queryForObject("SELECT action FROM audit_log WHERE entity_type = 'GOODS_RECEIPT_NOTE' "
                + "AND entity_id = ? AND actor_id = ?", String.class, grn, manager.getId()))
                .isEqualTo("GOODS_RECEIPT_CREATED");
        // BR-21: the PO can no longer change.
        detail(ownerToken, order)
                .andExpect(jsonPath("$.status").value("RECEIVING"))
                .andExpect(jsonPath("$.canUpdate").value(false))
                .andExpect(jsonPath("$.receipts[*].id", contains(ids(grn))));

        assign(grn, staff);
        receipt(staffToken, grn)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee.id").value(staff.getId()))
                .andExpect(jsonPath("$.assignee.fullName").value("Test staff"))
                .andExpect(jsonPath("$.assignee.taskStatus").value("ASSIGNED"))
                .andExpect(jsonPath("$.canRecord").value(true))
                .andExpect(jsonPath("$.lines[*].expectedQty", contains(10, 24)))
                .andExpect(jsonPath("$.lines[*].unitPrice", everyItem(nullValue())));

        long padLine = lineOf(grn, pad);
        long oilLine = lineOf(grn, oil);
        Map<String, Object> pads = counted(padLine, 10, 10, null, 2);
        pads.put("manufacturerLotNo", "  LOT-A  ");
        pads.put("manufactureDate", "2026-01-01");
        pads.put("expiryDate", "2028-01-01");
        // Two pads are damaged and four oils did not come.
        record(staffToken, grn, findings(pads, counted(oilLine, 24, 20, 0, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.recordedById").value(staff.getId()))
                .andExpect(jsonPath("$.recordedByName").value("Test staff"))
                .andExpect(jsonPath("$.submittedAt").value("2026-10-13T08:00:00"))
                .andExpect(jsonPath("$.assignee.taskStatus").value("RESULT_SUBMITTED"))
                .andExpect(jsonPath("$.lines[*].passedQty", contains(8, 20)))
                .andExpect(jsonPath("$.lines[*].shortQty", contains(0, 4)))
                .andExpect(jsonPath("$.lines[*].toStockQty", contains(8, 20)))
                .andExpect(jsonPath("$.lines[*].excessQty", contains(0, 0)))
                .andExpect(jsonPath("$.lines[0].manufacturerLotNo").value("LOT-A"))
                .andExpect(jsonPath("$.canRecord").value(false));
        // BR-25: nothing reaches stock before the confirmation.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Long.class)).isZero();
        assertThat(notificationsOf(manager)).contains(List.of("GOODS_RECEIPT_SUBMITTED", "Phiếu nhận hàng #" + grn
                + " của yêu cầu nhập hàng #" + order + " đã có kết quả kiểm nhận và đang chờ xác nhận.",
                "GOODS_RECEIPT_NOTE", grn));

        receipt(managerToken, grn)
                .andExpect(jsonPath("$.canConfirm").value(true))
                .andExpect(jsonPath("$.canReturnForRecount").value(true));
        confirm(managerToken, grn)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedById").value(manager.getId()))
                .andExpect(jsonPath("$.confirmedByName").value("Test bm"))
                .andExpect(jsonPath("$.confirmedAt").value("2026-10-13T08:00:00"))
                .andExpect(jsonPath("$.lines[*].expectedQty", everyItem(nullValue())))
                .andExpect(jsonPath("$.lines[*].toStockQty", contains(8, 20)))
                .andExpect(jsonPath("$.lines[*].lotId", everyItem(notNullValue())))
                .andExpect(jsonPath("$.openDiscrepancies").value(2))
                .andExpect(jsonPath("$.canConfirm").value(false));

        // UC-44 steps 5-7: passed goods are available, damaged goods kept apart, one lot per line (BR-73).
        assertThat(stock(giaLam, pad, "AVAILABLE")).isEqualTo(8);
        assertThat(stock(giaLam, pad, "DAMAGED")).isEqualTo(2);
        assertThat(stock(giaLam, oil, "AVAILABLE")).isEqualTo(20);
        assertThat(jdbc.queryForList("SELECT purchase_unit_price, manufacturer_lot_no, expiry_date, received_date, "
                + "supplier_id FROM lot WHERE goods_receipt_note_line_id = ?", padLine))
                .singleElement().satisfies(lot -> {
                    assertThat(lot.get("purchase_unit_price")).isEqualTo(120_000L);
                    assertThat(lot.get("manufacturer_lot_no")).isEqualTo("LOT-A");
                    assertThat(lot.get("expiry_date").toString()).isEqualTo("2028-01-01");
                    assertThat(lot.get("received_date").toString()).isEqualTo("2026-10-13");
                    assertThat(lot.get("supplier_id")).isEqualTo(honda);
                });
        assertThat(averageCost(pad)).isEqualByComparingTo("120000");
        assertThat(averageCost(oil)).isEqualByComparingTo("85000");
        assertStockAgrees(giaLam, pad);
        assertStockAgrees(giaLam, oil);
        // The damaged pads and the missing oils are tracked until settled (UC-46).
        assertThat(jdbc.queryForList("SELECT product_id, short_qty, damaged_qty, return_required_qty, "
                + "replacement_required_qty, status FROM discrepancy_case WHERE goods_receipt_note_id = ? ORDER BY "
                + "product_id", grn)).containsExactly(
                Map.of("product_id", pad, "short_qty", 0, "damaged_qty", 2, "return_required_qty", 0,
                        "replacement_required_qty", 0, "status", "OPEN"),
                Map.of("product_id", oil, "short_qty", 4, "damaged_qty", 0, "return_required_qty", 0,
                        "replacement_required_qty", 4, "status", "OPEN"));
        assertThat(notificationsOf(owner)).contains(List.of("SUPPLIER_DISCREPANCY_OPENED", "Phiếu nhận hàng #" + grn
                + " của yêu cầu nhập hàng #" + order + " có 2 phụ tùng thiếu, giao sai hoặc hư hỏng cần xử lý với nhà "
                + "cung cấp.", "GOODS_RECEIPT_NOTE", grn));
        assertThat(jdbc.queryForObject("SELECT status FROM warehouse_task WHERE goods_receipt_note_id = ? "
                + "AND status <> 'CANCELLED'", String.class, grn)).isEqualTo("DONE");

        // BR-22: every line is covered by the delivery; what it lacks is owed through the discrepancy cases.
        detail(managerToken, order)
                .andExpect(jsonPath("$.status").value("FULLY_RECEIVED"))
                .andExpect(jsonPath("$.lines[*].receivedQty", contains(8, 20)))
                .andExpect(jsonPath("$.lines[*].outstandingQty", contains(0, 0)))
                .andExpect(jsonPath("$.canCreateReceipt").value(false))
                .andExpect(jsonPath("$.canCreateReplacement").value(true))
                .andExpect(jsonPath("$.discrepancies[*].outstandingReplacementQty", contains(0, 4)))
                .andExpect(jsonPath("$.discrepancies[*].openReplacementQty", contains(0, 4)));

        // BR-25: a second confirmation posts nothing.
        confirm(managerToken, grn)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG29"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE movement_type = 'RECEIPT_CONFIRMED'",
                Long.class)).isEqualTo(3);
        assertThat(stock(giaLam, pad, "AVAILABLE")).isEqualTo(8);
    }

    @Test
    void theBranchManagerReturnsAResultForARecountToTheStaffMemberWhoRecordedIt() throws Exception {
        long grn = receive("PX-001");
        assign(grn, staff);
        long padLine = lineOf(grn, pad);
        long oilLine = lineOf(grn, oil);
        // BR-32: only the assignee records.
        record(staff2Token, grn, findings(counted(padLine, 10, 10, 0, 0)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        record(staffToken, grn, findings(counted(padLine, 10, 9, 0, 0), counted(oilLine, 24, 24, 0, 0)))
                .andExpect(status().isOk());
        record(staffToken, grn, findings(counted(padLine, 10, 10, 0, 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG29"));

        recount(managerToken, grn, "  Đếm lại má phanh  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_RECOUNT"))
                .andExpect(jsonPath("$.recountReason").value("Đếm lại má phanh"))
                .andExpect(jsonPath("$.assignee.id").value(staff.getId()))
                .andExpect(jsonPath("$.assignee.taskStatus").value("ASSIGNED"))
                .andExpect(jsonPath("$.canConfirm").value(false));
        assertThat(notificationsOf(staff)).contains(List.of("GOODS_RECEIPT_RECOUNT", "Phiếu nhận hàng #" + grn
                + " được trả về để kiểm lại. Lý do: Đếm lại má phanh", "GOODS_RECEIPT_NOTE", grn));
        assertThat(jdbc.queryForObject("SELECT reason FROM audit_log WHERE entity_type = 'GOODS_RECEIPT_NOTE' "
                + "AND entity_id = ? AND action = 'GOODS_RECEIPT_RECOUNT'", String.class, grn))
                .isEqualTo("Đếm lại má phanh");
        confirm(managerToken, grn)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG29"));
        recount(managerToken, grn, "Lần nữa")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG29"));

        // UC-43 Step 2_Correction or recount: the earlier result is there to correct.
        receipt(staffToken, grn)
                .andExpect(jsonPath("$.lines[*].deliveredQty", contains(9, 24)))
                .andExpect(jsonPath("$.lines[*].shortQty", contains(1, 0)))
                .andExpect(jsonPath("$.canRecord").value(true));
        record(staffToken, grn, findings(counted(padLine, 10, 10, 0, 0), counted(oilLine, 24, 24, 0, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_CONFIRMATION"));
        confirm(managerToken, grn)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openDiscrepancies").value(0));
        assertThat(stock(giaLam, pad, "AVAILABLE")).isEqualTo(10);
        assertThat(stock(giaLam, oil, "AVAILABLE")).isEqualTo(24);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM discrepancy_case", Long.class)).isZero();
    }

    @Test
    void whoRecordedTheResultCannotConfirmIt() throws Exception {
        long grn = receive("PX-001");
        // The Branch Manager takes the inspection themself (MSG40), which needs another manager of the store to
        // confirm it (review R-01).
        createUser("bm3", Role.BRANCH_MANAGER, giaLam);
        assign(grn, manager);
        record(managerToken, grn, findings(counted(lineOf(grn, pad), 10, 10, 0, 0)))
                .andExpect(status().isOk())
                // A part left out did not come.
                .andExpect(jsonPath("$.lines[*].deliveredQty", contains(10, 0)))
                .andExpect(jsonPath("$.canConfirm").value(false))
                .andExpect(jsonPath("$.canReturnForRecount").value(true));
        confirm(managerToken, grn)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG32"))
                .andExpect(jsonPath("$.message").value("Bạn đã ghi kết quả kiểm nhận của lượt này nên không thể tự xác "
                        + "nhận. Lượt này cần người khác xác nhận."));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM goods_receipt_note WHERE id = ?", String.class, grn))
                .isEqualTo("AWAITING_CONFIRMATION");
    }

    @Test
    void anInvalidResultIsRefusedWithItsLineAndNothingIsSaved() throws Exception {
        long grn = receive("PX-001");
        long other = receive("PX-002");
        assign(grn, staff);
        long padLine = lineOf(grn, pad);
        long oilLine = lineOf(grn, oil);

        record(staffToken, grn, findings(counted(padLine, 10, 3, 2, 2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG27"))
                .andExpect(jsonPath("$.message").value(MSG27));
        record(staffToken, grn, findings(counted(oilLine, 24, 24, 0, 0), counted(oilLine, 24, 24, 0, 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG136"))
                .andExpect(jsonPath("$.message").value(MSG136));
        record(staffToken, grn, findings(counted(padLine, 10, 10, 0, 0), counted(lineOf(other, oil), 1, 1, 0, 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG136"));
        Map<String, Object> expired = counted(padLine, 10, 10, 0, 0);
        expired.put("manufactureDate", "2026-05-01");
        expired.put("expiryDate", "2026-04-30");
        record(staffToken, grn, findings(expired))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG137"))
                .andExpect(jsonPath("$.message").value("Hạn dùng ở dòng 1 không được trước ngày sản xuất."));
        record(staffToken, grn, findings(counted(padLine, 0, 0, 0, 0), counted(oilLine, 0, 0, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG140"));
        Map<String, Object> later = new LinkedHashMap<>(findings(counted(padLine, 10, 10, 0, 0)));
        later.put("arrivedAt", "2026-10-13T09:00:00");
        record(staffToken, grn, later)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG138"));
        record(staffToken, grn, findings(counted(padLine, 10, -1, 0, 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"))
                .andExpect(jsonPath("$.fieldErrors['lines[0].deliveredQty']").value("Số lượng không được âm."));
        record(staffToken, grn, Map.of("lines", List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.lines").value("Vui lòng ghi kết quả cho ít nhất một phụ tùng."));

        assertThat(jdbc.queryForObject("SELECT status FROM goods_receipt_note WHERE id = ?", String.class, grn))
                .isEqualTo("AWAITING_INSPECTION");
        assertThat(jdbc.queryForObject("SELECT sum(delivered_qty) FROM goods_receipt_note_line "
                + "WHERE goods_receipt_note_id = ?", Long.class, grn)).isZero();

        // The arrival time the Staff member saw replaces the one the Branch Manager entered.
        Map<String, Object> earlier = new LinkedHashMap<>(findings(counted(padLine, 10, 10, 0, 0)));
        earlier.put("arrivedAt", "2026-10-13T07:15:00");
        record(staffToken, grn, earlier)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.arrivedAt").value("2026-10-13T07:15:00"));
    }

    @Test
    void onlyTheReceivingBranchManagerRecordsADeliveryOncePerDocumentWhileThePoIsOpen() throws Exception {
        createReceipt(otherManagerToken, order, deliveryNote("PX-001"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        createReceipt(ownerToken, order, deliveryNote("PX-001"))
                .andExpect(status().isForbidden());
        createReceipt(staffToken, order, deliveryNote("PX-001"))
                .andExpect(status().isForbidden());
        createReceipt(managerToken, 999_999, deliveryNote("PX-001"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG131"));
        Map<String, Object> tomorrow = deliveryNote("PX-001");
        tomorrow.put("deliveryDocumentDate", TODAY.plusDays(1).toString());
        createReceipt(managerToken, order, tomorrow)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG138"));
        Map<String, Object> noDocument = deliveryNote("  ");
        createReceipt(managerToken, order, noDocument)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"))
                .andExpect(jsonPath("$.fieldErrors.deliveryDocumentNo").value("Vui lòng nhập số chứng từ giao hàng."));
        // UC-106 step 3: the photo of the delivery document comes with it.
        Map<String, Object> noPhoto = deliveryNote("PX-001");
        noPhoto.remove("documents");
        createReceipt(managerToken, order, noPhoto)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"))
                .andExpect(jsonPath("$.fieldErrors.documents").value("Vui lòng đính kèm ảnh chứng từ giao hàng."));
        noPhoto.put("documents", List.of());
        createReceipt(managerToken, order, noPhoto)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.documents").value("Vui lòng đính kèm ảnh chứng từ giao hàng."));

        long first = receive("PX-001");
        // UC-106 Step 5_Delivery document already recorded, whatever the letter case.
        createReceipt(managerToken, order, deliveryNote(" px-001 "))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG28"))
                .andExpect(jsonPath("$.message").value("Chứng từ giao hàng này đã được ghi nhận cho yêu cầu này."));
        // A second truck of the same PO is a GRN of its own (BR-22).
        long second = receive("PX-002");
        assertThat(second).isNotEqualTo(first);

        long cancelled = created(order(honda, giaLam, TODAY, null, line(lamp, 2, 200_000)));
        cancel(ownerToken, cancelled, "NCC hết hàng").andExpect(status().isOk());
        createReceipt(managerToken, cancelled, deliveryNote("PX-009"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG29"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM goods_receipt_note", Long.class)).isEqualTo(2);
    }

    @Test
    void aLaterDeliveryOnlyStocksWhatThePoStillExpects() throws Exception {
        long first = receive("PX-001");
        assign(first, staff);
        // The supplier's document lists 6 pads: 4 are still to come in another delivery.
        record(staffToken, first, findings(counted(lineOf(first, pad), 6, 6, 0, 0),
                counted(lineOf(first, oil), 24, 24, 0, 0))).andExpect(status().isOk());
        long second = receive("PX-002");
        receipt(managerToken, second).andExpect(jsonPath("$.lines[*].expectedQty", contains(10, 24)));
        confirm(managerToken, first).andExpect(status().isOk());
        detail(managerToken, order)
                .andExpect(jsonPath("$.status").value("RECEIVING"))
                .andExpect(jsonPath("$.lines[*].outstandingQty", contains(4, 0)))
                .andExpect(jsonPath("$.canCreateReceipt").value(true));
        // A GRN created now only lists the pads.
        long third = receive("PX-003");
        receipt(managerToken, third).andExpect(jsonPath("$.lines[*].sku", contains("MA-PHANH-01")));

        // The second truck brings 5 pads and 2 oils: the PO expects 4 pads and no oil any more (BR-67).
        assign(second, staff2);
        receipt(managerToken, second).andExpect(jsonPath("$.lines[*].expectedQty", contains(4, 0)));
        record(staff2Token, second, findings(counted(lineOf(second, pad), 5, 5, 0, 0),
                counted(lineOf(second, oil), 2, 2, 0, 0)))
                .andExpect(jsonPath("$.lines[*].toStockQty", contains(4, 0)))
                .andExpect(jsonPath("$.lines[*].excessQty", contains(1, 2)));
        confirm(managerToken, second)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[*].excessDecision", contains("AWAITING_OWNER", "AWAITING_OWNER")));
        assertThat(stock(giaLam, pad, "AVAILABLE")).isEqualTo(10);
        assertThat(stock(giaLam, oil, "AVAILABLE")).isEqualTo(24);
        detail(managerToken, order)
                .andExpect(jsonPath("$.status").value("FULLY_RECEIVED"))
                .andExpect(jsonPath("$.lines[*].receivedQty", contains(10, 24)))
                .andExpect(jsonPath("$.lines[*].outstandingQty", contains(0, 0)));
        assertThat(notificationsOf(owner)).anySatisfy(n -> assertThat(n.get(0)).isEqualTo("EXCESS_AWAITING_DECISION"));
        // The third GRN, still waiting, can only bring excess now.
        receipt(managerToken, third).andExpect(jsonPath("$.lines[*].expectedQty", contains(0)));
        createReceipt(managerToken, order, deliveryNote("PX-004"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG29"));
    }

    @Test
    void staffSeeOnlyTheDeliveriesTheyInspectAndBranchManagersOnlyTheirStores() throws Exception {
        long grn = receive("PX-001");
        receipt(staffToken, grn)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        assign(grn, staff);
        receipt(staffToken, grn).andExpect(status().isOk());
        receipt(staff2Token, grn).andExpect(status().isForbidden());
        receipt(otherManagerToken, grn).andExpect(status().isForbidden());
        receipt(ownerToken, grn)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[*].unitPrice", contains(120_000, 85_000)))
                .andExpect(jsonPath("$.canRecord").value(false));
        receipt(managerToken, 999_999)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG132"));
        long transfer = transferIn(longBien, giaLam, LocalDateTime.now(clock));
        receipt(managerToken, transfer).andExpect(status().isNotFound());

        long elsewhere = created(order(yamaha, longBien, TODAY, null, line(filter, 3, 50_000)));
        clock.advance(Duration.ofMinutes(5));
        String response = createReceipt(otherManagerToken, elsewhere, deliveryNote("YM-1"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long theirs = ((Number) JsonPath.read(response, "$.id")).longValue();

        receipts(managerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", contains(ids(grn))))
                .andExpect(jsonPath("$.items[0].assigneeId").value(staff.getId()))
                .andExpect(jsonPath("$.items[0].assigneeName").value("Test staff"))
                .andExpect(jsonPath("$.items[0].lineCount").value(2))
                .andExpect(jsonPath("$.items[0].supplierName").value("Honda Việt Nam"))
                .andExpect(jsonPath("$.items[0].storeName").value("Gia Lâm"));
        receipts(managerToken, "storeId", String.valueOf(longBien))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        receipts(ownerToken)
                .andExpect(jsonPath("$.items[*].id", contains(ids(theirs, grn))));
        // Every GRN goes to its recorder for inspection, so none is unassigned (lecturer feedback 12/11).
        receipts(ownerToken, "unassigned", "true")
                .andExpect(jsonPath("$.totalItems").value(0));
        receipts(ownerToken, "purchaseOrderId", String.valueOf(order), "status", "AWAITING_INSPECTION")
                .andExpect(jsonPath("$.items[*].id", contains(ids(grn))));
        receipts(ownerToken, "excessAwaiting", "true")
                .andExpect(jsonPath("$.totalItems").value(0));
        receipts(staffToken).andExpect(status().isForbidden());
    }

    @Test
    void thePhotosOfTheDeliveryDocumentAreKeptWithTheReceipt() throws Exception {
        Map<String, Object> body = deliveryNote("PX-001");
        body.put("documents", List.of(Map.of("storageKey", "motoflow/attachments/px001", "fileName", "chung-tu.jpg",
                "contentType", "image/jpeg", "fileSize", 200_000)));
        long grn = ((Number) JsonPath.read(createReceipt(managerToken, order, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documents[*].fileName", contains("chung-tu.jpg")))
                .andExpect(jsonPath("$.documents[0].contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.documents[0].fileSize").value(200_000))
                .andExpect(jsonPath("$.documents[0].uploadedById").value(manager.getId()))
                .andExpect(jsonPath("$.documents[0].uploadedByName").value("Test bm"))
                .andExpect(jsonPath("$.documents[0].downloadUrl", startsWith("https://")))
                .andReturn().getResponse().getContentAsString(), "$.id")).longValue();

        Map<String, Object> again = deliveryNote("PX-002");
        again.put("documents", body.get("documents"));
        createReceipt(managerToken, order, again)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG143"))
                .andExpect(jsonPath("$.message").value("Tệp chung-tu.jpg đã được đính kèm trước đó."));
        Map<String, Object> outside = deliveryNote("PX-003");
        outside.put("documents", List.of(Map.of("storageKey", "motoflow/products/px003", "fileName", "a.jpg",
                "contentType", "image/jpeg", "fileSize", 1_000)));
        createReceipt(managerToken, order, outside)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG142"));
        Map<String, Object> video = deliveryNote("PX-004");
        video.put("documents", List.of(Map.of("storageKey", "motoflow/attachments/px004", "fileName", "a.mp4",
                "contentType", "video/mp4", "fileSize", 1_000)));
        createReceipt(managerToken, order, video)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG142"));
        // A refused file saves no GRN either.
        assertThat(jdbc.queryForList("SELECT id FROM goods_receipt_note", Long.class)).containsExactly(grn);

        // The Staff member's evidence joins the same receipt.
        assign(grn, staff);
        Map<String, Object> evidence = new LinkedHashMap<>(findings(counted(lineOf(grn, pad), 10, 10, 0, 0)));
        evidence.put("evidence", List.of(Map.of("storageKey", "motoflow/attachments/hang-hu", "fileName", "hu.png",
                "contentType", "IMAGE/PNG", "fileSize", 3_000)));
        record(staffToken, grn, evidence)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents[*].fileName", contains("chung-tu.jpg", "hu.png")))
                .andExpect(jsonPath("$.documents[1].contentType").value("image/png"))
                .andExpect(jsonPath("$.documents[1].uploadedById").value(staff.getId()));
    }

    @Test
    void excessAcceptedByOwnerJoinsTheLotOfItsLineAtItsPrice() throws Exception {
        // 10 pads already in stock from an earlier PO at 100 000.
        long earlier = created(order(honda, giaLam, TODAY, null, line(pad, 10, 100_000)));
        String response = createReceipt(managerToken, earlier, deliveryNote("CU-1"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long old = ((Number) JsonPath.read(response, "$.id")).longValue();
        assign(old, staff);
        record(staffToken, old, findings(counted(lineOf(old, pad), 10, 10, 0, 0))).andExpect(status().isOk());
        confirm(managerToken, old).andExpect(status().isOk());
        assertThat(averageCost(pad)).isEqualByComparingTo("100000");

        long grn = receive("PX-001");
        assign(grn, staff);
        long padLine = lineOf(grn, pad);
        long oilLine = lineOf(grn, oil);
        record(staffToken, grn, findings(counted(padLine, 12, 12, 0, 0), counted(oilLine, 25, 25, 0, 0)))
                .andExpect(status().isOk());
        confirm(managerToken, grn)
                .andExpect(jsonPath("$.lines[*].toStockQty", contains(10, 24)))
                .andExpect(jsonPath("$.lines[*].excessQty", contains(2, 1)))
                .andExpect(jsonPath("$.lines[*].excessDecision", contains("AWAITING_OWNER", "AWAITING_OWNER")))
                .andExpect(jsonPath("$.canDecideExcess").value(false));
        // BR-74 on the 10 PO pads: (10 x 100 000 + 10 x 120 000) / 20. Excess is not in stock yet.
        assertThat(averageCost(pad)).isEqualByComparingTo("110000");
        assertThat(stock(giaLam, pad, "AVAILABLE")).isEqualTo(20);
        assertThat(notificationsOf(owner)).contains(List.of("EXCESS_AWAITING_DECISION", "Phiếu nhận hàng #" + grn
                + " của yêu cầu nhập hàng #" + order + " có hàng giao thừa đang chờ quyết định nhận hoặc không nhận.",
                "GOODS_RECEIPT_NOTE", grn));
        receipts(ownerToken, "excessAwaiting", "true")
                .andExpect(jsonPath("$.items[*].id", contains(ids(grn))))
                .andExpect(jsonPath("$.items[0].excessAwaiting").value(true));
        receipt(managerToken, grn).andExpect(jsonPath("$.canDecideExcess").value(false));
        receipt(ownerToken, grn)
                .andExpect(jsonPath("$.canDecideExcess").value(true))
                .andExpect(jsonPath("$.lines[*].unitPrice", contains(120_000, 85_000)));

        decide(managerToken, grn, padLine, "ACCEPTED", oilLine, "ACCEPTED")
                .andExpect(status().isForbidden());
        // UC-45 Step 4_Incomplete or changed decision.
        decide(ownerToken, grn, padLine, "ACCEPTED")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG139"));
        decide(ownerToken, grn, padLine, "ACCEPTED", padLine, "REJECTED")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG136"));
        decide(ownerToken, grn, padLine, "ACCEPTED", oilLine, "ACCEPTED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[*].excessDecision", contains("ACCEPTED", "ACCEPTED")))
                .andExpect(jsonPath("$.excessDecidedById").value(owner.getId()))
                .andExpect(jsonPath("$.excessDecidedByName").value("Test owner"))
                .andExpect(jsonPath("$.canDecideExcess").value(false));
        decide(ownerToken, grn, padLine, "ACCEPTED", oilLine, "ACCEPTED")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG29"));

        // BR-73: the excess is in the same lot; BR-74: (20 x 110 000 + 2 x 120 000) / 22.
        assertThat(stock(giaLam, pad, "AVAILABLE")).isEqualTo(22);
        assertThat(stock(giaLam, oil, "AVAILABLE")).isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT b.quantity FROM lot_balance b JOIN lot l ON l.id = b.lot_id "
                + "WHERE l.goods_receipt_note_line_id = ? AND b.stock_status = 'AVAILABLE'", Integer.class, padLine))
                .isEqualTo(12);
        assertThat(averageCost(pad)).isEqualByComparingTo(new BigDecimal("110909.0909"));
        assertThat(movementsOf(pad)).endsWith(List.of("EXCESS_ACCEPTED", "AVAILABLE", 2));
        assertStockAgrees(giaLam, pad);
        assertStockAgrees(giaLam, oil);
        assertThat(notificationsOf(manager)).contains(List.of("EXCESS_DECIDED",
                "Owner đã quyết định phần hàng thừa của phiếu nhận hàng #" + grn + ".", "GOODS_RECEIPT_NOTE", grn));
        detail(ownerToken, order)
                .andExpect(jsonPath("$.lines[*].receivedQty", contains(10, 24)))
                .andExpect(jsonPath("$.lines[*].acceptedExcessQty", contains(2, 1)));
    }

    @Test
    void excessOfALineWithNothingExpectedGetsALotWhenOwnerAcceptsIt() throws Exception {
        long first = receive("PX-001");
        long second = receive("PX-002");
        assign(first, staff);
        assign(second, staff2);
        record(staffToken, first, findings(counted(lineOf(first, pad), 10, 10, 0, 0),
                counted(lineOf(first, oil), 24, 24, 0, 0))).andExpect(status().isOk());
        record(staff2Token, second, findings(counted(lineOf(second, pad), 3, 3, 0, 0))).andExpect(status().isOk());
        confirm(managerToken, first).andExpect(status().isOk());
        confirm(managerToken, second)
                .andExpect(jsonPath("$.lines[*].excessQty", contains(3, 0)))
                .andExpect(jsonPath("$.lines[*].lotId", contains(nullValue(), nullValue())));
        decide(ownerToken, second, lineOf(second, pad), "ACCEPTED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].lotId", notNullValue()));
        assertThat(stock(giaLam, pad, "AVAILABLE")).isEqualTo(13);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lot WHERE product_id = ?", Long.class, pad)).isEqualTo(2);
        assertStockAgrees(giaLam, pad);
    }
}
