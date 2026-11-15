package fu.hl.g64.motorflow.stockcount;

import fu.hl.g64.motorflow.inventory.entity.enums.ScopeType;
import fu.hl.g64.motorflow.inventory.entity.enums.StockCountStatus;
import fu.hl.g64.motorflow.operation.entity.enums.WarehouseTaskStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-93/94/95 A04: damaged goods in a stock count. */
class StockCountDamagedApiTest extends StockCountTestData {

  private Map<String, Object> countedLine(long lineId, int counted, int damaged) {
    Map<String, Object> m = new HashMap<>();
    m.put("lineId", lineId);
    m.put("countedQty", counted);
    m.put("damagedQty", damaged);
    return m;
  }

  @Test
  void countingPostsDifferenceAndDamageAdjustments() throws Exception {
    markDamaged(store, oil, oilLot1, 2);
    long count = count(StockCountStatus.DRAFT).scope(ScopeType.PRODUCTS, null)
            .line(SeedLine.of(oil, null, null, 0, null)).task(staff, WarehouseTaskStatus.ASSIGNED).insert();
    List<Long> lineIds = lineIds(count);
    postAs(staffToken, "/api/v1/stock-counts/" + count + "/start", null).andExpect(status().isOk());
    long oilLine = lineIds.getFirst();
    assertThat(jdbc.queryForObject("SELECT book_qty FROM stock_count_line WHERE id = ?", Integer.class, oilLine))
            .isEqualTo(12);
    assertThat(jdbc.queryForObject("SELECT book_damaged_qty FROM stock_count_line WHERE id = ?", Integer.class,
            oilLine)).isEqualTo(2);

    putAs(staffToken, "/api/v1/stock-counts/" + count + "/lines", Map.of("lines", List.of(countedLine(oilLine, 11, 3))))
            .andExpect(status().isOk());
    postAs(staffToken, "/api/v1/stock-counts/" + count + "/submit", Map.of()).andExpect(status().isOk());
    postAs(managerToken, "/api/v1/stock-counts/" + count + "/confirm",
            Map.of("removedLineIds", List.of(), "reasons", List.of(Map.of("lineId", oilLine, "reason", "Hư hỏng"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"));

    postAs(ownerToken, "/api/v1/stock-counts/" + count + "/approve", Map.of()).andExpect(status().isOk());
    assertThat(availableOfLot(oilLot1)).isEqualTo(1);
    assertThat(availableOfLot(oilLot2)).isEqualTo(7);
    assertThat(damagedOfLot(oilLot1)).isEqualTo(3);
    assertThat(damagedOfLot(oilLot2)).isZero();
    assertThat(availableOf(store, oil)).isEqualTo(8);

    List<Map<String, Object>> movements = movementsOf(count);
    assertThat(movements).hasSize(3);
    assertThat(movements.stream().map(m -> m.get("quantity_change"))).containsExactlyInAnyOrder(-1, -1, 1);
    List<BigDecimal> values = jdbc.queryForList(
            "SELECT quantity_change * unit_cost FROM stock_movement WHERE stock_count_id = ? ORDER BY id",
            BigDecimal.class, count);
    assertThat(values.get(1).add(values.get(2))).isEqualByComparingTo("0");
  }

  @Test
  void matchingCountWithUnchangedDamagedCompletesAtOnce() throws Exception {
    markDamaged(store, oil, oilLot1, 2);
    long count = count(StockCountStatus.COUNTING).scope(ScopeType.PRODUCTS, null)
            .line(SeedLine.of(oil, null, null, 12, 12).withDamaged(2, 2))
            .task(staff, WarehouseTaskStatus.RESULT_SUBMITTED).insert();
    postAs(managerToken, "/api/v1/stock-counts/" + count + "/confirm", Map.of("removedLineIds", List.of(), "reasons", List.of()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
    assertThat(movementsOf(count)).isEmpty();
  }

  @Test
  void matchingTotalButZeroDamagedWaitsForOwner() throws Exception {
    markDamaged(store, oil, oilLot1, 2);
    long count = count(StockCountStatus.COUNTING).scope(ScopeType.PRODUCTS, null)
            .line(SeedLine.of(oil, null, null, 12, 12).withDamaged(2, 0))
            .task(staff, WarehouseTaskStatus.RESULT_SUBMITTED).insert();
    postAs(managerToken, "/api/v1/stock-counts/" + count + "/confirm", Map.of("removedLineIds", List.of(), "reasons", List.of()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"));
    postAs(ownerToken, "/api/v1/stock-counts/" + count + "/approve", Map.of()).andExpect(status().isOk());
    assertThat(damagedOfLot(oilLot1)).isZero();
    assertThat(availableOfLot(oilLot1)).isEqualTo(5);
    assertThat(availableOf(store, oil)).isEqualTo(12);
  }

  @Test
  void aLotWithOnlyDamagedStockStillGetsALine() throws Exception {
    markDamaged(store, filter, filterLotA, 4);
    long count = count(StockCountStatus.DRAFT).scope(ScopeType.PRODUCTS, null)
            .line(SeedLine.of(filter, filterLotA, LocalDate.of(2027, 1, 31), 0, null))
            .task(staff, WarehouseTaskStatus.ASSIGNED).insert();
    postAs(staffToken, "/api/v1/stock-counts/" + count + "/start", null).andExpect(status().isOk());
    long line = jdbc.queryForObject("SELECT id FROM stock_count_line WHERE stock_count_id = ? AND lot_id = ?",
            Long.class, count, filterLotA);
    assertThat(jdbc.queryForObject("SELECT book_qty FROM stock_count_line WHERE id = ?", Integer.class, line))
            .isEqualTo(4);
    assertThat(jdbc.queryForObject("SELECT book_damaged_qty FROM stock_count_line WHERE id = ?", Integer.class, line))
            .isEqualTo(4);
  }

}