package fu.hl.g64.motorflow.transfer;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-74. */
class TransferDispatchApiTest extends TransferFlowTestData {

    @Test
    void dispatchMayRecordHandOverDetailsAndPhotos() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = createOrder(brake, 2);
        assign("bmcg", "DISPATCH_PREPARATION", order, sourceStaff).andExpect(status().isOk());
        prepare("nvcg", order, brake, 2).andExpect(status().isOk());

        Map<String, Object> handover = Map.of("delivererName", "  Anh Hùng ", "vehiclePlateNo", "29C-678.90",
                "evidence", List.of(uploaded("ban-giao.jpg")));
        dispatch("bmcg", order, handover, brake, 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.delivererName").value("Anh Hùng"))
                .andExpect(jsonPath("$.vehiclePlateNo").value("29C-678.90"))
                .andExpect(jsonPath("$.attachments.length()").value(1))
                .andExpect(jsonPath("$.attachments[0].fileName").value("ban-giao.jpg"));
        assertThat(jdbc.queryForObject("SELECT deliverer_name FROM transfer_order WHERE id = ?", String.class, order))
                .isEqualTo("Anh Hùng");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attachment WHERE owner_type = 'TRANSFER_ORDER' "
                + "AND owner_id = ?", Long.class, order)).isEqualTo(1);

        lot(source, chain, 5, 50_000, null, "B");
        long bare = createOrder(chain, 1);
        assign("bmcg", "DISPATCH_PREPARATION", bare, sourceStaff).andExpect(status().isOk());
        prepare("nvcg", bare, chain, 1).andExpect(status().isOk());
        dispatch("bmcg", bare, chain, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.delivererName").isEmpty())
                .andExpect(jsonPath("$.vehiclePlateNo").isEmpty())
                .andExpect(jsonPath("$.attachments").isEmpty());
    }

    @Test
    void dispatchMovesPreparedLotsIntoTransitFefoSkippingExpired() throws Exception {
        long expired = lot(source, brake, 5, 90_000, today.minusDays(1), "HET");
        long soon = lot(source, brake, 3, 100_000, today.plusDays(10), "SOM");
        long later = lot(source, brake, 10, 120_000, today.plusDays(90), "MUON");
        long open = lot(source, brake, 10, 110_000, null, "KHONG-HAN");
        long chainLot = lot(source, chain, 4, 50_000, null, "XICH");
        BigDecimal average = averageCost(brake);
        long order = createOrder(brake, 8, chain, 2);
        assign("bmcg", "DISPATCH_PREPARATION", order, sourceStaff).andExpect(status().isOk());
        prepare("nvcg", order, brake, 7, chain, 2).andExpect(status().isOk());

        view("bmcg", "/" + order + "/dispatch-preview").andExpect(status().isOk())
                .andExpect(jsonPath("$.readyToConfirm").value(true))
                .andExpect(jsonPath("$.canConfirm").value(true))
                .andExpect(jsonPath("$.preparedBy").value(sourceStaff.getId()))
                .andExpect(jsonPath("$.lines[0].shortageQty").value(1))
                .andExpect(jsonPath("$.lines[0].sellableQty").value(23))
                .andExpect(jsonPath("$.lines[0].lots[*].lotId").value(contains((int) soon, (int) later)))
                .andExpect(jsonPath("$.lines[0].lots[*].takeQty").value(contains(3, 4)));
        view("nvcg", "/" + order + "/dispatch-preview").andExpect(status().isForbidden());
        view("bmlb", "/" + order + "/dispatch-preview").andExpect(status().isForbidden());
        view("bmkt", "/" + order + "/dispatch-preview").andExpect(jsonPath("$.canConfirm").value(false));

        dispatch("bmcg", order, brake, 7, chain, 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.dispatchedBy.id").value(sourceManager.getId()))
                .andExpect(jsonPath("$.lines[0].dispatchedQty").value(7))
                .andExpect(jsonPath("$.lines[0].unresolvedQty").value(7))
                .andExpect(jsonPath("$.lots[*].lotId").value(contains((int) soon, (int) later, (int) chainLot)));

        assertThat(stock(source, brake, "available_qty")).isEqualTo(28 - 7);
        assertThat(stock(dest, brake, "in_transit_qty")).isEqualTo(7);
        assertThat(stock(dest, chain, "in_transit_qty")).isEqualTo(2);
        assertThat(lotStock(expired, source, "AVAILABLE")).isEqualTo(5);
        assertThat(lotStock(open, source, "AVAILABLE")).isEqualTo(10);
        assertThat(lotStock(soon, dest, "IN_TRANSIT")).isEqualTo(3);
        assertThat(lotStock(later, dest, "IN_TRANSIT")).isEqualTo(4);
        assertThat(averageCost(brake)).isEqualByComparingTo(average);
        List<Map<String, Object>> moves = jdbc.queryForList("SELECT lot_id, store_id, stock_status, quantity_change, "
                + "unit_cost FROM stock_movement WHERE movement_type = 'TRANSFER_DISPATCH' AND transfer_order_id = ? "
                + "AND product_id = ? ORDER BY lot_id, quantity_change", order, brake);
        assertThat(moves).hasSize(4);
        assertThat(moves.get(0)).containsEntry("store_id", source).containsEntry("quantity_change", -3);
        assertThat(moves.get(1)).containsEntry("store_id", dest).containsEntry("stock_status", "IN_TRANSIT")
                .containsEntry("quantity_change", 3);
        assertThat((BigDecimal) moves.get(0).get("unit_cost")).isEqualByComparingTo((BigDecimal) moves.get(1).get("unit_cost"));
        assertThat((BigDecimal) moves.get(2).get("unit_cost")).isEqualByComparingTo((BigDecimal) moves.get(3).get("unit_cost"));
        assertThat(jdbc.queryForObject("SELECT status FROM warehouse_task WHERE transfer_order_id = ?", String.class,
                order)).isEqualTo("DONE");
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notification WHERE notification_type = "
                + "'TRANSFER_DISPATCHED'", Long.class)).containsExactly(destManager.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = "
                + "'TRANSFER_ORDER_DISPATCHED'", Long.class, order)).isEqualTo(1);

        dispatch("bmcg", order, brake, 7, chain, 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));
        dispatch("bmcg2", order, brake, 1, chain, 1).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE movement_type = 'TRANSFER_DISPATCH'",
                Long.class)).isEqualTo(6);
        view("bmcg", "/" + order + "/dispatch-preview").andExpect(jsonPath("$.readyToConfirm").value(false))
                .andExpect(jsonPath("$.lines[0].lots").isEmpty());
    }

    @Test
    void onlyAnotherSourceManagerConfirms() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        long order = createOrder(brake, 4);

        dispatch("bmcg", order, brake, 4).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG207"));
        assign("bmcg", "DISPATCH_PREPARATION", order, sourceManager).andExpect(status().isOk());
        prepare("bmcg", order, brake, 4).andExpect(status().isOk());

        dispatch("owner", order, brake, 4).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        dispatch("bmkt", order, brake, 4).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        dispatch("bmlb", order, brake, 4).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        dispatch("nvcg", order, brake, 4).andExpect(status().isForbidden());

        dispatch("bmcg", order, brake, 4).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE notification_type = 'TASK_SELF_CONFIRMED'",
                Long.class)).isEqualTo(1);
        assertThat(stock(source, brake, "available_qty")).isEqualTo(6);
    }

    @Test
    void dispatchedMustMatchPreparedOnEveryLine() throws Exception {
        lot(source, brake, 10, 100_000, null, "A");
        lot(source, chain, 10, 100_000, null, "B");
        long order = createOrder(brake, 4, chain, 2);
        assign("bmcg", "DISPATCH_PREPARATION", order, sourceStaff).andExpect(status().isOk());
        prepare("nvcg", order, brake, 3, chain, 2).andExpect(status().isOk());

        dispatch("bmcg", order, brake, 4, chain, 2).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG208"));
        dispatch("bmcg", order, brake, 3).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG167"));
        dispatch("bmcg", order, brake, 3, brake, 3, chain, 2).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG166"));
        dispatch("bmcg", 999_999, brake, 3).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG175"));
        assertThat(jdbc.queryForObject("SELECT status FROM warehouse_task WHERE transfer_order_id = ?", String.class,
                order)).isEqualTo("RESULT_SUBMITTED");
        assertThat(stock(source, brake, "available_qty")).isEqualTo(10);
    }

    @Test
    void nothingPreparedCannotBeDispatched() throws Exception {
        lot(source, brake, 5, 100_000, null, "A");
        long order = createOrder(brake, 1);
        assign("bmcg", "DISPATCH_PREPARATION", order, sourceStaff).andExpect(status().isOk());
        prepare("nvcg", order, brake, 0).andExpect(status().isOk());

        dispatch("bmcg", order, brake, 0).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG209"));
    }

    @Test
    void notEnoughStockAtConfirmationRollsBack() throws Exception {
        long only = lot(source, brake, 5, 100_000, null, "A");
        long order = createOrder(brake, 5);
        assign("bmcg", "DISPATCH_PREPARATION", order, sourceStaff).andExpect(status().isOk());
        prepare("nvcg", order, brake, 5).andExpect(status().isOk());
        jdbc.update("UPDATE lot_balance SET quantity = 2 WHERE lot_id = ?", only);
        jdbc.update("UPDATE stock_balance SET available_qty = 2 WHERE store_id = ? AND product_id = ?", source, brake);

        dispatch("bmcg", order, brake, 5).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG84"));
        assertThat(jdbc.queryForObject("SELECT status FROM transfer_order WHERE id = ?", String.class, order))
                .isEqualTo("AWAITING_DISPATCH");
        assertThat(jdbc.queryForObject("SELECT status FROM warehouse_task WHERE transfer_order_id = ?", String.class,
                order)).isEqualTo("RESULT_SUBMITTED");
    }

    private BigDecimal averageCost(long product) {
        return jdbc.queryForObject("SELECT moving_avg_cost FROM product WHERE id = ?", BigDecimal.class, product);
    }
}
