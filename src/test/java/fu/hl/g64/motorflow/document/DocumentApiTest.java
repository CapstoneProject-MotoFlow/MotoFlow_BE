package fu.hl.g64.motorflow.document;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.sales.SalesOrderTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC-78 Search Documents, UC-79 View Document Detail, UC-80 Print or Export Document.
 * Gia Lâm ({@code store}) holds a paid sale, a confirmed supplier receipt, a transfer from Long Biên and an adjustment
 * request; Long Biên ({@code otherStore}) holds a debt payment and sent the transfer. Documents not posted yet (an open
 * order, a receipt waiting for inspection, a draft adjustment) are never found.
 */
class DocumentApiTest extends SalesOrderTestData {

    private long sale;
    private long salePayment;
    private long openOrder;
    private long receipt;
    private long transfer;
    private long transferReceipt;
    private long debt;
    private long debtPayment;
    private long adjustment;
    private long draft;
    private String thirdManagerToken;

    @BeforeEach
    void createDocuments() throws Exception {
        // 13/10 08:00: 2 pads and 1 oil sold and paid in cash; another order still open.
        sale = createOrder(pad, 2, oil, 1);
        assign(managerToken, "PICKING", sale, staff).andExpect(status().isOk());
        pick(staffToken, sale, 1, pad, 2, null, oil, 1, null).andExpect(status().isOk());
        confirm(managerToken, sale, 1, pad, 2, oil, 1).andExpect(status().isOk());
        mvc.perform(post("/api/v1/sales-orders/" + sale + "/payments").header(HttpHeaders.AUTHORIZATION, managerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("amount", 285_000,
                                "paymentMethod", "CASH", "verified", true, "expectedPayable", 285_000))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true));
        salePayment = id("SELECT id FROM payment WHERE sales_order_id = ?", sale);
        openOrder = createOrder(oil, 1);

        // 14/10: 6 pads from Honda, 1 of them excess Owner refused.
        long purchaseOrder = id("SELECT min(id) FROM purchase_order");
        receipt = id("INSERT INTO goods_receipt_note (receipt_type, purchase_order_id, delivery_document_no, arrived_at, "
                + "status, recorded_by, submitted_at, confirmed_by, confirmed_at) VALUES ('SUPPLIER_DELIVERY', ?, "
                + "'PX-900', '2026-10-14 09:00', 'CONFIRMED', ?, '2026-10-14 10:00', ?, '2026-10-14 11:00') "
                + "RETURNING id", purchaseOrder, staff.getId(), manager.getId());
        jdbc.update("INSERT INTO goods_receipt_note_line (goods_receipt_note_id, product_id, document_qty, "
                + "delivered_qty, passed_qty, excess_qty, excess_decision, unit_price, manufacturer_lot_no) "
                + "VALUES (?, ?, 5, 6, 6, 1, 'REJECTED', 60000, 'HD-01')", receipt, pad);
        jdbc.update("INSERT INTO audit_log (entity_type, entity_id, action, actor_id, occurred_at) "
                + "VALUES ('GOODS_RECEIPT_NOTE', ?, 'GRN_CONFIRMED', ?, '2026-10-14 11:00')", receipt, manager.getId());

        // 15/10: Long Biên sends Gia Lâm 2 oils; the goods have arrived but are not inspected yet.
        transfer = id("INSERT INTO transfer_order (source_store_id, dest_store_id, status, created_by, dispatched_by, "
                + "dispatched_at) VALUES (?, ?, 'IN_TRANSIT', ?, ?, '2026-10-15 09:00') RETURNING id", otherStore,
                store, otherManager.getId(), otherManager.getId());
        jdbc.update("INSERT INTO transfer_order_line (transfer_order_id, product_id, planned_qty, prepared_qty, "
                + "dispatched_qty) VALUES (?, ?, 2, 2, 2)", transfer, oil);
        long period = id("SELECT max(id) FROM inventory_period");
        jdbc.update("INSERT INTO stock_movement (store_id, product_id, inventory_period_id, stock_status, "
                + "quantity_change, unit_cost, value, movement_type, transfer_order_id, performed_by, occurred_at) "
                + "VALUES (?, ?, ?, 'AVAILABLE', -2, 70000, -140000, 'TRANSFER_DISPATCH', ?, ?, '2026-10-15 09:00'), "
                + "(?, ?, ?, 'IN_TRANSIT', 2, 70000, 140000, 'TRANSFER_DISPATCH', ?, ?, '2026-10-15 09:00')",
                otherStore, oil, period, transfer, otherManager.getId(),
                store, oil, period, transfer, otherManager.getId());
        transferReceipt = id("INSERT INTO goods_receipt_note (receipt_type, transfer_order_id, arrived_at) "
                + "VALUES ('TRANSFER_IN', ?, '2026-10-16 08:00') RETURNING id", transfer);

        // 16/10: Anh Tuấn pays 200,000 of an opening debt at Long Biên.
        debt = id("INSERT INTO receivable (origin_type, customer_id, store_id, original_amount, recognized_by, "
                + "recognized_at) VALUES ('OPENING_BALANCE', ?, ?, 500000, ?, '2026-10-01 08:00') RETURNING id",
                customer, otherStore, owner.getId());
        debtPayment = id("INSERT INTO payment (payment_type, amount, tendered_amount, payment_method, status, "
                + "recorded_by, recorded_at, verified_by, verified_at) VALUES ('DEBT_PAYMENT', 200000, 250000, 'CASH', "
                + "'VERIFIED', ?, '2026-10-16 10:00', ?, '2026-10-16 10:00') RETURNING id", otherManager.getId(),
                otherManager.getId());
        jdbc.update("INSERT INTO payment_allocation (payment_id, receivable_id, allocated_amount) VALUES (?, ?, 200000)",
                debtPayment, debt);

        // 17/10: a correction of the receipt waits for Owner; a draft is not a document yet.
        adjustment = id("INSERT INTO adjustment_request (adjustment_type, store_id, goods_receipt_note_id, reason, "
                + "status, requested_by, submitted_at) VALUES ('DOCUMENT_CORRECTION', ?, ?, 'Đếm thừa 1 cái', "
                + "'PENDING', ?, '2026-10-17 09:00') RETURNING id", store, receipt, manager.getId());
        jdbc.update("INSERT INTO adjustment_stock_line (adjustment_request_id, store_id, product_id, stock_status, "
                + "quantity_change) VALUES (?, ?, ?, 'AVAILABLE', -1)", adjustment, store, pad);
        jdbc.update("INSERT INTO audit_log (entity_type, entity_id, action, actor_id, occurred_at) "
                + "VALUES ('ADJUSTMENT_REQUEST', ?, 'SUBMITTED', ?, '2026-10-17 09:00')", adjustment, manager.getId());
        draft = id("INSERT INTO adjustment_request (adjustment_type, store_id, reason, requested_by) "
                + "VALUES ('STOCK_LOSS', ?, 'Vỡ', ?) RETURNING id", store, manager.getId());

        long thirdStore = createStore("Đông Anh");
        createUser("bm3", Role.BRANCH_MANAGER, thirdStore);
        thirdManagerToken = signIn("bm3");
    }

    // ---- UC-78 -------------------------------------------------------------------------------------------------------

    @Test
    void aBranchManagerFindsThePostedDocumentsOfTheirStoreNewestFirst() throws Exception {
        search(managerToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(5))
                .andExpect(jsonPath("$.items[*].type").value(contains("ADJUSTMENT", "TRANSFER_ORDER", "GOODS_RECEIPT",
                        "PAYMENT", "SALES_ORDER")))
                .andExpect(jsonPath("$.items[*].id").value(contains((int) adjustment, (int) transfer, (int) receipt,
                        (int) salePayment, (int) sale)))
                .andExpect(jsonPath("$.items[0].number").value("#" + adjustment))
                .andExpect(jsonPath("$.items[0].subType").value("DOCUMENT_CORRECTION"))
                .andExpect(jsonPath("$.items[0].printForms").value(contains("ADJUSTMENT_NOTE")))
                .andExpect(jsonPath("$.items[1].storeName").value("Long Biên"))
                .andExpect(jsonPath("$.items[1].otherStoreName").value("Gia Lâm"))
                .andExpect(jsonPath("$.items[1].documentDate").value("2026-10-15T09:00:00"))
                .andExpect(jsonPath("$.items[2].partnerName").value("Honda"))
                // BR-72: purchase values are Owner's.
                .andExpect(jsonPath("$.items[2].value").value(nullValue()))
                .andExpect(jsonPath("$.items[3].subType").value("ORDER_PAYMENT"))
                .andExpect(jsonPath("$.items[3].value").value(285_000))
                .andExpect(jsonPath("$.items[4].status").value("COMPLETED"))
                .andExpect(jsonPath("$.items[4].subType").value("PAID"))
                .andExpect(jsonPath("$.items[4].partnerName").value("Anh Tuấn"))
                .andExpect(jsonPath("$.items[4].value").value(285_000))
                .andExpect(jsonPath("$.items[4].printForms").value(contains("SALES_INVOICE", "STOCK_ISSUE_NOTE")));

        // The source store sees the transfer too, and its own debt payment.
        search(otherManagerToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id").value(contains((int) debtPayment, (int) transfer)))
                .andExpect(jsonPath("$.items[0].subType").value("DEBT_PAYMENT"))
                .andExpect(jsonPath("$.items[0].storeName").value("Long Biên"))
                .andExpect(jsonPath("$.items[0].partnerName").value("Anh Tuấn"));
        search(thirdManagerToken).andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void ownerSearchesEveryStoreAndSeesPurchaseValues() throws Exception {
        search(ownerToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(6))
                // 5 pads went into stock at 60,000; the refused excess did not.
                .andExpect(jsonPath("$.items[?(@.type == 'GOODS_RECEIPT')].value").value(contains(300_000)));
        search(ownerToken, "storeId", String.valueOf(otherStore)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id").value(contains((int) debtPayment, (int) transfer)));
    }

    @Test
    void filtersByTypeNumberAndDate() throws Exception {
        search(managerToken, "type", "PAYMENT").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id").value(contains((int) salePayment)));
        search(managerToken, "type", "SALES_ORDER", "number", "#" + sale).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id").value(contains((int) sale)));
        search(managerToken, "type", "SALES_ORDER", "number", String.valueOf(openOrder)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
        search(managerToken, "number", " # " + receipt).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].type").value(hasItem("GOODS_RECEIPT")));
        search(managerToken, "from", "2026-10-14", "to", "2026-10-15").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id").value(contains((int) transfer, (int) receipt)));
        search(managerToken, "to", "2026-10-13").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id").value(contains((int) salePayment, (int) sale)));
        search(managerToken, "page", "1", "size", "2").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(5)).andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.items[*].id").value(contains((int) receipt, (int) salePayment)));
    }

    @Test
    void refusesWrongFiltersAndOtherRoles() throws Exception {
        search(managerToken, "from", "2026-10-15", "to", "2026-10-14")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG251"));
        search(managerToken, "number", "PX-900")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG02"));
        search(managerToken, "storeId", String.valueOf(otherStore))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        search(managerToken, "storeId", String.valueOf(store)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(5));
        search(staffToken).andExpect(status().isForbidden());
        detail(staffToken, "SALES_ORDER", sale).andExpect(status().isForbidden());
        print(staffToken, "SALES_ORDER", sale, null).andExpect(status().isForbidden());
    }

    // ---- UC-79 -------------------------------------------------------------------------------------------------------

    @Test
    void aSaleOpensWithItsPaymentsAndHistory() throws Exception {
        detail(managerToken, "SALES_ORDER", sale).andExpect(status().isOk())
                .andExpect(jsonPath("$.document.number").value("#" + sale))
                .andExpect(jsonPath("$.content.status").value("COMPLETED"))
                .andExpect(jsonPath("$.content.total").value(285_000))
                .andExpect(jsonPath("$.sources").isEmpty())
                .andExpect(jsonPath("$.related[0].recordType").value("PAYMENT"))
                .andExpect(jsonPath("$.related[0].id").value(salePayment))
                .andExpect(jsonPath("$.related[0].documentType").value("PAYMENT"))
                .andExpect(jsonPath("$.related[0].note").value("ORDER_PAYMENT"))
                .andExpect(jsonPath("$.adjustments").isEmpty())
                .andExpect(jsonPath("$.history[0].action").value("SALES_ORDER_CREATED"))
                .andExpect(jsonPath("$.history[0].actorName").value("Test bm"))
                .andExpect(jsonPath("$.history[*].action").value(hasItem("SALES_ORDER_COMPLETED")));

        detail(otherManagerToken, "SALES_ORDER", sale)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        // An order still being sold is not a posted document.
        detail(managerToken, "SALES_ORDER", openOrder)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG250"));
        detail(managerToken, "PAYMENT", 999_999)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG250"));
    }

    @Test
    void aReceiptOpensWithItsPurchaseOrderAndAdjustmentsAndHidesPricesFromTheBranchManager() throws Exception {
        detail(managerToken, "GOODS_RECEIPT", receipt).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.deliveryDocumentNo").value("PX-900"))
                .andExpect(jsonPath("$.content.lines[0].unitPrice").value(nullValue()))
                .andExpect(jsonPath("$.sources[0].recordType").value("PURCHASE_ORDER"))
                .andExpect(jsonPath("$.sources[0].documentType").value(nullValue()))
                .andExpect(jsonPath("$.adjustments[0].id").value(adjustment))
                .andExpect(jsonPath("$.adjustments[0].status").value("PENDING"))
                .andExpect(jsonPath("$.adjustments[0].reason").value("Đếm thừa 1 cái"))
                .andExpect(jsonPath("$.history[0].action").value("GRN_CONFIRMED"));
        detail(ownerToken, "GOODS_RECEIPT", receipt).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.lines[0].unitPrice").value(60_000))
                .andExpect(jsonPath("$.document.value").value(300_000));
        detail(managerToken, "GOODS_RECEIPT", transferReceipt)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG250"));
    }

    @Test
    void aTransferOpensForBothStoresWithItsReceipts() throws Exception {
        detail(otherManagerToken, "TRANSFER_ORDER", transfer).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.content.lines[0].dispatchedQty").value(2))
                .andExpect(jsonPath("$.related[0].recordType").value("GOODS_RECEIPT"))
                .andExpect(jsonPath("$.related[0].id").value(transferReceipt))
                .andExpect(jsonPath("$.related[0].documentType").value(nullValue()))
                .andExpect(jsonPath("$.related[0].note").value("TRANSFER_IN"));
        detail(managerToken, "TRANSFER_ORDER", transfer).andExpect(status().isOk());
        detail(thirdManagerToken, "TRANSFER_ORDER", transfer)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
    }

    @Test
    void aPaymentOpensWithWhatItPaid() throws Exception {
        detail(otherManagerToken, "PAYMENT", debtPayment).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.paymentType").value("DEBT_PAYMENT"))
                .andExpect(jsonPath("$.content.changeGiven").value(50_000))
                .andExpect(jsonPath("$.content.customerName").value("Anh Tuấn"))
                .andExpect(jsonPath("$.content.allocations[0].receivableId").value(debt))
                .andExpect(jsonPath("$.content.allocations[0].amount").value(200_000))
                .andExpect(jsonPath("$.sources[0].recordType").value("RECEIVABLE"))
                .andExpect(jsonPath("$.sources[0].status").value("OPEN"))
                .andExpect(jsonPath("$.history[*].action").value(contains("RECORDED", "VERIFIED")))
                .andExpect(jsonPath("$.history[0].actorName").value("Test bm2"));
        detail(managerToken, "PAYMENT", debtPayment).andExpect(status().isForbidden());
        detail(managerToken, "PAYMENT", salePayment).andExpect(status().isOk())
                .andExpect(jsonPath("$.sources[0].recordType").value("SALES_ORDER"))
                .andExpect(jsonPath("$.sources[0].documentType").value("SALES_ORDER"));
    }

    @Test
    void anAdjustmentOpensWithTheDocumentItCorrects() throws Exception {
        detail(managerToken, "ADJUSTMENT", adjustment).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.adjustmentType").value("DOCUMENT_CORRECTION"))
                .andExpect(jsonPath("$.content.requestedBy").value("Test bm"))
                .andExpect(jsonPath("$.content.stockLines[0].sku").value("MA-PHANH-01"))
                .andExpect(jsonPath("$.content.stockLines[0].quantityChange").value(-1))
                .andExpect(jsonPath("$.sources[0].recordType").value("GOODS_RECEIPT"))
                .andExpect(jsonPath("$.sources[0].documentType").value("GOODS_RECEIPT"))
                .andExpect(jsonPath("$.history[0].action").value("SUBMITTED"));
        detail(managerToken, "ADJUSTMENT", draft)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG250"));
    }

    // ---- UC-80 -------------------------------------------------------------------------------------------------------

    @Test
    void aCompletedSalePrintsAsAnInvoiceAndAStockIssueNote() throws Exception {
        print(managerToken, "SALES_ORDER", sale, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.form").value("SALES_INVOICE"))
                .andExpect(jsonPath("$.number").value("#" + sale))
                .andExpect(jsonPath("$.printedBy").value("Test bm"))
                .andExpect(jsonPath("$.store.name").value("Gia Lâm"))
                .andExpect(jsonPath("$.counterpart.name").value("Anh Tuấn"))
                .andExpect(jsonPath("$.counterpart.phone").value("0912345678"))
                .andExpect(jsonPath("$.lines[0].sku").value("MA-PHANH-01"))
                .andExpect(jsonPath("$.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(100_000))
                .andExpect(jsonPath("$.lines[0].amount").value(200_000))
                .andExpect(jsonPath("$.lines[1].amount").value(85_000))
                .andExpect(jsonPath("$.totals.total").value(285_000))
                .andExpect(jsonPath("$.totals.discountAmount").value(0))
                .andExpect(jsonPath("$.totals.paid").value(285_000))
                .andExpect(jsonPath("$.totals.credit").value(nullValue()))
                .andExpect(jsonPath("$.note").value("PAID"));
        // BR-75: the issue note lists the lots taken, earliest expiry first; no prices.
        print(managerToken, "SALES_ORDER", sale, "STOCK_ISSUE_NOTE").andExpect(status().isOk())
                .andExpect(jsonPath("$.form").value("STOCK_ISSUE_NOTE"))
                .andExpect(jsonPath("$.lines[0].lotId").value(padSoon))
                .andExpect(jsonPath("$.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(nullValue()))
                .andExpect(jsonPath("$.lines[1].sku").value("NHOT-01"))
                .andExpect(jsonPath("$.totals").value(nullValue()));
        print(managerToken, "SALES_ORDER", sale, "GOODS_RECEIPT_NOTE")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG252"));
        print(managerToken, "SALES_ORDER", openOrder, null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG250"));
    }

    @Test
    void aCancelledSaleHasNothingToPrint() throws Exception {
        mvc.perform(post("/api/v1/sales-orders/" + openOrder + "/cancel").header(HttpHeaders.AUTHORIZATION, managerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("reason", "Khách đổi ý"))))
                .andExpect(status().isOk());
        search(managerToken, "number", "#" + openOrder, "type", "SALES_ORDER").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.items[0].printForms").isEmpty());
        print(managerToken, "SALES_ORDER", openOrder, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG252"));
    }

    @Test
    void otherDocumentsPrintTheirOwnForms() throws Exception {
        print(otherManagerToken, "TRANSFER_ORDER", transfer, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.form").value("STOCK_ISSUE_NOTE"))
                .andExpect(jsonPath("$.store.name").value("Long Biên"))
                .andExpect(jsonPath("$.counterpart.name").value("Gia Lâm"))
                .andExpect(jsonPath("$.lines[0].sku").value("NHOT-01"))
                .andExpect(jsonPath("$.lines[0].quantity").value(2));

        print(managerToken, "GOODS_RECEIPT", receipt, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.form").value("GOODS_RECEIPT_NOTE"))
                .andExpect(jsonPath("$.counterpart.name").value("Honda"))
                .andExpect(jsonPath("$.note").value("PX-900"))
                .andExpect(jsonPath("$.lines[0].quantity").value(5))
                .andExpect(jsonPath("$.lines[0].manufacturerLotNo").value("HD-01"))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(nullValue()))
                .andExpect(jsonPath("$.totals").value(nullValue()));
        print(ownerToken, "GOODS_RECEIPT", receipt, "GOODS_RECEIPT_NOTE").andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].unitPrice").value(60_000))
                .andExpect(jsonPath("$.lines[0].amount").value(300_000))
                .andExpect(jsonPath("$.totals.total").value(300_000));

        print(otherManagerToken, "PAYMENT", debtPayment, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.form").value("PAYMENT_RECEIPT"))
                .andExpect(jsonPath("$.counterpart.name").value("Anh Tuấn"))
                .andExpect(jsonPath("$.counterpart.address").value("Gia Lâm"))
                .andExpect(jsonPath("$.payment.amount").value(200_000))
                .andExpect(jsonPath("$.payment.changeGiven").value(50_000))
                .andExpect(jsonPath("$.payment.allocations[0].receivableId").value(debt))
                .andExpect(jsonPath("$.totals.total").value(200_000));

        print(managerToken, "ADJUSTMENT", adjustment, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.form").value("ADJUSTMENT_NOTE"))
                .andExpect(jsonPath("$.lines[0].quantity").value(-1))
                .andExpect(jsonPath("$.note").value("Đếm thừa 1 cái"));
        print(thirdManagerToken, "ADJUSTMENT", adjustment, null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private ResultActions search(String token, String... parameters) throws Exception {
        var request = get("/api/v1/documents").header(HttpHeaders.AUTHORIZATION, token);
        for (int i = 0; i < parameters.length; i += 2) {
            request.param(parameters[i], parameters[i + 1]);
        }
        return mvc.perform(request);
    }

    private ResultActions detail(String token, String type, long id) throws Exception {
        return mvc.perform(get("/api/v1/documents/" + type + "/" + id).header(HttpHeaders.AUTHORIZATION, token));
    }

    private ResultActions print(String token, String type, long id, String form) throws Exception {
        var request = get("/api/v1/documents/" + type + "/" + id + "/print").header(HttpHeaders.AUTHORIZATION, token);
        if (form != null) {
            request.param("form", form);
        }
        return mvc.perform(request);
    }
}
