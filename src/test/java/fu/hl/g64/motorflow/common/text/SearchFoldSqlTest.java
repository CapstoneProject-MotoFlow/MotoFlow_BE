package fu.hl.g64.motorflow.common.text;

import fu.hl.g64.motorflow.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The SQL function search_fold (V13) must fold exactly like SearchText.fold, or a search misses rows. */
class SearchFoldSqlTest extends IntegrationTest {

    private static final String LOWER = "aàáạảãâầấậẩẫăằắặẳẵeèéẹẻẽêềếệểễiìíịỉĩoòóọỏõôồốộổỗơờớợởỡuùúụủũưừứựửữyỳýỵỷỹdđ";

    @Test
    void sqlAndJavaFoldEveryVietnameseLetterTheSameWay() {
        List<String> samples = new ArrayList<>(List.of("Nguyễn Văn An", "ĐÀO THỊ ĐỨC", "Lốp Michelin 80/90-17",
                "Kho tổng", "Long Biên", "SKU-ABC_01", LOWER, LOWER.toUpperCase()));
        samples.add(Normalizer.normalize("Trần Thị Hồng Nhung", Normalizer.Form.NFD));
        for (char letter : (LOWER + LOWER.toUpperCase()).toCharArray()) {
            samples.add(String.valueOf(letter));
        }

        for (String sample : samples) {
            String sql = jdbc.queryForObject("select search_fold(?)", String.class, sample);
            assertThat(sql).as(sample).isEqualTo(SearchText.fold(sample));
        }
        assertThat(jdbc.queryForObject("select search_fold(?)", String.class, "Nguyễn Văn An"))
                .isEqualTo("nguyen van an");
    }

    @Test
    void nullStaysNull() {
        assertThat(jdbc.queryForObject("select search_fold(null)", String.class)).isNull();
    }
}
