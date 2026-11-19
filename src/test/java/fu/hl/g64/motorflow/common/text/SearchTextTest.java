package fu.hl.g64.motorflow.common.text;

import org.junit.jupiter.api.Test;

import java.text.Normalizer;

import static org.assertj.core.api.Assertions.assertThat;

class SearchTextTest {

    @Test
    void foldDropsCaseAndVietnameseAccents() {
        assertThat(SearchText.fold("Nguyễn Văn An")).isEqualTo("nguyen van an");
        assertThat(SearchText.fold("ĐÀO THỊ ĐỨC")).isEqualTo("dao thi duc");
        assertThat(SearchText.fold("Phụ tùng XE MÁY Honda")).isEqualTo("phu tung xe may honda");
        assertThat(SearchText.fold(null)).isNull();
    }

    @Test
    void foldJoinsAccentsTypedAsSeparateMarks() {
        String decomposed = Normalizer.normalize("Long Biên", Normalizer.Form.NFD);

        assertThat(decomposed).isNotEqualTo("Long Biên");
        assertThat(SearchText.fold(decomposed)).isEqualTo("long bien");
    }

    @Test
    void likePatternTrimsFoldsAndTakesWildcardsLiterally() {
        assertThat(SearchText.likePattern("  Lốp  ")).isEqualTo("%lop%");
        assertThat(SearchText.likePattern("50%_a\\b")).isEqualTo("%50\\%\\_a\\\\b%");
    }

    @Test
    void containsComparesFoldedText() {
        assertThat(SearchText.contains("Kho tổng Hà Nội", "ha noi")).isTrue();
        assertThat(SearchText.contains("Kho tổng Hà Nội", "hai")).isFalse();
        assertThat(SearchText.contains(null, "a")).isFalse();
    }
}
