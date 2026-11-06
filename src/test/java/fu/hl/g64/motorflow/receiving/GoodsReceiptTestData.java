package fu.hl.g64.motorflow.receiving;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A purchase order from Honda to Gia Lâm for 10 pads at 120 000 and 24 oils at 85 000, with the calls of the GRN and
 * discrepancy APIs, shared by the receiving tests.
 */
abstract class GoodsReceiptTestData extends PurchaseOrderTestData {

    protected static final String[] STATUSES = {"AVAILABLE", "AWAITING_INSPECTION", "WRONG_ITEM", "DAMAGED",
            "AWAITING_SUPPLIER_RETURN", "IN_TRANSIT"};

    protected long order;

    @BeforeEach
    void createOrderToReceive() throws Exception {
        order = created(order(honda, giaLam, TODAY, null, line(pad, 10, 120_000), line(oil, 24, 85_000)));
    }

    /**
     * The body of UC-106: a delivery document dated today with one photo of it, for the PO's outstanding lines or for
     * the cases.
     */
    protected static Map<String, Object> deliveryNote(String documentNo, Long... replacementForCaseIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deliveryDocumentNo", documentNo);
        body.put("deliveryDocumentDate", TODAY.toString());
        body.put("delivererName", "Anh Tuấn");
        body.put("vehiclePlateNo", "29C-123.45");
        if (replacementForCaseIds.length > 0) {
            body.put("replacementForCaseIds", Arrays.asList(replacementForCaseIds));
        }
        body.put("documents", List.of(uploaded("chung-tu.jpg")));
        return body;
    }

    /** A JPG uploaded with an ATTACHMENT signature, under a key no other test file uses. */
    protected static Map<String, Object> uploaded(String fileName) {
        return Map.of("storageKey", "motoflow/attachments/" + UUID.randomUUID(), "fileName", fileName,
                "contentType", "image/jpeg", "fileSize", 100_000);
    }

    protected ResultActions createReceipt(String token, long purchaseOrder, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/purchase-orders/" + purchaseOrder + "/goods-receipt-notes")
                .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    /** The Branch Manager of Gia Lâm records a delivery of {@link #order}; the GRN number. */
    protected long receive(String documentNo, Long... replacementForCaseIds) throws Exception {
        String response = createReceipt(managerToken, order, deliveryNote(documentNo, replacementForCaseIds))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    /** UC-86: the Branch Manager of Gia Lâm gives the inspection to {@code assignee}. */
    protected void assign(long receipt, UserAccount assignee) throws Exception {
        assign(managerToken, receipt, assignee);
    }

    /** UC-86: the Branch Manager signed in with {@code token} gives the inspection to {@code assignee}. */
    protected void assign(String token, long receipt, UserAccount assignee) throws Exception {
        Map<String, Object> body = Map.of("taskType", "INSPECTION", "documentId", receipt, "assigneeId",
                assignee.getId());
        mvc.perform(post("/api/v1/warehouse-tasks").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isOk());
    }

    protected static Map<String, Object> counted(long lineId, Integer documentQty, Integer deliveredQty,
                                                 Integer wrongItemQty, Integer damagedQty) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("lineId", lineId);
        line.put("documentQty", documentQty);
        line.put("deliveredQty", deliveredQty);
        line.put("wrongItemQty", wrongItemQty);
        line.put("damagedQty", damagedQty);
        return line;
    }

    @SafeVarargs
    protected static Map<String, Object> findings(Map<String, Object>... lines) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lines", Arrays.asList(lines));
        return body;
    }

    protected ResultActions record(String token, long receipt, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/goods-receipt-notes/" + receipt + "/result")
                .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    protected ResultActions confirm(String token, long receipt) throws Exception {
        return mvc.perform(post("/api/v1/goods-receipt-notes/" + receipt + "/confirm")
                .header(HttpHeaders.AUTHORIZATION, token));
    }

    protected ResultActions recount(String token, long receipt, String reason) throws Exception {
        return mvc.perform(post("/api/v1/goods-receipt-notes/" + receipt + "/recount")
                .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("reason", reason))));
    }

    /** UC-45 with line id, decision, line id, decision... */
    protected ResultActions decide(String token, long receipt, Object... lineAndDecision) throws Exception {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 0; i < lineAndDecision.length; i += 2) {
            lines.add(Map.of("lineId", lineAndDecision[i], "decision", lineAndDecision[i + 1]));
        }
        return mvc.perform(post("/api/v1/goods-receipt-notes/" + receipt + "/excess-decision")
                .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("lines", lines))));
    }

    protected ResultActions receipt(String token, long receipt) throws Exception {
        return mvc.perform(get("/api/v1/goods-receipt-notes/" + receipt).header(HttpHeaders.AUTHORIZATION, token));
    }

    /** The GRN list with query parameters given as name, value, name, value... */
    protected ResultActions receipts(String token, String... parameters) throws Exception {
        var request = get("/api/v1/goods-receipt-notes").header(HttpHeaders.AUTHORIZATION, token);
        for (int i = 0; i < parameters.length; i += 2) {
            request.param(parameters[i], parameters[i + 1]);
        }
        return mvc.perform(request);
    }

    /** UC-46 handling with case id, quantity to return, case id, quantity...; the replacement left empty. */
    protected ResultActions handle(String token, long receipt, Object... caseAndReturn) throws Exception {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 0; i < caseAndReturn.length; i += 2) {
            lines.add(handling(caseAndReturn[i], caseAndReturn[i + 1], null));
        }
        return handle(token, receipt, lines);
    }

    /** One line of UC-46 handling; a null quantity is sent as null. */
    protected static Map<String, Object> handling(Object caseId, Object returnQty, Object replacementQty) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("caseId", caseId);
        line.put("returnRequiredQty", returnQty);
        if (replacementQty != null) {
            line.put("replacementRequiredQty", replacementQty);
        }
        return line;
    }

    /** UC-46 handling with the given lines. */
    protected ResultActions handle(String token, long receipt, List<Map<String, Object>> lines) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lines", lines);
        body.put("note", "Đã thống nhất với NCC");
        return mvc.perform(put("/api/v1/goods-receipt-notes/" + receipt + "/discrepancies")
                .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    /** UC-46 Step 1_Actual supplier return; a body with no {@code evidence} key gets one photo of the handover. */
    protected ResultActions supplierReturn(String token, long discrepancy, Map<String, Object> body)
            throws Exception {
        body = new LinkedHashMap<>(body);
        body.putIfAbsent("evidence", List.of(uploaded("bien-ban.jpg")));
        return mvc.perform(post("/api/v1/discrepancy-cases/" + discrepancy + "/supplier-returns")
                .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    protected ResultActions waive(String token, long discrepancy, Integer quantity, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("quantity", quantity);
        body.put("reason", reason);
        return mvc.perform(post("/api/v1/discrepancy-cases/" + discrepancy + "/waive-replacement")
                .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    protected ResultActions discrepancy(String token, long discrepancy) throws Exception {
        return mvc.perform(get("/api/v1/discrepancy-cases/" + discrepancy).header(HttpHeaders.AUTHORIZATION, token));
    }

    protected ResultActions discrepancies(String token, String... parameters) throws Exception {
        var request = get("/api/v1/discrepancy-cases").header(HttpHeaders.AUTHORIZATION, token);
        for (int i = 0; i < parameters.length; i += 2) {
            request.param(parameters[i], parameters[i + 1]);
        }
        return mvc.perform(request);
    }

    /** The line of {@code product} on the GRN. */
    protected long lineOf(long receipt, long product) {
        return id("SELECT id FROM goods_receipt_note_line WHERE goods_receipt_note_id = ? AND product_id = ?",
                receipt, product);
    }

    /** The discrepancy case of {@code product} on the GRN. */
    protected long caseOf(long receipt, long product) {
        return id("SELECT id FROM discrepancy_case WHERE goods_receipt_note_id = ? AND product_id = ?", receipt,
                product);
    }

    /** One status of the part at the store: AVAILABLE, DAMAGED... as stock_balance holds it. */
    protected int stock(long store, long product, String status) {
        Integer held = jdbc.query("SELECT " + status.toLowerCase() + "_qty FROM stock_balance WHERE store_id = ? "
                + "AND product_id = ?", rs -> rs.next() ? rs.getInt(1) : 0, store, product);
        return held == null ? 0 : held;
    }

    protected BigDecimal averageCost(long product) {
        return jdbc.queryForObject("SELECT moving_avg_cost FROM product WHERE id = ?", BigDecimal.class, product);
    }

    /** BR-29: in every status, stock_balance = the sum of lot_balance = the sum of the movements. */
    protected void assertStockAgrees(long store, long product) {
        for (String status : STATUSES) {
            long lots = jdbc.queryForObject("SELECT coalesce(sum(b.quantity), 0) FROM lot_balance b JOIN lot l "
                    + "ON l.id = b.lot_id WHERE b.store_id = ? AND l.product_id = ? AND b.stock_status = ?",
                    Long.class, store, product, status);
            long moved = jdbc.queryForObject("SELECT coalesce(sum(quantity_change), 0) FROM stock_movement "
                    + "WHERE store_id = ? AND product_id = ? AND stock_status = ?", Long.class, store, product, status);
            assertThat(lots).as("lots in %s", status).isEqualTo(stock(store, product, status));
            assertThat(moved).as("movements in %s", status).isEqualTo(stock(store, product, status));
        }
    }

    /** [movement type, status, quantity] of the part's movements, in the order they were written. */
    protected List<List<Object>> movementsOf(long product) {
        return jdbc.query("SELECT movement_type, stock_status, quantity_change FROM stock_movement WHERE product_id = ? "
                + "ORDER BY id", (rs, i) -> List.<Object>of(rs.getString(1), rs.getString(2), rs.getInt(3)), product);
    }

    protected static Integer[] ids(long... ids) {
        return Arrays.stream(ids).mapToObj(id -> (int) id).toArray(Integer[]::new);
    }
}
