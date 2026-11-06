package fu.hl.g64.motorflow.pricing;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-38 Configure Discount Threshold and Minimum Margin, UC-101 Configure Price Suggestion Rules (BR-11, BR-69). */
class PricingRuleApiTest extends PricingTestData {

    @Test
    void ownerSetsEachSalesLimitFromItsOwnDay() throws Exception {
        salesLimits(ownerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discountThreshold.current").doesNotExist())
                .andExpect(jsonPath("$.discountThreshold.history", hasSize(0)))
                .andExpect(jsonPath("$.minimumMargin.current").doesNotExist());

        setLimit(ownerToken, "DISCOUNT_THRESHOLD", 10, TODAY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discountThreshold.current.rate").value(10.0))
                .andExpect(jsonPath("$.discountThreshold.current.effectiveFrom").value("2026-10-13T08:00:00"))
                .andExpect(jsonPath("$.discountThreshold.current.state").value("ACTIVE"))
                .andExpect(jsonPath("$.discountThreshold.current.setByName").value("Test owner"))
                .andExpect(jsonPath("$.minimumMargin.current").doesNotExist());
        setLimit(ownerToken, "MINIMUM_MARGIN", 8.5, LocalDate.of(2026, 10, 15))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minimumMargin.current").doesNotExist())
                .andExpect(jsonPath("$.minimumMargin.scheduled.rate").value(8.5))
                .andExpect(jsonPath("$.minimumMargin.scheduled.state").value("SCHEDULED"));
        setLimit(ownerToken, "DISCOUNT_THRESHOLD", 15, LocalDate.of(2026, 10, 20)).andExpect(status().isOk());
        // Another later day replaces the threshold waiting.
        setLimit(ownerToken, "DISCOUNT_THRESHOLD", 12, LocalDate.of(2026, 10, 18))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discountThreshold.scheduled.rate").value(12.0))
                .andExpect(jsonPath("$.discountThreshold.scheduled.effectiveFrom").value("2026-10-18T00:00:00"))
                .andExpect(jsonPath("$.discountThreshold.history[*].rate", contains(12.0, 10.0)))
                .andExpect(jsonPath("$.discountThreshold.history[1].effectiveTo").value("2026-10-18T00:00:00"));

        today(LocalDate.of(2026, 10, 18));
        salesLimits(ownerToken)
                .andExpect(jsonPath("$.discountThreshold.current.rate").value(12.0))
                .andExpect(jsonPath("$.discountThreshold.scheduled").doesNotExist())
                .andExpect(jsonPath("$.discountThreshold.history[*].state", contains("ACTIVE", "SUPERSEDED")))
                .andExpect(jsonPath("$.minimumMargin.current.rate").value(8.5));
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE entity_type = 'PRICING_POLICY' ORDER BY id",
                String.class)).containsExactly("DISCOUNT_THRESHOLD_SET", "MINIMUM_MARGIN_SET", "DISCOUNT_THRESHOLD_SET",
                "DISCOUNT_THRESHOLD_SET");
    }

    @Test
    void salesLimitsAreCheckedAndSetByOwnerOnly() throws Exception {
        setLimit(ownerToken, "DISCOUNT_THRESHOLD", 100.5, TODAY)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"))
                .andExpect(jsonPath("$.fieldErrors.rate").value("Tỷ lệ phải từ 0 đến 100%."));
        setLimit(ownerToken, "MINIMUM_MARGIN", 5.555, TODAY)
                .andExpect(jsonPath("$.fieldErrors.rate").value("Tỷ lệ có tối đa 2 chữ số thập phân."));
        setLimit(ownerToken, "RETAIL_PRICE", 5, TODAY)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"));
        setLimit(ownerToken, "MINIMUM_MARGIN", 5, TODAY.minusDays(1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG125"));
        setLimit(pricerToken, "DISCOUNT_THRESHOLD", 5, TODAY)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        salesLimits(managerToken).andExpect(status().isForbidden());
        salesLimits(staffToken).andExpect(status().isForbidden());
        assertThat(pricingRows()).isZero();
    }

    @Test
    void ownerSavesAWholeMarkupConfigurationThatReplacesTheRulesFromItsDay() throws Exception {
        setMarkup(ownerToken, TODAY, "UP_1000", Map.of(brakes, 30, oils, 20), Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.effectiveFrom").value("2026-10-13T08:00:00"))
                .andExpect(jsonPath("$.current.roundingRule").value("UP_1000"))
                .andExpect(jsonPath("$.current.categories[*].categoryName", contains("Dầu nhớt", "Phanh")))
                .andExpect(jsonPath("$.current.categories[*].markupRate", contains(20.0, 30.0)))
                .andExpect(jsonPath("$.current.categories[1].vehicleType").value("MOTORCYCLE"))
                .andExpect(jsonPath("$.current.products", hasSize(0)))
                .andExpect(jsonPath("$.current.setByName").value("Test owner"))
                .andExpect(jsonPath("$.scheduled").doesNotExist());
        setMarkup(ownerToken, LocalDate.of(2026, 10, 20), "UP_500", Map.of(brakes, 25), Map.of(oil, 15))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.roundingRule").value("UP_1000"))
                .andExpect(jsonPath("$.scheduled.effectiveFrom").value("2026-10-20T00:00:00"))
                .andExpect(jsonPath("$.scheduled.roundingRule").value("UP_500"))
                .andExpect(jsonPath("$.scheduled.categories[*].categoryName", contains("Phanh")))
                .andExpect(jsonPath("$.scheduled.products[*].sku", contains("NHOT-01")))
                .andExpect(jsonPath("$.scheduled.products[0].markupRate").value(15.0));
        // Suggestions follow the rules in force: 30% and 20% until 20/10.
        priceList(ownerToken, "")
                .andExpect(jsonPath("$.items[1].suggestedPrice").value(161_000))
                .andExpect(jsonPath("$.items[2].suggestedPrice").value(102_000));

        today(LocalDate.of(2026, 10, 20));
        // "Dầu nhớt" has no rate any more, so the oil takes its own 15%: 85,000 x 1.15 = 97,750, up to 98,000.
        // The pad inherits 25% from "Phanh": 123,456 x 1.25 = 154,320, up to 154,500.
        priceList(ownerToken, "")
                .andExpect(jsonPath("$.items[1].suggestedPrice").value(154_500))
                .andExpect(jsonPath("$.items[1].purchaseBasis.markupRate").value(25.0))
                .andExpect(jsonPath("$.items[2].suggestedPrice").value(98_000))
                .andExpect(jsonPath("$.items[2].purchaseBasis.roundingRule").value("UP_500"));
        markupRules(ownerToken)
                .andExpect(jsonPath("$.current.roundingRule").value("UP_500"))
                .andExpect(jsonPath("$.scheduled").doesNotExist());
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE entity_type = 'PRICING_POLICY' ORDER BY id",
                String.class)).containsExactly("MARKUP_RULES_SET", "MARKUP_RULES_SET");
    }

    @Test
    void aLaterConfigurationReplacesTheOneWaitingAndOneForTodayKeepsIt() throws Exception {
        setMarkup(ownerToken, LocalDate.of(2026, 10, 20), "UP_500", Map.of(brakes, 25), Map.of()).andExpect(status().isOk());
        setMarkup(ownerToken, LocalDate.of(2026, 10, 22), "UP_100", Map.of(oils, 10), Map.of())
                .andExpect(jsonPath("$.scheduled.effectiveFrom").value("2026-10-22T00:00:00"))
                .andExpect(jsonPath("$.scheduled.categories[*].categoryName", contains("Dầu nhớt")));
        setMarkup(ownerToken, TODAY, "NONE", Map.of(brakes, 30), Map.of())
                .andExpect(jsonPath("$.current.roundingRule").value("NONE"))
                .andExpect(jsonPath("$.scheduled.roundingRule").value("UP_100"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pricing_policy WHERE policy_type = 'MARKUP_RATE'",
                Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT old_data -> 'replaced_scheduled' ->> 'rounding_rule' FROM audit_log "
                + "WHERE old_data IS NOT NULL", String.class)).isEqualTo("UP_500");
    }

    @Test
    void aCategoryRateOfItsOwnBeatsItsParentAndAPartRateBeatsBoth() throws Exception {
        setMarkup(ownerToken, TODAY, "NONE", Map.of(brakes, 30, brakePads, 40), Map.of()).andExpect(status().isOk());
        // 123,456 x 1.40 = 172,838.4, rounded up to the next dong.
        priceList(ownerToken, "?q=MA-PHANH").andExpect(jsonPath("$.items[0].suggestedPrice").value(172_839));

        clock.advance(Duration.ofMinutes(1));
        setMarkup(ownerToken, TODAY, "UP_100", Map.of(brakes, 30, brakePads, 40), Map.of(pad, 10))
                .andExpect(status().isOk());
        // 123,456 x 1.10 = 135,801.6, up to 135,900.
        priceList(ownerToken, "?q=MA-PHANH").andExpect(jsonPath("$.items[0].suggestedPrice").value(135_900));
    }

    @Test
    void markupConfigurationsAreCheckedAndSetByOwnerOnly() throws Exception {
        setMarkup(ownerToken, TODAY, "UP_1000", Map.of(), Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG128"))
                .andExpect(jsonPath("$.message").value(
                        "Vui lòng nhập tỷ lệ cộng thêm cho ít nhất một nhóm hàng hoặc phụ tùng."));
        markup(ownerToken, Map.of("effectiveDate", TODAY.toString(), "roundingRule", "NONE",
                "categories", List.of(Map.of("categoryId", brakes, "markupRate", 30),
                        Map.of("categoryId", brakes, "markupRate", 35))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG127"))
                .andExpect(jsonPath("$.message").value("Mỗi nhóm hàng hoặc phụ tùng chỉ được có một tỷ lệ cộng thêm."));
        setMarkup(ownerToken, TODAY, "NONE", Map.of(999_999L, 30), Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG61"));
        setMarkup(ownerToken, TODAY, "NONE", Map.of(), Map.of(999_999L, 30))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG57"));
        setMarkup(ownerToken, TODAY, "NONE", Map.of(brakes, -1), Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"))
                .andExpect(jsonPath("$.fieldErrors['categories[0].markupRate']")
                        .value("Tỷ lệ cộng thêm phải từ 0 đến 999,99%."));
        // Rounding steps are the ones the database knows.
        setMarkup(ownerToken, TODAY, "UP_5000", Map.of(brakes, 30), Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"));
        setMarkup(ownerToken, TODAY.minusDays(1), "NONE", Map.of(brakes, 30), Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG125"));
        setMarkup(pricerToken, TODAY, "NONE", Map.of(brakes, 30), Map.of())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        markupRules(managerToken).andExpect(status().isForbidden());
        assertThat(pricingRows()).isZero();
    }

    @Test
    void previewShowsSampleSuggestionsWithoutSavingAnything() throws Exception {
        setPrice(ownerToken, oil, 100_000, TODAY, null, false).andExpect(status().isOk());

        preview(ownerToken, Map.of("roundingRule", "UP_100", "categories", rates("categoryId", Map.of(brakes, 30))))
                .andExpect(status().isOk())
                // The parts most recently bought from a supplier; the lamp never was.
                .andExpect(jsonPath("$[*].sku", contains("MA-PHANH-01", "NHOT-01")))
                .andExpect(jsonPath("$[0].latestPurchasePrice").value(123_456))
                .andExpect(jsonPath("$[0].receivedDate").value("2026-10-10"))
                .andExpect(jsonPath("$[0].markupRate").value(30.0))
                // 160,492.8 up to 100.
                .andExpect(jsonPath("$[0].suggestedPrice").value(160_500))
                .andExpect(jsonPath("$[0].currentPrice").doesNotExist())
                .andExpect(jsonPath("$[1].currentPrice").value(100_000))
                .andExpect(jsonPath("$[1].markupRate").doesNotExist())
                .andExpect(jsonPath("$[1].suggestedPrice").doesNotExist());
        preview(ownerToken, Map.of("roundingRule", "NONE", "products", rates("productId", Map.of(lamp, 10)),
                "productIds", List.of(lamp)))
                .andExpect(jsonPath("$[*].sku", contains("DEN-01")))
                .andExpect(jsonPath("$[0].markupRate").value(10.0))
                .andExpect(jsonPath("$[0].latestPurchasePrice").doesNotExist())
                .andExpect(jsonPath("$[0].suggestedPrice").doesNotExist());
        preview(ownerToken, Map.of("roundingRule", "NONE", "productIds", List.of(999_999)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG57"));
        preview(pricerToken, Map.of("roundingRule", "NONE")).andExpect(status().isForbidden());

        markupRules(ownerToken).andExpect(jsonPath("$.current").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pricing_policy WHERE policy_type = 'MARKUP_RATE'",
                Long.class)).isZero();
    }

    private ResultActions salesLimits(String token) throws Exception {
        return mvc.perform(get("/api/v1/pricing/sales-limits").header(HttpHeaders.AUTHORIZATION, token));
    }

    private ResultActions markupRules(String token) throws Exception {
        return mvc.perform(get("/api/v1/pricing/markup-rules").header(HttpHeaders.AUTHORIZATION, token));
    }

    private ResultActions markup(String token, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/pricing/markup-rules").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions preview(String token, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/pricing/markup-rules/preview").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private long pricingRows() {
        return jdbc.queryForObject("SELECT count(*) FROM pricing_policy", Long.class);
    }
}
