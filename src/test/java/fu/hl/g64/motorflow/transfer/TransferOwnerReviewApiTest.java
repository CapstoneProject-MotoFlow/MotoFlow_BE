package fu.hl.g64.motorflow.transfer;

import fu.hl.g64.motorflow.account.entity.enums.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TransferOwnerReviewApiTest extends TransferFlowTestData {

    @Test
    void acknowledgementPreservesTransitUntilOwnerCloses() throws Exception {
        lot(source, brake, 15, 100_000, null, "A");
        long order = dispatchedOrder(brake, 15);
        confirm("bmlb", order, inspected(order, brake, 6, 8)).andExpect(status().isOk());
        assertThat(stock(dest, brake, "available_qty")).isEqualTo(6);
        assertThat(stock(dest, brake, "damaged_qty")).isZero();
        assertThat(stock(dest, brake, "in_transit_qty")).isEqualTo(1);
        action("owner", order, "/close").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG336"));

        action("bmcg", order, "/discrepancy-acknowledgement").andExpect(status().isForbidden());
        action("bmlb", order, "/discrepancy-acknowledgement").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECONCILING"))
                .andExpect(jsonPath("$.lines[0].receivedQty").value(6))
                .andExpect(jsonPath("$.lines[0].damagedQty").value(8))
                .andExpect(jsonPath("$.lines[0].shortageQty").value(1))
                .andExpect(jsonPath("$.lines[0].unresolvedQty").value(0));
        assertThat(stock(dest, brake, "in_transit_qty")).isEqualTo(1);

        action("owner", order, "/close").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.lines[0].shortageQty").value(1));
        action("owner", order, "/close").andExpect(status().isOk());
        assertThat(stock(dest, brake, "available_qty")).isEqualTo(6);
        assertThat(stock(dest, brake, "in_transit_qty")).isZero();
    }

    @Test
    void ownerMovesExcessFromMatchingExpiryOnlyAndRetriesDoNotDoubleIt() throws Exception {
        long earlier = lot(source, brake, 3, 100_000, today.plusDays(30), "A");
        long matching = lot(source, brake, 5, 100_000, today.plusDays(60), "B");
        long order = dispatchedOrder(brake, 2);
        long receipt = arrive("bmlb", order);
        assign("bmlb", "INSPECTION", receipt, destStaff).andExpect(status().isOk());
        mvc.perform(put(ORDERS + "/" + order + "/receipts/" + receipt + "/result")
                        .header(HttpHeaders.AUTHORIZATION, signIn("nvlb")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("inspectionNote", "Thua 2",
                                "lines", List.of(Map.of("productId", brake, "passedQty", 4, "damagedQty", 0,
                                        "expiryDate", today.plusDays(60).toString()))))))
                .andExpect(status().isOk());
        confirm("bmlb", order, receipt).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].excessDecision").value("AWAITING_OWNER"));
        action("owner", order, "/close").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG338"));
        mvc.perform(get("/api/v1/goods-receipt-notes/transfer-excess")
                        .header(HttpHeaders.AUTHORIZATION, signIn("owner")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].receiptId").value(receipt))
                .andExpect(jsonPath("$[0].sourceStoreName").isNotEmpty())
                .andExpect(jsonPath("$[0].lines[0].excessQty").value(2))
                .andExpect(jsonPath("$[0].lines[0].lots[0].lotId").value(matching))
                .andExpect(jsonPath("$[0].lines[0].lots[0].quantity").value(2))
                .andExpect(jsonPath("$[0].lines[0].blockedCode").doesNotExist());
        Long line = jdbc.queryForObject("SELECT id FROM goods_receipt_note_line WHERE goods_receipt_note_id = ?",
                Long.class, receipt);
        String body = json(Map.of("lines", List.of(Map.of("lineId", line, "decision", "ACCEPTED"))));
        decide(order, receipt, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].excessDecision").value("ACCEPTED"));
        decide(order, receipt, body).andExpect(status().isConflict());
        assertThat(lotStock(earlier, source, "AVAILABLE")).isEqualTo(1);
        assertThat(lotStock(matching, source, "AVAILABLE")).isEqualTo(3);
        assertThat(lotStock(matching, dest, "AVAILABLE")).isEqualTo(2);
        action("owner", order, "/close").andExpect(status().isOk());

        mvc.perform(get("/api/v1/stock-documents").header(HttpHeaders.AUTHORIZATION, signIn("owner"))
                        .param("type", "GOODS_RECEIPT_NOTE").param("direction", "IN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].quantity").value(4))
                .andExpect(jsonPath("$.items[0].value").value(400000));
        mvc.perform(get("/api/v1/stock-documents/GOODS_RECEIPT_NOTE/" + receipt)
                        .header(HttpHeaders.AUTHORIZATION, signIn("nvlb")).param("direction", "IN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.document.quantity").value(4))
                .andExpect(jsonPath("$.document.value").doesNotExist())
                .andExpect(jsonPath("$.lines[0].unitCost").doesNotExist());
    }

    @Test
    void insufficientMatchingLotsLeaveTheDecisionPendingAndRejectionChangesNoStock() throws Exception {
        lot(source, brake, 10, 100_000, today.plusDays(30), "A");
        long order = dispatchedOrder(brake, 2);
        long receipt = arrive("bmlb", order);
        assign("bmlb", "INSPECTION", receipt, destStaff).andExpect(status().isOk());
        mvc.perform(put(ORDERS + "/" + order + "/receipts/" + receipt + "/result")
                        .header(HttpHeaders.AUTHORIZATION, signIn("nvlb")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("inspectionNote", "Thua 1 khac HSD",
                                "lines", List.of(Map.of("productId", brake, "passedQty", 3, "damagedQty", 0,
                                        "expiryDate", today.plusDays(60).toString()))))))
                .andExpect(status().isOk());
        confirm("bmlb", order, receipt).andExpect(status().isOk());
        Long line = jdbc.queryForObject("SELECT id FROM goods_receipt_note_line WHERE goods_receipt_note_id = ?",
                Long.class, receipt);
        mvc.perform(get("/api/v1/goods-receipt-notes/transfer-excess")
                        .header(HttpHeaders.AUTHORIZATION, signIn("owner")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].lines[0].blockedCode").value("MSG341"))
                .andExpect(jsonPath("$[0].lines[0].lots.length()").value(0));
        decide(order, receipt, json(Map.of("lines", List.of(Map.of("lineId", line, "decision", "ACCEPTED")))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG341"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        today.plusDays(60).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")))));
        receiptView("owner", order, receipt).andExpect(jsonPath("$.lines[0].excessDecision").value("AWAITING_OWNER"));
        decide(order, receipt, json(Map.of("lines", List.of(Map.of("lineId", line, "decision", "REJECTED")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].excessDecision").value("REJECTED"));
        assertThat(stock(source, brake, "available_qty")).isEqualTo(8);
        assertThat(stock(dest, brake, "available_qty")).isEqualTo(2);
        action("owner", order, "/close").andExpect(status().isOk());
    }

    @Test
    void stockDocumentsSeparateDirectionsAndSkipCountStatusChanges() throws Exception {
        long brakes = lot(source, brake, 10, 100_000, null, "A");
        long chains = lot(source, chain, 10, 100_000, null, "B");
        long period = jdbc.queryForObject("SELECT id FROM inventory_period WHERE status = 'OPEN'", Long.class);
        long count = jdbc.queryForObject("""
                INSERT INTO stock_count (store_id, inventory_period_id, count_type, count_date, scope_type, created_by)
                VALUES (?, ?, 'CYCLE', ?, 'WHOLE', ?) RETURNING id
                """, Long.class, source, period, today, owner.getId());
        countMovement(count, period, brake, brakes, "AVAILABLE", -2);
        countMovement(count, period, brake, brakes, "DAMAGED", 2);
        countMovement(count, period, chain, chains, "AVAILABLE", -1);

        mvc.perform(get("/api/v1/stock-documents").header(HttpHeaders.AUTHORIZATION, signIn("bmcg"))
                        .param("kind", "STOCK_COUNT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].direction").value("OUT"))
                .andExpect(jsonPath("$.items[0].code").value("KK-%04d".formatted(count)))
                .andExpect(jsonPath("$.items[0].quantity").value(1))
                .andExpect(jsonPath("$.items[0].value").doesNotExist());
        // The code shown is what the search finds.
        mvc.perform(get("/api/v1/stock-documents").header(HttpHeaders.AUTHORIZATION, signIn("bmcg"))
                        .param("q", "kk-%04d".formatted(count)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1));
        mvc.perform(get("/api/v1/stock-documents/STOCK_COUNT/" + count)
                        .header(HttpHeaders.AUTHORIZATION, signIn("owner"))
                        .param("storeId", String.valueOf(source)).param("direction", "OUT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].productId").value(chain));
    }

    @Test
    void aCleanTransferHasNothingToAcknowledgeAndClosesAsBefore() throws Exception {
        lot(source, brake, 5, 100_000, null, "A");
        long order = dispatchedOrder(brake, 5);
        confirm("bmlb", order, inspected(order, brake, 5, 0)).andExpect(status().isOk());
        action("bmlb", order, "/discrepancy-acknowledgement").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG29"));
        action("bmkt", order, "/close").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        assertThat(stock(dest, brake, "available_qty")).isEqualTo(5);
    }

    @Test
    void excessWithoutARecordedExpiryTakesTheOldestLotsOfTheSource() throws Exception {
        long earlier = lot(source, brake, 3, 100_000, today.plusDays(30), "A");
        long later = lot(source, brake, 5, 100_000, today.plusDays(60), "B");
        long order = dispatchedOrder(brake, 2);
        long receipt = arrive("bmlb", order);
        assign("bmlb", "INSPECTION", receipt, destStaff).andExpect(status().isOk());
        mvc.perform(put(ORDERS + "/" + order + "/receipts/" + receipt + "/result")
                        .header(HttpHeaders.AUTHORIZATION, signIn("nvlb")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("inspectionNote", "Thua 2",
                                "lines", List.of(Map.of("productId", brake, "passedQty", 4, "damagedQty", 0))))))
                .andExpect(status().isOk());
        confirm("bmlb", order, receipt).andExpect(status().isOk());
        Long line = jdbc.queryForObject("SELECT id FROM goods_receipt_note_line WHERE goods_receipt_note_id = ?",
                Long.class, receipt);
        decide(order, receipt, json(Map.of("lines", List.of(Map.of("lineId", line, "decision", "ACCEPTED")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].excessDecision").value("ACCEPTED"));
        assertThat(lotStock(earlier, source, "AVAILABLE")).isZero();
        assertThat(lotStock(later, source, "AVAILABLE")).isEqualTo(4);
        assertThat(stock(dest, brake, "available_qty")).isEqualTo(4);
    }

    @Test
    void stockDocumentsKeepEachUserToTheirWarehouseAndHideValuesFromAllButTheOwner() throws Exception {
        lot(source, brake, 5, 100_000, null, "A");
        long order = dispatchedOrder(brake, 5);
        createUser("qtv", Role.ADMIN, null);

        mvc.perform(get("/api/v1/stock-documents").header(HttpHeaders.AUTHORIZATION, signIn("qtv")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/stock-documents").header(HttpHeaders.AUTHORIZATION, signIn("bmlb"))
                        .param("storeId", String.valueOf(source)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        // In transit is not stock of the destination yet: nothing there until the receipt.
        mvc.perform(get("/api/v1/stock-documents").header(HttpHeaders.AUTHORIZATION, signIn("nvlb")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get("/api/v1/stock-documents").header(HttpHeaders.AUTHORIZATION, signIn("nvcg"))
                        .param("direction", "OUT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].kind").value("TRANSFER_DISPATCH"))
                .andExpect(jsonPath("$.items[0].sourceId").value(order))
                .andExpect(jsonPath("$.items[0].quantity").value(5))
                .andExpect(jsonPath("$.items[0].value").doesNotExist());
        mvc.perform(get("/api/v1/stock-documents").header(HttpHeaders.AUTHORIZATION, signIn("owner"))
                        .param("storeId", String.valueOf(source)).param("direction", "OUT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].value").value(500000));
        mvc.perform(get("/api/v1/stock-documents/TRANSFER_ORDER/" + order)
                        .header(HttpHeaders.AUTHORIZATION, signIn("owner")).param("direction", "OUT"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG02"));
        mvc.perform(get("/api/v1/stock-documents/TRANSFER_ORDER/" + order)
                        .header(HttpHeaders.AUTHORIZATION, signIn("bmcg")).param("direction", "OUT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].quantity").value(5))
                .andExpect(jsonPath("$.lines[0].unitCost").doesNotExist());
    }

    private void countMovement(long count, long period, long product, long lot, String status, int amount) {
        jdbc.update("""
                INSERT INTO stock_movement (store_id, product_id, lot_id, inventory_period_id, stock_status,
                    quantity_change, unit_cost, value, movement_type, stock_count_id, performed_by, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, 100000, ?, 'COUNT_ADJUSTMENT', ?, ?, now())
                """, source, product, lot, period, status, amount, 100_000L * amount, count, owner.getId());
    }

    private ResultActions action(String username, long order, String action) throws Exception {
        return mvc.perform(post(ORDERS + "/" + order + action).header(HttpHeaders.AUTHORIZATION, signIn(username)));
    }

    private ResultActions decide(long order, long receipt, String body) throws Exception {
        return mvc.perform(post(ORDERS + "/" + order + "/receipts/" + receipt + "/excess-decision")
                .header(HttpHeaders.AUTHORIZATION, signIn("owner")).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
