package fu.hl.g64.motorflow.inventory;

import fu.hl.g64.motorflow.transfer.TransferFlowTestData;
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

/**
 * Stock documents (Q8) of a transfer and of a stock count: both directions of a transfer, an excess sent back from the
 * source, a count with two steps on one lot, and the paging of the list. The detail of every row is checked against
 * the list by {@link StockDocumentChecks}.
 */
class StockDocumentApiTest extends TransferFlowTestData {

    private static final String DOCUMENTS = "/api/v1/stock-documents";

    @Test
    void transferDocumentsAreOutAtTheSourceAndInAtTheDestination() throws Exception {
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
        confirm("bmlb", order, receipt).andExpect(status().isOk());
        Long line = jdbc.queryForObject("SELECT id FROM goods_receipt_note_line WHERE goods_receipt_note_id = ?",
                Long.class, receipt);
        decide(order, receipt, json(Map.of("lines", List.of(Map.of("lineId", line, "decision", "ACCEPTED")))))
                .andExpect(status().isOk());
        action("owner", order, "/close").andExpect(status().isOk());

        // The dispatch: 2 out of the source, under the transfer order.
        mvc.perform(get(DOCUMENTS).header(HttpHeaders.AUTHORIZATION, signIn("owner"))
                        .param("type", "TRANSFER_ORDER").param("direction", "OUT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].kind").value("TRANSFER_DISPATCH"))
                .andExpect(jsonPath("$.items[0].sourceId").value(order))
                .andExpect(jsonPath("$.items[0].storeId").value(source))
                .andExpect(jsonPath("$.items[0].quantity").value(2));
        // The receipt at the destination: IN, 4 in all with the accepted excess.
        mvc.perform(get(DOCUMENTS).header(HttpHeaders.AUTHORIZATION, signIn("owner"))
                        .param("type", "GOODS_RECEIPT_NOTE").param("direction", "IN").param("storeId", String.valueOf(dest)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].kind").value("TRANSFER_RECEIPT"))
                .andExpect(jsonPath("$.items[0].sourceId").value(receipt))
                .andExpect(jsonPath("$.items[0].quantity").value(4));
        // The excess sent back from the source: a receipt document with an OUT direction at the source.
        mvc.perform(get(DOCUMENTS).header(HttpHeaders.AUTHORIZATION, signIn("owner"))
                        .param("type", "GOODS_RECEIPT_NOTE").param("direction", "OUT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].kind").value("TRANSFER_EXCESS_DISPATCH"))
                .andExpect(jsonPath("$.items[0].sourceId").value(receipt))
                .andExpect(jsonPath("$.items[0].storeId").value(source))
                .andExpect(jsonPath("$.items[0].quantity").value(2));
        mvc.perform(get(DOCUMENTS + "/GOODS_RECEIPT_NOTE/" + receipt).header(HttpHeaders.AUTHORIZATION, signIn("owner"))
                        .param("storeId", String.valueOf(source)).param("direction", "OUT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.lines[0].lotId").value(matching))
                .andExpect(jsonPath("$.lines[0].value").value(200_000));
        assertThat(lotStock(earlier, source, "AVAILABLE")).isEqualTo(1);
        assertThat(lotStock(matching, source, "AVAILABLE")).isEqualTo(3);
        // Two lots received at the source, the dispatch, the receipt at the destination and the excess sent back.
        assertThat(StockDocumentChecks.assertDetailsMatchTheList(mvc, jsonMapper, signIn("owner")))
                .isEqualTo(5);
    }

    @Test
    void aCountWithTwoStepsOnOneLotIsOneLineWithTheSumOfTheSteps() throws Exception {
        long chainLot = lot(source, chain, 10, 100_000, null, "C");
        long period = jdbc.queryForObject("SELECT id FROM inventory_period WHERE status = 'OPEN'", Long.class);
        long count = jdbc.queryForObject("""
                INSERT INTO stock_count (store_id, inventory_period_id, count_type, count_date, scope_type, created_by)
                VALUES (?, ?, 'CYCLE', ?, 'WHOLE', ?) RETURNING id
                """, Long.class, source, period, today, owner.getId());
        countMovement(count, period, chain, chainLot, -2);
        countMovement(count, period, chain, chainLot, -1);

        mvc.perform(get(DOCUMENTS).header(HttpHeaders.AUTHORIZATION, signIn("owner"))
                        .param("type", "STOCK_COUNT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].kind").value("STOCK_COUNT"))
                .andExpect(jsonPath("$.items[0].direction").value("OUT"))
                .andExpect(jsonPath("$.items[0].code").value("KK-%04d".formatted(count)))
                .andExpect(jsonPath("$.items[0].lineCount").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(3));
        mvc.perform(get(DOCUMENTS + "/STOCK_COUNT/" + count).header(HttpHeaders.AUTHORIZATION, signIn("owner"))
                        .param("storeId", String.valueOf(source)).param("direction", "OUT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].lotId").value(chainLot))
                .andExpect(jsonPath("$.lines[0].quantity").value(3))
                .andExpect(jsonPath("$.lines[0].value").value(300_000));
        assertThat(StockDocumentChecks.assertDetailsMatchTheList(mvc, jsonMapper, signIn("owner")))
                .isEqualTo(2);
    }

    @Test
    void pagingCountsEveryMatchingDocumentNotOnlyThePage() throws Exception {
        lot(source, brake, 10, 50_000, null, "P-A");
        lot(source, chain, 10, 50_000, null, "P-B");
        lot(dest, brake, 10, 50_000, null, "P-C");
        dispatchedOrder(brake, 2);
        String owner = signIn("owner");

        // Three IN documents (one per lot, one of them at the destination) and one OUT (the dispatch) in all.
        mvc.perform(get(DOCUMENTS).header(HttpHeaders.AUTHORIZATION, owner).param("direction", "IN")
                        .param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(get(DOCUMENTS).header(HttpHeaders.AUTHORIZATION, owner).param("direction", "IN")
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items.length()").value(1));
        // Past the last page: no rows, but the total is still the full count.
        mvc.perform(get(DOCUMENTS).header(HttpHeaders.AUTHORIZATION, owner).param("direction", "IN")
                        .param("page", "5").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get(DOCUMENTS).header(HttpHeaders.AUTHORIZATION, owner).param("page", "9").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(4))
                .andExpect(jsonPath("$.items.length()").value(0));
        // A warehouse with no stock documents: page 0 is empty and the total is 0.
        long empty = createStore("Kho trống");
        mvc.perform(get(DOCUMENTS).header(HttpHeaders.AUTHORIZATION, owner).param("storeId", String.valueOf(empty)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
        assertThat(StockDocumentChecks.assertDetailsMatchTheList(mvc, jsonMapper, owner)).isEqualTo(4);
    }

    private void countMovement(long count, long period, long product, long lot, int amount) {
        jdbc.update("""
                INSERT INTO stock_movement (store_id, product_id, lot_id, inventory_period_id, stock_status,
                    quantity_change, unit_cost, value, movement_type, stock_count_id, performed_by, occurred_at)
                VALUES (?, ?, ?, ?, 'AVAILABLE', ?, 100000, ?, 'COUNT_ADJUSTMENT', ?, ?, now())
                """, source, product, lot, period, amount, 100_000L * amount, count, owner.getId());
    }

    private ResultActions action(String username, long order, String action) throws Exception {
        return mvc.perform(post(ORDERS + "/" + order + action).header(HttpHeaders.AUTHORIZATION, signIn(username)));
    }

    private ResultActions decide(long order, long receipt, String body) throws Exception {
        return mvc.perform(post(ORDERS + "/" + order + "/receipts/" + receipt + "/excess-decision")
                .header(HttpHeaders.AUTHORIZATION, signIn("owner")).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
