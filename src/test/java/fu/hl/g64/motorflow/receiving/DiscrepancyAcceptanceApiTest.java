package fu.hl.g64.motorflow.receiving;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GVHD 20/11 (PR 5): held damaged or wrong goods taken into stock. A Branch Manager asks, Owner refuses or takes them:
 * dented goods as the part's "-M" variant, wrong items as the part they really are.
 */
class DiscrepancyAcceptanceApiTest extends GoodsReceiptTestData {

    @Test
    void ownerTakesDentedGoodsAsTheVariantWithItsOwnPriceAndCost() throws Exception {
        long grn = receive("PX-001");
        assign(grn, staff);
        record(staffToken, grn, findings(counted(lineOf(grn, pad), 10, 10, 0, 2),
                counted(lineOf(grn, oil), 24, 24, 0, 0))).andExpect(status().isOk());
        confirm(managerToken, grn).andExpect(status().isOk());
        long found = caseOf(grn, pad);
        discrepancy(managerToken, found)
                .andExpect(jsonPath("$.damagedUndecidedQty").value(2))
                .andExpect(jsonPath("$.canRequestAcceptance").value(true))
                .andExpect(jsonPath("$.canApproveAcceptance").value(false))
                .andExpect(jsonPath("$.pendingRequests", empty()));

        // The Branch Manager asks for at most what is held; Owner is told.
        request(managerToken, found, "DAMAGED", null, 3)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG309"))
                .andExpect(jsonPath("$.message").value("Chỉ còn 2 hàng đang giữ chưa quyết định để nhận vào kho."));
        request(managerToken, found, "DAMAGED", null, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingRequests[0].type").value("DAMAGED"))
                .andExpect(jsonPath("$.pendingRequests[0].quantity").value(2))
                .andExpect(jsonPath("$.pendingRequests[0].requestedByName").value("Test bm"))
                .andExpect(jsonPath("$.canRequestAcceptance").value(false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE notification_type = "
                + "'DISCREPANCY_ACCEPTANCE_REQUESTED' AND recipient_id = ?", Long.class, owner.getId())).isEqualTo(1);
        discrepancy(ownerToken, found).andExpect(jsonPath("$.canApproveAcceptance").value(true));

        // Only Owner takes them, and the variant needs a price the first time.
        acceptAsVariant(managerToken, found, 1, 60_000, 90_000L).andExpect(status().isForbidden());
        acceptAsVariant(ownerToken, found, 1, 60_000, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG311"));
        acceptAsVariant(ownerToken, found, 1, 60_000, 90_000L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.damagedUndecidedQty").value(1))
                .andExpect(jsonPath("$.pendingRequests", empty()))
                .andExpect(jsonPath("$.events[*].type", contains("ACCEPTED_AS_VARIANT")));
        long variant = jdbc.queryForObject("SELECT id FROM product WHERE variant_of_product_id = ?", Long.class, pad);
        assertThat(jdbc.queryForMap("SELECT sku, part_name, category_id, base_unit FROM product WHERE id = ?",
                variant)).containsEntry("sku", "MA-PHANH-01-M").containsEntry("part_name", "Má phanh trước (hàng móp)")
                .containsEntry("base_unit", "cái");
        assertThat(jdbc.queryForObject("SELECT unit_price FROM pricing_policy WHERE product_id = ? "
                + "AND policy_type = 'RETAIL_PRICE'", Long.class, variant)).isEqualTo(90_000);

        // The second time reuses the variant and keeps its price.
        acceptAsVariant(ownerToken, found, 1, 50_000, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.heldUndecidedQty").value(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product WHERE variant_of_product_id = ?", Long.class,
                pad)).isEqualTo(1);
        assertThat(stock(giaLam, pad, "DAMAGED")).isZero();
        assertThat(stock(giaLam, variant, "AVAILABLE")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT storage_area FROM stock_balance WHERE store_id = ? AND product_id = ?",
                String.class, giaLam, variant)).isEqualTo("Khu hàng lỗi");
        assertThat(jdbc.queryForList("SELECT purchase_unit_price FROM lot WHERE product_id = ? "
                + "AND source_type = 'DISCREPANCY' AND supplier_id = ? ORDER BY id", Long.class, variant, honda))
                .containsExactly(60_000L, 50_000L);
        // Its own moving average; the original's is unchanged, damaged goods were never in it.
        assertThat(averageCost(variant)).isEqualByComparingTo("55000");
        assertThat(averageCost(pad)).isEqualByComparingTo("120000");
        assertThat(movementsOf(variant)).containsExactly(List.of("DAMAGED_TO_VARIANT", "AVAILABLE", 1),
                List.of("DAMAGED_TO_VARIANT", "AVAILABLE", 1));
        assertThat(movementsOf(pad)).endsWith(List.of("DAMAGED_TO_VARIANT", "DAMAGED", -1),
                List.of("DAMAGED_TO_VARIANT", "DAMAGED", -1));
        assertStockAgrees(giaLam, pad);
        assertStockAgrees(giaLam, variant);

        // Selling the part shows its dented goods next to it.
        Map<String, Object> preview = new HashMap<>();
        preview.put("priceType", "RETAIL");
        preview.put("lines", List.of(Map.of("productId", pad, "quantity", 1)));
        mvc.perform(post("/api/v1/sales-orders/preview").header(HttpHeaders.AUTHORIZATION, managerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json(preview)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].variant.productId").value((int) variant))
                .andExpect(jsonPath("$.lines[0].variant.sku").value("MA-PHANH-01-M"))
                .andExpect(jsonPath("$.lines[0].variant.sellableQty").value(2))
                .andExpect(jsonPath("$.lines[0].variant.retailPrice").value(90_000));
    }

    @Test
    void ownerTakesWrongItemsAsThePartTheyAreAndMayWaiveTheirReplacement() throws Exception {
        long grn = receive("PX-001");
        assign(grn, staff);
        record(staffToken, grn, findings(counted(lineOf(grn, pad), 10, 10, 0, 0),
                counted(lineOf(grn, oil), 24, 24, 2, 0))).andExpect(status().isOk());
        confirm(managerToken, grn).andExpect(status().isOk());
        long found = caseOf(grn, oil);
        // The supplier is to send 2 oils again; nothing goes back.
        handle(managerToken, grn, List.of(handling(found, 0, 2))).andExpect(status().isOk());

        // A request Owner refuses: the goods stay held and the store is told.
        request(managerToken, found, "WRONG_ITEM", lamp, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingRequests[0].sku").value("DEN-01"));
        reject(managerToken, found, "WRONG_ITEM").andExpect(status().isForbidden());
        reject(ownerToken, found, "WRONG_ITEM")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingRequests", empty()))
                .andExpect(jsonPath("$.wrongUndecidedQty").value(2));
        reject(ownerToken, found, "WRONG_ITEM").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG315"))
                .andExpect(jsonPath("$.message").value(
                        "Hồ sơ không có đề nghị nhận hàng giao sai nào đang chờ duyệt."));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE notification_type = "
                + "'DISCREPANCY_ACCEPTANCE_REJECTED' AND recipient_id = ?", Long.class, manager.getId())).isEqualTo(1);
        assertThat(stock(giaLam, oil, "WRONG_ITEM")).isEqualTo(2);

        receiveWrong(ownerToken, found, oil, 1, true)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG310"));
        receiveWrong(ownerToken, found, discontinued, 1, true)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG313"));
        receiveWrong(ownerToken, found, lamp, 3, true)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG309"));
        receiveWrong(managerToken, found, lamp, 1, true).andExpect(status().isForbidden());

        // Still to be replaced: the case stays open.
        receiveWrong(ownerToken, found, lamp, 1, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.outstandingReplacementQty").value(2))
                .andExpect(jsonPath("$.wrongUndecidedQty").value(1));
        // Not to be replaced: waived with it.
        receiveWrong(ownerToken, found, lamp, 1, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outstandingReplacementQty").value(1))
                .andExpect(jsonPath("$.waivedQty").value(1))
                .andExpect(jsonPath("$.heldUndecidedQty").value(0))
                .andExpect(jsonPath("$.events[*].type", contains("RECEIVED_AS_OTHER_PRODUCT",
                        "RECEIVED_AS_OTHER_PRODUCT", "CLOSED_BY_AGREEMENT")));
        assertThat(stock(giaLam, oil, "WRONG_ITEM")).isZero();
        assertThat(stock(giaLam, lamp, "AVAILABLE")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lot WHERE product_id = ? AND source_type = 'DISCREPANCY'",
                Long.class, lamp)).isEqualTo(2);
        assertStockAgrees(giaLam, oil);
        assertStockAgrees(giaLam, lamp);

        // The lot shows where it came from.
        long lot = jdbc.queryForObject("SELECT min(id) FROM lot WHERE product_id = ?", Long.class, lamp);
        String detail = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/inventory/lots/" + lot).header(HttpHeaders.AUTHORIZATION, ownerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(detail, "$.sourceDocument.type")).isEqualTo("DISCREPANCY_RESOLUTION_EVENT");
    }

    @Test
    void requestsArePendingPerKindAndOneKindAnsweredLeavesTheOther() throws Exception {
        long found = caseWithDamagedAndWrong();
        request(managerToken, found, "DAMAGED", null, 2).andExpect(status().isOk());
        request(managerToken, found, "WRONG_ITEM", lamp, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingRequests.length()").value(2))
                .andExpect(jsonPath("$.pendingRequests[*].type", contains("DAMAGED", "WRONG_ITEM")))
                .andExpect(jsonPath("$.canRequestAcceptance").value(false));
        request(managerToken, found, "DAMAGED", null, 1)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG314"))
                .andExpect(jsonPath("$.message").value("Hồ sơ đã có đề nghị nhận hàng móp đang chờ Owner duyệt."));

        // Owner refuses the wrong items only: the dented goods are still waiting and the store is told.
        reject(ownerToken, found, "WRONG_ITEM")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingRequests.length()").value(1))
                .andExpect(jsonPath("$.pendingRequests[0].type").value("DAMAGED"))
                .andExpect(jsonPath("$.canRequestAcceptance").value(true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE notification_type = "
                + "'DISCREPANCY_ACCEPTANCE_REJECTED' AND recipient_id = ?", Long.class, manager.getId())).isEqualTo(1);
        reject(ownerToken, found, "WRONG_ITEM").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG315"));
        // A refused kind may be asked for again.
        request(managerToken, found, "WRONG_ITEM", lamp, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingRequests.length()").value(2));
        reject(ownerToken, found, "WRONG_ITEM").andExpect(status().isOk());

        // Owner taking the dented goods answers their request only.
        reject(ownerToken, found, "DAMAGED").andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingRequests", empty()));
        request(managerToken, found, "DAMAGED", null, 2).andExpect(status().isOk());
        request(managerToken, found, "WRONG_ITEM", lamp, 2).andExpect(status().isOk());
        acceptAsVariant(ownerToken, found, 2, 60_000, 90_000L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingRequests.length()").value(1))
                .andExpect(jsonPath("$.pendingRequests[0].type").value("WRONG_ITEM"))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void rejectingNeedsTheKindOfRequest() throws Exception {
        long found = caseWithDamagedAndWrong();
        mvc.perform(post("/api/v1/discrepancy-cases/" + found + "/acceptance-requests/reject")
                        .header(HttpHeaders.AUTHORIZATION, ownerToken).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "Trả lại nhà cung cấp"))))
                .andExpect(status().isBadRequest());
        reject(ownerToken, found, "DAMAGED").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG315"));
    }

    @Test
    void waivingTheReplacementOfWrongItemsNeverWaivesWhatTheShortUnitsStillOwe() throws Exception {
        long grn = receive("PX-001");
        assign(grn, staff);
        record(staffToken, grn, findings(counted(lineOf(grn, pad), 10, 10, 0, 0),
                counted(lineOf(grn, oil), 24, 22, 2, 0))).andExpect(status().isOk());
        confirm(managerToken, grn).andExpect(status().isOk());
        long found = caseOf(grn, oil);
        discrepancy(managerToken, found).andExpect(jsonPath("$.shortQty").value(2))
                .andExpect(jsonPath("$.wrongItemQty").value(2));
        handle(managerToken, grn, List.of(handling(found, 0, 4))).andExpect(status().isOk());

        receiveWrong(ownerToken, found, lamp, 2, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.waivedQty").value(2))
                .andExpect(jsonPath("$.outstandingReplacementQty").value(2))
                .andExpect(jsonPath("$.heldUndecidedQty").value(0));
    }

    @Test
    void replacementNeededMustBeSent() throws Exception {
        long found = caseWithDamagedAndWrong();
        Map<String, Object> body = new HashMap<>(Map.of("productId", lamp, "quantity", 1, "unitCost", 70_000));
        receiveWrongBody(ownerToken, found, body).andExpect(status().isBadRequest());
        assertThat(stock(giaLam, oil, "WRONG_ITEM")).isEqualTo(2);
    }

    @Test
    void ownerGivesTheLotNumberAndDatesOfWrongItems() throws Exception {
        long found = caseWithDamagedAndWrong();
        Map<String, Object> body = new HashMap<>(Map.of("productId", lamp, "quantity", 1, "unitCost", 70_000,
                "replacementNeeded", true, "manufacturerLotNo", " L-77 ", "manufactureDate", TODAY.minusDays(30).toString(),
                "expiryDate", TODAY.plusDays(300).toString()));
        body.put("expiryDate", TODAY.minusDays(40).toString());
        receiveWrongBody(ownerToken, found, body)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG137"));
        body.put("manufactureDate", TODAY.minusDays(90).toString());
        receiveWrongBody(ownerToken, found, body)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG318"));
        assertThat(stock(giaLam, oil, "WRONG_ITEM")).isEqualTo(2);

        body.put("manufactureDate", TODAY.minusDays(30).toString());
        body.put("expiryDate", TODAY.plusDays(300).toString());
        receiveWrongBody(ownerToken, found, body).andExpect(status().isOk());
        Map<String, Object> lot = jdbc.queryForMap("SELECT manufacturer_lot_no, manufacture_date, expiry_date FROM lot "
                + "WHERE product_id = ? AND source_type = 'DISCREPANCY'", lamp);
        assertThat(lot).containsEntry("manufacturer_lot_no", "L-77");
        assertThat(lot.get("manufacture_date").toString()).isEqualTo(TODAY.minusDays(30).toString());
        assertThat(lot.get("expiry_date").toString()).isEqualTo(TODAY.plusDays(300).toString());
    }

    @Test
    void aDentedGoodsVariantIsNeverBoughtNorTakenAsTheRealPartOfWrongItems() throws Exception {
        long found = caseWithDamagedAndWrong();
        long variant = part("DEN-01-M", "Đèn pha (hàng móp)", jdbc.queryForObject(
                "SELECT category_id FROM product WHERE id = ?", Long.class, lamp));
        jdbc.update("UPDATE product SET variant_of_product_id = ? WHERE id = ?", lamp, variant);

        create(ownerToken, order(honda, giaLam, TODAY, null, line(variant, 1, 50_000)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG316"))
                .andExpect(jsonPath("$.message").value("Phụ tùng DEN-01-M là hàng móp, không đặt mua, yêu cầu bổ sung "
                        + "hay nhập đầu kỳ được."));
        request(managerToken, found, "WRONG_ITEM", variant, 1)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG317"));
        receiveWrong(ownerToken, found, variant, 1, true)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG317"));
        assertThat(stock(giaLam, oil, "WRONG_ITEM")).isEqualTo(2);

        // A variant of a variant is never made: the case's part may not be a variant either.
        jdbc.update("UPDATE discrepancy_case SET product_id = ? WHERE id = ?", variant, found);
        acceptAsVariant(ownerToken, found, 1, 60_000, 90_000L)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG317"));
    }

    /** A case of the oil with 2 wrong items and 2 damaged ones held at the store, nothing to return. */
    private long caseWithDamagedAndWrong() throws Exception {
        long grn = receive("PX-001");
        assign(grn, staff);
        record(staffToken, grn, findings(counted(lineOf(grn, pad), 10, 10, 0, 0),
                counted(lineOf(grn, oil), 24, 24, 2, 2))).andExpect(status().isOk());
        confirm(managerToken, grn).andExpect(status().isOk());
        long found = caseOf(grn, oil);
        discrepancy(managerToken, found)
                .andExpect(jsonPath("$.damagedUndecidedQty").value(2))
                .andExpect(jsonPath("$.wrongUndecidedQty").value(2));
        return found;
    }

    private ResultActions request(String token, long found, String type, Long product, int quantity)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("type", type);
        body.put("productId", product);
        body.put("quantity", quantity);
        body.put("note", "NCC đồng ý giảm giá");
        return mvc.perform(post("/api/v1/discrepancy-cases/" + found + "/acceptance-requests")
                .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions reject(String token, long found, String type) throws Exception {
        return mvc.perform(post("/api/v1/discrepancy-cases/" + found + "/acceptance-requests/reject")
                .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("type", type, "reason", "Trả lại nhà cung cấp"))));
    }

    private ResultActions acceptAsVariant(String token, long found, int quantity, long unitCost, Long salePrice)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("quantity", quantity);
        body.put("unitCost", unitCost);
        body.put("salePrice", salePrice);
        return mvc.perform(post("/api/v1/discrepancy-cases/" + found + "/damaged/accept-as-variant")
                .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions receiveWrong(String token, long found, long product, int quantity, boolean replacement)
            throws Exception {
        return receiveWrongBody(token, found, Map.of("productId", product, "quantity", quantity, "unitCost", 70_000,
                "replacementNeeded", replacement));
    }

    private ResultActions receiveWrongBody(String token, long found, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/discrepancy-cases/" + found + "/wrong-items/receive")
                .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }
}
