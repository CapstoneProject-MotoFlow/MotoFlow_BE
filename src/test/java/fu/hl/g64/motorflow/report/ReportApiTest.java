package fu.hl.g64.motorflow.report;

import fu.hl.g64.motorflow.sales.SalesOrderTestData;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC-87, UC-102, UC-89 on real sales of today (13/10/2026): a paid order and a credit order, each pad 2 + oil 1 =
 * 285,000 đ (pads cost 60,000 đ, oil 70,000 đ). Then one paid pad is corrected away and one pad is written off.
 */
class ReportApiTest extends SalesOrderTestData {

    private long paidOrder;

    @BeforeEach
    void sales() throws Exception {
        jdbc.update("UPDATE customer SET credit_status = 'ELIGIBLE' WHERE id = ?", customer);
        paidOrder = confirmedOrder();
        send("/api/v1/sales-orders/" + paidOrder + "/payments", Map.of("amount", 285_000,
                "paymentMethod", "BANK_TRANSFER", "transactionReference", "FT" + System.nanoTime(),
                "verified", true, "expectedPayable", moneyNow(paidOrder, "payableNow")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true));
        long creditOrder = confirmedOrder();
        send("/api/v1/sales-orders/" + creditOrder + "/payments", Map.of("amount", 85_000,
                "paymentMethod", "CASH", "verified", true, "expectedPayable", moneyNow(creditOrder, "payableNow")))
                .andExpect(status().isOk());
        send("/api/v1/sales-orders/" + creditOrder + "/credit", Map.of("version", 1, "creditAmount", 200_000,
                "dueDate", TODAY.plusDays(30).toString(), "customerAgreed", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));

        long period = id("SELECT inventory_period_id FROM stock_movement LIMIT 1");
        long payment = id("SELECT id FROM payment WHERE sales_order_id = ?", paidOrder);
        // An applied correction: one paid pad was not delivered; it went back to stock and 100,000 đ off the payment.
        long correction = applied("DOCUMENT_CORRECTION", paidOrder);
        jdbc.update("INSERT INTO adjustment_amount_line (adjustment_request_id, payment_id, amount_change) "
                + "VALUES (?, ?, -100000)", correction, payment);
        movement(correction, period, "AVAILABLE", 1);
        // An applied write-off of one damaged pad.
        movement(applied("STOCK_LOSS", null), period, "DAMAGED", -1);
    }

    @Test
    void salesAndCollectionOfTheMonthWithCorrectionsApart() throws Exception {
        report(ownerToken, "/sales-collection?storeId=" + store).andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-10-01"))
                .andExpect(jsonPath("$.to").value("2026-10-13"))
                .andExpect(jsonPath("$.groupBy").value("DAY"))
                .andExpect(jsonPath("$.periods.length()").value(13))
                .andExpect(jsonPath("$.periods[12].figures.revenue").value(570_000))
                .andExpect(jsonPath("$.periods[0].figures.revenue").value(0))
                .andExpect(jsonPath("$.total.completedOrders").value(2))
                .andExpect(jsonPath("$.total.revenue").value(570_000))
                .andExpect(jsonPath("$.total.orderPayments").value(370_000))
                .andExpect(jsonPath("$.total.totalCollected").value(370_000))
                .andExpect(jsonPath("$.total.creditSales").value(200_000))
                .andExpect(jsonPath("$.total.revenueCorrections").value(-100_000))
                .andExpect(jsonPath("$.total.collectionCorrections").value(-100_000))
                .andExpect(jsonPath("$.total.receivableAtEnd").value(200_000))
                .andExpect(jsonPath("$.stores[*].storeName", contains("Gia Lâm")));

        report(ownerToken, "/sales-collection?from=2026-09-15&to=2026-10-13&groupBy=MONTH")
                .andExpect(jsonPath("$.periods[*].from", contains("2026-09-15", "2026-10-01")))
                .andExpect(jsonPath("$.periods[*].to", contains("2026-09-30", "2026-10-13")))
                .andExpect(jsonPath("$.periods[1].figures.revenue").value(570_000))
                .andExpect(jsonPath("$.stores[*].storeName", contains("Gia Lâm", "Long Biên")))
                .andExpect(jsonPath("$.stores[1].figures.revenue").value(0));

        // Debt left at the end of an earlier day: none yet.
        report(ownerToken, "/sales-collection?to=2026-10-12").andExpect(jsonPath("$.total.receivableAtEnd").value(0));
        // A Branch Manager sees their own store only.
        report(otherManagerToken, "/sales-collection").andExpect(jsonPath("$.total.revenue").value(0))
                .andExpect(jsonPath("$.stores[*].storeName", contains("Long Biên")));
        report(otherManagerToken, "/sales-collection?storeId=" + store).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        report(staffToken, "/sales-collection").andExpect(status().isForbidden());
        report(ownerToken, "/sales-collection?from=2026-10-14&to=2026-10-13").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG264"));
    }

    @Test
    void grossMarginByPartWithCorrectionsAndWriteOffsApart() throws Exception {
        report(ownerToken, "/gross-margin?groupBy=PRODUCT").andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].code", contains("MA-PHANH-01", "NHOT-01")))
                .andExpect(jsonPath("$.rows[0].figures.quantity").value(4))
                .andExpect(jsonPath("$.rows[0].figures.revenue").value(400_000))
                .andExpect(jsonPath("$.rows[0].figures.cost").value(240_000))
                .andExpect(jsonPath("$.rows[0].figures.grossMargin").value(160_000))
                .andExpect(jsonPath("$.rows[0].figures.marginRate").value(40.0))
                .andExpect(jsonPath("$.rows[0].figures.correctionRevenue").value(-100_000))
                .andExpect(jsonPath("$.rows[0].figures.correctionCost").value(-60_000))
                .andExpect(jsonPath("$.rows[0].figures.adjustedMargin").value(120_000))
                .andExpect(jsonPath("$.rows[0].figures.writeOffValue").value(60_000))
                .andExpect(jsonPath("$.rows[1].figures.cost").value(140_000))
                .andExpect(jsonPath("$.total.revenue").value(570_000))
                .andExpect(jsonPath("$.total.grossMargin").value(190_000));
        report(ownerToken, "/gross-margin").andExpect(jsonPath("$.groupBy").value("STORE"))
                .andExpect(jsonPath("$.rows[*].name", contains("Gia Lâm")));
        report(ownerToken, "/gross-margin?groupBy=CATEGORY&productId=" + oil)
                .andExpect(jsonPath("$.rows[*].name", contains("Phanh")))
                .andExpect(jsonPath("$.total.revenue").value(170_000));
        report(ownerToken, "/gross-margin?categoryId=999999").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG61"));
        report(managerToken, "/gross-margin").andExpect(status().isForbidden());
    }

    @Test
    void theAccountantGetsAWorkbookOfFiveSheets() throws Exception {
        byte[] file = report(ownerToken, "/accounting-export").andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("MotoFlow_KeToan_2026-10-01_2026-10-13.xlsx")))
                .andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            List<String> names = new ArrayList<>();
            book.forEach(sheet -> names.add(sheet.getSheetName()));
            assertThat(names).containsExactly("Doanh thu", "Bán hàng", "Mua hàng", "Thu tiền", "Chứng từ điều chỉnh");
            Sheet sold = book.getSheet("Bán hàng");
            assertThat(sold.getLastRowNum()).isEqualTo(2 + 4);
            assertThat(sold.getRow(3).getCell(5).getStringCellValue()).isEqualTo("MA-PHANH-01");
            assertThat(sold.getRow(3).getCell(10).getNumericCellValue()).isEqualTo(200_000);
            assertThat(sold.getRow(3).getCell(11).getNumericCellValue()).isEqualTo(120_000);
            assertThat(book.getSheet("Thu tiền").getLastRowNum()).isEqualTo(2 + 2);
            assertThat(book.getSheet("Chứng từ điều chỉnh").getLastRowNum()).isEqualTo(2 + 2);
            Sheet revenue = book.getSheet("Doanh thu");
            assertThat(revenue.getRow(3 + 12).getCell(2).getNumericCellValue()).isEqualTo(570_000);
        }
        report(managerToken, "/accounting-export").andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------------------------------------------

    @Test
    void aCorrectedDebtPaymentKeepsItsRecordedCollectionAndOnlyItsNewSplitCounts() throws Exception {
        // 150,000 đ paid on the 200,000 đ debt at 10:00, corrected to 100,000 đ at 13:00 (BR-71: the first
        // allocation is reversed and 100,000 allocated again).
        long debt = id("SELECT id FROM receivable WHERE store_id = ?", store);
        long payment = id("INSERT INTO payment (payment_type, amount, payment_method, status, recorded_by, recorded_at, "
                + "verified_by, verified_at) VALUES ('DEBT_PAYMENT', 150000, 'CASH', 'VERIFIED', ?, "
                + "'2026-10-13 10:00', ?, '2026-10-13 10:00') RETURNING id", manager.getId(), manager.getId());
        long first = id("INSERT INTO payment_allocation (payment_id, receivable_id, allocated_amount) "
                + "VALUES (?, ?, 150000) RETURNING id", payment, debt);
        long correction = id("INSERT INTO adjustment_request (adjustment_type, store_id, payment_id, reason, status, "
                + "requested_by, submitted_at, decided_by, decided_at, applied_at) VALUES ('DOCUMENT_CORRECTION', ?, ?, "
                + "'Thu nhầm', 'APPLIED', ?, '2026-10-13 12:00', ?, '2026-10-13 13:00', '2026-10-13 13:00') RETURNING id",
                store, payment, manager.getId(), owner.getId());
        jdbc.update("INSERT INTO adjustment_amount_line (adjustment_request_id, payment_id, amount_change) "
                + "VALUES (?, ?, -50000)", correction, payment);
        jdbc.update("UPDATE payment_allocation SET reversed_by_adjustment_id = ?, reversed_at = '2026-10-13 13:00' "
                + "WHERE id = ?", correction, first);
        jdbc.update("INSERT INTO payment_allocation (payment_id, receivable_id, allocated_amount) VALUES (?, ?, 100000)",
                payment, debt);

        report(ownerToken, "/sales-collection").andExpect(status().isOk())
                .andExpect(jsonPath("$.total.debtCollected").value(150_000))
                .andExpect(jsonPath("$.total.collectionCorrections").value(-150_000))
                .andExpect(jsonPath("$.total.receivableAtEnd").value(100_000));
    }

    private long confirmedOrder() throws Exception {
        long order = createOrder(pad, 2, oil, 1);
        assign(managerToken, "PICKING", order, staff).andExpect(status().isOk());
        pick(staffToken, order, 1, pad, 2, null, oil, 1, null).andExpect(status().isOk());
        confirm(managerToken, order, 1, pad, 2, oil, 1).andExpect(status().isOk());
        return order;
    }

    private long applied(String type, Long salesOrder) {
        return id("INSERT INTO adjustment_request (adjustment_type, store_id, sales_order_id, reason, status, "
                + "requested_by, submitted_at, decided_by, decided_at, applied_at) VALUES (?, ?, ?, 'Kiểm tra', "
                + "'APPLIED', ?, '2026-10-13 12:00', ?, '2026-10-13 13:00', '2026-10-13 13:00') RETURNING id",
                type, store, salesOrder, manager.getId(), owner.getId());
    }

    private void movement(long request, long period, String status, int quantity) {
        jdbc.update("INSERT INTO stock_movement (store_id, product_id, inventory_period_id, stock_status, "
                + "quantity_change, unit_cost, value, movement_type, adjustment_request_id, performed_by, occurred_at) "
                + "VALUES (?, ?, ?, ?, ?, 60000, ?, 'ADJUSTMENT', ?, ?, '2026-10-13 13:00')", store, pad, period,
                status, quantity, quantity * 60_000L, request, owner.getId());
    }

    private ResultActions send(String path, Map<String, Object> body) throws Exception {
        return mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, managerToken)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions report(String token, String path) throws Exception {
        return mvc.perform(get("/api/v1/reports" + path).header(HttpHeaders.AUTHORIZATION, token));
    }
}
