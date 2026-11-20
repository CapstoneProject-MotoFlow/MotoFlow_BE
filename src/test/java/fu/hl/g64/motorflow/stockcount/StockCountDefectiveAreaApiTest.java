package fu.hl.g64.motorflow.stockcount;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GVHD 20/11: damaged goods are kept in the defective area. A count of a shelf area counts only the sellable goods of
 * its parts, a count of the defective area counts the damaged goods, so counting both never counts a unit twice.
 */
class StockCountDefectiveAreaApiTest extends StockCountTestData {

    private static final String COUNTS = "/api/v1/stock-counts";

    @Test
    void countingTheShelfAndTheDefectiveAreaCountsEachUnitOnce() throws Exception {
        // Oil on Kệ A: lot 1 has 3 sellable and 2 damaged, lot 2 has 7 sellable.
        markDamaged(store, oil, oilLot1, 2);

        long shelf = countArea("Kệ A");
        assertThat(books(shelf)).containsExactly(List.of(oil, 10, 0));
        completeWith(shelf, 10, 0);

        long defective = countArea("khu hàng lỗi");
        assertThat(jdbc.queryForObject("SELECT scope_note FROM stock_count WHERE id = ?", String.class, defective))
                .isEqualTo("khu hàng lỗi");
        assertThat(books(defective)).containsExactly(List.of(oil, 2, 2));
        completeWith(defective, 2, 2);

        // Nothing was adjusted: 10 + 2 counted is the 12 held.
        assertThat(movementsOf(shelf)).isEmpty();
        assertThat(movementsOf(defective)).isEmpty();
        assertThat(availableOf(store, oil)).isEqualTo(10);
    }

    @Test
    void theDefectiveAreaCountFindsItsShelvesWhateverTheLetterCase() throws Exception {
        // The filter's 9 units are shelved under the area's name typed in capitals; one plain-lot unit is damaged.
        jdbc.update("UPDATE stock_balance SET storage_area = 'KHU HÀNG LỖI' WHERE store_id = ? AND product_id = ?",
                store, filter);
        markDamaged(store, filter, filterLotPlain, 1);

        long defective = countArea("Khu hàng lỗi");
        assertThat(books(defective).stream().filter(line -> line.get(0).equals(filter))
                .mapToInt(line -> (Integer) line.get(1)).sum()).isEqualTo(9);
    }

    @Test
    void aShelfCountWithOnlyDamagedGoodsLeftHasNothingToCount() throws Exception {
        markDamaged(store, plug, plugLot(), 6);
        postAs(managerToken, COUNTS, area("Kệ C")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG268"));
    }

    private long plugLot() {
        return jdbc.queryForObject("SELECT id FROM lot WHERE product_id = ?", Long.class, plug);
    }

    private Map<String, Object> area(String name) {
        return Map.of("countType", "CYCLE", "countDate", TODAY.toString(), "scopeType", "AREA", "scopeNote", name);
    }

    private long countArea(String name) throws Exception {
        String created = postAs(managerToken, COUNTS, area(name)).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        long count = ((Number) JsonPath.read(created, "$.id")).longValue();
        postAs(managerToken, "/api/v1/warehouse-tasks", Map.of("taskType", "COUNTING", "documentId", count,
                "assigneeId", staff.getId())).andExpect(status().isOk());
        postAs(staffToken, COUNTS + "/" + count + "/start", null).andExpect(status().isOk());
        return count;
    }

    /** [product, book, book damaged] of each line. */
    private List<List<Object>> books(long count) {
        return jdbc.queryForList("SELECT product_id, book_qty, book_damaged_qty FROM stock_count_line "
                        + "WHERE stock_count_id = ? ORDER BY id", count).stream()
                .map(r -> List.<Object>of(((Number) r.get("product_id")).longValue(),
                        ((Number) r.get("book_qty")).intValue(), ((Number) r.get("book_damaged_qty")).intValue()))
                .toList();
    }

    private void completeWith(long count, int counted, int damaged) throws Exception {
        long line = jdbc.queryForObject("SELECT id FROM stock_count_line WHERE stock_count_id = ?", Long.class, count);
        postAs(staffToken, COUNTS + "/" + count + "/submit", Map.of("lines",
                List.of(Map.of("lineId", line, "countedQty", counted, "damagedQty", damaged))))
                .andExpect(status().isOk());
        postAs(managerToken, COUNTS + "/" + count + "/confirm", Map.of("reasons", List.of()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
    }
}
