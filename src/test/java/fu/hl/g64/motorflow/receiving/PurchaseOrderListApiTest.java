package fu.hl.g64.motorflow.receiving;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-39 View PO List and UC-41 View PO Detail (Owner, Branch Manager), and the inspections a Staff member holds. */
class PurchaseOrderListApiTest extends PurchaseOrderTestData {

    private static final String INVALID_VALUE = "Giá trị không hợp lệ.";

    /** Honda to Gia Lâm, created on 01/09 and fully received. */
    private long fullyReceived;
    /** Yamaha to Long Biên, created on 01/10, expected on 10/10 and still not delivered. */
    private long lateOrder;
    /** Honda to Gia Lâm, created on 05/10, cancelled. */
    private long cancelledOrder;
    /** Honda to Gia Lâm, created on 12/10, receiving: one delivery confirmed, one waiting for its inspection. */
    private long receivingOrder;
    /** Yamaha to Gia Lâm, created today and expected today: its delivery waits for confirmation. */
    private long newOrder;

    private long confirmedDelivery;
    private long uninspectedDelivery;
    private long unconfirmedDelivery;

    @BeforeEach
    void createOrders() throws Exception {
        fullyReceived = created(order(honda, giaLam, LocalDate.of(2026, 9, 5), null, line(pad, 10, 120_000)));
        lateOrder = created(order(yamaha, longBien, LocalDate.of(2026, 10, 10), null, line(oil, 24, 85_000)));
        cancelledOrder = created(order(honda, giaLam, LocalDate.of(2026, 10, 8), null, line(pad, 5, 120_000),
                line(lamp, 2, 200_000)));
        cancel(ownerToken, cancelledOrder, "NCC hết hàng").andExpect(status().isOk());
        receivingOrder = created(order(honda, giaLam, null, "Giao làm hai đợt", line(pad, 10, 120_000),
                line(lamp, 4, 200_000), line(oil, 6, 85_000)));
        newOrder = created(order(yamaha, giaLam, TODAY, null, line(oil, 12, 85_000)));
        createdAt(fullyReceived, LocalDateTime.of(2026, 9, 1, 9, 0), "FULLY_RECEIVED");
        createdAt(lateOrder, LocalDateTime.of(2026, 10, 1, 9, 0), "AWAITING_DELIVERY");
        createdAt(cancelledOrder, LocalDateTime.of(2026, 10, 5, 9, 0), "CANCELLED");
        createdAt(receivingOrder, LocalDateTime.of(2026, 10, 12, 9, 0), "RECEIVING");
        jdbc.update("UPDATE purchase_order SET status = 'RECEIVING' WHERE id = ?", newOrder);

        // Two pads damaged, one lamp more than ordered and accepted, one oil of the wrong kind.
        confirmedDelivery = delivery(receivingOrder, "PX-001", LocalDateTime.of(2026, 10, 12, 14, 0), "CONFIRMED",
                staff, manager);
        result(confirmedDelivery, pad, 10, 8, 0, 2, 0, "NOT_APPLICABLE");
        result(confirmedDelivery, lamp, 4, 5, 0, 0, 1, "ACCEPTED");
        result(confirmedDelivery, oil, 6, 5, 1, 0, 0, "NOT_APPLICABLE");
        discrepancy(confirmedDelivery, pad, 0, 2, LocalDateTime.of(2026, 10, 12, 16, 0), null);
        discrepancy(confirmedDelivery, oil, 1, 0, LocalDateTime.of(2026, 10, 12, 16, 0),
                LocalDateTime.of(2026, 10, 13, 7, 0));
        uninspectedDelivery = delivery(receivingOrder, "PX-002", LocalDateTime.of(2026, 10, 13, 7, 30),
                "AWAITING_INSPECTION", null, null);
        result(uninspectedDelivery, pad, 2, 0, 0, 0, 0, "NOT_APPLICABLE");

        unconfirmedDelivery = delivery(newOrder, "PX-101", LocalDateTime.of(2026, 10, 13, 7, 0),
                "AWAITING_CONFIRMATION", staff, null);
        result(unconfirmedDelivery, oil, 12, 12, 0, 0, 0, "NOT_APPLICABLE");
    }

    @Test
    void theOpenTabIsTheDefaultNewestFirstAndFlagsLateDeliveries() throws Exception {
        list(ownerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items[*].id", contains(ids(newOrder, receivingOrder, lateOrder))))
                .andExpect(jsonPath("$.items[*].status", contains("RECEIVING", "RECEIVING", "AWAITING_DELIVERY")))
                .andExpect(jsonPath("$.items[*].expectedArrival", contains("2026-10-13", null, "2026-10-10")))
                .andExpect(jsonPath("$.items[*].overdue", contains(false, false, true)))
                .andExpect(jsonPath("$.items[*].supplierCode", contains("NCC-YM", "NCC-HD", "NCC-YM")))
                .andExpect(jsonPath("$.items[*].supplierName", contains("Yamaha Motor", "Honda Việt Nam", "Yamaha Motor")))
                .andExpect(jsonPath("$.items[*].storeName", contains("Gia Lâm", "Gia Lâm", "Long Biên")))
                .andExpect(jsonPath("$.items[*].createdAt",
                        contains("2026-10-13T08:00:00", "2026-10-12T09:00:00", "2026-10-01T09:00:00")))
                .andExpect(jsonPath("$.items[*].lineCount", contains(1, 3, 1)))
                .andExpect(jsonPath("$.items[*].orderedValue", contains(1_020_000, 2_510_000, 2_040_000)))
                // The closed case of the receiving order does not count.
                .andExpect(jsonPath("$.items[*].openDiscrepancies", contains(0, 1, 0)));

        list(ownerToken, "view", "ALL", "page", "1", "size", "2")
                .andExpect(jsonPath("$.totalItems").value(4))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.items[*].id", contains(ids(cancelledOrder, lateOrder))));
    }

    @Test
    void theOtherTabsShowTheLast30DaysUnlessAnotherRangeIsChosen() throws Exception {
        list(ownerToken, "view", "ALL")
                .andExpect(jsonPath("$.items[*].id", contains(ids(newOrder, receivingOrder, cancelledOrder, lateOrder))));
        list(ownerToken, "view", "ALL", "createdFrom", "2026-09-01")
                .andExpect(jsonPath("$.items[*].id",
                        contains(ids(newOrder, receivingOrder, cancelledOrder, lateOrder, fullyReceived))));
        list(ownerToken, "view", "FULLY_RECEIVED")
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalItems").value(0));
        list(ownerToken, "view", "FULLY_RECEIVED", "createdFrom", "2026-09-01", "createdTo", "2026-09-30")
                .andExpect(jsonPath("$.items[*].id", contains(ids(fullyReceived))))
                .andExpect(jsonPath("$.items[0].overdue").value(false));
        // Its expected day has passed, but a cancelled order is not late.
        list(ownerToken, "view", "CANCELLED")
                .andExpect(jsonPath("$.items[*].id", contains(ids(cancelledOrder))))
                .andExpect(jsonPath("$.items[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.items[0].overdue").value(false));
        // 30 days up to the chosen end: 05/09 to 04/10.
        list(ownerToken, "view", "ALL", "createdTo", "2026-10-04")
                .andExpect(jsonPath("$.items[*].id", contains(ids(lateOrder))));
        list(ownerToken, "view", "ALL", "createdFrom", "2026-10-05", "createdTo", "2026-10-12")
                .andExpect(jsonPath("$.items[*].id", contains(ids(receivingOrder, cancelledOrder))));
        // The open tab has no default range, but keeps a chosen one.
        list(ownerToken, "createdFrom", "2026-10-12")
                .andExpect(jsonPath("$.items[*].id", contains(ids(newOrder, receivingOrder))));

        list(ownerToken, "view", "DONE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"))
                .andExpect(jsonPath("$.fieldErrors.view").value(INVALID_VALUE));
        list(ownerToken, "createdFrom", "13/10/2026")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"))
                .andExpect(jsonPath("$.fieldErrors.createdFrom").value(INVALID_VALUE));
    }

    @Test
    void searchFindsAnOrderByItsNumberOrItsSupplier() throws Exception {
        list(ownerToken, "q", "#" + receivingOrder)
                .andExpect(jsonPath("$.items[*].id", contains(ids(receivingOrder))));
        list(ownerToken, "q", " " + receivingOrder + " ")
                .andExpect(jsonPath("$.items[*].id", contains(ids(receivingOrder))));
        list(ownerToken, "q", "yamaha")
                .andExpect(jsonPath("$.items[*].id", contains(ids(newOrder, lateOrder))));
        list(ownerToken, "q", "VIỆT NAM")
                .andExpect(jsonPath("$.items[*].id", contains(ids(receivingOrder))));
        list(ownerToken, "q", "ncc-hd", "view", "ALL")
                .andExpect(jsonPath("$.items[*].id", contains(ids(receivingOrder, cancelledOrder))));
        list(ownerToken, "q", "#999999")
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void ownerFiltersByWarehouseAndSupplier() throws Exception {
        list(ownerToken, "storeId", String.valueOf(longBien))
                .andExpect(jsonPath("$.items[*].id", contains(ids(lateOrder))));
        list(ownerToken, "supplierId", String.valueOf(yamaha))
                .andExpect(jsonPath("$.items[*].id", contains(ids(newOrder, lateOrder))));
        list(ownerToken, "storeId", String.valueOf(giaLam), "supplierId", String.valueOf(honda))
                .andExpect(jsonPath("$.items[*].id", contains(ids(receivingOrder))));
        list(ownerToken, "view", "ALL", "storeId", String.valueOf(giaLam))
                .andExpect(jsonPath("$.items[*].id", contains(ids(newOrder, receivingOrder, cancelledOrder))));
    }

    @Test
    void aBranchManagerFollowsTheOrdersOfTheirWarehouseWithoutPrices() throws Exception {
        list(managerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", contains(ids(newOrder, receivingOrder))))
                .andExpect(jsonPath("$.items[*].lineCount", contains(1, 3)))
                .andExpect(jsonPath("$.items[*].orderedValue", everyItem(nullValue())))
                .andExpect(jsonPath("$.items[*].openDiscrepancies", contains(0, 1)));
        list(managerToken, "storeId", String.valueOf(giaLam))
                .andExpect(jsonPath("$.items[*].id", contains(ids(newOrder, receivingOrder))));
        list(managerToken, "storeId", String.valueOf(longBien))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        list(otherManagerToken)
                .andExpect(jsonPath("$.items[*].id", contains(ids(lateOrder))));

        detail(managerToken, lateOrder)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        detail(otherManagerToken, lateOrder)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdue").value(true))
                .andExpect(jsonPath("$.orderedValue").doesNotExist());
        detail(managerToken, receivingOrder)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderedValue").doesNotExist())
                .andExpect(jsonPath("$.lines[*].purchasePrice", everyItem(nullValue())))
                .andExpect(jsonPath("$.lines[*].lineValue", everyItem(nullValue())))
                .andExpect(jsonPath("$.lines[*].receivedQty", contains(8, 4, 5)))
                .andExpect(jsonPath("$.receipts", hasSize(2)))
                .andExpect(jsonPath("$.discrepancies", hasSize(2)))
                .andExpect(jsonPath("$.openDiscrepancies").value(1))
                .andExpect(jsonPath("$.canUpdate").value(false))
                .andExpect(jsonPath("$.canCancel").value(false));
    }

    @Test
    void theDetailShowsTheDeliveriesWhatTheyBroughtInAndTheirDiscrepancies() throws Exception {
        detail(ownerToken, receivingOrder)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVING"))
                .andExpect(jsonPath("$.expectedArrival").doesNotExist())
                .andExpect(jsonPath("$.overdue").value(false))
                .andExpect(jsonPath("$.note").value("Giao làm hai đợt"))
                .andExpect(jsonPath("$.orderedValue").value(2_510_000))
                .andExpect(jsonPath("$.openDiscrepancies").value(1))
                .andExpect(jsonPath("$.canUpdate").value(false))
                .andExpect(jsonPath("$.canCancel").value(false))
                // Excess beyond the order is not counted as received; the delivery still waiting is not counted.
                .andExpect(jsonPath("$.lines[*].sku", contains("MA-PHANH-01", "DEN-01", "NHOT-01")))
                .andExpect(jsonPath("$.lines[*].orderedQty", contains(10, 4, 6)))
                .andExpect(jsonPath("$.lines[*].receivedQty", contains(8, 4, 5)))
                .andExpect(jsonPath("$.lines[*].outstandingQty", contains(2, 0, 1)))
                .andExpect(jsonPath("$.receipts[*].id", contains(ids(confirmedDelivery, uninspectedDelivery))))
                .andExpect(jsonPath("$.receipts[*].deliveryDocumentNo", contains("PX-001", "PX-002")))
                .andExpect(jsonPath("$.receipts[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.receipts[0].deliveryDocumentDate").value("2026-10-12"))
                .andExpect(jsonPath("$.receipts[0].arrivedAt").value("2026-10-12T14:00:00"))
                .andExpect(jsonPath("$.receipts[0].recordedById").value(staff.getId()))
                .andExpect(jsonPath("$.receipts[0].recordedByName").value("Test staff"))
                .andExpect(jsonPath("$.receipts[0].submittedAt").value("2026-10-12T15:00:00"))
                .andExpect(jsonPath("$.receipts[0].confirmedById").value(manager.getId()))
                .andExpect(jsonPath("$.receipts[0].confirmedByName").value("Test bm"))
                .andExpect(jsonPath("$.receipts[0].confirmedAt").value("2026-10-12T16:00:00"))
                .andExpect(jsonPath("$.receipts[0].lines[*].sku", contains("MA-PHANH-01", "DEN-01", "NHOT-01")))
                .andExpect(jsonPath("$.receipts[0].lines[*].partName", contains("Má phanh trước", "Đèn pha", "Nhớt 10W40")))
                .andExpect(jsonPath("$.receipts[0].lines[*].documentQty", contains(10, 4, 6)))
                .andExpect(jsonPath("$.receipts[0].lines[*].deliveredQty", contains(10, 5, 6)))
                .andExpect(jsonPath("$.receipts[0].lines[*].passedQty", contains(8, 5, 5)))
                .andExpect(jsonPath("$.receipts[0].lines[*].wrongItemQty", contains(0, 0, 1)))
                .andExpect(jsonPath("$.receipts[0].lines[*].damagedQty", contains(2, 0, 0)))
                .andExpect(jsonPath("$.receipts[0].lines[*].excessQty", contains(0, 1, 0)))
                .andExpect(jsonPath("$.receipts[0].lines[*].excessDecision",
                        contains("NOT_APPLICABLE", "ACCEPTED", "NOT_APPLICABLE")))
                // Nothing is shown of a delivery before its inspection result is recorded.
                .andExpect(jsonPath("$.receipts[1].status").value("AWAITING_INSPECTION"))
                .andExpect(jsonPath("$.receipts[1].recordedById").doesNotExist())
                .andExpect(jsonPath("$.receipts[1].lines", hasSize(0)))
                .andExpect(jsonPath("$.discrepancies[*].goodsReceiptNoteId",
                        contains(ids(confirmedDelivery, confirmedDelivery))))
                .andExpect(jsonPath("$.discrepancies[*].sku", contains("MA-PHANH-01", "NHOT-01")))
                .andExpect(jsonPath("$.discrepancies[*].partName", contains("Má phanh trước", "Nhớt 10W40")))
                .andExpect(jsonPath("$.discrepancies[*].damagedQty", contains(2, 0)))
                .andExpect(jsonPath("$.discrepancies[*].wrongItemQty", contains(0, 1)))
                .andExpect(jsonPath("$.discrepancies[*].returnRequiredQty", contains(2, 1)))
                .andExpect(jsonPath("$.discrepancies[*].replacementRequiredQty", contains(2, 1)))
                .andExpect(jsonPath("$.discrepancies[*].status", contains("OPEN", "CLOSED")))
                .andExpect(jsonPath("$.discrepancies[0].openedAt").value("2026-10-12T16:00:00"))
                .andExpect(jsonPath("$.discrepancies[0].closedAt").doesNotExist())
                .andExpect(jsonPath("$.discrepancies[1].closedAt").value("2026-10-13T07:00:00"))
                .andExpect(jsonPath("$.history[*].action", contains("CREATED")));

        // A recorded result waiting for confirmation is shown, but nothing has come into stock yet.
        detail(ownerToken, newOrder)
                .andExpect(jsonPath("$.receipts[0].status").value("AWAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.receipts[0].confirmedById").doesNotExist())
                .andExpect(jsonPath("$.receipts[0].lines[*].passedQty", contains(12)))
                .andExpect(jsonPath("$.lines[*].receivedQty", contains(0)))
                .andExpect(jsonPath("$.lines[*].outstandingQty", contains(12)));
    }

    @Test
    void staffSeeTheSupplierDeliveriesTheyStillHaveToInspect() throws Exception {
        inspection(unconfirmedDelivery, staff, "RESULT_SUBMITTED", LocalDateTime.of(2026, 10, 13, 7, 5));
        inspection(uninspectedDelivery, staff, "ASSIGNED", LocalDateTime.of(2026, 10, 13, 7, 35));
        // Not shown: a finished inspection, a receipt from another warehouse, and someone else's inspections.
        inspection(confirmedDelivery, staff, "DONE", LocalDateTime.of(2026, 10, 12, 14, 10));
        inspection(transferIn(longBien, giaLam, LocalDateTime.of(2026, 10, 13, 7, 0)), staff, "ASSIGNED",
                LocalDateTime.of(2026, 10, 13, 7, 10));
        long another = delivery(newOrder, "PX-102", LocalDateTime.of(2026, 10, 13, 7, 45), "AWAITING_INSPECTION",
                null, null);
        inspection(another, staff2, "ASSIGNED", LocalDateTime.of(2026, 10, 13, 7, 50));
        long selfAssigned = delivery(receivingOrder, "PX-003", LocalDateTime.of(2026, 10, 13, 7, 50),
                "AWAITING_INSPECTION", null, null);
        inspection(selfAssigned, manager, "ASSIGNED", LocalDateTime.of(2026, 10, 13, 7, 55));

        assignedToMe(staffToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[*].goodsReceiptNoteId", contains(ids(unconfirmedDelivery, uninspectedDelivery))))
                .andExpect(jsonPath("$.items[*].purchaseOrderId", contains(ids(newOrder, receivingOrder))))
                .andExpect(jsonPath("$.items[*].supplierName", contains("Yamaha Motor", "Honda Việt Nam")))
                .andExpect(jsonPath("$.items[*].deliveryDocumentNo", contains("PX-101", "PX-002")))
                .andExpect(jsonPath("$.items[*].deliveryDocumentDate", contains("2026-10-13", "2026-10-13")))
                .andExpect(jsonPath("$.items[*].arrivedAt", contains("2026-10-13T07:00:00", "2026-10-13T07:30:00")))
                .andExpect(jsonPath("$.items[*].status", contains("AWAITING_CONFIRMATION", "AWAITING_INSPECTION")))
                .andExpect(jsonPath("$.items[*].lineCount", contains(1, 1)))
                .andExpect(jsonPath("$.items[*].taskStatus", contains("RESULT_SUBMITTED", "ASSIGNED")))
                .andExpect(jsonPath("$.items[*].assignedAt", contains("2026-10-13T07:05:00", "2026-10-13T07:35:00")));
        assignedToMe(staff2Token)
                .andExpect(jsonPath("$.items[*].goodsReceiptNoteId", contains(ids(another))))
                .andExpect(jsonPath("$.items[0].lineCount").value(0));
        // A Branch Manager who took an inspection themself sees it too.
        assignedToMe(managerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].goodsReceiptNoteId", contains(ids(selfAssigned))));
        assignedToMe(otherManagerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalItems").value(0));

        assignedToMe(ownerToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        assignedToMe(adminToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
    }

    /** Puts the PO on another creation day and status, as if it had been made then. */
    private void createdAt(long order, LocalDateTime at, String status) {
        jdbc.update("UPDATE purchase_order SET created_at = ?, updated_at = ?, status = ? WHERE id = ?", at, at, status,
                order);
    }

    /** A supplier discrepancy case on one part of a delivery: what is wrong or damaged goes back and is replaced. */
    private void discrepancy(long receipt, long product, int wrongItem, int damaged, LocalDateTime openedAt,
                             LocalDateTime closedAt) {
        boolean closed = closedAt != null;
        jdbc.update("INSERT INTO discrepancy_case (source_type, goods_receipt_note_id, product_id, wrong_item_qty, "
                        + "damaged_qty, return_required_qty, replacement_required_qty, status, opened_at, closure_type, "
                        + "closed_by, closed_at) VALUES ('SUPPLIER', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", receipt, product,
                wrongItem, damaged, wrongItem + damaged, wrongItem + damaged, closed ? "CLOSED" : "OPEN", openedAt,
                closed ? "ALL_SETTLED" : null, closed ? owner.getId() : null, closedAt);
    }

    private static Integer[] ids(long... ids) {
        return Arrays.stream(ids).mapToObj(id -> (int) id).toArray(Integer[]::new);
    }
}
