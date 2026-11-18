package fu.hl.g64.motorflow.receiving;

import com.jayway.jsonpath.JsonPath;
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
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PM 17/11 D1: purchase order drafts saved on the server, seen by their creator only, outside every PO screen. */
class PurchaseOrderDraftApiTest extends GoodsReceiptTestData {

    private static final String DRAFTS = "/api/v1/purchase-orders/drafts";

    @Test
    void aDraftIsSeenByItsCreatorOnlyAndNowhereElse() throws Exception {
        long draft = saved(order(honda, giaLam, TODAY.plusDays(3), "Nháp", line(pad, 5, 180_000)));
        createUser("owner2", Role.OWNER, null);
        String otherOwner = signIn("owner2");

        send(get(DRAFTS), ownerToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", contains((int) draft)))
                .andExpect(jsonPath("$.items[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.items[0].orderedValue").value(900_000));
        send(get(DRAFTS).param("q", "DNH-" + draft), ownerToken).andExpect(jsonPath("$.items", hasSize(1)));
        send(get(DRAFTS).param("q", "Yamaha"), ownerToken).andExpect(jsonPath("$.items", hasSize(0)));
        send(get(DRAFTS + "/" + draft), ownerToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].orderedQty").value(5));

        // N5: anyone else gets 404, as for a PO that does not exist.
        send(get(DRAFTS), otherOwner).andExpect(jsonPath("$.items", hasSize(0)));
        send(get(DRAFTS + "/" + draft), otherOwner).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG131"));
        send(put(DRAFTS + "/" + draft), otherOwner, order(honda, giaLam, null, null, line(pad, 1, 1)))
                .andExpect(status().isNotFound());
        send(post(DRAFTS + "/" + draft + "/submit"), otherOwner, order(honda, giaLam, null, null, line(pad, 1, 1)))
                .andExpect(status().isNotFound());
        send(delete(DRAFTS + "/" + draft), otherOwner).andExpect(status().isNotFound());
        send(get(DRAFTS), managerToken).andExpect(status().isForbidden());

        // N4: in no PO list, detail, supplier page, receiving or change; no notification and no task.
        for (String view : new String[]{"OPEN", "ALL", "AWAITING_RECEIVING_ASSIGNMENT"}) {
            list(ownerToken, "view", view).andExpect(jsonPath("$.items[*].id", not(hasItem((int) draft))));
        }
        detail(ownerToken, draft).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MSG131"));
        detail(managerToken, draft).andExpect(status().isNotFound());
        send(get("/api/v1/suppliers/" + honda), ownerToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.purchaseOrders[*].id", not(hasItem((int) draft))));
        update(ownerToken, draft, change(null, null, "Đổi", line(pad, 6, 180_000))).andExpect(status().isNotFound());
        cancel(ownerToken, draft, "Bỏ").andExpect(status().isNotFound());
        createReceipt(managerToken, draft, deliveryNote("PX-1")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MSG131"));
        send(get("/api/v1/purchase-orders/" + draft + "/receiving-view"), managerToken)
                .andExpect(status().isNotFound());
        assertThat(notificationsOf(manager)).noneMatch(n -> n.get(3).equals(draft));
    }

    @Test
    void submittingPlacesTheDraftWithItsIdAndARefusalKeepsIt() throws Exception {
        long draft = saved(order(honda, giaLam, null, null, line(pad, 5, 180_000)));
        // PUT replaces everything.
        send(put(DRAFTS + "/" + draft), ownerToken,
                order(yamaha, giaLam, TODAY.plusDays(2), "Gọi trước", line(oil, 10, 90_000), line(lamp, 2, 300_000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplierId").value((int) yamaha))
                .andExpect(jsonPath("$.lines[*].productId", contains((int) oil, (int) lamp)));
        // A draft never buys for a replenishment request.
        Map<String, Object> linked = order(honda, giaLam, null, null, line(pad, 1, 1));
        linked.put("replenishmentRequestId", 1);
        send(post(DRAFTS), ownerToken, linked).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MSG02"));

        // Refused at submit (a discontinued part): the draft keeps what it had.
        send(post(DRAFTS + "/" + draft + "/submit"), ownerToken,
                order(yamaha, giaLam, null, null, line(discontinued, 1, 10_000)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MSG21"));
        send(get(DRAFTS + "/" + draft), ownerToken)
                .andExpect(jsonPath("$.lines[*].productId", contains((int) oil, (int) lamp)))
                .andExpect(jsonPath("$.note").value("Gọi trước"));

        send(post(DRAFTS + "/" + draft + "/submit"), ownerToken,
                order(yamaha, giaLam, TODAY.plusDays(2), "Gọi trước", line(oil, 10, 90_000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) draft))
                .andExpect(jsonPath("$.status").value("AWAITING_DELIVERY"));
        list(ownerToken).andExpect(jsonPath("$.items[*].id", hasItem((int) draft)));
        detail(managerToken, draft).andExpect(status().isOk());
        assertThat(notificationsOf(manager)).filteredOn(n -> n.get(3).equals(draft)).extracting(n -> n.get(0))
                .containsExactly("PURCHASE_ORDER_CREATED");
        send(get(DRAFTS + "/" + draft), ownerToken).andExpect(status().isNotFound());
        send(get(DRAFTS), ownerToken).andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void deletingADraftRemovesItForGood() throws Exception {
        long draft = saved(order(honda, giaLam, null, null, line(pad, 5, 180_000)));
        long placed = created(order(honda, giaLam, null, null, line(oil, 1, 90_000)));
        send(delete(DRAFTS + "/" + placed), ownerToken).andExpect(status().isNotFound());
        send(delete(DRAFTS + "/" + draft), ownerToken).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_order WHERE id = ?", Long.class, draft))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_order_line WHERE purchase_order_id = ?",
                Long.class, draft)).isZero();
    }

    private long saved(Map<String, Object> body) throws Exception {
        String response = send(post(DRAFTS), ownerToken, body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT")).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, token));
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String token, Map<String, Object> body)
            throws Exception {
        return send(request.contentType(MediaType.APPLICATION_JSON).content(json(body)), token);
    }
}
