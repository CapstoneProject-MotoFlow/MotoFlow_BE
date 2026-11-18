package fu.hl.g64.motorflow.adjustment;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Review 17/11 R-07 (SRS 11.2.2 "Lưu nháp", 11.2.3 tab "Nháp"): a stock loss saved as a draft, seen by its requester
 * alone, saved again, then submitted to Owner or thrown away.
 */
class StockLossDraftApiTest extends AdjustmentTestData {

    private long saved(String token, Map<String, Object> body) throws Exception {
        String response = call(post(REQUESTS + "/stock-loss/draft"), token, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    @Test
    void aDraftWaitsForItsEvidenceSeenByItsRequesterAloneThenGoesToOwner() throws Exception {
        Map<String, Object> body = loss(null, line(oil, oilLot1, "AVAILABLE", 2));
        body.put("attachments", List.of());
        long draft = saved(managerToken, body);

        detail(managerToken, draft).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.submittedAt").doesNotExist())
                .andExpect(jsonPath("$.evidence", empty()))
                .andExpect(jsonPath("$.actions.canEditDraft").value(true))
                .andExpect(jsonPath("$.actions.canWithdraw").value(false));
        // Nobody is told, and nobody else sees it: not Owner, not another manager of the store.
        assertThat(notifications(owner, "ADJUSTMENT_AWAITING_APPROVAL")).isZero();
        detail(ownerToken, draft).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG253"));
        detail(manager2Token, draft).andExpect(status().isNotFound());
        list(ownerToken, "status", "DRAFT").andExpect(jsonPath("$.items[*].id", not(hasItem((int) draft))));
        list(managerToken).andExpect(jsonPath("$.items[*].id", not(hasItem((int) draft))));
        list(managerToken, "status", "DRAFT", "mine", "true")
                .andExpect(jsonPath("$.items[*].id", contains((int) draft)))
                .andExpect(jsonPath("$.items[0].savedAt").value("2026-10-13T08:00:00"));
        // Its stock is not held: a draft is no request yet.
        submitted(managerToken, loss(null, line(oil, oilLot1, "AVAILABLE", 5)));

        // Only the requester changes it; Owner cannot decide it.
        call(put(REQUESTS + "/" + draft), manager2Token, body).andExpect(status().isNotFound());
        act(ownerToken, draft, "approve").andExpect(status().isNotFound());

        // Submitting checks again: the stock the other request now holds, then the evidence.
        call(post(REQUESTS + "/" + draft + "/submit"), managerToken, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG257"));
        Map<String, Object> again = loss(null, line(oil, oilLot2, "AVAILABLE", 1));
        again.put("reason", "Một can nhớt bị thủng");
        List<Object> evidence = List.copyOf((List<?>) again.get("attachments"));
        again.put("attachments", List.of());
        call(put(REQUESTS + "/" + draft), managerToken, again).andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("Một can nhớt bị thủng"))
                .andExpect(jsonPath("$.stockLines[*].lotId", contains((int) oilLot2)));
        call(post(REQUESTS + "/" + draft + "/submit"), managerToken, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG255"));
        again.put("attachments", evidence);
        call(put(REQUESTS + "/" + draft), managerToken, again).andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence[*].fileName", contains("anh-hong.jpg")));
        // Saving the same files again keeps them once.
        call(put(REQUESTS + "/" + draft), managerToken, again).andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence.length()").value(1));

        call(post(REQUESTS + "/" + draft + "/submit"), managerToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.submittedAt").value("2026-10-13T08:00:00"))
                .andExpect(jsonPath("$.history[*].action", contains("ADJUSTMENT_SUBMITTED")))
                .andExpect(jsonPath("$.actions.canEditDraft").value(false));
        assertThat(notifications(owner, "ADJUSTMENT_AWAITING_APPROVAL")).isEqualTo(2);
        detail(ownerToken, draft).andExpect(status().isOk());
        // Once submitted it is no draft any more.
        call(put(REQUESTS + "/" + draft), managerToken, again).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MSG281"));
        call(delete(REQUESTS + "/" + draft), managerToken, null).andExpect(status().isConflict());
    }

    @Test
    void aDraftIsCheckedAsThePreviewAndMayBeThrownAway() throws Exception {
        // The checks of the preview: the store's stock is the limit.
        call(post(REQUESTS + "/stock-loss/draft"), managerToken, loss(null, line(oil, oilLot1, "AVAILABLE", 99)))
                .andExpect(status().isBadRequest());
        call(post(REQUESTS + "/stock-loss/draft"), managerToken, loss(otherStore, line(oil, oilLot1, "AVAILABLE", 1)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MSG05"));

        long draft = saved(managerToken, loss(null, line(oil, oilLot1, "AVAILABLE", 1)));
        call(delete(REQUESTS + "/" + draft), manager2Token, null).andExpect(status().isNotFound());
        call(delete(REQUESTS + "/" + draft), managerToken, null).andExpect(status().isNoContent());
        detail(managerToken, draft).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM adjustment_stock_line WHERE adjustment_request_id = ?",
                Long.class, draft)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attachment WHERE owner_type = 'ADJUSTMENT_REQUEST' "
                + "AND owner_id = ?", Long.class, draft)).isZero();
    }
}
