package fu.hl.g64.motorflow.receiving;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-40 Create PO and UC-42 Update/Cancel PO (Owner), with BR-07, BR-19, BR-20 and BR-21. */
class PurchaseOrderApiTest extends PurchaseOrderTestData {

    private static final String MSG02 = "Vui lòng nhập đầy đủ và đúng định dạng các thông tin bắt buộc.";
    private static final String MSG20 = "Số lượng và giá nhập phải lớn hơn 0, và mỗi phụ tùng chỉ có một dòng (dòng %d).";
    private static final String MSG25 = "Yêu cầu nhập hàng đã có lượt giao được ghi nhận nên không thể sửa hoặc hủy.";
    private static final String MSG130 = "Yêu cầu nhập hàng đã bị hủy nên không thể sửa hoặc hủy lại.";

    private static final LocalDate OCT_20 = LocalDate.of(2026, 10, 20);
    private static final LocalDate OCT_22 = LocalDate.of(2026, 10, 22);

    @Autowired
    private DataSource dataSource;

    @Test
    void ownerCreatesAnOrderAndTheManagerOfTheReceivingWarehouseIsTold() throws Exception {
        MvcResult result = create(ownerToken, order(honda, giaLam, OCT_20, "  Giao buổi sáng  ",
                line(pad, 10, 120_000), line(oil, 24, 85_000)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AWAITING_DELIVERY"))
                .andExpect(jsonPath("$.supplierId").value(honda))
                .andExpect(jsonPath("$.supplierCode").value("NCC-HD"))
                .andExpect(jsonPath("$.supplierName").value("Honda Việt Nam"))
                .andExpect(jsonPath("$.storeId").value(giaLam))
                .andExpect(jsonPath("$.storeName").value("Gia Lâm"))
                .andExpect(jsonPath("$.expectedArrival").value("2026-10-20"))
                .andExpect(jsonPath("$.overdue").value(false))
                .andExpect(jsonPath("$.note").value("Giao buổi sáng"))
                .andExpect(jsonPath("$.replenishmentRequestId").doesNotExist())
                .andExpect(jsonPath("$.createdById").value(owner.getId()))
                .andExpect(jsonPath("$.createdByName").value("Test owner"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-13T08:00:00"))
                .andExpect(jsonPath("$.updatedAt").value("2026-10-13T08:00:00"))
                .andExpect(jsonPath("$.cancelledAt").doesNotExist())
                .andExpect(jsonPath("$.orderedValue").value(3_240_000))
                .andExpect(jsonPath("$.openDiscrepancies").value(0))
                .andExpect(jsonPath("$.canUpdate").value(true))
                .andExpect(jsonPath("$.canCancel").value(true))
                .andExpect(jsonPath("$.lines[*].sku", contains("MA-PHANH-01", "NHOT-01")))
                .andExpect(jsonPath("$.lines[*].partName", contains("Má phanh trước", "Nhớt 10W40")))
                .andExpect(jsonPath("$.lines[*].baseUnit", contains("cái", "cái")))
                .andExpect(jsonPath("$.lines[*].productStatus", contains("ACTIVE", "ACTIVE")))
                .andExpect(jsonPath("$.lines[*].orderedQty", contains(10, 24)))
                .andExpect(jsonPath("$.lines[*].purchasePrice", contains(120_000, 85_000)))
                .andExpect(jsonPath("$.lines[*].lineValue", contains(1_200_000, 2_040_000)))
                .andExpect(jsonPath("$.lines[*].receivedQty", contains(0, 0)))
                .andExpect(jsonPath("$.lines[*].outstandingQty", contains(10, 24)))
                .andExpect(jsonPath("$.receipts", hasSize(0)))
                .andExpect(jsonPath("$.discrepancies", hasSize(0)))
                .andExpect(jsonPath("$.history[*].action", contains("CREATED")))
                .andExpect(jsonPath("$.history[0].actorName").value("Test owner"))
                .andExpect(jsonPath("$.history[0].at").value("2026-10-13T08:00:00"))
                .andExpect(jsonPath("$.history[0].lineChanges", hasSize(0)))
                .andReturn();
        long order = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/api/v1/purchase-orders/" + order);

        // Only the Branch Manager of the receiving warehouse is told. Stock does not change before a delivery.
        assertThat(notificationsOf(manager)).containsExactly(List.of("PURCHASE_ORDER_CREATED",
                "Có yêu cầu nhập hàng mới #" + order + " từ nhà cung cấp Honda Việt Nam.", "PURCHASE_ORDER", order));
        assertThat(notificationsOf(otherManager)).isEmpty();
        assertThat(notificationsOf(staff)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_balance", Long.class)).isZero();
    }

    @Test
    void anOrderNeedsASupplierAWarehouseAndAtLeastOneCompleteLine() throws Exception {
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("note", "x".repeat(501));
        empty.put("lines", List.of());
        create(ownerToken, empty)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"))
                .andExpect(jsonPath("$.message").value(MSG02))
                .andExpect(jsonPath("$.fieldErrors.supplierId").value("Vui lòng chọn nhà cung cấp."))
                .andExpect(jsonPath("$.fieldErrors.storeId").value("Vui lòng chọn kho nhận."))
                .andExpect(jsonPath("$.fieldErrors.note").value("Ghi chú tối đa 500 ký tự."))
                .andExpect(jsonPath("$.fieldErrors.lines").value("Vui lòng thêm ít nhất một phụ tùng."));

        Map<String, Object> noPart = line(pad, 1, 1);
        noPart.put("productId", null);
        create(ownerToken, order(honda, giaLam, null, null, line(pad, null, null), noPart,
                line(oil, 1_000_001, 10_000_000_001L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"))
                .andExpect(jsonPath("$.fieldErrors['lines[0].quantity']").value("Vui lòng nhập số lượng."))
                .andExpect(jsonPath("$.fieldErrors['lines[0].purchasePrice']").value("Vui lòng nhập giá nhập."))
                .andExpect(jsonPath("$.fieldErrors['lines[1].productId']").value("Vui lòng chọn phụ tùng."))
                .andExpect(jsonPath("$.fieldErrors['lines[2].quantity']").value("Số lượng tối đa 1.000.000."))
                .andExpect(jsonPath("$.fieldErrors['lines[2].purchasePrice']").value("Giá nhập tối đa 10.000.000.000 đ."));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_order", Long.class)).isZero();
    }

    @Test
    void aQuantityOrPriceNotAboveZeroOrAPartTwiceNamesTheLine() throws Exception {
        create(ownerToken, order(honda, giaLam, null, null, line(pad, 10, 120_000), line(oil, 0, 85_000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG20"))
                .andExpect(jsonPath("$.message").value(MSG20.formatted(2)));
        create(ownerToken, order(honda, giaLam, null, null, line(pad, 10, -1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(MSG20.formatted(1)));
        create(ownerToken, order(honda, giaLam, null, null, line(pad, 10, 120_000), line(oil, 1, 85_000),
                line(pad, 2, 120_000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(MSG20.formatted(3)));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_order", Long.class)).isZero();
    }

    @Test
    void theSupplierMustStillTradeTheWarehouseBeOpenAndEveryPartBeOnSale() throws Exception {
        create(ownerToken, order(999_999, giaLam, null, null, line(pad, 1, 1_000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG73"))
                .andExpect(jsonPath("$.message").value("Không tìm thấy nhà cung cấp."));
        create(ownerToken, order(inactiveSupplier, giaLam, null, null, line(pad, 1, 1_000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG129"))
                .andExpect(jsonPath("$.message")
                        .value("Nhà cung cấp Phụ tùng Cũ đã ngừng giao dịch, không thể lập yêu cầu nhập hàng."));

        jdbc.update("UPDATE store SET status = 'INACTIVE' WHERE id = ?", longBien);
        for (long store : new long[] {planned, longBien, 999_999}) {
            create(ownerToken, order(honda, store, null, null, line(pad, 1, 1_000)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MSG46"))
                    .andExpect(jsonPath("$.message").value("Cửa hàng không tồn tại hoặc đã ngừng hoạt động."));
        }

        create(ownerToken, order(honda, giaLam, null, null, line(pad, 1, 1_000), line(999_999, 1, 1_000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG57"))
                .andExpect(jsonPath("$.message").value("Không tìm thấy phụ tùng."));
        create(ownerToken, order(honda, giaLam, null, null, line(pad, 1, 1_000), line(discontinued, 1, 1_000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG21"))
                .andExpect(jsonPath("$.message")
                        .value("Phụ tùng CU-01 đã ngừng kinh doanh, không thể đưa vào yêu cầu nhập hàng."));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_order", Long.class)).isZero();
    }

    @Test
    void onlyOwnerCreatesChangesAndCancelsOrders() throws Exception {
        Map<String, Object> body = order(honda, giaLam, null, null, line(pad, 1, 1_000));
        for (String token : List.of(managerToken, staffToken, adminToken)) {
            create(token, body).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        }
        long order = created(body);
        update(managerToken, order, change(null, null, "Đổi số lượng", line(pad, 2, 1_000)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        cancel(managerToken, order, "Không cần nữa")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        // Staff and Admin do not follow purchase orders at all.
        for (String token : List.of(staffToken, adminToken)) {
            detail(token, order).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
            list(token).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        }

        detail(ownerToken, order)
                .andExpect(jsonPath("$.lines[*].orderedQty", contains(1)))
                .andExpect(jsonPath("$.status").value("AWAITING_DELIVERY"));
    }

    @Test
    void ownerChangesTheLinesAndDeliveryDetailsAndTheHistoryKeepsWhatChangedAndWhy() throws Exception {
        long order = created(order(honda, giaLam, OCT_20, "Giao buổi sáng", line(pad, 10, 120_000),
                line(oil, 24, 85_000), line(lamp, 5, 200_000)));
        clock.advance(Duration.ofMinutes(10));

        // A new price for the pads, more oil, no lamps, and air filters added.
        update(ownerToken, order, change(OCT_22, "Giao buổi chiều", "  NCC báo tăng giá má phanh  ",
                line(pad, 10, 125_000), line(oil, 30, 85_000), line(filter, 4, 60_000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_DELIVERY"))
                .andExpect(jsonPath("$.expectedArrival").value("2026-10-22"))
                .andExpect(jsonPath("$.note").value("Giao buổi chiều"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-13T08:00:00"))
                .andExpect(jsonPath("$.updatedAt").value("2026-10-13T08:10:00"))
                .andExpect(jsonPath("$.lines[*].sku", contains("MA-PHANH-01", "NHOT-01", "LOC-GIO-01")))
                .andExpect(jsonPath("$.lines[*].orderedQty", contains(10, 30, 4)))
                .andExpect(jsonPath("$.lines[*].purchasePrice", contains(125_000, 85_000, 60_000)))
                .andExpect(jsonPath("$.orderedValue").value(4_040_000))
                .andExpect(jsonPath("$.canUpdate").value(true))
                .andExpect(jsonPath("$.history[*].action", contains("UPDATED", "CREATED")))
                .andExpect(jsonPath("$.history[0].actorName").value("Test owner"))
                .andExpect(jsonPath("$.history[0].at").value("2026-10-13T08:10:00"))
                .andExpect(jsonPath("$.history[0].reason").value("NCC báo tăng giá má phanh"))
                .andExpect(jsonPath("$.history[0].delivery.oldExpectedArrival").value("2026-10-20"))
                .andExpect(jsonPath("$.history[0].delivery.newExpectedArrival").value("2026-10-22"))
                .andExpect(jsonPath("$.history[0].delivery.oldNote").value("Giao buổi sáng"))
                .andExpect(jsonPath("$.history[0].delivery.newNote").value("Giao buổi chiều"))
                // Changed and removed lines in their old order, then the added ones.
                .andExpect(jsonPath("$.history[0].lineChanges[*].sku",
                        contains("MA-PHANH-01", "NHOT-01", "DEN-01", "LOC-GIO-01")))
                .andExpect(jsonPath("$.history[0].lineChanges[*].partName",
                        contains("Má phanh trước", "Nhớt 10W40", "Đèn pha", "Lọc gió")))
                .andExpect(jsonPath("$.history[0].lineChanges[*].oldQty", contains(10, 24, 5, null)))
                .andExpect(jsonPath("$.history[0].lineChanges[*].newQty", contains(10, 30, null, 4)))
                .andExpect(jsonPath("$.history[0].lineChanges[*].oldPrice", contains(120_000, 85_000, 200_000, null)))
                .andExpect(jsonPath("$.history[0].lineChanges[*].newPrice", contains(125_000, 85_000, null, 60_000)));

        assertThat(jdbc.queryForList("SELECT product_id FROM purchase_order_line WHERE purchase_order_id = ? ORDER BY id",
                Long.class, order)).containsExactly(pad, oil, filter);
        assertThat(notificationsOf(manager)).last().isEqualTo(List.of("PURCHASE_ORDER_UPDATED",
                "Yêu cầu nhập hàng #" + order + " đã được cập nhật. Lý do: NCC báo tăng giá má phanh",
                "PURCHASE_ORDER", order));

        // The Branch Manager sees neither prices nor a change of price alone (BR-72).
        detail(managerToken, order)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderedValue").doesNotExist())
                .andExpect(jsonPath("$.lines[*].purchasePrice", contains(null, null, null)))
                .andExpect(jsonPath("$.lines[*].lineValue", contains(null, null, null)))
                .andExpect(jsonPath("$.canUpdate").value(false))
                .andExpect(jsonPath("$.canCancel").value(false))
                .andExpect(jsonPath("$.history[0].reason").value("NCC báo tăng giá má phanh"))
                .andExpect(jsonPath("$.history[0].delivery.newExpectedArrival").value("2026-10-22"))
                .andExpect(jsonPath("$.history[0].lineChanges[*].sku", contains("NHOT-01", "DEN-01", "LOC-GIO-01")))
                .andExpect(jsonPath("$.history[0].lineChanges[*].oldPrice", contains(null, null, null)))
                .andExpect(jsonPath("$.history[0].lineChanges[*].newPrice", contains(null, null, null)));
    }

    @Test
    void aChangeNeedsAReasonAndValidLines() throws Exception {
        long order = created(order(honda, giaLam, OCT_20, null, line(pad, 10, 120_000)));

        update(ownerToken, order, change(OCT_20, null, "   ", line(pad, 12, 120_000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"))
                .andExpect(jsonPath("$.fieldErrors.reason").value("Vui lòng nhập lý do thay đổi."));
        update(ownerToken, order, change(OCT_20, null, "Thêm nhớt", line(pad, 12, 120_000), line(oil, 0, 85_000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG20"))
                .andExpect(jsonPath("$.message").value(MSG20.formatted(2)));
        update(ownerToken, order, change(OCT_20, null, "Bỏ hết"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.lines").value("Vui lòng thêm ít nhất một phụ tùng."));

        detail(ownerToken, order)
                .andExpect(jsonPath("$.lines[*].orderedQty", contains(10)))
                .andExpect(jsonPath("$.history", hasSize(1)));
    }

    @Test
    void sendingTheOrderBackUnchangedRecordsNothing() throws Exception {
        long order = created(order(honda, giaLam, OCT_20, "Giao buổi sáng", line(pad, 10, 120_000),
                line(oil, 24, 85_000)));
        clock.advance(Duration.ofMinutes(5));

        // Same lines in another order, the same note with spaces around it.
        update(ownerToken, order, change(OCT_20, "  Giao buổi sáng ", "Kiểm tra lại", line(oil, 24, 85_000),
                line(pad, 10, 120_000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedAt").value("2026-10-13T08:00:00"))
                .andExpect(jsonPath("$.lines[*].sku", contains("MA-PHANH-01", "NHOT-01")))
                .andExpect(jsonPath("$.history[*].action", contains("CREATED")));

        assertThat(notificationsOf(manager)).hasSize(1);
    }

    @Test
    void aPartDiscontinuedAfterOrderingMayStayOnTheOrderButNotComeBack() throws Exception {
        long order = created(order(honda, giaLam, null, null, line(pad, 10, 120_000), line(lamp, 5, 200_000)));
        discontinue(lamp);

        update(ownerToken, order, change(null, null, "Thêm đèn", line(pad, 10, 120_000), line(lamp, 8, 200_000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[*].orderedQty", contains(10, 8)))
                .andExpect(jsonPath("$.lines[*].productStatus", contains("ACTIVE", "DISCONTINUED")));
        update(ownerToken, order, change(null, null, "Thêm gương", line(pad, 10, 120_000), line(lamp, 8, 200_000),
                line(discontinued, 1, 50_000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG21"))
                .andExpect(jsonPath("$.message")
                        .value("Phụ tùng CU-01 đã ngừng kinh doanh, không thể đưa vào yêu cầu nhập hàng."));

        // Once taken off, the discontinued lamp cannot be put back.
        update(ownerToken, order, change(null, null, "Bỏ đèn", line(pad, 10, 120_000))).andExpect(status().isOk());
        update(ownerToken, order, change(null, null, "Lấy lại đèn", line(pad, 10, 120_000), line(lamp, 8, 200_000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Phụ tùng DEN-01 đã ngừng kinh doanh, không thể đưa vào yêu cầu nhập hàng."));
    }

    @Test
    void ownerCancelsAnOrderWithAReasonAndItCannotChangeAfterwards() throws Exception {
        long order = created(order(honda, giaLam, OCT_20, null, line(pad, 10, 120_000)));
        cancel(ownerToken, order, "  ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"))
                .andExpect(jsonPath("$.fieldErrors.reason").value("Vui lòng nhập lý do hủy."));
        clock.advance(Duration.ofMinutes(5));

        cancel(ownerToken, order, "  NCC hết hàng  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledById").value(owner.getId()))
                .andExpect(jsonPath("$.cancelledByName").value("Test owner"))
                .andExpect(jsonPath("$.cancelledAt").value("2026-10-13T08:05:00"))
                .andExpect(jsonPath("$.cancellationReason").value("NCC hết hàng"))
                .andExpect(jsonPath("$.updatedAt").value("2026-10-13T08:05:00"))
                .andExpect(jsonPath("$.canUpdate").value(false))
                .andExpect(jsonPath("$.canCancel").value(false))
                .andExpect(jsonPath("$.history[*].action", contains("CANCELLED", "CREATED")))
                .andExpect(jsonPath("$.history[0].reason").value("NCC hết hàng"))
                .andExpect(jsonPath("$.history[0].at").value("2026-10-13T08:05:00"));
        assertThat(notificationsOf(manager)).last().isEqualTo(List.of("PURCHASE_ORDER_CANCELLED",
                "Yêu cầu nhập hàng #" + order + " đã bị hủy. Lý do: NCC hết hàng", "PURCHASE_ORDER", order));

        cancel(ownerToken, order, "Hủy lần nữa")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG130"))
                .andExpect(jsonPath("$.message").value(MSG130));
        update(ownerToken, order, change(OCT_20, null, "Đặt lại", line(pad, 10, 120_000)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG130"));
    }

    @Test
    void anOrderWithADeliveryOrThatIsReceivingCanNoLongerChange() throws Exception {
        long delivered = created(order(honda, giaLam, OCT_20, null, line(pad, 10, 120_000)));
        delivery(delivered, "PX-001", LocalDateTime.now(clock), "AWAITING_INSPECTION", null, null);
        long receiving = created(order(yamaha, giaLam, OCT_20, null, line(oil, 24, 85_000)));
        jdbc.update("UPDATE purchase_order SET status = 'RECEIVING' WHERE id = ?", receiving);

        for (long order : new long[] {delivered, receiving}) {
            update(ownerToken, order, change(OCT_22, null, "Giao muộn", line(pad, 10, 120_000)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MSG25"))
                    .andExpect(jsonPath("$.message").value(MSG25));
            cancel(ownerToken, order, "NCC hết hàng")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MSG25"));
            detail(ownerToken, order)
                    .andExpect(jsonPath("$.canUpdate").value(false))
                    .andExpect(jsonPath("$.canCancel").value(false))
                    .andExpect(jsonPath("$.expectedArrival").value("2026-10-20"))
                    .andExpect(jsonPath("$.history", hasSize(1)));
        }
    }

    @Test
    void aDeliveryRecordedWhileOwnerCancelsComesFirstAndTheCancelIsRefused() throws Exception {
        long order = created(order(honda, giaLam, OCT_20, null, line(pad, 10, 120_000)));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection receiving = dataSource.getConnection()) {
            // The warehouse records the delivery and holds the PO, as recording a GRN does, but has not committed yet.
            receiving.setAutoCommit(false);
            try (PreparedStatement lock = receiving.prepareStatement(
                    "SELECT id FROM purchase_order WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, order);
                lock.executeQuery().close();
            }
            try (PreparedStatement insert = receiving.prepareStatement("INSERT INTO goods_receipt_note (receipt_type, "
                    + "purchase_order_id, delivery_document_no, arrived_at) VALUES ('SUPPLIER_DELIVERY', ?, 'PX-001', ?)")) {
                insert.setLong(1, order);
                insert.setObject(2, LocalDateTime.now(clock));
                insert.executeUpdate();
            }
            Future<MvcResult> ours = executor.submit(() -> cancel(ownerToken, order, "NCC hết hàng").andReturn());
            // The cancel waits for the PO row; let the delivery commit first.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!ours.isDone() && System.nanoTime() < deadline && jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'", Long.class) == 0) {
                Thread.sleep(20);
            }
            receiving.commit();

            MvcResult result = ours.get(30, TimeUnit.SECONDS);
            String body = result.getResponse().getContentAsString();
            assertThat(result.getResponse().getStatus()).as(body).isEqualTo(409);
            assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo("MSG25");
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM purchase_order WHERE id = ?", String.class, order))
                .isEqualTo("AWAITING_DELIVERY");
    }

    @Test
    void anUnknownOrderIsNotFound() throws Exception {
        detail(ownerToken, 999_999)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG131"))
                .andExpect(jsonPath("$.message").value("Không tìm thấy yêu cầu nhập hàng."));
        update(ownerToken, 999_999, change(null, null, "Sửa", line(pad, 1, 1_000)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG131"));
        cancel(ownerToken, 999_999, "Hủy")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG131"));
    }
}
