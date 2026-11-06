package fu.hl.g64.motorflow.pricing.helper;

import fu.hl.g64.motorflow.catalog.entity.Product;
import fu.hl.g64.motorflow.pricing.entity.enums.RoundingRule;
import fu.hl.g64.motorflow.pricing.helper.PriceSuggestions.MarkupRules;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static fu.hl.g64.motorflow.pricing.helper.PriceSuggestions.suggestedPrice;
import static org.assertj.core.api.Assertions.assertThat;

/** BR-69: latest purchase price x (1 + markup rate), rounded up by Owner's rounding rule. */
class PriceSuggestionsTest {

    @Test
    void roundsUpToTheChosenStep() {
        // 123,456 x 1.30 = 160,492.8
        assertThat(suggestedPrice(123_456, new BigDecimal("30"), RoundingRule.NONE)).isEqualTo(160_493L);
        assertThat(suggestedPrice(123_456, new BigDecimal("30"), RoundingRule.UP_100)).isEqualTo(160_500L);
        assertThat(suggestedPrice(123_456, new BigDecimal("30"), RoundingRule.UP_500)).isEqualTo(160_500L);
        assertThat(suggestedPrice(123_456, new BigDecimal("30"), RoundingRule.UP_1000)).isEqualTo(161_000L);
        // Already on a step: nothing to round.
        assertThat(suggestedPrice(100_000, new BigDecimal("20.00"), RoundingRule.UP_1000)).isEqualTo(120_000L);
        // 99,999 x 1.125 = 112,498.875
        assertThat(suggestedPrice(99_999, new BigDecimal("12.5"), RoundingRule.NONE)).isEqualTo(112_499L);
        assertThat(suggestedPrice(123_456, BigDecimal.ZERO, RoundingRule.UP_1000)).isEqualTo(124_000L);
    }

    @Test
    void aPurchaseAtZeroGivesNoSuggestion() {
        assertThat(suggestedPrice(0, new BigDecimal("30"), RoundingRule.UP_1000)).isNull();
    }

    @Test
    void aPartTakesItsOwnRateThenTheNearestCategoryThatHasOne() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 13, 8, 0);
        Product part = Product.create("MA-PHANH-01", "Má phanh", 3L, "Honda", null, "cái", null, 1L, now);
        Map<Long, Long> parentOf = new HashMap<>();
        parentOf.put(3L, 2L);
        parentOf.put(2L, 1L);
        parentOf.put(1L, null);
        // The part is not saved, so it has no id yet; a HashMap takes the null key.
        Map<Long, BigDecimal> noPartRates = new HashMap<>();

        MarkupRules grandparentOnly = new MarkupRules(null, RoundingRule.NONE, Map.of(1L, new BigDecimal("30")), noPartRates);
        assertThat(grandparentOnly.rateFor(part, parentOf)).isEqualByComparingTo("30");
        MarkupRules parentToo = new MarkupRules(null, RoundingRule.NONE,
                Map.of(1L, new BigDecimal("30"), 2L, new BigDecimal("40")), noPartRates);
        assertThat(parentToo.rateFor(part, parentOf)).isEqualByComparingTo("40");
        MarkupRules none = new MarkupRules(null, RoundingRule.NONE, Map.of(9L, new BigDecimal("30")), noPartRates);
        assertThat(none.rateFor(part, parentOf)).isNull();

        // A broken tree that loops back on itself still ends.
        parentOf.put(1L, 3L);
        assertThat(none.rateFor(part, parentOf)).isNull();
    }
}
