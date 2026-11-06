package fu.hl.g64.motorflow.sales;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SalesOrderApiTest extends SalesOrderTestData {

    @Test
    void branchManagerCreatesAnOrderWithPricesStockAndLotsToPickFirst() throws Exception {
        create(managerToken, order(null, customer, "RETAIL", null, lines(pad, 4, oil, 2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AWAITING_PICKING"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.storeId").value(store))
                .andExpect(jsonPath("$.customerName").value("Anh Tuấn"))
                .andExpect(jsonPath("$.customerPhone").value("0912345678"))
                .andExpect(jsonPath("$.quantityBasis").value("REQUESTED"))
                .andExpect(jsonPath("$.referenceTotal").value(570_000))
                .andExpect(jsonPath("$.total").value(570_000))
                .andExpect(jsonPath("$.discountThreshold").value(10.0))
                .andExpect(jsonPath("$.lines[0].sku").value("MA-PHANH-01"))
                .andExpect(jsonPath("$.lines[0].referencePrice").value(100_000))
                .andExpect(jsonPath("$.lines[0].actualUnitPrice").value(100_000))
                .andExpect(jsonPath("$.lines[0].storageArea").value("Kệ A"))
                .andExpect(jsonPath("$.lines[0].binLocation").value("A-01"))
                .andExpect(jsonPath("$.lines[0].barcodes[0]").value("8930001"))
                // 2 of the 10 units expired on 01/10 and cannot be sold (BR-76).
                .andExpect(jsonPath("$.lines[0].sellableQty").value(8))
                .andExpect(jsonPath("$.lines[0].stockShortQty").value(0))
                // Earliest expiry first, then the lot without expiry (BR-75).
                .andExpect(jsonPath("$.lines[0].pickFirst", hasSize(2)))
                .andExpect(jsonPath("$.lines[0].pickFirst[0].lotId").value(padSoon))
                .andExpect(jsonPath("$.lines[0].pickFirst[0].takeQty").value(3))
                .andExpect(jsonPath("$.lines[0].pickFirst[1].lotId").value(padNoExpiry))
                .andExpect(jsonPath("$.lines[0].pickFirst[1].takeQty").value(1))
                .andExpect(jsonPath("$.lines[1].referencePrice").value(85_000))
                .andExpect(jsonPath("$.actions.canUpdate").value(true))
                .andExpect(jsonPath("$.actions.canAssignPicker").value(true))
                .andExpect(jsonPath("$.actions.canConfirm").value(false))
                .andExpect(jsonPath("$.history[0].action").value("CREATED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_type = 'SALES_ORDER' "
                + "AND action = 'SALES_ORDER_CREATED'", Integer.class)).isEqualTo(1);
        // Nothing is reserved (BR-33).
        assertThat(jdbc.queryForObject("SELECT available_qty FROM stock_balance WHERE store_id = ? AND product_id = ?",
                Integer.class, store, pad)).isEqualTo(10);
        assertThat(notifications(manager, "SALES_ORDER_CREATED")).isZero();

        // A wholesale order below the threshold: 9.5% off, 95,000 and 76,925 (76,925.0 rounded half up).
        create(managerToken, order(store, null, "WHOLESALE", "9.5", lines(pad, 2, oil, 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId").value(nullValue()))
                .andExpect(jsonPath("$.customerName").value(nullValue()))
                .andExpect(jsonPath("$.lines[0].actualUnitPrice").value(90_500))
                .andExpect(jsonPath("$.lines[1].actualUnitPrice").value(76_925))
                .andExpect(jsonPath("$.referenceTotal").value(285_000))
                .andExpect(jsonPath("$.total").value(257_925))
                .andExpect(jsonPath("$.discountAmount").value(27_075));

        // Missing stock does not stop the order (UC-53 step 5).
        Map<String, Object> walkIn = order(null, null, "RETAIL", "0", lines(pad, 20));
        walkIn.put("walkInName", "  Chị Lan ");
        create(managerToken, walkIn).andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerName").value("Chị Lan"))
                .andExpect(jsonPath("$.lines[0].stockShortQty").value(12));

        // Owner chooses the store; its Branch Manager is asked to assign the picking.
        create(ownerToken, order(null, customer, "RETAIL", null, lines(pad, 1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG157"));
        create(ownerToken, order(store, customer, "RETAIL", null, lines(pad, 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.actions.canAssignPicker").value(false));
        assertThat(notifications(manager, "SALES_ORDER_CREATED")).isEqualTo(1);
    }

    @Test
    void createRefusesWrongLinesPricesAndScope() throws Exception {
        create(managerToken, order(null, customer, "RETAIL", null, lines(pad, 1, oil, 1, pad, 2)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG158"))
                .andExpect(jsonPath("$.message").value("Mỗi phụ tùng chỉ có một dòng trong đơn bán (dòng 3)."));
        create(managerToken, order(null, customer, "RETAIL", null, lines(999, 1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG57"));
        create(managerToken, order(null, customer, "RETAIL", null, lines(old, 1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG159"))
                .andExpect(jsonPath("$.message").value("Phụ tùng CU-01 đã ngừng kinh doanh, không thể đưa vào đơn bán."));
        create(managerToken, order(null, customer, "RETAIL", null, lines(pad, 1, lamp, 1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG160"))
                .andExpect(jsonPath("$.message").value("Phụ tùng DEN-01 chưa có giá bán lẻ đang áp dụng nên chưa bán được."));
        create(managerToken, order(null, customer, "RETAIL", "5", lines(pad, 1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG161"));
        // BR-16: the threshold itself is not "below" it.
        create(managerToken, order(null, customer, "WHOLESALE", "10", lines(pad, 1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG162"))
                .andExpect(jsonPath("$.message").value("Chiết khấu của đơn phải nhỏ hơn ngưỡng 10% đang áp dụng."));
        // 9.99% off 19 đ gives 17.10 đ, rounded to 17 đ: the invoice-wide rate 2/19 = 10.5% reaches the threshold (BR-15).
        price(lamp, 19, TODAY.atStartOfDay());
        create(managerToken, order(null, customer, "WHOLESALE", "9.99", lines(lamp, 1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG162"));
        create(managerToken, order(null, 999L, "RETAIL", null, lines(pad, 1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG74"));
        create(managerToken, order(otherStore, customer, "RETAIL", null, lines(pad, 1)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        create(staffToken, order(null, customer, "RETAIL", null, lines(pad, 1)))
                .andExpect(status().isForbidden());
        create(managerToken, order(null, customer, "RETAIL", null, lines(pad, 0)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG02"))
                .andExpect(jsonPath("$.fieldErrors['lines[0].quantity']").value("Số lượng phải lớn hơn 0."));
        create(managerToken, order(null, customer, "WHOLESALE", "5.123", lines(pad, 1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG02"));
        jdbc.update("UPDATE store SET status = 'INACTIVE' WHERE id = ?", otherStore);
        create(ownerToken, order(otherStore, customer, "RETAIL", null, lines(pad, 1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG46"));

        // Without a threshold in effect no discount is allowed; a retail order still is.
        jdbc.update("DELETE FROM pricing_policy WHERE policy_type = 'DISCOUNT_THRESHOLD'");
        create(managerToken, order(null, customer, "WHOLESALE", "5", lines(pad, 1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG163"));
        create(managerToken, order(null, customer, "WHOLESALE", "0", lines(pad, 1)))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_order", Integer.class)).isEqualTo(1);
    }

    @Test
    void previewShowsPricesStockAndThresholdWithoutSaving() throws Exception {
        mvc.perform(post("/api/v1/sales-orders/preview").header(HttpHeaders.AUTHORIZATION, managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(order(null, null, "WHOLESALE", "5", lines(pad, 9, lamp, 1)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priced").value(false))
                .andExpect(jsonPath("$.withinThreshold").value(true))
                .andExpect(jsonPath("$.discountThreshold").value(10.0))
                .andExpect(jsonPath("$.referenceTotal").value(900_000))
                .andExpect(jsonPath("$.total").value(855_000))
                .andExpect(jsonPath("$.invoiceDiscountRate").value(5.0))
                .andExpect(jsonPath("$.lines[0].actualUnitPrice").value(95_000))
                .andExpect(jsonPath("$.lines[0].sellableQty").value(8))
                .andExpect(jsonPath("$.lines[0].shortQty").value(1))
                .andExpect(jsonPath("$.lines[1].retailPrice").value(nullValue()));
        mvc.perform(post("/api/v1/sales-orders/preview").header(HttpHeaders.AUTHORIZATION, managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(order(null, null, "WHOLESALE", "12", lines(pad, 1)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.withinThreshold").value(false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_order", Integer.class)).isZero();
    }

    @Test
    void assignedStaffPicksAndCorrectsWithoutPricesUntilConfirmation() throws Exception {
        long order = createOrder(pad, 4, oil, 2);
        assign(managerToken, "PICKING", order, staff).andExpect(status().isOk());

        list(staffToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].total").value(nullValue()))
                .andExpect(jsonPath("$.items[0].customerPhone").value(nullValue()))
                .andExpect(jsonPath("$.items[0].nextAction").value("RECORD_PICKING"));
        list(otherStaffToken).andExpect(jsonPath("$.items", hasSize(0)));
        list(managerToken).andExpect(jsonPath("$.items[0].pickerName").value("Test staff"))
                .andExpect(jsonPath("$.items[0].nextAction").value(nullValue()));
        detail(staffToken, order).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(nullValue()))
                .andExpect(jsonPath("$.lines[0].referencePrice").value(nullValue()))
                .andExpect(jsonPath("$.lines[0].pickFirst[0].lotId").value(padSoon))
                .andExpect(jsonPath("$.history", hasSize(0)))
                .andExpect(jsonPath("$.actions.canRecordPicking").value(true));
        detail(otherStaffToken, order).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));

        pick(otherStaffToken, order, 1, pad, 4, null, oil, 2, null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        pick(staffToken, order, 1, pad, 4, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG167"))
                .andExpect(jsonPath("$.message").value("Vui lòng ghi số lượng cho mọi phụ tùng của chứng từ, còn thiếu NHOT-01."));
        pick(staffToken, order, 1, pad, 4, null, lamp, 1, null, oil, 2, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG166"))
                .andExpect(jsonPath("$.message").value("Phụ tùng ở dòng 2 không có trong chứng từ hoặc đã có ở dòng trên."));
        pick(staffToken, order, 1, pad, 5, null, oil, 2, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG168"))
                .andExpect(jsonPath("$.message").value("Số đã lấy của phụ tùng MA-PHANH-01 không được lớn hơn số yêu cầu 4."));
        pick(staffToken, order, 2, pad, 4, null, oil, 2, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG165"));

        pick(staffToken, order, 1, pad, 3, " Hộp bị móp ", oil, 2, "không dùng")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PICKED"))
                .andExpect(jsonPath("$.lines[0].pickedQty").value(3))
                .andExpect(jsonPath("$.lines[0].shortQty").value(1))
                .andExpect(jsonPath("$.lines[0].shortReason").value("Hộp bị móp"))
                .andExpect(jsonPath("$.lines[1].shortReason").value(nullValue()))
                .andExpect(jsonPath("$.actions.canRecordPicking").value(true));
        assertThat(taskStatus("PICKING", order)).isEqualTo("RESULT_SUBMITTED");
        assertThat(notifications(manager, "SALES_ORDER_PICKED")).isEqualTo(1);
        // Picking moves no stock (UC-57).
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE movement_type = 'SALE_ISSUE'",
                Integer.class)).isZero();
        // The result is in: no reassignment (MSG39).
        assign(managerToken, "PICKING", order, otherStaff).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG39"));
        list(managerToken).andExpect(jsonPath("$.items[0].nextAction").value("CONFIRM"));

        // "Correct an unconfirmed result".
        pick(staffToken, order, 1, pad, 4, null, oil, 2, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].shortQty").value(0))
                .andExpect(jsonPath("$.lines[0].shortReason").value(nullValue()));
        assertThat(taskStatus("PICKING", order)).isEqualTo("RESULT_SUBMITTED");

        confirm(managerToken, order, 1, pad, 4, oil, 2).andExpect(status().isOk());
        pick(staffToken, order, 1, pad, 4, null, oil, 2, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG29"));
    }

    @Test
    void confirmingShortDeliverySavesARepricedVersion() throws Exception {
        long order = createOrder(pad, 4, oil, 2);
        assign(managerToken, "PICKING", order, staff);
        confirm(managerToken, order, 1, pad, 4, oil, 2)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG174"));
        pick(staffToken, order, 1, pad, 3, "Thiếu hàng", oil, 2, null).andExpect(status().isOk());

        confirm(managerToken, order, 1, pad, 4, oil, 2)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG169"))
                .andExpect(jsonPath("$.message").value("Số giao của phụ tùng MA-PHANH-01 không được lớn hơn số đã lấy 3."));
        confirm(managerToken, order, 1, pad, 0, oil, 0)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG170"));
        confirm(managerToken, order, 2, pad, 3, oil, 2)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG165"));
        mvc.perform(post("/api/v1/sales-orders/" + order + "/confirm").header(HttpHeaders.AUTHORIZATION, managerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("version", 1,
                                "customerAgreed", false, "lines", List.of(Map.of("productId", pad, "deliveredQty", 3))))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG02"))
                .andExpect(jsonPath("$.fieldErrors.customerAgreed").value("Vui lòng xác nhận khách hàng đồng ý nhận hàng."));
        confirm(staffToken, order, 1, pad, 3, oil, 2).andExpect(status().isForbidden());
        confirm(otherManagerToken, order, 1, pad, 3, oil, 2)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));

        // The oil price changed after the order was saved: the new version takes it (BR-18, BR-36).
        price(oil, 90_000, TODAY.atStartOfDay());
        confirm(managerToken, order, 1, pad, 3, oil, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.quantityBasis").value("DELIVERED"))
                .andExpect(jsonPath("$.lines[0].deliveredQty").value(3))
                .andExpect(jsonPath("$.lines[0].requestedQty").value(4))
                .andExpect(jsonPath("$.lines[1].actualUnitPrice").value(90_000))
                .andExpect(jsonPath("$.total").value(480_000))
                .andExpect(jsonPath("$.confirmedBy").value("Test bm"))
                .andExpect(jsonPath("$.history[0].action").value("CONFIRMED"))
                .andExpect(jsonPath("$.history[0].version").value(2))
                .andExpect(jsonPath("$.history[1].action").value("VERSIONED"))
                .andExpect(jsonPath("$.history[1].cause").value("DELIVERY"));
        assertThat(taskStatus("PICKING", order)).isEqualTo("DONE");
        // Confirmation moves no stock and creates no receivable (BR-38).
        assertThat(jdbc.queryForObject("SELECT available_qty FROM stock_balance WHERE store_id = ? AND product_id = ?",
                Integer.class, store, pad)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM receivable", Integer.class)).isZero();

        mvc.perform(get("/api/v1/sales-orders/" + order + "/versions").header(HttpHeaders.AUTHORIZATION, managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].cause").value("CREATED"))
                .andExpect(jsonPath("$[0].lines[1].referencePrice").value(85_000))
                .andExpect(jsonPath("$[0].total").value(570_000))
                .andExpect(jsonPath("$[1].cause").value("DELIVERY"))
                .andExpect(jsonPath("$[1].lines[0].deliveredQty").value(3))
                .andExpect(jsonPath("$[1].total").value(480_000));
        mvc.perform(get("/api/v1/sales-orders/" + order + "/versions").header(HttpHeaders.AUTHORIZATION, staffToken))
                .andExpect(status().isForbidden());

        // The same delivery again changes nothing; another delivery is a new version.
        confirm(managerToken, order, 2, pad, 3, oil, 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        confirm(managerToken, order, 2, pad, 2, oil, 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.total").value(380_000));
        list(managerToken).andExpect(jsonPath("$.items[0].version").value(3))
                .andExpect(jsonPath("$.items[0].total").value(380_000))
                .andExpect(jsonPath("$.items[0].nextAction").value("COLLECT_PAYMENT"));
    }

    @Test
    void updatingSavesVersionsAndSendsChangedLinesBackToThePicker() throws Exception {
        long order = createOrder(pad, 4, oil, 2);
        assign(managerToken, "PICKING", order, staff);
        pick(staffToken, order, 1, pad, 4, null, oil, 2, null);
        confirm(managerToken, order, 1, pad, 4, oil, 2).andExpect(jsonPath("$.status").value("CONFIRMED"));

        // Nothing changed: no version.
        update(managerToken, order, 1, order(null, customer, "RETAIL", null, lines(pad, 4, oil, 2)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // A discount only: picking still holds, confirmation does not (BR-36).
        update(managerToken, order, 1, order(null, customer, "WHOLESALE", "5", lines(pad, 4, oil, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("PICKED"))
                .andExpect(jsonPath("$.confirmedAt").value(nullValue()))
                .andExpect(jsonPath("$.lines[0].pickedQty").value(4))
                .andExpect(jsonPath("$.lines[0].deliveredQty").value(nullValue()))
                .andExpect(jsonPath("$.lines[0].actualUnitPrice").value(95_000))
                .andExpect(jsonPath("$.history[0].cause").value("UPDATE"));
        update(managerToken, order, 1, order(null, customer, "RETAIL", null, lines(pad, 4)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG165"))
                .andExpect(jsonPath("$.message").value("Đơn bán vừa được lưu sang phiên bản 2. Vui lòng tải lại đơn rồi thực hiện lại."));
        update(managerToken, order, 2, order(null, customer, "WHOLESALE", "5", lines(pad, 4, oil, 2, old, 1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG159"));
        confirm(managerToken, order, 2, pad, 4, oil, 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED")).andExpect(jsonPath("$.version").value(2));

        // More oil: the goods must be picked again by the same Staff member.
        update(managerToken, order, 2, order(null, customer, "WHOLESALE", "5", lines(pad, 4, oil, 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.status").value("AWAITING_PICKING"))
                .andExpect(jsonPath("$.lines[0].pickedQty").value(4))
                .andExpect(jsonPath("$.lines[1].pickedQty").value(nullValue()))
                .andExpect(jsonPath("$.picker.name").value("Test staff"))
                .andExpect(jsonPath("$.picker.taskStatus").value("ASSIGNED"));
        assertThat(notifications(staff, "SALES_ORDER_CHANGED")).isEqualTo(1);
        pick(staffToken, order, 2, pad, 4, null, oil, 3, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG165"));
        // A walk-in name only after that: version 4 asks for the same goods, so a result for version 3 still counts.
        Map<String, Object> walkIn = order(null, null, "WHOLESALE", "5", lines(pad, 4, oil, 3));
        walkIn.put("walkInName", "Khách quen");
        update(managerToken, order, 3, walkIn).andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.customerName").value("Khách quen"));
        pick(staffToken, order, 3, pad, 4, null, oil, 3, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PICKED"));

        // A removed line goes; the removed and kept parts keep their line numbers.
        update(managerToken, order, 4, order(null, customer, "RETAIL", null, lines(oil, 3, pad, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines", hasSize(2)))
                .andExpect(jsonPath("$.lines[0].lineNo").value(1))
                .andExpect(jsonPath("$.lines[0].requestedQty").value(1))
                .andExpect(jsonPath("$.status").value("AWAITING_PICKING"));
        update(managerToken, order, 5, order(null, customer, "RETAIL", null, lines(oil, 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines", hasSize(1)))
                .andExpect(jsonPath("$.lines[0].sku").value("NHOT-01"));
        update(managerToken, order, 6, order(null, customer, "RETAIL", null, lines(oil, 3, pad, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[1].lineNo").value(3));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'SALES_ORDER_VERSIONED' "
                + "AND entity_id = ?", Integer.class, order)).isEqualTo(6);

        update(otherManagerToken, order, 7, order(null, customer, "RETAIL", null, lines(oil, 1)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        jdbc.update("UPDATE sales_order SET status = 'CANCELLED', cancellation_reason = 'x' WHERE id = ?", order);
        update(managerToken, order, 7, order(null, customer, "RETAIL", null, lines(oil, 1)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG164"));
    }

    @Test
    void confirmationChecksStockMarginAndWhoPicked() throws Exception {
        // The Branch Manager picks the goods themself: someone else confirms (BR-26).
        long order = createOrder(pad, 4);
        assign(managerToken, "PICKING", order, manager).andExpect(status().isOk());
        pick(managerToken, order, 1, pad, 4, null).andExpect(status().isOk());
        confirm(managerToken, order, 1, pad, 4)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG124"));

        // Only 3 sellable units left: the lot without expiry was used elsewhere.
        jdbc.update("UPDATE lot_balance SET quantity = 0 WHERE lot_id = ?", padNoExpiry);
        confirm(ownerToken, order, 1, pad, 4)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG171"))
                .andExpect(jsonPath("$.message").value("Không đủ hàng còn hạn tại cửa hàng để giao: MA-PHANH-01 cần 4, còn 3."));
        jdbc.update("UPDATE lot_balance SET quantity = 5 WHERE lot_id = ?", padNoExpiry);

        // BR-88 at 60%: 4 × 60,000 × 1.6 = 384,000; at 5% off the order makes 380,000.
        jdbc.update("UPDATE pricing_policy SET rate = 60 WHERE policy_type = 'MINIMUM_MARGIN'");
        update(ownerToken, order, 1, order(null, customer, "WHOLESALE", "5", lines(pad, 4))).andExpect(status().isOk());
        confirm(ownerToken, order, 2, pad, 4)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG173"))
                .andExpect(jsonPath("$.message").value("Tổng tiền của đơn phải từ 384.000 đ trở lên theo biên lợi nhuận tối thiểu 60%."));
        long other = createOrder(pad, 4);
        assign(managerToken, "PICKING", other, staff);
        pick(staffToken, other, 1, pad, 4, null);
        update(managerToken, other, 1, order(null, customer, "WHOLESALE", "5", lines(pad, 4)));
        confirm(managerToken, other, 2, pad, 4)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG172"));
        update(ownerToken, order, 2, order(null, customer, "WHOLESALE", "4", lines(pad, 4))).andExpect(status().isOk());
        confirm(ownerToken, order, 3, pad, 4).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(384_000))
                .andExpect(jsonPath("$.confirmedBy").value("Test owner"));
    }

    @Test
    void listFiltersByStoreTabSearchAndScope() throws Exception {
        long first = createOrder(pad, 1);
        Map<String, Object> walkIn = order(null, null, "RETAIL", null, lines(oil, 1));
        walkIn.put("walkInName", "Chị Lan");
        walkIn.put("walkInPhone", "0987000111");
        long second = ((Number) JsonPath.read(create(managerToken, walkIn).andReturn().getResponse()
                .getContentAsString(), "$.id")).longValue();
        long elsewhere = ((Number) JsonPath.read(create(ownerToken, order(otherStore, null, "RETAIL", null,
                lines(oil, 1))).andReturn().getResponse().getContentAsString(), "$.id")).longValue();

        list(managerToken).andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].id").value(second))
                .andExpect(jsonPath("$.items[0].customerName").value("Chị Lan"))
                .andExpect(jsonPath("$.items[1].nextAction").value("ASSIGN_PICKER"))
                .andExpect(jsonPath("$.items[1].total").value(100_000));
        list(managerToken, "storeId", String.valueOf(otherStore))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        list(ownerToken).andExpect(jsonPath("$.totalItems").value(3));
        list(ownerToken, "storeId", String.valueOf(otherStore)).andExpect(jsonPath("$.items[0].id").value(elsewhere))
                .andExpect(jsonPath("$.items[0].storeName").value("Long Biên"));
        list(managerToken, "q", "0987").andExpect(jsonPath("$.items", hasSize(1)));
        list(managerToken, "q", "tuấn").andExpect(jsonPath("$.items[0].id").value(first));
        list(managerToken, "q", "#" + first).andExpect(jsonPath("$.items[0].id").value(first));

        jdbc.update("UPDATE sales_order SET status = 'CANCELLED', cancellation_reason = 'x', created_at = ? WHERE id = ?",
                TODAY.minusDays(3).atTime(9, 0), first);
        list(managerToken).andExpect(jsonPath("$.totalItems").value(1));
        // Other tabs: today by default, or the chosen days.
        list(managerToken, "view", "CANCELLED").andExpect(jsonPath("$.totalItems").value(0));
        list(managerToken, "view", "CANCELLED", "createdFrom", TODAY.minusDays(5).toString())
                .andExpect(jsonPath("$.totalItems").value(1));
        list(managerToken, "view", "ALL", "createdFrom", TODAY.minusDays(5).toString())
                .andExpect(jsonPath("$.totalItems").value(2));

        detail(otherManagerToken, second).andExpect(status().isForbidden());
        detail(ownerToken, elsewhere).andExpect(status().isOk());
        detail(managerToken, 999).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG156"));
    }
}
