package fu.hl.g64.motorflow.transfer;

import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-106, UC-43, UC-44 for a transfer arriving at its destination. */
class TransferReceiptApiTest extends TransferFlowTestData {

    @Test
    void fullReceiptMovesTheOrdersLotsIntoAvailable() throws Exception {
        long soon = lot(source, brake, 3, 100_000, today.plusDays(10), "SOM");
        long later = lot(source, brake, 10, 120_000, today.plusDays(90), "MUON");
        long order = dispatchedOrder(brake, 5);

        long receipt = arrive("bmlb", order);
        receiptView("nvlb", order, receipt).andExpect(status().isForbidden());
        assign("bmlb", "INSPECTION", receipt, destStaff).andExpect(status().isOk());
        receiptView("nvlb", order, receipt).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].documentQty").value(5));
        result("nvlb", order, receipt, brake, 5, 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_CONFIRMATION"));
        confirm("bmlb", order, receipt).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        assertThat(stock(dest, brake, "available_qty")).isEqualTo(5);
        assertThat(stock(dest, brake, "in_transit_qty")).isZero();
        assertThat(lotStock(soon, dest, "AVAILABLE")).isEqualTo(3);
        assertThat(lotStock(later, dest, "AVAILABLE")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE movement_type = 'TRANSFER_RECEIPT' "
                + "AND goods_receipt_note_id = ?", Long.class, receipt)).isEqualTo(4);
        view("bmkt", "/" + order).andExpect(jsonPath("$.status").value("RECONCILING"))
                .andExpect(jsonPath("$.lines[0].receivedQty").value(5))
                .andExpect(jsonPath("$.lines[0].unresolvedQty").value(0))
                .andExpect(jsonPath("$.discrepancies").isEmpty());
        assertThat(recipients("TRANSFER_RECEIVED")).containsExactly(coordinatorId());
        assertThat(recipients("TRANSFER_DISCREPANCY")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM warehouse_task WHERE goods_receipt_note_id = ?",
                String.class, receipt)).isEqualTo("DONE");

        confirm("bmlb", order, receipt).andExpect(status().isOk());
        assertThat(stock(dest, brake, "available_qty")).isEqualTo(5);
        arrive(order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG210"));
    }

    @Test
    void shortReceiptOpensACaseAndTheRestStaysInTransit() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = dispatchedOrder(brake, 5);

        long receipt = inspected(order, brake, 3, 0);
        confirm("bmlb", order, receipt).andExpect(status().isOk());

        assertThat(stock(dest, brake, "available_qty")).isEqualTo(3);
        assertThat(stock(dest, brake, "in_transit_qty")).isEqualTo(2);
        view("owner", "/" + order).andExpect(jsonPath("$.lines[0].unresolvedQty").value(2))
                .andExpect(jsonPath("$.discrepancies[0].shortQty").value(2))
                .andExpect(jsonPath("$.discrepancies[0].status").value("OPEN"));
        assertThat(recipients("TRANSFER_DISCREPANCY")).containsExactlyInAnyOrder(owner.getId(), coordinatorId());

        // The rest may still arrive: the next receipt expects only what is missing, and the case is not opened twice.
        long late = arrive("bmlb", order);
        receiptView("bmlb", order, late).andExpect(jsonPath("$.lines[0].documentQty").value(2));
        arrive(order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG211"));
        assign("bmlb", "INSPECTION", late, destStaff).andExpect(status().isOk());
        result("nvlb", order, late, brake, 1, 0).andExpect(status().isOk());
        confirm("bmlb", order, late).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM discrepancy_case", Long.class)).isEqualTo(1);
        view("owner", "/" + order).andExpect(jsonPath("$.lines[0].unresolvedQty").value(1));
    }

    @Test
    void damagedGoodsOfOneLotGoToDamagedWithOneMovementOut() throws Exception {
        long only = lot(source, brake, 3, 100_000, null, "A");
        long order = dispatchedOrder(brake, 3);

        long receipt = inspected(order, brake, 2, 1);
        confirm("bmlb", order, receipt).andExpect(status().isOk());

        assertThat(lotStock(only, dest, "AVAILABLE")).isEqualTo(2);
        assertThat(lotStock(only, dest, "DAMAGED")).isEqualTo(1);
        assertThat(stock(dest, brake, "damaged_qty")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT stock_status || ':' || quantity_change FROM stock_movement WHERE "
                + "goods_receipt_note_id = ? ORDER BY quantity_change", String.class, receipt))
                .containsExactly("IN_TRANSIT:-3", "DAMAGED:1", "AVAILABLE:2");
        view("owner", "/" + order).andExpect(jsonPath("$.lines[0].receivedQty").value(3))
                .andExpect(jsonPath("$.lines[0].unresolvedQty").value(0));
    }

    @Test
    void excessStaysOutOfStockAndTheSourceIsTold() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = dispatchedOrder(brake, 2);
        long receipt = arrive("bmlb", order);
        assign("bmlb", "INSPECTION", receipt, destStaff).andExpect(status().isOk());

        result("nvlb", order, receipt, brake, 0, 3).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG212"));
        result("nvlb", order, receipt, brake, 3, 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].excessQty").value(1));
        confirm("bmlb", order, receipt).andExpect(status().isOk());

        assertThat(stock(dest, brake, "available_qty")).isEqualTo(2);
        assertThat(stock(source, brake, "available_qty")).isEqualTo(8);
        view("owner", "/" + order).andExpect(jsonPath("$.lines[0].receivedQty").value(2))
                .andExpect(jsonPath("$.lines[0].extraReceivedQty").value(1))
                .andExpect(jsonPath("$.lines[0].unresolvedQty").value(0));
        assertThat(recipients("TRANSFER_EXCESS_RECEIVED")).containsExactlyInAnyOrder(sourceManager.getId(),
                otherSourceManager.getId());
    }

    @Test
    void twoOrdersOfOnePartEachReceiveTheirOwnLots() throws Exception {
        long soon = lot(source, brake, 3, 100_000, today.plusDays(10), "SOM");
        long later = lot(source, brake, 10, 120_000, today.plusDays(90), "MUON");
        long first = dispatchedOrder(brake, 2);
        long second = dispatchedOrder(brake, 2);

        confirm("bmlb", second, inspected(second, brake, 2, 0)).andExpect(status().isOk());

        assertThat(lotStock(soon, dest, "AVAILABLE")).isEqualTo(1);
        assertThat(lotStock(later, dest, "AVAILABLE")).isEqualTo(1);
        assertThat(lotStock(soon, dest, "IN_TRANSIT")).isEqualTo(2);
        assertThat(lotStock(later, dest, "IN_TRANSIT")).isZero();

        confirm("bmlb", first, inspected(first, brake, 2, 0)).andExpect(status().isOk());
        assertThat(lotStock(soon, dest, "AVAILABLE")).isEqualTo(3);
        assertThat(stock(dest, brake, "in_transit_qty")).isZero();
    }

    @Test
    void whoMayRecordAndConfirm() throws Exception {
        UserAccount secondDestManager = createUser("bmlb2", Role.BRANCH_MANAGER, dest);
        lot(source, brake, 10, 100_000, null, "A");
        long order = createOrder(brake, 2);
        arrive(order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MSG29"));
        assign("bmcg", "DISPATCH_PREPARATION", order, sourceStaff).andExpect(status().isOk());
        prepare("nvcg", order, brake, 2).andExpect(status().isOk());
        dispatch("bmcg", order, brake, 2).andExpect(status().isOk());

        mvc.perform(post(ORDERS + "/" + order + "/receipts").header(HttpHeaders.AUTHORIZATION, signIn("bmcg")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        mvc.perform(post(ORDERS + "/" + order + "/receipts").header(HttpHeaders.AUTHORIZATION, signIn("owner")))
                .andExpect(status().isForbidden());
        long receipt = arrive("bmlb", order);
        result("nvlb", order, receipt, brake, 2, 0).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        assign("bmlb", "INSPECTION", receipt, destManager).andExpect(status().isOk());
        result("bmlb", order, receipt, brake, 1, 0).andExpect(status().isOk());

        confirm("bmlb", order, receipt).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG32"));
        confirm("bmcg", order, receipt).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        confirm("owner", order, receipt).andExpect(status().isForbidden());
        confirm("bmlb2", 999_999, receipt).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG175"));
        assertThat(stock(dest, brake, "available_qty")).isZero();

        // The second Branch Manager sends it back; the recorder counts again and the second one confirms.
        mvc.perform(post(ORDERS + "/" + order + "/receipts/" + receipt + "/recount")
                        .header(HttpHeaders.AUTHORIZATION, signIn("bmlb2")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "Đếm lại thùng 2"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AWAITING_RECOUNT"));
        assertThat(recipients("TRANSFER_RECEIPT_RECOUNT")).containsExactly(destManager.getId());
        result("bmlb", order, receipt, brake, 2, 0).andExpect(status().isOk());
        confirm("bmlb2", order, receipt).andExpect(status().isOk());
        assertThat(stock(dest, brake, "available_qty")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT confirmed_by FROM goods_receipt_note WHERE id = ?", Long.class, receipt))
                .isEqualTo(secondDestManager.getId());
        result("bmlb", order, receipt, brake, 2, 0).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------------------------------------------

    private ResultActions arrive(long order) throws Exception {
        return mvc.perform(post(ORDERS + "/" + order + "/receipts").header(HttpHeaders.AUTHORIZATION, signIn("bmlb")));
    }

    private long arrive(String manager, long order) throws Exception {
        mvc.perform(post(ORDERS + "/" + order + "/receipts").header(HttpHeaders.AUTHORIZATION, signIn(manager)))
                .andExpect(status().isCreated());
        return jdbc.queryForObject("SELECT max(id) FROM goods_receipt_note", Long.class);
    }

    /** Arrived, given to the destination Staff member and recorded: [productId, passed, damaged, ...]. */
    private long inspected(long order, long... lines) throws Exception {
        long receipt = arrive("bmlb", order);
        assign("bmlb", "INSPECTION", receipt, destStaff).andExpect(status().isOk());
        result("nvlb", order, receipt, lines).andExpect(status().isOk());
        return receipt;
    }

    private ResultActions result(String recorder, long order, long receipt, long... lines) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < lines.length; i += 3) {
            rows.add(Map.of("productId", lines[i], "passedQty", lines[i + 1], "damagedQty", lines[i + 2]));
        }
        return mvc.perform(put(ORDERS + "/" + order + "/receipts/" + receipt + "/result")
                .header(HttpHeaders.AUTHORIZATION, signIn(recorder)).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("lines", rows))));
    }

    private ResultActions confirm(String manager, long order, long receipt) throws Exception {
        return mvc.perform(post(ORDERS + "/" + order + "/receipts/" + receipt + "/confirm")
                .header(HttpHeaders.AUTHORIZATION, signIn(manager)));
    }

    private ResultActions receiptView(String username, long order, long receipt) throws Exception {
        return mvc.perform(get(ORDERS + "/" + order + "/receipts/" + receipt)
                .header(HttpHeaders.AUTHORIZATION, signIn(username)));
    }

    private List<Long> recipients(String type) {
        return jdbc.queryForList("SELECT recipient_id FROM notification WHERE notification_type = ?", Long.class, type);
    }

    private long coordinatorId() {
        return jdbc.queryForObject("SELECT id FROM user_account WHERE username = 'bmkt'", Long.class);
    }
}
