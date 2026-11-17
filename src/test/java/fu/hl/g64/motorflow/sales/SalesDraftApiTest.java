package fu.hl.g64.motorflow.sales;

import com.jayway.jsonpath.JsonPath;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PM 13/11: sales drafts kept on the server, outside the order flow until submitted. */
class SalesDraftApiTest extends SalesOrderTestData {

    private static final String DRAFTS = "/api/v1/sales-orders/drafts";

    private ResultActions call(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json(body));
        }
        return mvc.perform(request);
    }

    private long saveDraft(String token, Map<String, Object> body) throws Exception {
        String response = call(post(DRAFTS), token, body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    /** A walk-in retail sale at the caller's store. */
    private static Map<String, Object> retail(long... productAndQuantity) {
        return order(null, null, "RETAIL", null, lines(productAndQuantity));
    }

    @Test
    void draftsAreListedOnlyForTheirCreatorAndNeverAsSalesOrders() throws Exception {
        long first = saveDraft(managerToken, order(null, customer, "RETAIL", null, lines(pad, 2)));
        clock.advance(java.time.Duration.ofMinutes(1));
        long second = saveDraft(managerToken, retail(oil, 1, pad, 1));
        call(get(DRAFTS + "/" + first), managerToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.history").isEmpty())
                .andExpect(jsonPath("$.lines[0].referencePrice").value(100_000))
                .andExpect(jsonPath("$.actions.canUpdate").value(true))
                .andExpect(jsonPath("$.actions.canConfirm").value(false))
                .andExpect(jsonPath("$.actions.canCancel").value(false));

        call(get(DRAFTS), managerToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", contains(ids(second, first))))
                .andExpect(jsonPath("$.items[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.items[1].total").value(200_000));
        call(get(DRAFTS).param("q", "Tuấn"), managerToken, null)
                .andExpect(jsonPath("$.items[*].id", contains(ids(first))));
        call(get(DRAFTS), ownerToken, null).andExpect(jsonPath("$.totalItems").value(0));

        for (String view : new String[]{"OPEN", "COMPLETED", "CANCELLED", "ALL"}) {
            list(managerToken, "view", view).andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems").value(0));
        }
        list(managerToken, "status", "DRAFT").andExpect(jsonPath("$.totalItems").value(0));
        list(ownerToken, "view", "ALL").andExpect(jsonPath("$.totalItems").value(0));
        // The customer's page lists no draft either.
        call(get("/api/v1/customers/" + customer), ownerToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderCount").value(0));
    }

    @Test
    void someoneElsesDraftIsNotFound() throws Exception {
        UserAccount sameStore = createUser("bm3", Role.BRANCH_MANAGER, store);
        String sameStoreToken = signIn("bm3");
        long draft = saveDraft(managerToken, retail(pad, 1));
        for (String token : new String[]{otherManagerToken, ownerToken, sameStoreToken}) {
            call(get(DRAFTS + "/" + draft), token, null).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("MSG156"));
            call(put(DRAFTS + "/" + draft), token, order(store, null, "RETAIL", null, lines(pad, 2)))
                    .andExpect(status().isNotFound());
            call(post(DRAFTS + "/" + draft + "/submit"), token, order(store, null, "RETAIL", null, lines(pad, 2)))
                    .andExpect(status().isNotFound());
            call(delete(DRAFTS + "/" + draft), token, null).andExpect(status().isNotFound());
        }
        call(get(DRAFTS), staffToken, null).andExpect(status().isForbidden());
        call(get(DRAFTS + "/" + draft), staffToken, null).andExpect(status().isForbidden());
        call(post(DRAFTS), staffToken, retail(pad, 1)).andExpect(status().isForbidden());
        assertThat(sameStore.getId()).isNotNull();
        // Owner chooses the store of a draft too.
        call(post(DRAFTS), ownerToken, retail(pad, 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG157"));
    }

    @Test
    void theThresholdIsCheckedOnlyWhenTheDraftIsSubmitted() throws Exception {
        Map<String, Object> tooMuch = order(null, customer, "WHOLESALE", "15", lines(pad, 2));
        long draft = saveDraft(managerToken, tooMuch);
        call(post(DRAFTS + "/" + draft + "/submit"), managerToken, tooMuch).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG162"));
        Map<String, Object> changed = order(null, customer, "WHOLESALE", "20", lines(pad, 5));
        call(post(DRAFTS + "/" + draft + "/submit"), managerToken, changed).andExpect(status().isBadRequest());
        // The refused submission changed nothing.
        call(get(DRAFTS + "/" + draft), managerToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.discountRate").value(15.0))
                .andExpect(jsonPath("$.lines[0].requestedQty").value(2));
        // A retail order has no discount (BR-12), a draft included.
        call(post(DRAFTS), managerToken, order(null, customer, "RETAIL", "5", lines(pad, 1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG161"));
        call(post(DRAFTS), managerToken, retail(lamp, 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG160"));
    }

    @Test
    void ownerSubmitsADraftOnceAndItBecomesASalesOrder() throws Exception {
        long draft = saveDraft(ownerToken, order(store, customer, "RETAIL", null, lines(pad, 1)));
        call(put(DRAFTS + "/" + draft), ownerToken, order(store, customer, "WHOLESALE", "5", lines(pad, 2, oil, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[*].requestedQty", contains(2, 1)));
        assertThat(notifications(manager, "SALES_ORDER_CREATED")).isZero();
        clock.advance(java.time.Duration.ofMinutes(10));

        Map<String, Object> body = order(store, customer, "WHOLESALE", "5", lines(pad, 2, oil, 1));
        call(post(DRAFTS + "/" + draft + "/submit"), ownerToken, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(draft))
                .andExpect(jsonPath("$.status").value("AWAITING_PICKING"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-13T08:10:00"));
        call(post(DRAFTS + "/" + draft + "/submit"), ownerToken, body).andExpect(status().isNotFound());
        call(get("/api/v1/sales-orders/" + draft + "/versions"), ownerToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].cause").value("CREATED"));
        list(managerToken).andExpect(jsonPath("$.items[*].id", hasItem((int) draft)));
        call(get(DRAFTS), ownerToken, null).andExpect(jsonPath("$.totalItems").value(0));
        assertThat(notifications(manager, "SALES_ORDER_CREATED")).isEqualTo(1);
        assign(managerToken, "PICKING", draft, staff).andExpect(status().isOk());
    }

    @Test
    void aDeletedDraftIsGoneWithItsLines() throws Exception {
        long draft = saveDraft(managerToken, retail(pad, 1, oil, 2));
        call(delete(DRAFTS + "/" + draft), managerToken, null).andExpect(status().isNoContent());
        call(get(DRAFTS + "/" + draft), managerToken, null).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_order_line WHERE sales_order_id = ?",
                Integer.class, draft)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_order WHERE id = ?", Integer.class, draft))
                .isZero();
    }

    @Test
    void theSalesOrderApisDoNotFindADraft() throws Exception {
        long draft = saveDraft(managerToken, retail(pad, 1));
        detail(managerToken, draft).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG156"));
        detail(ownerToken, draft).andExpect(status().isNotFound());
        call(get("/api/v1/sales-orders/" + draft + "/versions"), managerToken, null).andExpect(status().isNotFound());
        update(managerToken, draft, 1, retail(pad, 2)).andExpect(status().isNotFound());
        confirm(managerToken, draft, 1, pad, 1).andExpect(status().isNotFound());
        call(post("/api/v1/sales-orders/" + draft + "/payments"), managerToken, Map.of("amount", 100_000,
                "paymentMethod", "CASH", "verified", true, "expectedPayable", 0)).andExpect(status().isNotFound());
        call(post("/api/v1/sales-orders/" + draft + "/cancel"), managerToken, Map.of("reason", "Khách không mua"))
                .andExpect(status().isNotFound());
        assign(managerToken, "PICKING", draft, staff).andExpect(status().isNotFound());
        call(get(DRAFTS + "/" + draft), managerToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
        list(managerToken, "view", "ALL").andExpect(jsonPath("$.items[*].id", not(hasItem((int) draft))));
    }

    private static Integer[] ids(long... ids) {
        Integer[] boxed = new Integer[ids.length];
        for (int i = 0; i < ids.length; i++) {
            boxed[i] = (int) ids[i];
        }
        return boxed;
    }
}
