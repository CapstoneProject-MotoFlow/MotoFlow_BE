package fu.hl.g64.motorflow.perf;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.receiving.GoodsReceiptTestData;
import fu.hl.g64.motorflow.sales.SalesOrderTestData;
import fu.hl.g64.motorflow.stockcount.StockCountTestData;
import fu.hl.g64.motorflow.support.QueryCounter;
import fu.hl.g64.motorflow.transfer.TransferFlowTestData;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Baseline executed-SQL and JDBC round-trip measurements (via {@link QueryCounter#executed()} and
 * {@link QueryCounter#roundTrips()}, backed by {@code datasource-proxy}) for the heaviest write and common read flows.
 * Every ceiling is {@code ceil(baseline * 1.1)} to prevent regressions.
 */
class SqlBaselineTest {

    private static void assertBudget(String flow, QueryCounter queries, long executedBaseline,
                                     long roundTripsBaseline) {
        long executed = queries.executed();
        long roundTrips = queries.roundTrips();
        long executedCeiling = (long) Math.ceil(executedBaseline * 1.1);
        long roundTripsCeiling = (long) Math.ceil(roundTripsBaseline * 1.1);
        System.out.printf("[SQL] %-32s: %s (executed=%d/%d/%d, roundTrips=%d/%d/%d)%n",
                flow, queries.summary(), executed, executedBaseline, executedCeiling,
                roundTrips, roundTripsBaseline, roundTripsCeiling);
        assertThat(executed).as("Executed SQL count for %s (%s)", flow, queries.summary())
                .isPositive()
                .isLessThanOrEqualTo(executedCeiling);
        assertThat(roundTrips).as("Round-trip count for %s (%s)", flow, queries.summary())
                .isPositive()
                .isLessThanOrEqualTo(roundTripsCeiling);
    }

    @Nested
    class GrnFlows extends GoodsReceiptTestData {

        @Autowired
        private QueryCounter queries;

        @Test
        void grnConfirm20Lines() throws Exception {
            long category = id("SELECT min(id) FROM part_category");
            List<Map<String, Object>> poLines = new ArrayList<>();
            poLines.add(line(pad, 5, 120_000));
            poLines.add(line(oil, 5, 85_000));
            poLines.add(line(lamp, 5, 95_000));
            poLines.add(line(filter, 5, 65_000));
            for (int i = 5; i <= 20; i++) {
                long extra = part("GRN-P-" + i, "Phụ tùng nhập " + i, category);
                poLines.add(line(extra, 5, 50_000 + i * 1_000L));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object>[] poLineArray = poLines.toArray(Map[]::new);
            long poId = created(order(honda, giaLam, TODAY, null, poLineArray));
            long receiptId = createdReceipt(poId);
            assign(receiptId, staff);

            List<Long> lineIds = jdbc.queryForList(
                    "SELECT id FROM goods_receipt_note_line WHERE goods_receipt_note_id = ? ORDER BY id",
                    Long.class, receiptId);
            @SuppressWarnings("unchecked")
            Map<String, Object>[] findingLines = lineIds.stream()
                    .map(id -> counted(id, 5, 5, 0, 0))
                    .toArray(Map[]::new);
            record(staffToken, receiptId, findings(findingLines)).andExpect(status().isOk());

            queries.reset();
            confirm(managerToken, receiptId).andExpect(status().isOk());
            assertBudget("grn-confirm-20-lines", queries, 145, 107);
        }

        @Test
        void discrepancyHandling3Cases() throws Exception {
            long poId = created(order(honda, giaLam, TODAY, null, line(pad, 5, 120_000), line(oil, 5, 85_000),
                    line(lamp, 5, 95_000)));
            long receiptId = createdReceipt(poId);
            assign(receiptId, staff);
            record(staffToken, receiptId, findings(counted(lineOf(receiptId, pad), 5, 5, 2, 1),
                    counted(lineOf(receiptId, oil), 5, 5, 2, 1), counted(lineOf(receiptId, lamp), 5, 5, 2, 1)))
                    .andExpect(status().isOk());
            confirm(managerToken, receiptId).andExpect(status().isOk());
            // Each case: 2 wrong items and 1 damaged part go back to the supplier, and 3 are replaced.
            List<Map<String, Object>> handling = List.of(handling(caseOf(receiptId, pad), 3, 3),
                    handling(caseOf(receiptId, oil), 3, 3), handling(caseOf(receiptId, lamp), 3, 3));

            queries.reset();
            handle(managerToken, receiptId, handling).andExpect(status().isOk());
            assertBudget("discrepancy-save-handling-3-cases", queries, 142, 136);
        }

        private long createdReceipt(long poId) throws Exception {
            String response = createReceipt(managerToken, poId, deliveryNote("PX-20"))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            return ((Number) JsonPath.read(response, "$.id")).longValue();
        }
    }

    @Nested
    class StockCountFlows extends StockCountTestData {

        private static final String COUNTS = "/api/v1/stock-counts";

        @Autowired
        private QueryCounter queries;

        @Test
        void openingCountApprove50Lines() throws Exception {
            createUser("staff.lb", Role.STAFF, otherStore);
            String lbStaffToken = signIn("staff.lb");
            long staffLbId = id("SELECT id FROM user_account WHERE username = 'staff.lb'");

            List<Map<String, Object>> openingLines = new ArrayList<>();
            List<Map<String, Object>> openingCosts = new ArrayList<>();
            for (int i = 1; i <= 50; i++) {
                long productId = part("OP-" + i, "Phụ tùng đầu kỳ " + i, categoryA, null);
                Map<String, Object> line = new HashMap<>();
                line.put("productId", productId);
                line.put("expiryDate", null);
                line.put("countedQty", 10);
                line.put("damagedQty", 0);
                openingLines.add(line);
                openingCosts.add(Map.of("productId", productId, "initialUnitCost", 50_000 + i * 500L));
            }

            String createdBody = postAs(otherManagerToken, COUNTS,
                    Map.of("countType", "OPENING", "countDate", TODAY.toString(), "scopeType", "WHOLE"))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            long countId = ((Number) JsonPath.read(createdBody, "$.id")).longValue();

            postAs(otherManagerToken, "/api/v1/warehouse-tasks", Map.of(
                    "taskType", "COUNTING", "documentId", countId, "assigneeId", staffLbId))
                    .andExpect(status().isOk());
            postAs(lbStaffToken, COUNTS + "/" + countId + "/start", null).andExpect(status().isOk());
            putAs(lbStaffToken, COUNTS + "/" + countId + "/opening-lines", Map.of("lines", openingLines))
                    .andExpect(status().isOk());
            postAs(lbStaffToken, COUNTS + "/" + countId + "/submit", null).andExpect(status().isOk());
            postAs(otherManagerToken, COUNTS + "/" + countId + "/confirm", null).andExpect(status().isOk());
            putAs(ownerToken, COUNTS + "/" + countId + "/opening-costs", Map.of("costs", openingCosts))
                    .andExpect(status().isOk());

            queries.reset();
            postAs(ownerToken, COUNTS + "/" + countId + "/approve", null).andExpect(status().isOk());
            assertBudget("opening-count-approve-50-lines", queries, 325, 227);
        }
    }

    @Nested
    class SalesAndCommonFlows extends SalesOrderTestData {

        @Autowired
        private QueryCounter queries;

        @Test
        void salesComplete10Lines() throws Exception {
            long[] products = tenStockedParts();
            long[] createPairs = new long[20];
            Object[] pickArgs = new Object[30];
            long[] confirmPairs = new long[20];
            for (int i = 0; i < 10; i++) {
                createPairs[i * 2] = products[i];
                createPairs[i * 2 + 1] = 1;
                pickArgs[i * 3] = products[i];
                pickArgs[i * 3 + 1] = 1;
                pickArgs[i * 3 + 2] = null;
                confirmPairs[i * 2] = products[i];
                confirmPairs[i * 2 + 1] = 1;
            }
            long orderId = createOrder(createPairs);
            assign(managerToken, "PICKING", orderId, staff).andExpect(status().isOk());
            pick(staffToken, orderId, 1, pickArgs).andExpect(status().isOk());
            String confirmBody = confirm(managerToken, orderId, 1, confirmPairs)
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            long payable = ((Number) JsonPath.read(confirmBody, "$.money.payableNow")).longValue();

            queries.reset();
            mvc.perform(post("/api/v1/sales-orders/" + orderId + "/payments")
                            .header(HttpHeaders.AUTHORIZATION, managerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "amount", payable,
                                    "paymentMethod", "CASH",
                                    "verified", true,
                                    "expectedPayable", payable))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.completed").value(true));
            assertBudget("sales-complete-10-lines", queries, 79, 61);
        }

        @Test
        void salesDraftCreateAndUpdate10Lines() throws Exception {
            long[] products = tenPricedParts();
            long[] createPairs = new long[20];
            long[] updatePairs = new long[20];
            for (int i = 0; i < 10; i++) {
                createPairs[i * 2] = products[i];
                createPairs[i * 2 + 1] = 1;
                updatePairs[i * 2] = products[i];
                updatePairs[i * 2 + 1] = 2;
            }
            Map<String, Object> createBody = order(null, customer, "RETAIL", null, lines(createPairs));
            Map<String, Object> updateBody = order(null, customer, "RETAIL", null, lines(updatePairs));

            queries.reset();
            String created = mvc.perform(post("/api/v1/sales-orders/drafts")
                            .header(HttpHeaders.AUTHORIZATION, managerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(createBody)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            assertBudget("sales-draft-create-10-lines", queries, 25, 25);
            long draftId = ((Number) JsonPath.read(created, "$.id")).longValue();

            queries.reset();
            mvc.perform(put("/api/v1/sales-orders/drafts/" + draftId)
                            .header(HttpHeaders.AUTHORIZATION, managerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(updateBody)))
                    .andExpect(status().isOk());
            assertBudget("sales-draft-update-10-lines", queries, 25, 25);
        }

        @Test
        void homeBmAndAdmin() throws Exception {
            createUser("admin01", Role.ADMIN, null);
            String adminToken = signIn("admin01");

            queries.reset();
            mvc.perform(get("/api/v1/home").header(HttpHeaders.AUTHORIZATION, managerToken))
                    .andExpect(status().isOk());
            assertBudget("home-bm", queries, 10, 10);

            queries.reset();
            mvc.perform(get("/api/v1/home").header(HttpHeaders.AUTHORIZATION, adminToken))
                    .andExpect(status().isOk());
            assertBudget("home-admin", queries, 3, 3);
        }

        @Test
        void documentsPage0Partial() throws Exception {
            long orderId = createOrder(pad, 2, oil, 1);
            assign(managerToken, "PICKING", orderId, staff).andExpect(status().isOk());
            pick(staffToken, orderId, 1, pad, 2, null, oil, 1, null).andExpect(status().isOk());
            confirm(managerToken, orderId, 1, pad, 2, oil, 1).andExpect(status().isOk());
            mvc.perform(post("/api/v1/sales-orders/" + orderId + "/payments")
                            .header(HttpHeaders.AUTHORIZATION, managerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "amount", 285_000,
                                    "paymentMethod", "CASH",
                                    "verified", true,
                                    "expectedPayable", 285_000))))
                    .andExpect(status().isOk());

            queries.reset();
            mvc.perform(get("/api/v1/documents").header(HttpHeaders.AUTHORIZATION, managerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems").value(2));
            assertBudget("documents-page-0", queries, 2, 2);
        }

        @Test
        void productCreateWith2Oems() throws Exception {
            long categoryId = id("SELECT min(id) FROM part_category");
            Map<String, Object> body = Map.of(
                    "sku", "PERF-OEM-01",
                    "partName", "Phụ tùng đo SQL",
                    "categoryId", categoryId,
                    "brand", "Honda",
                    "baseUnit", "cái",
                    "oemCodes", List.of("OEM-SQL-01", "OEM-SQL-02"));

            queries.reset();
            mvc.perform(post("/api/v1/products")
                            .header(HttpHeaders.AUTHORIZATION, ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body)))
                    .andExpect(status().isCreated());
            assertBudget("product-create-2-oem", queries, 12, 12);
        }

        private long[] tenPricedParts() {
            long categoryId = id("SELECT min(id) FROM part_category");
            long[] ids = new long[10];
            ids[0] = pad;
            ids[1] = oil;
            for (int i = 2; i < 10; i++) {
                ids[i] = part("SL-P-" + i, "Phụ tùng bán " + i, categoryId);
                price(ids[i], 100_000, TODAY.minusDays(12).atStartOfDay());
            }
            return ids;
        }

        private long[] tenStockedParts() {
            long[] ids = tenPricedParts();
            for (int i = 2; i < 10; i++) {
                lot(ids[i], 10, 60_000, null);
            }
            return ids;
        }
    }

    @Nested
    class TransferFlows extends TransferFlowTestData {

        @Autowired
        private QueryCounter queries;

        @Test
        void transferDispatch10Lines() throws Exception {
            long categoryId = jdbc.queryForObject("SELECT min(id) FROM part_category", Long.class);
            long[] products = new long[10];
            products[0] = brake;
            products[1] = chain;
            for (int i = 2; i < 10; i++) {
                products[i] = product("TR-P-" + i, "Phụ tùng chuyển " + i, categoryId);
            }
            long[] orderPairs = new long[20];
            List<Map<String, Object>> dispatchLines = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                lot(source, products[i], 10, 50_000, null, "TR-LOT-" + i);
                orderPairs[i * 2] = products[i];
                orderPairs[i * 2 + 1] = 2;
                dispatchLines.add(Map.of("productId", products[i], "dispatchedQty", 2));
            }
            long orderId = createOrder(orderPairs);
            assign("bmcg", "DISPATCH_PREPARATION", orderId, sourceStaff).andExpect(status().isOk());
            prepare("nvcg", orderId, orderPairs).andExpect(status().isOk());
            String bmcgToken = signIn("bmcg");

            queries.reset();
            mvc.perform(post(ORDERS + "/" + orderId + "/dispatch")
                            .header(HttpHeaders.AUTHORIZATION, bmcgToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("lines", dispatchLines))))
                    .andExpect(status().isOk());
            assertBudget("transfer-dispatch-10-lines", queries, 111, 64);
        }
    }
}
