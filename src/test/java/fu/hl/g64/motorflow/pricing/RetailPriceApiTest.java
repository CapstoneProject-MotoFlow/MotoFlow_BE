package fu.hl.g64.motorflow.pricing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-34 View Price List, UC-35 Update Retail Price, UC-36 View Price History (BR-08, BR-09, BR-69, BR-72). */
class RetailPriceApiTest extends PricingTestData {

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void markupRules() throws Exception {
        setMarkup(ownerToken, TODAY, "UP_1000", Map.of(brakes, 30, oils, 20), Map.of()).andExpect(status().isOk());
    }

    @Test
    void ownerSeesThePriceListWithSuggestionsAndWhatTheyAreBuiltFrom() throws Exception {
        setPrice(ownerToken, pad, 150_000, TODAY, "Giá đầu mùa", false).andExpect(status().isOk());

        priceList(ownerToken, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items[*].sku", contains("DEN-01", "MA-PHANH-01", "NHOT-01")))
                // Never bought and no markup rate: no price and no suggestion yet.
                .andExpect(jsonPath("$.items[0].currentPrice").doesNotExist())
                .andExpect(jsonPath("$.items[0].suggestedPrice").doesNotExist())
                .andExpect(jsonPath("$.items[0].purchaseBasis.latestPurchasePrice").doesNotExist())
                .andExpect(jsonPath("$.items[0].purchaseBasis.markupRate").doesNotExist())
                // 123,456 from the receipt of 10/10 (the opening lot of 12/10 is no purchase) x 1.30, the rate of the
                // parent category "Phanh", rounded up to 1,000.
                .andExpect(jsonPath("$.items[1].partName").value("Má phanh trước"))
                .andExpect(jsonPath("$.items[1].categoryName").value("Má phanh"))
                .andExpect(jsonPath("$.items[1].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[1].currentPrice").value(150_000))
                .andExpect(jsonPath("$.items[1].currentFrom").value("2026-10-13T08:00:00"))
                .andExpect(jsonPath("$.items[1].scheduledPrice").doesNotExist())
                .andExpect(jsonPath("$.items[1].suggestedPrice").value(161_000))
                .andExpect(jsonPath("$.items[1].purchaseBasis.latestPurchasePrice").value(123_456))
                .andExpect(jsonPath("$.items[1].purchaseBasis.receivedDate").value("2026-10-10"))
                .andExpect(jsonPath("$.items[1].purchaseBasis.markupRate").value(30.0))
                .andExpect(jsonPath("$.items[1].purchaseBasis.roundingRule").value("UP_1000"))
                // 85,000 x 1.20 = 102,000 exactly.
                .andExpect(jsonPath("$.items[2].suggestedPrice").value(102_000));
    }

    @Test
    void branchManagersSeeTheSuggestionOnlyWithRetailPricePermissionAndNeverTheCost() throws Exception {
        priceList(managerToken, "?q=phanh")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].sku", contains("MA-PHANH-01")))
                .andExpect(jsonPath("$.items[0].suggestedPrice").doesNotExist())
                .andExpect(jsonPath("$.items[0].purchaseBasis").doesNotExist());
        priceList(pricerToken, "?q=phanh")
                .andExpect(jsonPath("$.items[0].suggestedPrice").value(161_000))
                .andExpect(jsonPath("$.items[0].purchaseBasis").doesNotExist());
        mvc.perform(get("/api/v1/pricing/retail-prices/" + pad).header(HttpHeaders.AUTHORIZATION, pricerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("MA-PHANH-01"))
                .andExpect(jsonPath("$.suggestedPrice").value(161_000))
                .andExpect(jsonPath("$.purchaseBasis").doesNotExist());
    }

    @Test
    void staffAndAdminHaveNoPriceList() throws Exception {
        priceList(staffToken, "").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));
        priceList(adminToken, "").andExpect(status().isForbidden());
        priceHistory(staffToken, pad).andExpect(status().isForbidden());
    }

    @Test
    void filtersByCategoryWithItsSubCategoriesAndByASuggestionThatDiffers() throws Exception {
        priceList(managerToken, "?categoryId=" + brakes)
                .andExpect(jsonPath("$.items[*].sku", contains("MA-PHANH-01")));
        setPrice(ownerToken, pad, 150_000, TODAY, null, false).andExpect(status().isOk());
        setPrice(ownerToken, oil, 100_000, TODAY, null, false).andExpect(status().isOk());

        // Both differ from their suggestion; the lamp has none.
        priceList(ownerToken, "?suggestionDiffers=true&size=1&page=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.items[*].sku", contains("NHOT-01")));

        clock.advance(Duration.ofMinutes(1));
        setPrice(pricerToken, pad, 161_000, TODAY, null, true).andExpect(status().isOk());
        priceList(pricerToken, "?suggestionDiffers=true")
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[*].sku", contains("NHOT-01")));
        priceList(managerToken, "?suggestionDiffers=true")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
    }

    @Test
    void aPriceForTodayAppliesAtOnceAndKeepsTheOldOneAsHistory() throws Exception {
        setPrice(ownerToken, pad, 150_000, TODAY, "Giá đầu mùa", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPrice").value(150_000))
                .andExpect(jsonPath("$.currentFrom").value("2026-10-13T08:00:00"));
        clock.advance(Duration.ofMinutes(10));
        setPrice(pricerToken, pad, 155_000, TODAY, "  Nhà cung cấp tăng giá  ", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPrice").value(155_000))
                .andExpect(jsonPath("$.currentFrom").value("2026-10-13T08:10:00"));

        priceHistory(managerToken, pad)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].unitPrice", contains(155_000, 150_000)))
                .andExpect(jsonPath("$[*].state", contains("ACTIVE", "SUPERSEDED")))
                .andExpect(jsonPath("$[0].change").value(5_000))
                .andExpect(jsonPath("$[1].change").doesNotExist())
                .andExpect(jsonPath("$[0].effectiveTo").doesNotExist())
                .andExpect(jsonPath("$[1].effectiveTo").value("2026-10-13T08:10:00"))
                .andExpect(jsonPath("$[*].reason", contains("Nhà cung cấp tăng giá", "Giá đầu mùa")))
                .andExpect(jsonPath("$[*].fromSuggestion", contains(false, false)))
                .andExpect(jsonPath("$[*].setByName", contains("Test bm2", "Test owner")))
                .andExpect(jsonPath("$[0].setAt").value("2026-10-13T08:10:00"));
        priceHistory(ownerToken, lamp).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_type = 'PRICING_POLICY' "
                + "AND action = 'RETAIL_PRICE_SET'", Long.class)).isEqualTo(2);
    }

    @Test
    void aPriceForALaterDayWaitsAndReplacesThePriceAlreadyWaiting() throws Exception {
        setPrice(ownerToken, pad, 150_000, TODAY, null, false).andExpect(status().isOk());
        setPrice(ownerToken, pad, 170_000, LocalDate.of(2026, 10, 20), null, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPrice").value(150_000))
                .andExpect(jsonPath("$.scheduledPrice").value(170_000))
                .andExpect(jsonPath("$.scheduledFrom").value("2026-10-20T00:00:00"));
        // The same day again, then another day: one price waits at a time.
        setPrice(ownerToken, pad, 175_000, LocalDate.of(2026, 10, 20), null, false)
                .andExpect(jsonPath("$.scheduledPrice").value(175_000));
        setPrice(ownerToken, pad, 180_000, LocalDate.of(2026, 10, 25), "Tăng giá cuối tháng", false)
                .andExpect(jsonPath("$.scheduledPrice").value(180_000))
                .andExpect(jsonPath("$.scheduledFrom").value("2026-10-25T00:00:00"));
        // A price for today leaves the waiting one in place.
        clock.advance(Duration.ofMinutes(5));
        setPrice(ownerToken, pad, 160_000, TODAY, null, false)
                .andExpect(jsonPath("$.currentPrice").value(160_000))
                .andExpect(jsonPath("$.scheduledPrice").value(180_000));

        priceHistory(ownerToken, pad)
                .andExpect(jsonPath("$[*].unitPrice", contains(180_000, 160_000, 150_000)))
                .andExpect(jsonPath("$[*].state", contains("SCHEDULED", "ACTIVE", "SUPERSEDED")))
                .andExpect(jsonPath("$[0].change").value(20_000))
                .andExpect(jsonPath("$[0].reason").value("Tăng giá cuối tháng"))
                .andExpect(jsonPath("$[1].effectiveTo").value("2026-10-25T00:00:00"));
        // The prices that were replaced before their day stay in the change record.
        assertThat(jdbc.queryForList("SELECT old_data -> 'replaced_scheduled' -> 0 ->> 'unit_price' FROM audit_log "
                + "WHERE action = 'RETAIL_PRICE_SET' AND old_data IS NOT NULL ORDER BY id", String.class))
                .containsExactly("170000", "175000");

        today(LocalDate.of(2026, 10, 25));
        mvc.perform(get("/api/v1/pricing/retail-prices/" + pad).header(HttpHeaders.AUTHORIZATION, managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPrice").value(180_000))
                .andExpect(jsonPath("$.currentFrom").value("2026-10-25T00:00:00"))
                .andExpect(jsonPath("$.scheduledPrice").doesNotExist());
        priceHistory(ownerToken, pad)
                .andExpect(jsonPath("$[*].state", contains("ACTIVE", "SUPERSEDED", "SUPERSEDED")));
    }

    @Test
    void twoSavesForOnePartRunOneAfterTheOther() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection firstWriter = dataSource.getConnection()) {
            // The first writer holds the part's pricing lock and has saved a price for 20/10, not committed yet.
            firstWriter.setAutoCommit(false);
            try (PreparedStatement lock = firstWriter.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))")) {
                lock.setString(1, "RETAIL_PRICE:" + pad);
                lock.execute();
            }
            try (PreparedStatement insert = firstWriter.prepareStatement("INSERT INTO pricing_policy (policy_type, "
                    + "product_id, unit_price, effective_from, status, set_by, set_at) "
                    + "VALUES ('RETAIL_PRICE', ?, 170000, '2026-10-20 00:00', 'ACTIVE', ?, now())")) {
                insert.setLong(1, pad);
                insert.setLong(2, owner.getId());
                insert.executeUpdate();
            }
            Future<MvcResult> ours = executor.submit(() ->
                    setPrice(ownerToken, pad, 180_000, LocalDate.of(2026, 10, 25), null, false).andReturn());
            // Ours waits for the lock; let the first writer commit once it does.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!ours.isDone() && System.nanoTime() < deadline && jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'", Long.class) == 0) {
                Thread.sleep(20);
            }
            assertThat(ours.isDone()).isFalse();
            firstWriter.commit();

            MvcResult result = ours.get(30, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        } finally {
            executor.shutdownNow();
        }
        // Ours saw the price waiting for 20/10 and replaced it, so one price waits.
        assertThat(jdbc.queryForList("SELECT unit_price FROM pricing_policy WHERE product_id = ?", Long.class, pad))
                .containsExactly(180_000L);
    }

    @Test
    void applyingTheSuggestionNeedsTheSuggestionOfNow() throws Exception {
        setPrice(pricerToken, pad, 160_000, TODAY, null, true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG126"))
                .andExpect(jsonPath("$.message").value(
                        "Giá gợi ý của phụ tùng MA-PHANH-01 đã thay đổi. Vui lòng tải lại để xem giá gợi ý mới."));
        // A part never bought has no suggestion to apply: reloading would not help.
        setPrice(ownerToken, lamp, 50_000, TODAY, null, true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG145"))
                .andExpect(jsonPath("$.message").value("Phụ tùng DEN-01 chưa có giá gợi ý vì chưa có giá nhập từ nhà "
                        + "cung cấp hoặc chưa có tỷ lệ cộng thêm."));

        setPrice(pricerToken, pad, 161_000, TODAY, "Theo giá nhập mới", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPrice").value(161_000));
        priceHistory(pricerToken, pad)
                .andExpect(jsonPath("$[0].fromSuggestion").value(true))
                .andExpect(jsonPath("$[0].reason").value("Theo giá nhập mới"));

        // A new receipt moves the suggestion: 130,000 x 1.30 = 169,000.
        received(pad, 130_000, TODAY);
        clock.advance(Duration.ofMinutes(1));
        setPrice(ownerToken, pad, 161_000, TODAY, null, true).andExpect(status().isConflict());
        setPrice(ownerToken, pad, 169_000, TODAY, null, true).andExpect(status().isOk());
    }

    @Test
    void onlyOwnerAndBranchManagersWithPermissionUpdatePrices() throws Exception {
        setPrice(managerToken, pad, 150_000, TODAY, null, false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MSG05"));
        setPrice(staffToken, pad, 150_000, TODAY, null, false).andExpect(status().isForbidden());
        setPrice(adminToken, pad, 150_000, TODAY, null, false).andExpect(status().isForbidden());
        assertThat(retailPriceVersions()).isZero();
    }

    @Test
    void rejectsADayBeforeTodayAnUnknownPartAndAMissingPrice() throws Exception {
        setPrice(ownerToken, pad, 150_000, TODAY.minusDays(1), null, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG125"))
                .andExpect(jsonPath("$.message").value("Ngày áp dụng phải từ hôm nay trở đi."));
        setPrice(ownerToken, 999_999, 150_000, TODAY, null, false)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG57"));
        setPrice(ownerToken, pad, 0, TODAY, null, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"))
                .andExpect(jsonPath("$.fieldErrors.unitPrice").value("Giá bán lẻ phải lớn hơn 0."));
        mvc.perform(post("/api/v1/pricing/retail-prices").header(HttpHeaders.AUTHORIZATION, ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("productId", pad))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.unitPrice").value("Vui lòng nhập giá bán lẻ mới."))
                .andExpect(jsonPath("$.fieldErrors.effectiveDate").value("Vui lòng chọn ngày áp dụng."));
        priceHistory(ownerToken, 999_999).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG57"));
        assertThat(retailPriceVersions()).isZero();
    }

    private long retailPriceVersions() {
        return jdbc.queryForObject("SELECT count(*) FROM pricing_policy WHERE policy_type = 'RETAIL_PRICE'", Long.class);
    }
}
